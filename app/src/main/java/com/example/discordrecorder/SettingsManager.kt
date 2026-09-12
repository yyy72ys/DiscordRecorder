package com.example.discordrecorder

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import java.io.File

object SettingsManager {
    private const val PREF_NAME = "recorder_settings"
    private const val KEY_SAVE_MODE = "save_mode" // internal | custom
    private const val KEY_CUSTOM_URI = "custom_uri"
    private const val KEY_GITHUB_TOKEN = "github_token"
    private const val KEY_AUTO_SEND = "auto_send"

    // 保存先は「内部（アプリ専用領域）」と「選択（SAF）」の2択。
    // 共有Musicフォルダへの直接書き込みは Android 10+ のスコープドストレージで失敗するため廃止。
    // 選択(SAF)を選ぶと、録音はまず内部(アプリ専用)に書き、停止時に選んだフォルダへコピーする。
    enum class SaveMode { INTERNAL, CUSTOM }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getSaveMode(context: Context): SaveMode {
        return when (prefs(context).getString(KEY_SAVE_MODE, "internal")) {
            "custom" -> SaveMode.CUSTOM
            else -> SaveMode.INTERNAL // 旧 "music" も内部に読み替え
        }
    }

    fun setSaveMode(context: Context, mode: SaveMode) {
        prefs(context).edit().putString(
            KEY_SAVE_MODE,
            when (mode) {
                SaveMode.CUSTOM -> "custom"
                else -> "internal"
            }
        ).apply()
    }

    fun getCustomUri(context: Context): Uri? {
        val s = prefs(context).getString(KEY_CUSTOM_URI, null) ?: return null
        return try {
            Uri.parse(s)
        } catch (_: Exception) {
            null
        }
    }

    fun setCustomUri(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_CUSTOM_URI, uri?.toString()).apply()
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
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
        prefs(context).edit().putString(KEY_GITHUB_TOKEN, token?.trim()?.takeIf { it.isNotBlank() }).apply()
    }

    fun isAutoSendEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SEND, false)

    fun setAutoSendEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SEND, enabled).apply()
    }

    /**
     * 録音セッションの作業ディレクトリ（アプリ専用領域）を返す。
     * 選択(SAF)の場合も、まずここに書き、停止時に選択フォルダへコピーする。
     * mkdirs失敗時は filesDir にフォールバック。
     */
    fun getSessionDir(context: Context, sessionId: String): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val root = base ?: context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "DiscordRecorder/$sessionId")
        if (!dir.exists()) {
            val ok = dir.mkdirs()
            if (!ok) {
                val fallback = File(context.filesDir, "DiscordRecorder/$sessionId")
                fallback.mkdirs()
                return fallback
            }
        }
        return dir
    }

    fun getDisplayPath(context: Context): String {
        return when (getSaveMode(context)) {
            SaveMode.INTERNAL -> "内部: Android/data/com.example.discordrecorder/files/Music/DiscordRecorder/"
            SaveMode.CUSTOM -> {
                val uri = getCustomUri(context)
                if (uri != null && isCustomUriValid(context)) {
                    "選択フォルダ: $uri"
                } else {
                    "選択フォルダ未設定 → 内部に保存"
                }
            }
        }
    }
}
