package com.example.discordrecorder

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import java.io.File

object SettingsManager {
    private const val PREF_NAME = "recorder_settings"
    private const val KEY_SAVE_MODE = "save_mode" // internal | music | custom
    private const val KEY_CUSTOM_URI = "custom_uri"
    private const val KEY_GITHUB_TOKEN = "github_token"

    enum class SaveMode { INTERNAL, MUSIC, CUSTOM }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getSaveMode(context: Context): SaveMode {
        return when (prefs(context).getString(KEY_SAVE_MODE, "internal")) {
            "music" -> SaveMode.MUSIC
            "custom" -> SaveMode.CUSTOM
            else -> SaveMode.INTERNAL
        }
    }

    fun setSaveMode(context: Context, mode: SaveMode) {
        prefs(context).edit().putString(KEY_SAVE_MODE, when(mode){
            SaveMode.MUSIC -> "music"
            SaveMode.CUSTOM -> "custom"
            else -> "internal"
        }).apply()
    }

    fun getCustomUri(context: Context): Uri? {
        val s = prefs(context).getString(KEY_CUSTOM_URI, null) ?: return null
        return try { Uri.parse(s) } catch (_: Exception){ null }
    }

    fun setCustomUri(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_CUSTOM_URI, uri?.toString()).apply()
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
        }
    }

    fun isCustomUriValid(context: Context): Boolean {
        val uri = getCustomUri(context) ?: return false
        val perms = context.contentResolver.persistedUriPermissions
        return perms.any { it.uri == uri && it.isWritePermission }
    }

    fun getGithubToken(context: Context): String? =
        prefs(context).getString(KEY_GITHUB_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setGithubToken(context: Context, token: String?) {
        prefs(context).edit().putString(KEY_GITHUB_TOKEN, token).apply()
    }

    private const val KEY_AUTO_SEND = "auto_send"
    fun isAutoSendEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SEND, false)
    fun setAutoSendEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SEND, enabled).apply()
    }

    /** 保存先のセッションディレクトリを返す。mkdirs失敗時は internal にフォールバック */
    fun getSessionDir(context: Context, sessionId: String): File {
        val mode = getSaveMode(context)
        val base: File? = when(mode){
            SaveMode.MUSIC -> {
                try {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                } catch (_: Exception){ null }
            }
            SaveMode.CUSTOM -> {
                // CUSTOMはSAFだが、File APIでは扱えないため内部にフォールバックしつつ、SAFはService側でDocumentFileを使う
                // ここでは仮にinternalを返す。実際の書き込みはServiceでSAF分岐する
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            }
            SaveMode.INTERNAL -> context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        }
        val root = base ?: context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "DiscordRecorder/$sessionId")
        if (!dir.exists()) {
            val ok = dir.mkdirs()
            if (!ok) {
                // フォールバック: filesDir
                val fallback = File(context.filesDir, "DiscordRecorder/$sessionId")
                fallback.mkdirs()
                return fallback
            }
        }
        return dir
    }

    fun getDisplayPath(context: Context): String {
        return when(getSaveMode(context)){
            SaveMode.INTERNAL -> "内部ストレージ: Android/data/com.example.discordrecorder/files/Music/DiscordRecorder/"
            SaveMode.MUSIC -> "共有ストレージ: Music/DiscordRecorder/"
            SaveMode.CUSTOM -> getCustomUri(context)?.toString() ?: "カスタム: 未選択"
        }
    }
}
