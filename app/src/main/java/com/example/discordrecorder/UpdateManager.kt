package com.example.discordrecorder

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * アプリ内アップデート。
 * - GitHubの「リリース一覧」を取得し、APKが添付されたリリースの中から一番新しい版を選ぶ
 * - 版比較は versionName のセマンティック（例: 1.10 > 1.9）。タグの数字を単純連結しない
 * - 更新時はAPKをダウンロードしてインストーラを直接起動（確実にインストール画面へ）
 */
object UpdateManager {
    private const val REPO = "yyy72ys/DiscordRecorder"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=30"

    data class ReleaseInfo(
        val tag: String,
        val versionName: String,
        val version: List<Int>,
        val apkUrl: String,
        val body: String
    )

    fun getInstalledVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    fun getInstalledVersionCode(context: Context): Int = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pi.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") pi.versionCode
        }
    } catch (_: Exception) {
        0
    }

    fun getInstalledVersion(context: Context): String =
        "${getInstalledVersionName(context)} (${getInstalledVersionCode(context)})"

    /** "v1.10" -> [1,10], "1.7" -> [1,7], "v1.3" -> [1,3] */
    private fun parseVersion(s: String): List<Int> =
        Regex("\\d+").findAll(s).map { it.value.toIntOrNull() ?: 0 }.toList().ifEmpty { listOf(0) }

    /** candidate が current より新しいか（要素ごとに比較、足りない桁は0） */
    private fun isNewer(candidate: List<Int>, current: List<Int>): Boolean {
        val n = maxOf(candidate.size, current.size)
        for (i in 0 until n) {
            val a = candidate.getOrElse(i) { 0 }
            val b = current.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun githubGet(urlStr: String, token: String?, timeoutMs: Int = 8000): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        val code = conn.responseCode
        val text = try {
            if (code in 200..299) conn.inputStream.bufferedReader().readText()
            else conn.errorStream?.bufferedReader()?.readText() ?: ""
        } catch (_: Exception) {
            ""
        }
        conn.disconnect()
        return code to text
    }

    suspend fun checkForUpdate(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        val token = SettingsManager.getGithubToken(context)
        val (code, text) = githubGet(RELEASES_API, token)
        Logger.i("checkForUpdate: releases HTTP $code")
        if (code != 200) {
            Logger.w("checkForUpdate: non-200 $code body=${text.take(200)}")
            return@withContext null
        }
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            return@withContext null
        }

        var best: JSONObject? = null
        var bestVersion: List<Int> = listOf(0)
        for (i in 0 until arr.length()) {
            val rel = arr.optJSONObject(i) ?: continue
            if (rel.optBoolean("draft", false)) continue
            if (rel.optBoolean("prerelease", false)) continue
            val tag = rel.optString("tag_name", "")
            if (tag.isBlank()) continue
            if (findApkUrl(rel) == null) continue
            val v = parseVersion(tag)
            if (isNewer(v, bestVersion)) {
                bestVersion = v
                best = rel
            }
        }
        val rel = best ?: run {
            Logger.i("checkForUpdate: APK付きリリースなし")
            return@withContext null
        }
        val current = parseVersion(getInstalledVersionName(context))
        if (!isNewer(bestVersion, current)) {
            Logger.i("checkForUpdate: 最新です (best=$bestVersion current=$current)")
            return@withContext null
        }
        val apkUrl = findApkUrl(rel) ?: return@withContext null
        val tag = rel.optString("tag_name")
        Logger.i("checkForUpdate: 更新あり tag=$tag url=$apkUrl")
        ReleaseInfo(
            tag = tag,
            versionName = tag.trimStart('v', 'V'),
            version = bestVersion,
            apkUrl = apkUrl,
            body = rel.optString("body", "")
        )
    }

    private fun findApkUrl(rel: JSONObject): String? {
        val assets = rel.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                val url = a.optString("browser_download_url", "")
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    /** APKをダウンロードしてインストーラを起動。起動できたらtrue。 */
    suspend fun downloadAndInstall(context: Context, info: ReleaseInfo): Boolean =
        withContext(Dispatchers.IO) {
            val token = SettingsManager.getGithubToken(context)
            val out = File(context.cacheDir, "update-v${info.versionName}.apk")
            try {
                val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/octet-stream")
                    if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                val code = conn.responseCode
                Logger.i("downloadAndInstall: HTTP $code url=${info.apkUrl}")
                if (code !in 200..299) {
                    Logger.w("downloadAndInstall: 失敗 HTTP $code")
                    conn.disconnect()
                    return@withContext false
                }
                conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                if (out.length() <= 0L) {
                    Logger.w("downloadAndInstall: 空ファイル")
                    return@withContext false
                }
                Logger.i("downloadAndInstall: 保存 ${out.absolutePath} size=${out.length()}")
            } catch (e: Exception) {
                Logger.e("downloadAndInstall: ダウンロード失敗", e)
                return@withContext false
            }

            withContext(Dispatchers.Main) {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                } catch (e: Exception) {
                    Logger.e("downloadAndInstall: インストーラ起動失敗", e)
                    false
                }
            }
        }
}
