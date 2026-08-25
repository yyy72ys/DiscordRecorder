package com.example.discordrecorder

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val REPO = "yyy72ys/DiscordRecorder"

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) pi.longVersionCode.toInt() else pi.versionCode
        } catch (_: Exception){ 1 }
    }

    data class ReleaseInfo(
        val tag: String,
        val versionCode: Int,
        val apkUrl: String,
        val body: String
    )

    suspend fun checkForUpdate(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val token = SettingsManager.getGithubToken(context)
            val url = URL("https://api.github.com/repos/$REPO/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "token $token")
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) return@withContext null
            val text = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(text)
            val tag = json.optString("tag_name", "")
            // versionCodeはタグから抽出 e.g. v2 -> 2, または body内の versionCode
            val versionCode = extractVersionCode(tag, json.optString("body",""))
            val current = getCurrentVersionCode(context)
            if (versionCode <= current) return@withContext null
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null){
                for(i in 0 until assets.length()){
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk")){
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
            }
            // フォールバック: プライベートリポジトリでもトークンがあればAPI経由で取得可能だが、簡易的にリリースページを示す
            if (apkUrl == null) return@withContext null
            ReleaseInfo(tag, versionCode, apkUrl, json.optString("body",""))
        } catch (_: Exception){
            null
        }
    }

    private fun extractVersionCode(tag: String, body: String): Int {
        // v1, v1.0, 1 などを数値化
        val fromTag = tag.filter { it.isDigit() }.toIntOrNull()
        if (fromTag != null && fromTag > 0) return fromTag
        val m = Regex("versionCode\\s*[:=]\\s*(\\d+)").find(body)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun downloadAndInstall(context: Context, apkUrl: String){
        val token = SettingsManager.getGithubToken(context)
        // 認証が必要なプライベートリリースでもDownloadManagerにヘッダを付けられないため、直接ダウンロードURLにトークンを付与せず、手動DLにフォールバック
        // 公開リポジトリならそのままDLできる
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(apkUrl)
        val req = DownloadManager.Request(uri).apply {
            setTitle("DiscordRecorder アップデート")
            setDescription("ダウンロード中...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "update.apk")
            setMimeType("application/vnd.android.package-archive")
            if (!token.isNullOrBlank()){
                addRequestHeader("Authorization", "token $token")
            }
        }
        dm.enqueue(req)
    }

    fun getInstalledVersion(context: Context): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (_: Exception){ "不明" }
    }
}
