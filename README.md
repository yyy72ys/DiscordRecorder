# DiscordRecorder — 使い方・機能まとめ

AndroidでDiscord通話を録音し、**相手の声だけ**を残してPCで解析するツール。
ささやきの自分と小声の相手を分離し、微小音声も後から感度調整で救済できるのが特徴。

最終更新: 2026-08-24

---

## 1. できること

| 機能 | 説明 |
|---|---|
| **二系統同時録音** | `playback.wav`(相手側システム音声) + `mic.wav`(自分側マイク)を同時・同期録音。イヤホン装着時でも分離可能 |
| **ささやき除外** | micトラックの発話区間をマスクとしてplaybackから除去。話者埋め込みより高精度 |
| **微小音声増幅** | WAV無圧縮保存 → PC側で `enhance_faint()` により最大+20dB持ち上げ。後から閾値変えて再実行可能 |
| **別フォルダ保存** | Android: `Music/DiscordRecorder/<yyyyMMdd_HHmmss>/` / PC: `~/DiscordRecorderWork/output/<sessionId>/` に自動分離 |
| **小声VAD** | RMS閾値 `-50dB` / Silero VAD `0.32` でささやきも検出。`--mic-thresh-db` `--play-thresh-db` で調整 |
| **再処理可能** | エフェクトは全てデータに対する後処理。録り直し不要で何度でもパラメータを変えて試せる |
| **デバッグ出力** | `other_only.wav` / `mic_only.wav` / `segments.json` を同時出力。タイムライン確認可 |

---

## 2. 構成

```
Android: app/src/main/kotlin/com/example/discordrecorder/
  AudioCaptureService.kt  # ForegroundService, MediaProjection + MIC 同時録音, WavWriter
  MainActivity.kt         # 権限要求(MediaProjection/RECORD_AUDIO/POST_NOTIFICATIONS), 録音UI

PC: scripts/separate_speakers.py
  enhance_faint()              # 微小音声ゲイン
  simple_vad_intervals()       # 軽量RMS VAD (フォールバック)
  mask_with_mic()              # mic区間でplaybackをマスク
  process_dual() / process_single() # 二系統/単一モード

保存先:
  Android: /Music/DiscordRecorder/<sessionId>/playback.wav, mic.wav, meta.json
  PC: ~/DiscordRecorderWork/output/<sessionId>/other_only.wav, mic_only.wav, segments.json
```

---

## 3. 使い方

### 3-1. Android 録音

1. アプリ起動 → `録音開始` をタップ
2. 権限ダイアログを許可
   - `マイク` (RECORD_AUDIO) — 自分のささやき記録用
   - `通知` (POST_NOTIFICATIONS, Android 13+) — 録音中通知
   - `画面の録画/投影` (MediaProjection) — システム音声取得。必ず `許可` をタップ
3. Discord通話に参加（イヤホン推奨）。通知に `● 録音中...` が出る
4. 終了時: アプリの `停止` ボタン または 通知の `停止` をタップ
5. ファイルアプリ → `Music/DiscordRecorder/<時刻>/` に3ファイルできていることを確認

> 補足: 初回は `Music/DiscordRecorder/` が自動作成される。録音は `PCM 48kHz Mono 16bit WAV` 無圧縮なので後処理で音質劣化なし。

### 3-2. PCへコピー

USB / クラウド / `adb pull` などでフォルダごとPCにコピー:

```powershell
adb pull /sdcard/Music/DiscordRecorder/20260824_120000 ./input/20260824_120000
```

### 3-3. PCで分離（相手のみ抽出）

#### 前提
```powershell
python -m venv venv; .\venv\Scripts\activate
pip install soundfile librosa numpy
# 高精度VADを使う場合（任意）
pip install torch torchaudio
# 埋め込み検証を使う場合（任意）
pip install pyannote.audio
```

#### 基本（推奨: 二系統モード）
```powershell
python scripts/separate_speakers.py --playback input/20260824_120000/playback.wav --mic input/20260824_120000/mic.wav --output output/20260824_120000
# 出力: output/20260824_120000/other_only.wav  (相手のみ)
#       output/20260824_120000/mic_only.wav    (自分のみ, 確認用)
#       output/20260824_120000/segments.json   (区間タイムライン)
```

