package com.example.discordrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var playbackRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var sessionDir: File? = null
    private var startTimeMs: Long = 0

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_STOP = "com.example.discordrecorder.STOP"
        private const val CHANNEL_ID = "discord_recorder_channel"
        private const val NOTIF_ID = 1
        const val SAMPLE_RATE = 48000
    }

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        createNotificationChannel()
        Logger.i("Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.i("onStartCommand action=${intent?.action} resultCode=${intent?.getIntExtra(EXTRA_RESULT_CODE, -999)} hasData=${intent?.hasExtra(EXTRA_RESULT_DATA)} save=${intent?.getBooleanExtra("save", false)}")
        if (intent?.action == ACTION_STOP) {
            val isSave = intent.getBooleanExtra("save", false)
            Logger.i("ACTION_STOP received save=$isSave")
            if (isSave) {
                // 保存して停止: 通知で保存先を表示
                sessionDir?.let { updateNotification("保存しました: ${it.absolutePath}") }
                // 少し待ってから停止（ユーザーが通知を見られるように）
                thread { Thread.sleep(800); stopRecording() }
            } else {
                stopRecording()
            }
            return START_NOT_STICKY
        }

        if (intent == null) {
            Logger.w("onStartCommand: intent null (system restart), stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == -1 || resultData == null) {
            Logger.e("Invalid projection resultCode=$resultCode hasData=${resultData != null}")
            updateNotification("エラー: 投影データ無効")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                Logger.e("getMediaProjection returned null")
                updateNotification("エラー: MediaProjection取得失敗")
                stopSelf()
                return START_NOT_STICKY
            }
            // コールバックで停止を検知
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Logger.w("MediaProjection onStop callback")
                    stopRecording()
                }
            }, null)
        } catch (e: Exception) {
            Logger.e("getMediaProjection exception", e)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, createNotification("録音中... タップで停止"))
        Logger.i("startForeground done")

        // 別フォルダ: 設定に基づく保存先
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val sessionId = sdf.format(Date())
        sessionDir = SettingsManager.getSessionDir(this, sessionId)
        // 念のため mkdirs 成否をログ
        if (sessionDir?.exists() == false) {
            val ok = sessionDir?.mkdirs() ?: false
            if (!ok) {
                // フォールバック失敗時は filesDir に
                sessionDir = File(filesDir, "DiscordRecorder/$sessionId").apply { mkdirs() }
            }
        }
        startTimeMs = System.currentTimeMillis()

        isRecording.set(true)
        startDualRecording()

        return START_STICKY
    }

    private fun startDualRecording() {
        val dir = sessionDir ?: run {
            Logger.e("startDualRecording: sessionDir is null")
            updateNotification("エラー: 保存先が無効")
            return
        }
        Logger.i("startDualRecording dir=${dir.absolutePath} exists=${dir.exists()} canWrite=${dir.canWrite()} mode=${SettingsManager.getSaveMode(this)}")

        // Playback (相手側 = システム音声) 用
        val playbackFile = File(dir, "playback.wav")
        // Mic (自分側 = ささやき含む) 用
        val micFile = File(dir, "mic.wav")
        val metaFile = File(dir, "meta.json")
        Logger.i("files: playback=${playbackFile.absolutePath} mic=${micFile.absolutePath}")

        var bufferSizePlayback = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        Logger.i("getMinBufferSize playback raw=$bufferSizePlayback")
        if (bufferSizePlayback <= 0) bufferSizePlayback = 8192
        else bufferSizePlayback *= 2
        Logger.i("bufferSizePlayback final=$bufferSizePlayback")

        var bufferSizeMic = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        Logger.i("getMinBufferSize mic raw=$bufferSizeMic")
        if (bufferSizeMic <= 0) bufferSizeMic = 8192
        else bufferSizeMic *= 2
        Logger.i("bufferSizeMic final=$bufferSizeMic")

        // AudioPlaybackCaptureConfiguration: USAGE_MEDIA + USAGE_VOICE_COMMUNICATION + USAGE_GAME
        // Discordは VOICE_COMMUNICATION なので両方指定が重要
        val playbackConfig = try {
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build().also { Logger.i("playbackConfig created") }
        } catch (e: Exception) {
            Logger.e("playbackConfig build failed", e)
            updateNotification("エラー: キャプチャ設定失敗: ${e.message}")
            thread { Thread.sleep(5000); stopRecording() }
            return
        }

        val playbackFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        playbackRecord = try {
            AudioRecord.Builder()
                .setAudioFormat(playbackFormat)
                .setBufferSizeInBytes(bufferSizePlayback)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build().also { Logger.i("playbackRecord built state=${it.state}") }
        } catch (e: Exception) {
            Logger.e("playbackRecord build exception", e)
            updateNotification("エラー: 録音初期化例外 ${e.message}")
            thread { Thread.sleep(5000); stopRecording() }
            return
        }
        // 初期化チェック: 失敗時は即停止ではなく通知で知らせる
        if (playbackRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Logger.e("playbackRecord STATE not INITIALIZED: ${playbackRecord?.state}")
            updateNotification("エラー: システム音声の初期化失敗 (state=${playbackRecord?.state})")
            // 空ファイル削除
            try { playbackFile.delete() } catch (_: Exception) {}
            try { micFile.delete() } catch (_: Exception) {}
            // 5秒後に停止
            thread { Thread.sleep(5000); stopRecording() }
            return
        }
        Logger.i("playbackRecord initialized OK")

        val micFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        // MIC側は処理を無効化して生データを保持（後でささやき判定しやすくするため）
        // VOICE_COMMUNICATIONはAEC/NSが入るので MIC を使う
        // RECORD_AUDIO権限がない場合はmic録音をスキップ（クラッシュ防止）
        val hasMicPermission = try {
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception){ false }

        micRecord = if (hasMicPermission) {
            try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(micFormat)
                    .setBufferSizeInBytes(bufferSizeMic)
                    .build().also {
                        if (it.state != AudioRecord.STATE_INITIALIZED) {
                            it.release()
                            // mic失敗は致命的ではないのでnullで続行
                        }
                    }.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            } catch (_: Exception){ null }
        } else null

        val playbackWriter = try { WavWriter(playbackFile, SAMPLE_RATE, 1) } catch (e: Exception){
            updateNotification("エラー: ファイル作成失敗 ${e.message}")
            thread { Thread.sleep(4000); stopRecording() }
            return
        }
        val micWriter: WavWriter? = if (micRecord != null) {
            try { WavWriter(micFile, SAMPLE_RATE, 1) } catch (_: Exception){ null }
        } else null

        // 同期用メタ情報
        val meta = JSONObject().apply {
            put("sessionId", dir.name)
            put("startTimeMs", startTimeMs)
            put("sampleRate", SAMPLE_RATE)
            put("channels", 1)
            put("encoding", "PCM_16BIT")
            put("playbackFile", playbackFile.name)
            put("micFile", micFile.name)
            put("note", "playback=相手側システム音声, mic=自分側ささやき含む。後処理でmicの発話区間をマスクし、playbackの微小音声を増幅する")
        }
        metaFile.writeText(meta.toString(2))

        // それぞれ別スレッドで録音（タイムスタンプはSystem.nanoTimeで同期可能）
        thread(name = "playback-capture") {
            try {
                playbackRecord?.startRecording()
                // 録音開始成功を通知更新
                updateNotification("録音中... ${sessionDir?.absolutePath}")
                val buf = ByteArray(bufferSizePlayback)
                while (isRecording.get()) {
                    val n = playbackRecord?.read(buf, 0, buf.size) ?: -1
                    if (n > 0) playbackWriter.write(buf, 0, n)
                    else if (n < 0) {
                        // 読み込みエラー時は少し待つ
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateNotification("録音エラー: ${e.message}")
            } finally {
                try { playbackRecord?.stop() } catch (_: Exception) {}
                try { playbackWriter.close() } catch (_: Exception) {}
            }
        }

        thread(name = "mic-capture") {
            if (micRecord == null || micWriter == null) {
                // micなしでもmetaだけ更新
                return@thread
            }
            try {
                micRecord?.startRecording()
                val buf = ByteArray(bufferSizeMic)
                while (isRecording.get()) {
                    val n = micRecord?.read(buf, 0, buf.size) ?: -1
                    if (n > 0) micWriter.write(buf, 0, n)
                    else if (n < 0) Thread.sleep(10)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { micRecord?.stop() } catch (_: Exception) {}
                try { micWriter.close() } catch (_: Exception) {}
                // 録音終了後にmetaを更新
                try {
                    meta.put("endTimeMs", System.currentTimeMillis())
                    meta.put("durationMs", System.currentTimeMillis() - startTimeMs)
                    meta.put("micAvailable", micRecord != null)
                    metaFile.writeText(meta.toString(2))
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording.getAndSet(false)) return
        try { playbackRecord?.stop() } catch (_: Exception) {}
        try { micRecord?.stop() } catch (_: Exception) {}
        playbackRecord?.release()
        micRecord?.release()
        playbackRecord = null
        micRecord = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "録音", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Discord録音サービス"
                setShowBadge(false)
            }
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(ch)
        }
    }

    private fun updateNotification(text: String){
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, createNotification(text))
        } catch (_: Exception){}
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // 保存して停止は同じだが、ユーザーに「保存される」ことが分かるように別ラベル
        val saveIntent = Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP; putExtra("save", true) }
        val savePi = PendingIntent.getService(this, 1, saveIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 2, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DiscordRecorder 録音中")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPi)
            .addAction(android.R.drawable.ic_menu_save, "保存して停止", savePi)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    /**
     * 最低限のWAVライタ。ヘッダを後でパッチする方式。
     * 無圧縮PCMで保存することで、後処理で微小音声のゲイン調整・VAD閾値再調整が可能。
     */
    private class WavWriter(private val file: File, private val sampleRate: Int, private val channels: Int) {
        private val raf = RandomAccessFile(file, "rw")
        private var dataSize = 0

        init {
            // 44byteヘッダ仮書き
            raf.setLength(0)
            raf.writeBytes("RIFF")
            writeIntLE(0) // file size - 8 (後でパッチ)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeIntLE(16) // PCM
            writeShortLE(1) // audioFormat PCM
            writeShortLE(channels)
            writeIntLE(sampleRate)
            writeIntLE(sampleRate * channels * 2) // byteRate
            writeShortLE(channels * 2) // blockAlign
            writeShortLE(16) // bitsPerSample
            raf.writeBytes("data")
            writeIntLE(0) // data size (後でパッチ)
        }

        fun write(buf: ByteArray, off: Int, len: Int) {
            raf.write(buf, off, len)
            dataSize += len
        }

        fun close() {
            try {
                raf.seek(4); writeIntLE(36 + dataSize)
                raf.seek(40); writeIntLE(dataSize)
                raf.close()
            } catch (_: Exception) {}
        }

        private fun writeIntLE(v: Int) {
            raf.write(v and 0xFF); raf.write((v shr 8) and 0xFF)
            raf.write((v shr 16) and 0xFF); raf.write((v shr 24) and 0xFF)
        }
        private fun writeShortLE(v: Int) {
            raf.write(v and 0xFF); raf.write((v shr 8) and 0xFF)
        }
    }
}
