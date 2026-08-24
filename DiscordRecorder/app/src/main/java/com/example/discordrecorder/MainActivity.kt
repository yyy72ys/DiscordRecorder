package com.example.discordrecorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

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
            Toast.makeText(this, "録音開始: 別フォルダ Music/DiscordRecorder/<時刻>/ に保存", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "画面収録の許可が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { pad ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("DiscordRecorder", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "playback.wav(相手) + mic.wav(自分のささやき) を別フォルダに同時保存。\n" +
                            "後でPCで微小音声を増幅しつつ自分をマスク除去できます。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { if (isRecording) stopRecording() else checkAndStart() },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text(if (isRecording) "停止" else "録音開始")
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { openRecordingsFolder() }, modifier = Modifier.fillMaxWidth()) {
                            Text("保存フォルダを確認")
                        }
                        if (isRecording) {
                            Spacer(Modifier.height(12.dp))
                            Text("● 録音中... 通知からも停止できます", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
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
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun stopRecording() {
        val svc = Intent(this, AudioCaptureService::class.java).apply { action = AudioCaptureService.ACTION_STOP }
        startService(svc)
        isRecording = false
        Toast.makeText(this, "録音停止", Toast.LENGTH_SHORT).show()
    }

    private fun openRecordingsFolder() {
        Toast.makeText(this, "ファイルアプリ > Music > DiscordRecorder を確認", Toast.LENGTH_LONG).show()
    }
}
