package com.example.discordrecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var isRecording by mutableStateOf(false)
    private var saveModeState by mutableStateOf(SettingsManager.SaveMode.INTERNAL)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            requestProjection()
        } else {
            Toast.makeText(this, "録音に必要な権限が不足しています", Toast.LENGTH_LONG).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svc = Intent(this, AudioCaptureService::class.java).apply {
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
            isRecording = true
            Toast.makeText(this, "録音開始: ${SettingsManager.getDisplayPath(this)}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "画面収録の許可が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            var granted = true
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) {
                granted = false
                Logger.w("takePersistableUriPermission failed: ${e.message}")
            }
            SettingsManager.setCustomUri(this, uri)
            val name = try { DocumentFile.fromTreeUri(this, uri)?.name } catch (_: Exception) { null }
            SettingsManager.setCustomName(this, name)
            SettingsManager.setSaveMode(this, SettingsManager.SaveMode.CUSTOM)
            saveModeState = SettingsManager.SaveMode.CUSTOM
            Logger.i("Custom folder selected: $uri name=$name granted=$granted")
            // 書き込みテスト（選んだ場所に本当に書けるか）
            val writable = try {
                val df = DocumentFile.fromTreeUri(this, uri)
                val test = df?.createFile("text/plain", "write_test.txt")
                if (test != null) {
                    contentResolver.openOutputStream(test.uri)?.use { it.write("ok".toByteArray()) }
                    test.delete()
                    true
                } else false
            } catch (e: Exception) {
                Logger.e("write test failed", e)
                false
            }
            val msg = when {
                writable -> "保存先を設定しました: ${name ?: uri}"
                granted -> "保存先を設定しました（書き込み確認に失敗。別フォルダを試してください）"
                else -> "保存先を設定しました（アクセス権の保持に失敗。もう一度選び直してください）"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "キャンセルされました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(this)
        Logger.i("MainActivity onCreate")
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 画面に戻るたびに実際の録音状態へ同期（表示ズレ防止）
        isRecording = isServiceRunning()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        // saveModeはActivityのStateを使うことで、フォルダ選択後もUIが更新される
        var token by remember { mutableStateOf(SettingsManager.getGithubToken(this@MainActivity) ?: "") }
        var updateStatus by remember { mutableStateOf("") }
        var autoUpdateInfo by remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }
        val scope = rememberCoroutineScope()
        val scroll = rememberScrollState()

        // 起動時にサービスが動いているか確認して isRecording と saveMode を復元
        LaunchedEffect(Unit) {
            saveModeState = SettingsManager.getSaveMode(this@MainActivity)
            isRecording = isServiceRunning()
            Logger.i("MainScreen launch: saveMode=$saveModeState isRecording=$isRecording")
            // 自動で更新をチェック（起動時に1回）
            try {
                val info = UpdateManager.checkForUpdate(this@MainActivity)
                if (info != null) autoUpdateInfo = info
            } catch (e: Exception) {
                Logger.w("auto update check failed: ${e.message}")
            }
        }

        Scaffold { pad ->
            Column(
                modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("DiscordRecorder", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "playback.wav(相手) + mic.wav(自分のささやき) を同時保存。\n後でPCで微小音声を増幅しつつ自分をマスク除去できます。",
                    style = MaterialTheme.typography.bodySmall
                )

                // 自動更新バナー
                autoUpdateInfo?.let { info ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🆕 新バージョンあり: ${info.tag}", style = MaterialTheme.typography.titleSmall)
                            Text(info.body.take(120), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                // インストール権限チェック
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                        data = Uri.parse("package:$packageName")
                                    }
                                    startActivity(intent)
                                    Toast.makeText(this@MainActivity, "「この提供元を許可」をONにしてから再度押してください", Toast.LENGTH_LONG).show()
                                } else {
                                    scope.launch {
                                        Toast.makeText(this@MainActivity, "ダウンロード中...", Toast.LENGTH_SHORT).show()
                                        val ok = UpdateManager.downloadAndInstall(this@MainActivity, info)
                                        Toast.makeText(
                                            this@MainActivity,
                                            if (ok) "インストーラを起動しました" else "更新に失敗しました",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("ワンタップで更新")
                            }
                            TextButton(onClick = { autoUpdateInfo = null }) { Text("閉じる") }
                        }
                    }
                }

                Button(
                    onClick = { if (isRecording) stopRecording() else checkAndStart() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if(isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                ) {
                    Text(if (isRecording) "■ 停止" else "● 録音開始", style = MaterialTheme.typography.titleMedium)
                }
                if (isRecording) {
                    Text("● 録音中... 通知からも停止できます", color = MaterialTheme.colorScheme.error)
                    Text("保存先: ${SettingsManager.getDisplayPath(this@MainActivity)}", style = MaterialTheme.typography.bodySmall)
                }

                Divider()

                // 保存先設定
                Text("保存先の設定", style = MaterialTheme.typography.titleMedium)
                Text("現在: ${SettingsManager.getDisplayPath(this@MainActivity)}", style = MaterialTheme.typography.bodySmall)
                Text("状態: ${if(SettingsManager.isCustomUriValid(this@MainActivity)) "カスタム有効" else if(saveModeState==SettingsManager.SaveMode.CUSTOM) "カスタム無効→内部にフォールバック" else "OK"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = saveModeState == SettingsManager.SaveMode.INTERNAL, onClick = {
                        saveModeState = SettingsManager.SaveMode.INTERNAL
                        SettingsManager.setSaveMode(this@MainActivity, saveModeState)
                        Logger.i("saveMode set to INTERNAL")
                    }, label = { Text("内部") })
                    FilterChip(selected = saveModeState == SettingsManager.SaveMode.CUSTOM, onClick = {
                        folderPickerLauncher.launch(null)
                    }, label = { Text("選択") })
                }
                OutlinedButton(onClick = { folderPickerLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("フォルダを選択")
                }
                OutlinedButton(onClick = {
                    val sd = findSdCardRootUri()
                    if (sd == null) {
                        Toast.makeText(this@MainActivity, "SDカードが見つかりません（未挿入/未マウント）", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "SDカード内で保存先フォルダを選んで「このフォルダを使用」", Toast.LENGTH_LONG).show()
                        folderPickerLauncher.launch(sd)
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("SDカードを選ぶ")
                }
                OutlinedButton(onClick = { openRecordingsFolder() }, modifier = Modifier.fillMaxWidth()) {
                    Text("保存フォルダを確認")
                }
                Text("SDカードは一番上の階層は選べません。中に「DiscordRecorder」等のフォルダを作って選んでください。", style = MaterialTheme.typography.bodySmall)

                Divider()

                // アップデート
                Text("アップデート", style = MaterialTheme.typography.titleMedium)
                Text("現在: ${UpdateManager.getInstalledVersion(this@MainActivity)}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("GitHub Token (プライベート用、任意)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        SettingsManager.setGithubToken(this@MainActivity, token)
                        Toast.makeText(this@MainActivity, "トークンを保存しました", Toast.LENGTH_SHORT).show()
                    }) { Text("保存") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                updateStatus = "確認中..."
                                Logger.i("manual update check started")
                                val info = UpdateManager.checkForUpdate(this@MainActivity)
                                updateStatus = if (info != null) {
                                    "新バージョンあり: ${info.tag}\n${info.body.take(120)}"
                                } else "最新版です (v${UpdateManager.getInstalledVersion(this@MainActivity)})"
                                Logger.i("manual check result: $updateStatus")
                                Toast.makeText(this@MainActivity, updateStatus, Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                updateStatus = "確認失敗: ${e.message}"
                                Logger.e("manual check failed", e)
                                Toast.makeText(this@MainActivity, updateStatus, Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("更新を確認") }
                }
                if (updateStatus.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(updateStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                    if (updateStatus.startsWith("新バージョン")) {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                    data = Uri.parse("package:$packageName")
                                })
                                Toast.makeText(this@MainActivity, "「この提供元を許可」をONにしてから、もう一度押してください", Toast.LENGTH_LONG).show()
                            } else {
                                scope.launch {
                                    try {
                                        val info = autoUpdateInfo ?: UpdateManager.checkForUpdate(this@MainActivity)
                                        if (info != null) {
                                            autoUpdateInfo = null
                                            val ok = UpdateManager.downloadAndInstall(this@MainActivity, info)
                                            Toast.makeText(
                                                this@MainActivity,
                                                if (ok) "インストーラを起動しました: ${info.tag}" else "ダウンロード失敗",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "情報取得失敗", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Logger.e("download failed", e)
                                        Toast.makeText(this@MainActivity, "失敗: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("ダウンロードして更新") }
                    }
                }
                // ブラウザで直接開く（アプリ内更新が動かない時の保険）
                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yyy72ys/DiscordRecorder/releases/latest"))
                    startActivity(intent)
                }, modifier = Modifier.fillMaxWidth()) { Text("ブラウザでリリースを開く") }

                // 認証付きアップデートの説明
                Text("トークンを設定するとプライベートリポジトリのリリースも取得できます。公開リポジトリなら不要です。", style = MaterialTheme.typography.bodySmall)

                Divider()
                // デバッグ・診断（録音が始まらない時の観測用）
                var diagnosticText by remember { mutableStateOf("") }
                var logText by remember { mutableStateOf("") }
                var showLogs by remember { mutableStateOf(false) }
                Text("デバッグ・診断", style = MaterialTheme.typography.titleMedium)
                Text("録音が始まらない・保存できない時はここで原因を確認できます", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        diagnosticText = Logger.buildDiagnostics(this@MainActivity)
                        Logger.i("diagnostics executed")
                    }) { Text("診断を実行") }
                    OutlinedButton(onClick = {
                        logText = Logger.readAll(this@MainActivity)
                        showLogs = !showLogs
                    }) { Text(if(showLogs) "ログを隠す" else "ログを表示") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val f = Logger.fileForShare(this@MainActivity)
                        if (f != null) {
                            val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", f)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, "ログを共有"))
                        } else {
                            Toast.makeText(this@MainActivity, "ログファイルなし", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("ログを共有") }
                    OutlinedButton(onClick = {
                        Logger.clear(this@MainActivity)
                        logText = ""
                        diagnosticText = ""
                        Toast.makeText(this@MainActivity, "ログをクリアしました", Toast.LENGTH_SHORT).show()
                    }) { Text("ログをクリア") }
                }
                if (diagnosticText.isNotBlank()) {
                    Text("診断結果:", style = MaterialTheme.typography.titleSmall)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(diagnosticText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                    OutlinedButton(onClick = {
                        val token = SettingsManager.getGithubToken(this@MainActivity)
                        if (token.isNullOrBlank()) {
                            // トークン無しでも送れる: 端末の共有（メール/LINE/Drive等）
                            Toast.makeText(this@MainActivity, "共有先を選んで送ってください（トークン不要）", Toast.LENGTH_LONG).show()
                            shareDiagnostics(diagnosticText)
                        } else {
                            scope.launch {
                                try {
                                    Toast.makeText(this@MainActivity, "GitHubに送信中...", Toast.LENGTH_SHORT).show()
                                    val logs = Logger.readAll(this@MainActivity)
                                    val url = DiagnosticsReporter.sendAsIssue(this@MainActivity, diagnosticText, logs)
                                    if (url != null) {
                                        Toast.makeText(this@MainActivity, "送信成功", Toast.LENGTH_LONG).show()
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    } else {
                                        Toast.makeText(this@MainActivity, "送信失敗: 共有に切り替えます", Toast.LENGTH_LONG).show()
                                        shareDiagnostics(diagnosticText)
                                    }
                                } catch (e: Exception) {
                                    Logger.e("auto send failed", e)
                                    shareDiagnostics(diagnosticText)
                                }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("診断結果を送る") }
                    OutlinedButton(onClick = { shareDiagnostics(diagnosticText) }, modifier = Modifier.fillMaxWidth()) {
                        Text("診断結果を共有（ファイル）")
                    }
                    Text("GitHubへ自動投稿する場合のみトークンが必要です。トークン無しでも共有アプリで送れます。", style = MaterialTheme.typography.bodySmall)
                }
                if (showLogs && logText.isNotBlank()) {
                    Text("ログ:", style = MaterialTheme.typography.titleSmall)
                    Card(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        Text(logText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState()))
                    }
                }
                // クイックテスト: 保存先に1秒の無音WAVが作れるか
                OutlinedButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            val sid = "test_${sdf.format(java.util.Date())}"
                            val dir = SettingsManager.getSessionDir(this@MainActivity, sid)
                            val testFile = java.io.File(dir, "test.wav")
                            // 1秒無音
                            val dataSize = 48000 * 2 // 1sec mono 16bit
                            val raf = java.io.RandomAccessFile(testFile, "rw")
                            raf.setLength(0)
                            raf.writeBytes("RIFF"); writeIntLE(raf, 36 + dataSize); raf.writeBytes("WAVE")
                            raf.writeBytes("fmt "); writeIntLE(raf, 16); writeShortLE(raf, 1); writeShortLE(raf, 1)
                            writeIntLE(raf, 48000); writeIntLE(raf, 48000*2); writeShortLE(raf, 2); writeShortLE(raf, 16)
                            raf.writeBytes("data"); writeIntLE(raf, dataSize)
                            raf.write(ByteArray(dataSize))
                            raf.close()
                            launch(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "テスト成功: ${testFile.absolutePath} (${testFile.length()}B)", Toast.LENGTH_LONG).show()
                                diagnosticText = "テスト書き込み成功: ${testFile.absolutePath}\nexists=${testFile.exists()} size=${testFile.length()}\nこのファイルができていれば保存先は正常です"
                            }
                            Logger.i("test write success ${testFile.absolutePath}")
                        } catch (e: Exception) {
                            launch(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "テスト失敗: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            Logger.e("test write fail", e)
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("保存テスト（1秒無音を作成）") }
            }
        }
    }

    private fun writeIntLE(raf: java.io.RandomAccessFile, v: Int) {
        raf.write(v and 0xFF); raf.write((v shr 8) and 0xFF); raf.write((v shr 16) and 0xFF); raf.write((v shr 24) and 0xFF)
    }
    private fun writeShortLE(raf: java.io.RandomAccessFile, v: Int) {
        raf.write(v and 0xFF); raf.write((v shr 8) and 0xFF)
    }

    private fun checkAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun stopRecording() {
        val svc = Intent(this, AudioCaptureService::class.java).apply { action = AudioCaptureService.ACTION_STOP }
        startService(svc)
        isRecording = false
        Toast.makeText(this, "録音停止", Toast.LENGTH_SHORT).show()
    }

    private fun openRecordingsFolder() {
        Toast.makeText(this, "保存先: ${SettingsManager.getDisplayPath(this)}", Toast.LENGTH_LONG).show()
    }

    /**
     * リムーバブルSDカードのルートURI（SAFピッカーの初期位置に使う）。
     * getExternalFilesDirs の2番目以降がSDカード。パスからボリュームUUIDを取り出す。
     */
    @Suppress("DEPRECATION")
    private fun findSdCardRootUri(): Uri? {
        return try {
            val dirs = getExternalFilesDirs(null)
            if (dirs.size < 2 || dirs[1] == null) return null
            val path = dirs[1]!!.absolutePath // 例: /storage/1234-ABCD/Android/data/<pkg>/...
            val m = Regex("/storage/([^/]+)/").find(path) ?: return null
            val uuid = m.groupValues[1]
            if (uuid.isBlank() || uuid == "emulated") return null
            Uri.parse("content://com.android.externalstorage.documents/root/$uuid")
        } catch (e: Exception) {
            Logger.w("findSdCardRootUri failed: ${e.message}")
            null
        }
    }

    /** 診断結果を端末の共有シートで送る（GitHubトークン不要）。大きい本文はファイル経由。 */
    private fun shareDiagnostics(text: String) {
        try {
            val shareFile = java.io.File(cacheDir, "diagnostics.txt").apply { writeText(text) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", shareFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "診断結果を送る"))
        } catch (e: Exception) {
            Logger.e("share diagnostics failed", e)
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text.take(8000))
                }
                startActivity(Intent.createChooser(intent, "診断結果を送る"))
            } catch (_: Exception) {
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        return try {
            val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            manager.getRunningServices(Integer.MAX_VALUE).any { it.service.className == AudioCaptureService::class.java.name }
        } catch (e: Exception) {
            Logger.w("isServiceRunning check failed: ${e.message}")
            false
        }
    }
}
