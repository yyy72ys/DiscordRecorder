package com.example.discordrecorder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DiagnosticsReporter {
    private const val REPO = "yyy72ys/DiscordRecorder"

    suspend fun sendAsIssue(context: Context, diagnostics: String, logs: String): String? = withContext(Dispatchers.IO) {
        val token = SettingsManager.getGithubToken(context)
        if (token.isNullOrBlank()) {
            Logger.w("DiagnosticsReporter: no token, cannot auto-send")
            return@withContext null
        }
        try {
            val url = URL("https://api.github.com/repos/$REPO/issues")
            val body = """
                |### 自動診断レポート
                |**端末:** ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, SDK ${android.os.Build.VERSION.SDK_INT})
                |**アプリ:** ${UpdateManager.getInstalledVersion(context)}
                |**保存先:** ${SettingsManager.getDisplayPath(context)}
                |
                |#### 診断結果
                |```
                |$diagnostics
                |```
                |
                |#### ログ（最新）
                |```
                |${logs.take(8000)}
                |```
                |
                |*自動送信: テスターの動きとして*
            """.trimMargin()
            val json = JSONObject().apply {
                put("title", "自動診断: ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}")
                put("body", body)
                put("labels", org.json.JSONArray().apply { put("bug"); put("auto-report") })
            }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("Authorization", "token $token")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }
            conn.outputStream.bufferedWriter().use { it.write(json.toString()) }
            val code = conn.responseCode
            val resp = try { conn.inputStream.bufferedReader().readText() } catch (_: Exception) { conn.errorStream?.bufferedReader()?.readText() ?: "" }
            Logger.i("DiagnosticsReporter: issue create HTTP $code resp=${resp.take(500)}")
            if (code in 200..201) {
                val respJson = JSONObject(resp)
                respJson.optString("html_url")
            } else null
        } catch (e: Exception) {
            Logger.e("DiagnosticsReporter failed", e)
            null
        }
    }
}
