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
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            SettingsManager.setCustomUri(this, uri)
            SettingsManager.setSaveMode(this, SettingsManager.SaveMode.CUSTOM)
            Toast.makeText(this, "保存先を設定しました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        var saveMode by remember { mutableStateOf(SettingsManager.getSaveMode(this)) }
        var token by remember { mutableStateOf(SettingsManager.getGithubToken(this) ?: "") }
        var updateStatus by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val scroll = rememberScrollState()

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = saveMode == SettingsManager.SaveMode.INTERNAL, onClick = {
                        saveMode = SettingsManager.SaveMode.INTERNAL
                        SettingsManager.setSaveMode(this@MainActivity, saveMode)
                    }, label = { Text("内部") })
                    FilterChip(selected = saveMode == SettingsManager.SaveMode.MUSIC, onClick = {
                        saveMode = SettingsManager.SaveMode.MUSIC
                        SettingsManager.setSaveMode(this@MainActivity, saveMode)
                    }, label = { Text("Music") })
                    FilterChip(selected = saveMode == SettingsManager.SaveMode.CUSTOM, onClick = {
                        saveMode = SettingsManager.SaveMode.CUSTOM
                        SettingsManager.setSaveMode(this@MainActivity, saveMode)
                        folderPickerLauncher.launch(null)
                    }, label = { Text("選択") })
                }
                OutlinedButton(onClick = { folderPickerLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("フォルダを選択（SAF）")
                }
                OutlinedButton(onClick = { openRecordingsFolder() }, modifier = Modifier.fillMaxWidth()) {
                    Text("保存フォルダを確認")
                }

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
                            updateStatus = "確認中..."
                            val info = UpdateManager.checkForUpdate(this@MainActivity)
                            updateStatus = if (info != null) {
                                "新バージョンあり: ${info.tag}\n${info.body.take(120)}"
                            } else "最新版です"
                        }
                    }) { Text("更新を確認") }
                }
                if (updateStatus.isNotBlank()) {
                    Text(updateStatus, style = MaterialTheme.typography.bodySmall)
                    if (updateStatus.startsWith("新バージョン")) {
                        Button(onClick = {
                            scope.launch {
                                val info = UpdateManager.checkForUpdate(this@MainActivity)
                                if (info != null) {
                                    UpdateManager.downloadAndInstall(this@MainActivity, info.apkUrl)
                                    Toast.makeText(this@MainActivity, "ダウンロード開始", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("ダウンロードして更新") }
                    }
                }

                // 認証付きアップデートの説明
                Text("トークンを設定するとプライベートリポジトリのリリースも取得できます。公開リポジトリなら不要です。", style = MaterialTheme.typography.bodySmall)
            }
        }
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
        // 内部ストレージの場合はファイルアプリで開くヒント
        if (SettingsManager.getSaveMode(this) == SettingsManager.SaveMode.MUSIC) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "*/*")
                }
                // 単にトーストで案内するだけ
            } catch (_: Exception){}
        }
    }
}
