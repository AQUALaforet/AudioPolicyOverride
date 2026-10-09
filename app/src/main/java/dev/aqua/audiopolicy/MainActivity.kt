package dev.aqua.audiopolicy

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aqua.audiopolicy.ui.MainScreen
import dev.aqua.audiopolicy.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel
    private var pendingAutomation = false
    private var explainNotifications by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingAutomation) {
            pendingAutomation = false
            viewModel.setAutomation(true)
        }
        viewModel.foreground(true)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        pendingAutomation = savedInstanceState?.getBoolean("pendingAutomation") ?: false
        explainNotifications = savedInstanceState?.getBoolean("explainNotifications") ?: false
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                }
                darkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
                MainScreen(state, viewModel::requestPermission, viewModel::reload,
                    viewModel::setEnabled, viewModel::restore, ::toggleAutomation,
                    viewModel::selectPackages, viewModel::loadApps, ::requestNotifications, diagnostics,
                    viewModel::setDiagnosticRecording, viewModel::deleteDiagnosticLogs, ::copyDiagnostics)
                if (explainNotifications) AlertDialog(
                    onDismissRequest = { explainNotifications = false; pendingAutomation = false },
                    title = { Text("通知を許可してください") },
                    text = { Text("監視中の「復元して停止」と、復元失敗・復元待ちを通知します。許可しない場合も処理は続き、問題はアプリ内で確認できます。") },
                    confirmButton = { TextButton(onClick = {
                        explainNotifications = false
                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text("続ける") } },
                    dismissButton = { TextButton(onClick = { explainNotifications = false; pendingAutomation = false }) { Text("キャンセル") } })
            }
        }
    }
    override fun onResume() {
        super.onResume()
        viewModel.foreground(true)
    }
    override fun onPause() {
        viewModel.foreground(false)
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingAutomation", pendingAutomation)
        outState.putBoolean("explainNotifications", explainNotifications)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
    private fun copyDiagnostics() {
        lifecycleScope.launch {
            val text = viewModel.diagnosticInfo()
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Audio Policy Override 診断情報", text))
            Toast.makeText(this@MainActivity, "診断情報をコピーしました", Toast.LENGTH_SHORT).show()
        }
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            explainNotifications = true
        } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    private fun toggleAutomation(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingAutomation = true
            explainNotifications = true
        } else viewModel.setAutomation(enabled)
    }
}
