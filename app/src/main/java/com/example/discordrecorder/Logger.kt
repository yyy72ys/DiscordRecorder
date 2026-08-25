package com.example.discordrecorder

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * アプリ内で観測可能なロガー。
 * - logcat にも出す
 * - ファイルにも追記 (filesDir/logs/app.log)
 * - MainActivity のデバッグ画面から表示・共有可能
 */
object Logger {
    private const val TAG = "DiscordRecorder"
    private val lock = ReentrantLock()
    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            logFile = File(dir, "app.log")
            // 起動マーカー
            i("=== App started ${sdf.format(Date())} ===")
            // 未捕捉例外もログ
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                e("Uncaught on ${t.name}: ${e.javaClass.simpleName}: ${e.message}", e)
                prev?.uncaughtException(t, e)
            }
        } catch (_: Exception) {}
    }

    fun d(msg: String) = log("D", msg, null)
    fun i(msg: String) = log("I", msg, null)
    fun w(msg: String) = log("W", msg, null)
    fun e(msg: String, tr: Throwable? = null) = log("E", msg, tr)

    private fun log(level: String, msg: String, tr: Throwable?) {
        val line = "${sdf.format(Date())} $level/$TAG: $msg${tr?.let { " | ${it.javaClass.simpleName}: ${it.message}\n${Log.getStackTraceString(it)}" } ?: ""}"
        when (level) {
            "E" -> Log.e(TAG, msg, tr)
            "W" -> Log.w(TAG, msg, tr)
            "I" -> Log.i(TAG, msg)
            else -> Log.d(TAG, msg)
        }
        lock.withLock {
            try {
                logFile?.appendText(line + "\n")
                // 500KB 超えたらローテート
                if ((logFile?.length() ?: 0) > 500 * 1024) {
                    val old = File(logFile!!.parent, "app.old.log")
                    logFile!!.renameTo(old)
                    logFile = File(logFile!!.parent, "app.log")
                }
            } catch (_: Exception) {}
        }
    }

    fun readAll(context: Context): String {
        return try {
            val f = File(context.filesDir, "logs/app.log")
            if (!f.exists()) return "(ログなし)"
            // 最新 2000行だけ
            val lines = f.readLines()
            if (lines.size > 2000) lines.takeLast(2000).joinToString("\n") else lines.joinToString("\n")
        } catch (e: Exception) { "読み込み失敗: ${e.message}" }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, "logs/app.log").delete()
            File(context.filesDir, "logs/app.old.log").delete()
            i("logs cleared")
        } catch (_: Exception) {}
    }

    fun fileForShare(context: Context): File? {
        val f = File(context.filesDir, "logs/app.log")
        return if (f.exists()) f else null
    }

    /** 診断情報を組み立てる */
    fun buildDiagnostics(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== Diagnostics ${sdf.format(Date())} ===")
        sb.appendLine("package: ${context.packageName} ver=${UpdateManager.getInstalledVersion(context)}")
        sb.appendLine("saveMode=${SettingsManager.getSaveMode(context)} path=${SettingsManager.getDisplayPath(context)}")
        val dir = try { SettingsManager.getSessionDir(context, "diagnostic_probe") } catch (e: Exception) { null }
        sb.appendLine("probeDir=${dir?.absolutePath} exists=${dir?.exists()} canWrite=${dir?.canWrite()}")
        // 試しに1バイト書けるか
        try {
            val probe = File(dir, "probe.tmp")
            probe.writeText("ok")
            sb.appendLine("probeWrite=OK size=${probe.length()} deleted=${probe.delete()}")
            dir?.delete()
        } catch (e: Exception) {
            sb.appendLine("probeWrite=FAIL ${e.message}")
            e("probeWrite fail", e)
        }
        // 権限
        sb.appendLine("RECORD_AUDIO=${context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)}")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            sb.appendLine("POST_NOTIFICATIONS=${context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)}")
        }
        // AudioRecord
        try {
            val min = android.media.AudioRecord.getMinBufferSize(48000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            sb.appendLine("AudioRecord.getMinBufferSize(48k/mono/16bit)=$min")
        } catch (e: Exception) { sb.appendLine("getMinBufferSize fail ${e.message}") }
        sb.appendLine("--- recent logs ---")
        sb.append(readAll(context).takeLast(4000))
        return sb.toString()
    }
}
