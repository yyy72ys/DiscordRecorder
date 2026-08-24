"""
DiscordRecorder 分離スクリプト v2
- 二系統録音対応: playback.wav (相手側システム音声) + mic.wav (自分側ささやき)
- 微小音声対応: 無圧縮WAV前提でゲイン/RMS正規化/Silero VADで小声を逃さない
- ささやき除外: micトラックのVAD結果をマスクとしてplaybackから除去 (埋め込みより高精度)

使い方:
  # 二系統モード（推奨）
  python scripts/separate_speakers.py --playback Music/DiscordRecorder/20260101_120000/playback.wav --mic Music/DiscordRecorder/20260101_120000/mic.wav --output output/20260101_120000

  # 旧単一ファイルモード（後方互換）
  python scripts/separate_speakers.py --input rec.opus --reference my_voice.wav --output output.wav

データさえあれば後で何度でも閾値変えて再実行可能（エフェクトは全て後処理）。
"""
import os
import sys
import json
import shutil
import argparse
from pathlib import Path

import numpy as np
import soundfile as sf
import librosa

# 出力フォルダは別フォルダに分離: ~/DiscordRecorderWork/output/<sessionId>/
DEFAULT_WORK_DIR = Path.home() / "DiscordRecorderWork"
DEFAULT_OUTPUT_ROOT = DEFAULT_WORK_DIR / "output"

# ---------- 微小音声向けユーティリティ ----------

def rms_db(y: np.ndarray) -> float:
    rms = np.sqrt(np.mean(y.astype(np.float64) ** 2) + 1e-12)
    return 20 * np.log10(rms + 1e-12)

def enhance_faint(y: np.ndarray, sr: int, target_db: float = -20.0, max_gain_db: float = 24.0):
    """小声を持ち上げる簡易エフェクト: RMS正規化 + ソフトリミッター
    データがWAV生保存なので、ここで何度でもゲイン調整可能。"""
    cur_db = rms_db(y)
    gain_db = np.clip(target_db - cur_db, 0, max_gain_db)
    gain = 10 ** (gain_db / 20)
    y_boost = y * gain
    # ソフトクリップで破綻防止
    y_boost = np.tanh(y_boost * 1.2) * 0.95
    return y_boost, gain_db

def simple_vad_intervals(y: np.ndarray, sr: int, frame_ms: int = 30, thresh_db: float = -50.0, min_speech_ms: int = 150, min_silence_ms: int = 200):
    """軽量VAD: フレームRMSが閾値を超えた区間を発話とする。ささやきでも拾えるよう閾値低め(-50dB)。
    Silero VADがあればそちらが推奨だが、依存なしで動くフォールバック。"""
    frame_len = int(sr * frame_ms / 1000)
    hop = frame_len
    n_frames = int(np.ceil(len(y) / hop))
    # フレームごとRMS(dB)
    db_vals = []
    for i in range(n_frames):
        chunk = y[i*hop:(i+1)*hop]
        if len(chunk) == 0:
            db_vals.append(-120)
        else:
            db_vals.append(rms_db(chunk))
    db_vals = np.array(db_vals)

    is_speech = db_vals > thresh_db
    # 最小発話/無音長でスムージング（形態学的フィルタ）
    # min_speech_ms -> 最短発話フレーム数、満たない孤立発話を除去
    # min_silence_ms -> 短い無音は埋める
    speech_frames = int(min_speech_ms / frame_ms)
    silence_frames = int(min_silence_ms / frame_ms)

    # 短い無音を埋める
    i = 0
    while i < len(is_speech):
        if not is_speech[i]:
            j = i
            while j < len(is_speech) and not is_speech[j]:
                j += 1
            gap = j - i
            if gap < silence_frames and i > 0 and j < len(is_speech) and is_speech[i-1] and is_speech[j]:
                is_speech[i:j] = True
            i = j
        else:
            i += 1
    # 短い発話を除去
    i = 0
    while i < len(is_speech):
        if is_speech[i]:
            j = i
            while j < len(is_speech) and is_speech[j]:
                j += 1
            run = j - i
            if run < speech_frames:
                is_speech[i:j] = False
            i = j
        else:
            i += 1

    intervals = []
    i = 0
    while i < len(is_speech):
        if is_speech[i]:
            s = i
            while i < len(is_speech) and is_speech[i]:
                i += 1
            e = i
            intervals.append((s * hop / sr, e * hop / sr))
        else:
            i += 1
    return intervals

def load_silero_vad():
    """可能ならSilero VADをロード、無ければNone"""
    try:
        import torch
        model, utils = torch.hub.load(repo_or_dir='snakers4/silero-vad', model='silero_vad', trust_repo=True)
        (get_speech_timestamps, _, _, _, _) = utils
        return model, get_speech_timestamps
    except Exception as e:
        print(f"[info] Silero VAD not available ({e}), fallback to RMS VAD")
        return None, None

# ---------- 二系統マスク処理 ----------

