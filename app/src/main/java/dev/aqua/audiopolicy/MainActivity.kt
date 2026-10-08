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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aqua.audiopolicy.ui.MainScreen
import dev.aqua.audiopolicy.ui.MainViewModel
import dev.aqua.audiopolicy.automation.AutomationService

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel
    private var pendingAutomation = false
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
        handleIntent(intent)
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
                MainScreen(state, viewModel::requestPermission, viewModel::reload,
                    viewModel::setEnabled, viewModel::restore, ::toggleAutomation,
                    viewModel::selectPackages, viewModel::loadApps)
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
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    private fun handleIntent(intent: Intent) {
        if (intent.action == AutomationService.ACTION_STOP) {
            viewModel.setAutomation(false)
            intent.action = null
        }
    }
    private fun toggleAutomation(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingAutomation = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else viewModel.setAutomation(enabled)
    }
}
