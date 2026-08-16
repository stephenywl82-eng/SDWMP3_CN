package com.sdw.music.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.sdw.music.player.ui.navigation.SDWNavHost
import com.sdw.music.player.ui.theme.SDWMusicTheme
import com.sdw.music.player.ui.viewmodel.PlayerIntent
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val shouldOpenPlayer = mutableStateOf(false)
    /** 外部打开音频文件的 URI */
    private val externalAudioUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLog.init(applicationContext)
        FileLog.log("APP", "onCreate")
        handleOpenPlayer(intent)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        setContent {
            SDWMusicTheme {
                var hasPermission by remember { mutableStateOf(checkPermission()) }
                val mediaLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    // 仅由媒体权限决定 hasPermission，不受电话权限结果污染
                    hasPermission = getRequiredPermissions().all { perm ->
                        results[perm] == true ||
                            ContextCompat.checkSelfPermission(this@MainActivity, perm) == PackageManager.PERMISSION_GRANTED
                    }
                }

                // 电话状态监听需要 READ_PHONE_STATE（Android 10+）；可选，只问一次，拒绝后不再弹
                val phoneLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* 忽略结果：拒绝不影响播放 */ }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val prefs = getSharedPreferences("permission_flags", MODE_PRIVATE)
                        val phoneAsked = prefs.getBoolean("phone_perm_asked", false)
                        if (!phoneAsked &&
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            prefs.edit().putBoolean("phone_perm_asked", true).apply()
                            phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        }
                    }
                }

                if (hasPermission) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        SDWNavHost(
                            openPlayer = shouldOpenPlayer,
                            externalAudioUri = externalAudioUri
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Button(onClick = { mediaLauncher.launch(getRequiredPermissions()) }) {
                                Text("Grant Media Access")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenPlayer(intent)
    }

    private fun handleOpenPlayer(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            // 外部打开音频文件
            externalAudioUri.value = intent.data
            shouldOpenPlayer.value = true
            android.util.Log.i("MainActivity", "External audio URI: ${intent.data}")
        } else if (intent.getBooleanExtra("open_player", false)) {
            com.sdw.music.player.ui.viewmodel.PlayerViewModel.skipInitScan = true
            shouldOpenPlayer.value = true
        }
    }
    private fun checkPermission(): Boolean {
        return getRequiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}