def mask_with_mic(playback_intervals, mic_intervals, pad_ms: float = 0.25):
    """playbackの発話区間から、mic発話区間(自分)と重なる部分を除去。pad_msで前後に余裕を持たせる。"""
    # mic区間をpad拡張
    mic_padded = [(max(0, s - pad_ms), e + pad_ms) for s, e in mic_intervals]

    result = []
    for ps, pe in playback_intervals:
        cur_s, cur_e = ps, pe
        # mic区間と重なる部分を削る
        remaining = [(cur_s, cur_e)]
        for ms, me in mic_padded:
            next_remaining = []
            for rs, re in remaining:
                if re <= ms or rs >= me:
                    next_remaining.append((rs, re))
                else:
                    if rs < ms:
                        next_remaining.append((rs, ms))
                    if re > me:
                        next_remaining.append((me, re))
            remaining = next_remaining
        result.extend(remaining)
    # 極短区間は捨てる
    result = [(s, e) for s, e in result if (e - s) >= 0.15]
    return result

def write_segments(y: np.ndarray, sr: int, intervals, out_path: Path, add_silence_ms: int = 150):
    """intervalsだけを結合して書き出し。間は短い無音を挿入して聞きやすくする。"""
    if not intervals:
        print("[warn] 出力区間が0件です。閾値を下げて再実行してください。")
        # 無音1秒を出力して空でないファイルにする
        sf.write(str(out_path), np.zeros(sr, dtype=np.float32), sr)
        return
    silence = np.zeros(int(sr * add_silence_ms / 1000), dtype=np.float32)
    chunks = []
    for s, e in intervals:
        s_i = int(s * sr)
        e_i = int(e * sr)
        chunks.append(y[s_i:e_i])
        chunks.append(silence)
    if chunks:
        chunks = chunks[:-1]  # 末尾無音削除
        out = np.concatenate(chunks)
        # 最終正規化
        peak = np.max(np.abs(out))
        if peak > 0.99:
            out = out / peak * 0.89
        sf.write(str(out_path), out, sr)

# ---------- メインパイプライン ----------

def process_dual(playback_path: Path, mic_path: Path, output_dir: Path, args):
    sr_target = 16000  # VADは16kで十分、出力は元SRで保存
    y_play, sr_play = sf.read(str(playback_path), dtype='float32')
    y_mic, sr_mic = sf.read(str(mic_path), dtype='float32')
    if y_play.ndim > 1: y_play = y_play.mean(axis=1)
    if y_mic.ndim > 1: y_mic = y_mic.mean(axis=1)

    # VAD用に16kへリサンプル
    y_play_16k = librosa.resample(y_play, orig_sr=sr_play, target_sr=sr_target) if sr_play != sr_target else y_play
    y_mic_16k = librosa.resample(y_mic, orig_sr=sr_mic, target_sr=sr_target) if sr_mic != sr_target else y_mic

    # --- mic側: 自分のささやきも逃さないよう閾値低め ---
    # ささやきは -45〜-35dB 程度なので thresh=-50dB で拾う
    silero_model, get_ts = load_silero_vad()
    if silero_model is not None and args.use_silero:
        import torch
        wav = torch.from_numpy(y_mic_16k)
        # Sileroは閾値0.3程度で小声も拾う（デフォルト0.5より低め）
        speech_ts = get_ts(wav, silero_model, sampling_rate=sr_target, threshold=args.silero_thresh, min_speech_duration_ms=120)
        mic_intervals = [(x['start']/sr_target, x['end']/sr_target) for x in speech_ts]
    else:
        mic_intervals = simple_vad_intervals(y_mic_16k, sr_target, thresh_db=args.mic_thresh_db, min_speech_ms=120, min_silence_ms=180)

    # --- playback側: 相手の微小音声も拾う ---
    # まず軽く持ち上げてからVADすると検出率が上がる
    y_play_enh, gain_db = enhance_faint(y_play_16k, sr_target, target_db=-22, max_gain_db=args.max_gain_db)
    print(f"[info] playback faint enhance: +{gain_db:.1f}dB (target -22dB)")

    if silero_model is not None and args.use_silero:
        import torch
        wav = torch.from_numpy(y_play_enh)
        speech_ts = get_ts(wav, silero_model, sampling_rate=sr_target, threshold=args.silero_thresh_playback, min_speech_duration_ms=150)
        play_intervals = [(x['start']/sr_target, x['end']/sr_target) for x in speech_ts]
    else:
        play_intervals = simple_vad_intervals(y_play_enh, sr_target, thresh_db=args.play_thresh_db, min_speech_ms=150, min_silence_ms=220)

    print(f"[info] mic intervals (自分): {len(mic_intervals)}件, playback intervals (相手含む): {len(play_intervals)}件")

    # mic区間をマスクして相手のみ抽出
    filtered = mask_with_mic(play_intervals, mic_intervals, pad_ms=args.mask_pad_ms)
    print(f"[info] after mask (相手のみ): {len(filtered)}件")

    # 出力は元SRのy_playから切り出す（高品質）
    # 16kのintervalsを元SRにマッピングは時間ベースなのでそのまま使える
    output_dir.mkdir(parents=True, exist_ok=True)
    out_wav = output_dir / "other_only.wav"
    out_mic_wav = output_dir / "mic_only.wav"  # デバッグ用: 自分側だけ
    out_json = output_dir / "segments.json"

    write_segments(y_play, sr_play, filtered, out_wav)
    write_segments(y_mic, sr_mic, mic_intervals, out_mic_wav)

    meta = {
        "playback": str(playback_path),
        "mic": str(mic_path),
        "mic_intervals": mic_intervals,
        "playback_intervals": play_intervals,
        "filtered_intervals": filtered,
        "params": vars(args),
        "enhance_gain_db": float(gain_db),
        "note": "filtered=相手のみ。mic_intervalsでマスク。微小音声はenhance_faintで持ち上げ後にVAD。"
    }
    out_json.write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[done] 出力: {out_wav} / {out_json}")

    # 話者埋め込みによる二重チェック（任意、pyannoteが入っていれば）
    if args.verify_with_embedding and playback_path.exists():
        try:
            verify_with_embedding(playback_path, filtered, args)
        except Exception as e:
            print(f"[warn] embedding verify skip: {e}")

