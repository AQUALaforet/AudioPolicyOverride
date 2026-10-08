package dev.aqua.audiopolicy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.aqua.audiopolicy.AudioPolicyApplication
import dev.aqua.audiopolicy.automation.PolicyUiState

typealias MainUiState = PolicyUiState

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = (application as AudioPolicyApplication).engine
    val state = engine.state
    fun requestPermission() = engine.requestPermission()
    fun reload() = engine.reload()
    fun setEnabled(enabled: Boolean) { engine.setManual(enabled) }
    fun restore() { engine.restore() }
    fun setAutomation(enabled: Boolean) { engine.setAutomation(enabled) }
    fun selectPackages(packages: Set<String>) { engine.selectPackages(packages) }
    fun loadApps() = engine.loadApps()
    fun foreground(visible: Boolean) = engine.foreground(visible)
}
