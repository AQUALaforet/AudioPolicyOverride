package dev.aqua.audiopolicy.automation

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.app.NotificationManager
import android.os.SystemClock
import android.util.Log
import dev.aqua.audiopolicy.ForceUse
import dev.aqua.audiopolicy.data.AppCatalog
import dev.aqua.audiopolicy.data.AppChoice
import dev.aqua.audiopolicy.data.AutomationSettings
import dev.aqua.audiopolicy.data.OverrideController
import dev.aqua.audiopolicy.data.OverrideOwner
import dev.aqua.audiopolicy.data.PolicySnapshot
import dev.aqua.audiopolicy.data.SettingsRepository
import dev.aqua.audiopolicy.shizuku.ConnectionState
import dev.aqua.audiopolicy.shizuku.ShizukuManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PolicyUiState(
    val connection: ConnectionState = ConnectionState(),
    val snapshot: PolicySnapshot? = null,
    val automation: AutomationSettings = AutomationSettings(),
    val settingsReady: Boolean = false,
    val monitorRunning: Boolean = false,
    val targetMatched: Boolean = false,
    val automaticSuspended: Boolean = false,
    val foregroundPackage: String? = null,
    val apps: List<AppChoice> = emptyList(),
    val appsLoading: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null
) {
    val manualEnabled get() = snapshot?.enabled == true && snapshot.record.owner == OverrideOwner.MANUAL
    val automaticActive get() = snapshot?.enabled == true && snapshot.record.owner == OverrideOwner.AUTOMATIC
}