#### 旧: 単一ファイルモード（playbackのみしかない場合）
```powershell
python scripts/separate_speakers.py --input rec.opus --reference my_voice.wav --output output/other_only.wav
```

---

## 4. 微小音声・ささやきの調整（再実行でOK）

データは生WAVなので、録り直さずに閾値だけ変えて何度でも試せる。

```powershell
# 小声が消える → playbackがり�のを中央で位置く
python scripts/separate_speakers.py --playback ... --mic ... --output out_v2 --play-thresh-db -52 --max-gain-db 24

# ささやきが残る → micをより敏感に
python scripts/separate_speakers.py --playback ... --mic ... --output out_v3 --mic-thresh-db -52 --mask-pad-ms 0.35

# 最高精度（Silero VAD, ネット必要・初回DLあり）
python scripts/separate_speakers.py --playback ... --mic ... --output out_silero --use-silero --silero-thresh 0.30 --silero-thresh-playback 0.35
```

| 引数 | デフォルト | 意味 |
|---|---|---|
| `--mic-thresh-db` | -50 | mic VAD閾値。低いほどささやきを拾う |
| `--play-thresh-db` | -48 | playback VAD閾値。低いほど小声を拾う |
| `--max-gain-db` | 20 | 微小音声持ち上げ上限 |
| `--mask-pad-ms` | 0.25 | micマスク前後の余裕(秒)。口の開閉ブレ吸収 |
| `--use-silero` | off | Silero VAD使用。RMSより小声に強い |
| `--silero-thresh` | 0.32 | Silero mic閾値 |
| `--silero-thresh-playback` | 0.38 | Silero playback閾値 |

`segments.json` を見て `filtered_intervals` が想定通りか確認 → 聞き比べて閾値を詰めるのが最短。

---

## 5. 別フォルダ仕様

- **Android**: `Music/DiscordRecorder/<sessionId>/` ごとに `playback.wav` / `mic.wav` / `meta.json` (開始時刻・サンプルレート・同期用)
- **PC**: `~/DiscordRecorderWork/output/<sessionId>/` に出力。`--output` 未指定時は自動で `<sessionId>` 名で作成
- **メリット**: セッション混同防止、PC側で `output/` を丸ごとバックアップ/削除しやすい、手動転送（Option A）との相性◎

---

## 6. よくある質問

**Q. 相手が極小声で聞こえない → 拾える？**
A. 拾える。WAVで保存しているので `enhance_faint()` がRMS -22dBを目標に最大+20dB持ち上げる。VAD前にゲインするため検出漏れが減る。ダメなら `--max-gain-db 24 --play-thresh-db -52` で再実行。

**Q. 自分のささやきが混入する？**
A. micトラックをVADして `mask_with_mic()` で完全除去。埋め込み照合より確実。`--mask-pad-ms 0.35` で余裕を広げると取りこぼし減。

**Q. イヤホン無しでも使える？**
A. 使えるがスピーカーから自分の声がplaybackに回り込むためマスク精度が落ちる。イヤホン推奨。

**Q. Rootは必要？**
A. 不要。`AudioPlaybackCaptureConfiguration` (API 29+) + `MIC` の標準APIのみ。

**Q. 長時間録音は？**
A. WAVなので1時間で ~330MB (48kHz Mono)。ストレージに注意。将来の拡張で30分ごとに分割保存も可能。

---

## 7. 既知の制限・今後の拡張

- 現状は手動転送 (Option A)。自動アップロード (NAS/クラウド) は未実装
- 長時間の自動分割、OPUS並列保存、通知の経過時間表示は今後追加可能
- `pyannote` 埋め込み二重検証はスタブ。必要なら拡張

---

## 8. クイックリファレンス

```powershell
# Androidビルド
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 録音 → コピー → 分離 → 再生
adb pull /sdcard/Music/DiscordRecorder ./tmp
python scripts/separate_speakers.py --playback tmp/<session>/playback.wav --mic tmp/<session>/mic.wav --output output/<session>
start output/<session>/other_only.wav
```

問題があれば `segments.json` と `meta.json` を添えて再現パラメータを共有してください。