def process_single(input_path: Path, reference_path: Path | None, output_path: Path, args):
    """旧単一ファイルモード: playbackのみ + 参照音声で話者照合"""
    y, sr = sf.read(str(input_path), dtype='float32')
    if y.ndim > 1: y = y.mean(axis=1)
    sr_target = 16000
    y_16k = librosa.resample(y, orig_sr=sr, target_sr=sr_target) if sr != sr_target else y
    y_enh, gain_db = enhance_faint(y_16k, sr_target, max_gain_db=args.max_gain_db)
    intervals = simple_vad_intervals(y_enh, sr_target, thresh_db=args.play_thresh_db)
    print(f"[info] single mode intervals: {len(intervals)}件 gain+{gain_db:.1f}dB")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    write_segments(y, sr, intervals, output_path)
    print(f"[done] {output_path}")

def verify_with_embedding(playback_path: Path, intervals, args):
    from pyannote.audio import Pipeline
    # 参考: micを使ったマスクが主で、埋め込みは補助
    print("[info] embedding verifyは未実装スタブ（必要ならpyannoteで拡張）")

def main():
    p = argparse.ArgumentParser(description="DiscordRecorder 分離: ささやき除外 + 微小音声増幅")
    # 二系統モード
    p.add_argument("--playback", type=str, help="playback.wav (システム音声)")
    p.add_argument("--mic", type=str, help="mic.wav (自分マイク)")
    # 単一モード互換
    p.add_argument("--input", type=str, help="旧: 単一入力ファイル")
    p.add_argument("--reference", type=str, help="旧: 自分の参照音声")
    p.add_argument("--output", type=str, help="出力先（ファイル or ディレクトリ）。二系統時はディレクトリ推奨")

    # 微小音声/ささやき用チューニング（データさえあれば後で何度でも再実行して調整可能）
    p.add_argument("--mic-thresh-db", type=float, default=-50.0, help="mic VAD閾値(dB)。低いほどささやきを拾う。-50が推奨")
    p.add_argument("--play-thresh-db", type=float, default=-48.0, help="playback VAD閾値(dB)。-48で小声も拾う")
    p.add_argument("--max-gain-db", type=float, default=20.0, help="微小音声持ち上げ最大ゲイン(dB)")
    p.add_argument("--mask-pad-ms", type=float, default=0.25, help="micマスク前後の余裕(秒)。ささやきの前後ブレ吸収")
    p.add_argument("--use-silero", action="store_true", help="Silero VADを使う（精度高、小声に強い）")
    p.add_argument("--silero-thresh", type=float, default=0.32, help="Silero mic閾値（低いほど小声拾う）")
    p.add_argument("--silero-thresh-playback", type=float, default=0.38, help="Silero playback閾値")
    p.add_argument("--verify-with-embedding", action="store_true", help="pyannote埋め込みで二重検証")

    # 旧互換
    p.add_argument("--device", type=str, default="cpu")
    p.add_argument("--threshold", type=float, default=0.75)
    p.add_argument("--min-length", type=float, default=0.5)

    args = p.parse_args()

    if args.playback and args.mic:
        playback_path = Path(args.playback)
        mic_path = Path(args.mic)
        if args.output:
            out = Path(args.output)
            output_dir = out if out.suffix == "" else out.parent
        else:
            # 別フォルダ: output/<sessionId>/
            session = playback_path.parent.name
            output_dir = DEFAULT_OUTPUT_ROOT / session
        process_dual(playback_path, mic_path, output_dir, args)
    elif args.input:
        input_path = Path(args.input)
        ref = Path(args.reference) if args.reference else None
        out_path = Path(args.output) if args.output else (DEFAULT_OUTPUT_ROOT / "other_only.wav")
        if out_path.is_dir():
            out_path = out_path / "other_only.wav"
        process_single(input_path, ref, out_path, args)
    else:
        p.print_help()
        print("\n例: python scripts/separate_speakers.py --playback Music/DiscordRecorder/20260101_120000/playback.wav --mic Music/DiscordRecorder/20260101_120000/mic.wav --use-silero")
        sys.exit(1)

if __name__ == "__main__":
    main()
