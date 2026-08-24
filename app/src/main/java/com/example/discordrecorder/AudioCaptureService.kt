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
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == -1 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, resultData)

        startForeground(NOTIF_ID, createNotification("録音中... タップで停止"))

        // 別フォルダ: Music/DiscordRecorder/<sessionId>/
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val sessionId = sdf.format(Date())
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        sessionDir = File(base, "DiscordRecorder/$sessionId").apply { mkdirs() }
        startTimeMs = System.currentTimeMillis()

        isRecording.set(true)
        startDualRecording()

        return START_STICKY
    }

    private fun startDualRecording() {
        val dir = sessionDir ?: return

        // Playback (相手側 = システム音声) 用
        val playbackFile = File(dir, "playback.wav")
        // Mic (自分側 = ささやき含む) 用
        val micFile = File(dir, "mic.wav")
        val metaFile = File(dir, "meta.json")

        val bufferSizePlayback = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ) * 2
        val bufferSizeMic = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        // AudioPlaybackCaptureConfiguration: USAGE_MEDIA + USAGE_VOICE_COMMUNICATION + USAGE_GAME
        // Discordは VOICE_COMMUNICATION なので両方指定が重要
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val playbackFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        playbackRecord = AudioRecord.Builder()
            .setAudioFormat(playbackFormat)
            .setBufferSizeInBytes(bufferSizePlayback)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        val micFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        // MIC側は処理を無効化して生データを保持（後でささやき判定しやすくするため）
        // VOICE_COMMUNICATIONはAEC/NSが入るので MIC を使う
        micRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(micFormat)
            .setBufferSizeInBytes(bufferSizeMic)
            .build()

        val playbackWriter = WavWriter(playbackFile, SAMPLE_RATE, 1)
        val micWriter = WavWriter(micFile, SAMPLE_RATE, 1)

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
                val buf = ByteArray(bufferSizePlayback)
                while (isRecording.get()) {
                    val n = playbackRecord?.read(buf, 0, buf.size) ?: -1
                    if (n > 0) playbackWriter.write(buf, 0, n)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { playbackRecord?.stop() } catch (_: Exception) {}
                playbackWriter.close()
            }
        }

        thread(name = "mic-capture") {
            try {
                micRecord?.startRecording()
                val buf = ByteArray(bufferSizeMic)
                while (isRecording.get()) {
                    val n = micRecord?.read(buf, 0, buf.size) ?: -1
                    if (n > 0) micWriter.write(buf, 0, n)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { micRecord?.stop() } catch (_: Exception) {}
                micWriter.close()
                // 録音終了後にmetaを更新
                try {
                    meta.put("endTimeMs", System.currentTimeMillis())
                    meta.put("durationMs", System.currentTimeMillis() - startTimeMs)
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

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DiscordRecorder")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPi)
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