/** The Activity and foreground service share one manager, controller and operation lock. */
class AudioPolicyEngine(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = SettingsRepository(app)
    private val manager = ShizukuManager(app)
    private val controller = OverrideController(manager, repository)
    private val automaticPolicy = AutomaticPolicy(controller)
    private val catalog = AppCatalog(app)
    private val mutex = Mutex()
    private val gate = ForegroundGate()
    private val mutableState = MutableStateFlow(PolicyUiState())
    val state = mutableState.asStateFlow()
    private var pollJob: Job? = null
    private var uiVisible = false
    private var serviceStarting = false
    private var lastRead = 0L
    private val power = app.getSystemService(PowerManager::class.java)

    init {
        scope.launch {
            try {
                repository.automation.collect { settings ->
                    mutableState.update { it.copy(automation = settings, settingsReady = true) }
                    if (settings.enabled && uiVisible) {
                        try { ensureService() } catch (e: Exception) { report(e, true) }
                    }
                    if (!settings.enabled && manager.state.value.connected) {
                        operate(showBusy = false, suspendAutomatic = true) {
                            if (!state.value.automation.enabled) publish(controller.releaseAutomatic())
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                report(e, true)
            }
        }
        scope.launch {
            var session = -1L
            manager.state.collect { connection ->
                mutableState.update { it.copy(connection = connection,
                    snapshot = if (connection.connected) it.snapshot else null) }
                if (connection.connected && connection.session != session) {
                    gate.reset()
                    mutableState.update { it.copy(automaticSuspended = false) }
                    operate(showBusy = false, suspendAutomatic = true) {
                        publish(controller.read())
                        if (state.value.settingsReady && !state.value.automation.enabled) {
                            publish(controller.releaseAutomatic())
                        }
                    }
                }
                session = connection.session
            }
        }
        loadApps()
    }

    fun foreground(visible: Boolean) {
        uiVisible = visible
        if (visible) {
            mutableState.update { it.copy(notificationsEnabled = app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
            reload()
            if (state.value.settingsReady && state.value.automation.enabled) ensureService()
        }
    }
    fun requestPermission() = manager.requestPermission()
    fun reload() {
        manager.refresh()
        gate.reset()
        mutableState.update { it.copy(automaticSuspended = false, error = null) }
        if (manager.state.value.connected) operate { publish(controller.read()) }
    }
    fun loadApps() {
        scope.launch {
            mutableState.update { it.copy(appsLoading = true) }
            try {
                val apps = catalog.load()
                mutableState.update { it.copy(apps = apps) }
            }
            catch (e: Exception) { if (e is CancellationException) throw e; report(e, false) }
            finally { mutableState.update { it.copy(appsLoading = false) } }
        }
    }
    fun selectPackages(packages: Set<String>) = operate {
        val targets = packages.filter { it.isNotBlank() && it != app.packageName }.toSet()
        repository.setTargets(targets)
        mutableState.update { it.copy(automation = it.automation.copy(packages = targets)) }
        gate.reset()
        if (targets.isEmpty()) disableAutomation()
        else mutableState.update { it.copy(automaticSuspended = false) }
    }
    fun setAutomation(enabled: Boolean) = operate {
        check(state.value.settingsReady) { "設定を読み込めていません。" }
        if (enabled) {
            check(state.value.automation.packages.isNotEmpty()) { "対象アプリを1つ以上選択してください。" }
            check(manager.state.value.connected) { "Shizuku 権限と UserService 接続を確認してください。" }
            val snapshot = controller.read()
            check(ForceUse.supported(snapshot.current)) { "現在値が想定外のため自動切替を開始できません。" }
            check(!snapshot.record.changePending) { "前回の変更が未確認です。先に復元してください。" }
            repository.setAutomationEnabled(true)
            mutableState.update { it.copy(automation = it.automation.copy(enabled = true), automaticSuspended = false) }
            try { ensureService() }
            catch (e: Exception) {
                repository.setAutomationEnabled(false)
                mutableState.update { it.copy(automation = it.automation.copy(enabled = false)) }
                throw e
            }
        } else disableAutomation()
    }
    private suspend fun disableAutomation() {
        repository.setAutomationEnabled(false)
        mutableState.update { it.copy(automation = it.automation.copy(enabled = false), targetMatched = false) }
        gate.reset()
        try {
            if (manager.state.value.connected) publish(controller.releaseAutomatic())
            else if (repository.read().owner == OverrideOwner.AUTOMATIC && repository.read().originalForceUse != null) {
                error("自動監視は停止しました。復元には Shizuku への再接続が必要です。")
            }
        } finally { app.stopService(Intent(app, AutomationService::class.java)) }
    }
    fun setManual(enabled: Boolean) = operate {
        Log.i(TAG, "Manual override requested: enabled=$enabled")
        publish(automaticPolicy.setManual(enabled,
            state.value.automation.enabled && state.value.targetMatched && !state.value.automaticSuspended))
    }
    fun restore() = operate {
        Log.i(TAG, "Explicit restore requested; stopping automatic mode")
        // Explicit restore also stops automatic mode so it cannot immediately override again.
        repository.setAutomationEnabled(false)
        mutableState.update { it.copy(automation = it.automation.copy(enabled = false), targetMatched = false) }
        gate.reset()
        try { publish(controller.restore()) }
        finally { app.stopService(Intent(app, AutomationService::class.java)) }
    }

    private fun ensureService() {
        if (state.value.monitorRunning || serviceStarting) return
        serviceStarting = true
        try { app.startForegroundService(Intent(app, AutomationService::class.java)) }
        catch (e: Exception) { serviceStarting = false; throw e }
    }
    fun attachMonitor() {
        serviceStarting = false
        if (pollJob?.isActive == true) return
        mutableState.update { it.copy(monitorRunning = true) }
        pollJob = scope.launch {
            var retryAt = 0L
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (state.value.settingsReady && !manager.state.value.connected && now >= retryAt) {
                    manager.refresh()
                    retryAt = now + 5_000
                }
                if (state.value.settingsReady && manager.state.value.connected && !state.value.automaticSuspended) {
                    try {
                        val top = if (power.isInteractive) manager.getForegroundPackage() else ""
                        val matched = state.value.automation.enabled && top in state.value.automation.packages
                        val desired = gate.update(matched, now)
                        mutableState.update { it.copy(foregroundPackage = top.takeIf(String::isNotEmpty), targetMatched = desired) }
                        mutex.withLock {
                            if (!state.value.automaticSuspended) reconcile(desired, now)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        report(e, true)
                    }
                }
                delay(if (power.isInteractive && !state.value.automaticSuspended) 250 else 1_000)
            }
        }
    }
    private suspend fun reconcile(desired: Boolean, now: Long) {
        val cached = state.value.snapshot
        val wantsAutomatic = desired && state.value.automation.enabled
        val needsEnable = wantsAutomatic && cached?.enabled != true
        val needsRelease = !wantsAutomatic && cached?.record?.owner == OverrideOwner.AUTOMATIC && cached.canRestore
        if (now - lastRead >= 2_000 || needsEnable || needsRelease || cached == null) {
            if (needsEnable) Log.i(TAG, "Selected app entered foreground; applying automatic override")
            if (needsRelease) Log.i(TAG, "Selected apps left foreground; restoring automatic override")
            publish(automaticPolicy.reconcile(wantsAutomatic))
        }
    }
    fun detachMonitor() {
        pollJob?.cancel()
        pollJob = null
        serviceStarting = false
        gate.reset()
        mutableState.update { it.copy(monitorRunning = false, targetMatched = false) }
        // Best effort on orderly service destruction; abrupt process death retains the backup.
        if (manager.state.value.connected) operate(showBusy = false, suspendAutomatic = true) {
            if (!state.value.monitorRunning) publish(controller.releaseAutomatic())
        }
    }
    private fun publish(snapshot: PolicySnapshot) {
        lastRead = SystemClock.elapsedRealtime()
        mutableState.update { it.copy(snapshot = if (manager.state.value.connected) snapshot else null) }
    }
    private fun operate(showBusy: Boolean = true, suspendAutomatic: Boolean = false, block: suspend () -> Unit): Job = scope.launch {
        mutex.withLock {
            if (showBusy) mutableState.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                report(e, suspendAutomatic)
                if (manager.state.value.connected) {
                    try { publish(controller.read()) }
                    catch (readError: Exception) { if (readError is CancellationException) throw readError }
                }
            } finally { if (showBusy) mutableState.update { it.copy(busy = false) } }
        }
    }
    private fun report(e: Exception, suspendAutomatic: Boolean) {
        Log.e(TAG, "Audio policy operation failed", e)
        mutableState.update { it.copy(error = e.message ?: e.javaClass.simpleName,
            automaticSuspended = it.automaticSuspended || suspendAutomatic) }
    }
    companion object { private const val TAG = "AudioPolicyEngine" }
}
