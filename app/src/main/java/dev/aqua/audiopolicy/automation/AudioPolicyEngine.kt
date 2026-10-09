package dev.aqua.audiopolicy.automation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import android.os.PowerManager
import android.app.NotificationManager
import android.app.KeyguardManager
import android.os.SystemClock
import android.util.Log
import android.os.Build
import dev.aqua.audiopolicy.diagnostics.*
import dev.aqua.audiopolicy.ForceUse
import dev.aqua.audiopolicy.data.AppCatalog
import dev.aqua.audiopolicy.data.AppChoice
import dev.aqua.audiopolicy.data.AutomationSettings
import dev.aqua.audiopolicy.data.OverrideController
import dev.aqua.audiopolicy.data.OverrideOwner
import dev.aqua.audiopolicy.data.PolicySnapshot
import dev.aqua.audiopolicy.data.SettingsRepository
import dev.aqua.audiopolicy.data.PolicySettingsStore
import dev.aqua.audiopolicy.shizuku.ConnectionState
import dev.aqua.audiopolicy.shizuku.ShizukuManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.aqua.audiopolicy.data.RestoreRecord
import dev.aqua.audiopolicy.notifications.RecoveryNotifier
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val automaticStoppedInProcess: Boolean = false,
    val stopPersistenceError: String? = null,
    val foregroundPackage: String? = null,
    val apps: List<AppChoice> = emptyList(),
    val appsLoading: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val busy: Boolean = false,
    val recoveryRecord: RestoreRecord? = null,
    val recoveryIssue: RecoveryIssue? = null,
    val error: String? = null
) {
    val manualEnabled get() = snapshot?.enabled == true && snapshot.record.owner == OverrideOwner.MANUAL
    val automaticActive get() = snapshot?.enabled == true && snapshot.record.owner == OverrideOwner.AUTOMATIC
}

/** The Activity and foreground service share one manager, controller and operation lock. */
class AudioPolicyEngine internal constructor(private val app: Context,
    private val repository: PolicySettingsStore, private val manager: ShizukuManager,
    private val scope: CoroutineScope,
    val diagnostics: DiagnosticRecorder = DiagnosticRecorder(FileDiagnosticStorage(app), logcat = { Log.i("AudioPolicyDiagnostics", it.toString()) }),
    private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    constructor(app: Context) : this(app, SettingsRepository(app), ShizukuManager(app),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    private var operationSource = "RECOVERY"
    private val controller = OverrideController(manager, repository) { event -> recordDiagnostic(event.copy(source = operationSource)) }
    private val automaticPolicy = AutomaticPolicy(controller)
    private val catalog = AppCatalog(app)
    private val mutex = Mutex()
    private val gate = ForegroundGate()
    private val mutableState = MutableStateFlow(PolicyUiState())
    val state = mutableState.asStateFlow()
    private var pollJob: Job? = null
    private var restoreJob: Job? = null
    private var tileJob: Deferred<Boolean>? = null
    private var commandEpoch = 0L
    private var automaticStopped = false
    private var restorePendingInProcess = false
    private val monitorWake = Channel<Unit>(Channel.CONFLATED)
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { monitorWake.trySend(Unit) }
    }
    private var restoreFailure: String? = null
    private val notifier = RecoveryNotifier(app)
    private var uiVisible = false
    private var serviceStarting = false
    private var monitorGeneration = 0L
    private var validatedSession = -1L
    private var lastRead = 0L
    private val power = app.getSystemService(PowerManager::class.java)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)

    init {
        scope.launch { diagnostics.load() }
        operate(showBusy = false, source = "RECOVERY") { syncRecovery() }
        scope.launch {
            state.map { it.recoveryIssue to it.notificationsEnabled }.distinctUntilChanged().collect { (issue, _) ->
                val allowed = notifier.update(issue)
                mutableState.update { it.copy(notificationsEnabled = allowed) }
            }
        }
        scope.launch {
            try {
                repository.automation.collect { settings ->
                    mutableState.update { it.copy(automation = settings.copy(enabled = settings.enabled && !automaticStopped), settingsReady = true) }
                    if (settings.enabled && !automaticStopped && uiVisible) {
                        try { ensureService() } catch (e: Exception) { report(e, true) }
                    }
                    if (!settings.enabled && manager.state.value.connected) {
                        operate(showBusy = false, suspendAutomatic = true) {
                            if (!state.value.automation.enabled) publish(releaseAutomatic())
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
            var previousConnection: ConnectionState? = null
            manager.state.collect { connection ->
                if (previousConnection?.let { it.running == connection.running && it.ready == connection.ready && it.granted == connection.granted && it.connected == connection.connected } != true) {
                    recordDiagnostic(DiagnosticEvent("CONNECTION running=${connection.running} ready=${connection.ready} granted=${connection.granted} connected=${connection.connected}", "SHIZUKU"))
                }
                previousConnection = connection
                val newSession = connection.connected && connection.session != session
                if (!connection.connected || newSession) validatedSession = -1L
                mutableState.update { it.copy(connection = connection,
                    snapshot = if (connection.connected && !newSession) it.snapshot else null,
                    recoveryIssue = recoveryIssue(it.recoveryRecord, connection.connected, restoreFailure)) }
                if (newSession) {
                    val expectedSession = connection.session
                    operate(showBusy = false, suspendAutomatic = true, source = "RECOVERY") {
                        if (!manager.state.value.connected || manager.state.value.session != expectedSession) return@operate
                        gate.reset()
                        mutableState.update { it.copy(automaticSuspended = false, targetMatched = false) }
                        publish(controller.read())
                        seedGateFromRecovery()
                        resumeExplicitRestore()
                        if (state.value.settingsReady && !state.value.automation.enabled) {
                            publish(releaseAutomatic())
                        }
                        if (manager.state.value.connected && manager.state.value.session == expectedSession) validatedSession = expectedSession
                        monitorWake.trySend(Unit)
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
            if (state.value.settingsReady && state.value.automation.enabled) {
                try { ensureService() } catch (e: Exception) { report(e, true) }
            }
        }
    }
    fun requestPermission() = manager.requestPermission()
    fun reload() {
        manager.refresh()
        operate {
            gate.reset()
            mutableState.update { it.copy(automaticSuspended = false, targetMatched = false, error = null) }
            if (manager.state.value.connected) {
                publish(controller.read())
                seedGateFromRecovery()
                resumeExplicitRestore()
                validatedSession = manager.state.value.session
                if (state.value.settingsReady && !state.value.automation.enabled) publish(releaseAutomatic())
            }
        }
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
        // Preserve active and the original exit deadline for nonempty lists, including
        // identical saves. The next observation uses the new targets; explicit stop resets.
        if (targets.isEmpty()) disableAutomation()
        else mutableState.update { it.copy(automaticSuspended = false) }
    }
    fun setAutomation(enabled: Boolean): Job {
        val epoch = if (restoreJob?.isActive == true) -1L else commandEpoch
        return operate {
            if (enabled && (epoch != commandEpoch || restoreJob?.isActive == true)) return@operate
            check(state.value.settingsReady) { "設定を読み込めていません。" }
            if (enabled) {
                check(state.value.automation.packages.isNotEmpty()) { "対象アプリを1つ以上選択してください。" }
                check(manager.state.value.connected) { "Shizuku 権限と UserService 接続を確認してください。" }
                val snapshot = controller.read()
                check(ForceUse.supported(snapshot.current)) { "現在値が想定外のため自動切替を開始できません。" }
                check(!snapshot.record.changePending) { "前回の変更が未確認です。先に復元してください。" }
                check(!snapshot.record.restoreRequested) { "復元待ちです。先に復元してください。" }
                check(snapshot.warning == null && (!snapshot.record.hasRecovery || snapshot.enabled)) { "復元記録と実際の値を確認できません。先に復元してください。" }
                if (epoch != commandEpoch || restoreJob?.isActive == true) return@operate
                repository.setAutomationEnabled(true)
                if (epoch != commandEpoch || restoreJob?.isActive == true) return@operate
                automaticStopped = false // only an explicit, validated ON clears the process stop
                mutableState.update { it.copy(automation = it.automation.copy(enabled = true), automaticSuspended = false, automaticStoppedInProcess = false, stopPersistenceError = null) }
                try { ensureService() }
                catch (e: Exception) {
                    repository.setAutomationEnabled(false)
                    mutableState.update { it.copy(automation = it.automation.copy(enabled = false)) }
                    throw e
                }
            } else disableAutomation()
        }
    }
    private suspend fun disableAutomation() {
        repository.setAutomationEnabled(false)
        mutableState.update { it.copy(automation = it.automation.copy(enabled = false), targetMatched = false) }
        gate.reset()
        try {
            if (manager.state.value.connected) publish(releaseAutomatic())
            else if (repository.read().owner == OverrideOwner.AUTOMATIC && repository.read().originalForceUse != null) {
                error("自動監視は停止しました。復元には Shizuku への再接続が必要です。")
            }
        } catch (e: Exception) { syncRecovery(e.message); throw e }
        finally { app.stopService(Intent(app, AutomationService::class.java)) }
    }
    fun setManual(enabled: Boolean): Job {
        val epoch = if (restoreJob?.isActive == true) -1L else commandEpoch
        return operate {
            if (epoch != commandEpoch || restoreJob?.isActive == true) return@operate
            manual(enabled)
        }
    }
    private suspend fun manual(enabled: Boolean, source: String = "UI") {
        operationSource = source
        val epoch = commandEpoch
        val target = if (!enabled && state.value.automation.enabled && !state.value.automaticSuspended) observeTarget() else false
        try {
            val snapshot = automaticPolicy.setManual(enabled, target == true) { epoch == commandEpoch && restoreJob?.isActive != true }
            // A verified manual ON supersedes a previous implicit OFF/auto exit intent.
            // An explicit restoreRequested transaction is rejected by the controller.
            if (enabled && snapshot.enabled) restoreFailure = null
            publish(snapshot)
            monitorWake.trySend(Unit)
            if (enabled) {
                gate.reset()
                mutableState.update { it.copy(foregroundPackage = null, targetMatched = false) }
            } else if (target == true) gate.update(true, elapsed()) else gate.reset()
            recordDiagnostic(DiagnosticEvent(if (enabled) "MANUAL_ON" else "MANUAL_OFF", source, readBack = snapshot.current))
        }
        catch (e: Exception) { if (!enabled && target != true) syncRecovery(e.message); throw e }
    }
    /** Application-owned job survives Activity recreation and coalesces repeated requests. */
    fun restore(source: String = "UI"): Job {
        restoreJob?.takeIf { it.isActive }?.let { return it }
        ++commandEpoch
        stopInProcess()
        restorePendingInProcess = true
        val job = operate(start = CoroutineStart.LAZY, source = source) { performExplicitRestore(source) }
        restoreJob = job
        job.start()
        return job
    }
    private fun stopInProcess() {
        automaticStopped = true
        gate.reset()
        mutableState.update { it.copy(automation = it.automation.copy(enabled = false),
            automaticStoppedInProcess = true, targetMatched = false, foregroundPackage = null) }
    }
    private suspend fun stopAutomaticSettings() {
        stopInProcess() // takes effect even when DataStore.edit fails
        repository.setAutomationEnabled(false)
    }
    private suspend fun performExplicitRestore(source: String) {
        val failures = mutableListOf<String>()
        val persistenceFailures = mutableListOf<String>()
        suspend fun attempt(label: String, persistence: Boolean = false, block: suspend () -> Unit) {
            try { block() } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = "$label: ${e.message ?: e.javaClass.simpleName}"
                failures += message
                if (persistence) persistenceFailures += message
            }
        }
        try {
            // Independent attempts: saving the recovery intent must not prevent saving OFF.
            attempt("復元要求を保存できませんでした", true) { controller.requestRestore() }
            attempt("自動OFFを保存できませんでした", true) { stopAutomaticSettings() }
            mutableState.update { it.copy(stopPersistenceError = persistenceFailures.takeIf { errors -> errors.isNotEmpty() }?.joinToString("\n")) }
            // The controller still requires a valid original and a durable pending write.
            attempt("復元を完了できませんでした") { publish(controller.restoreIfPresent()) }
            if (failures.isNotEmpty()) {
                val error = IllegalStateException(failures.joinToString("\n"))
                syncRecovery(error.message)
                throw error
            }
            recordDiagnostic(DiagnosticEvent("RESTORED_AND_STOPPED", source, readBack = state.value.snapshot?.current))
        } finally { app.stopService(Intent(app, AutomationService::class.java)) }
    }
    private suspend fun resumeExplicitRestore() {
        operationSource = "RECOVERY"
        if (!restorePendingInProcess && state.value.recoveryRecord?.restoreRequested != true) return
        if (!automaticStopped) ++commandEpoch
        stopInProcess()
        restorePendingInProcess = true
        performExplicitRestore("RECOVERY")
    }
    fun refreshForTile() {
        manager.refresh()
        operate(showBusy = false) { if (manager.state.value.connected) publish(controller.read()) }
    }
    /** false opens the app without flipping an unverified state. */
    fun toggleFromTile(): Deferred<Boolean> {
        tileJob?.takeIf { it.isActive }?.let { return it }
        val epoch = if (restoreJob?.isActive == true) -1L else commandEpoch
        val job = scope.async(start = CoroutineStart.LAZY) {
            mutex.withLock {
                if (epoch != commandEpoch || restoreJob?.isActive == true || !state.value.connection.connected || !state.value.settingsReady) return@withLock false
                mutableState.update { it.copy(busy = true) }
                try {
                    publish(controller.read())
                    if (!tileCanOperate(state.value.copy(busy = false))) return@withLock false
                    manual(!state.value.manualEnabled, "TILE")
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    report(e, false)
                    try { syncRecovery() } catch (_: Exception) { /* keep the policy error visible */ }
                    false
                } finally { mutableState.update { it.copy(busy = false) } }
            }
        }
        tileJob = job
        job.start()
        return job
    }
    private suspend fun releaseAutomatic(): PolicySnapshot = try {
        // Explicit recovery owns the next retry. OFF collectors and service teardown
        // must not start a second restore immediately after its failed transaction.
        val actual = controller.read()
        if (automaticStopped || actual.record.restoreRequested) actual else controller.releaseAutomatic()
    }
    catch (e: Exception) { syncRecovery(e.message); throw e }
    private suspend fun syncRecovery(failure: String? = null) {
        if (failure != null) restoreFailure = failure
        val record = try { repository.read() } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A failed read cannot prove that the last known backup was cleared.
            mutableState.update { it.copy(recoveryIssue = recoveryIssue(it.recoveryRecord, manager.state.value.connected, restoreFailure)) }
            throw e
        }
        if (!record.hasRecovery) restoreFailure = null
        mutableState.update { it.copy(recoveryRecord = record,
            recoveryIssue = recoveryIssue(record, manager.state.value.connected, restoreFailure)) }
    }

    private fun recordDiagnostic(event: DiagnosticEvent) {
        // Never wait for diagnostic disk IO while holding the policy/Binder operation lock.
        scope.launch { diagnostics.record(event) }
    }
    fun setDiagnosticRecording(enabled: Boolean) = scope.launch { diagnostics.setEnabled(enabled) }
    fun deleteDiagnosticLogs() = scope.launch { diagnostics.clear() }
    suspend fun diagnosticInfo(): String {
        // Capture only policy facts under the operation lock. Disk IO and formatting
        // below use immutable local snapshots and cannot block another audio command.
        val facts = mutex.withLock {
            val snapshot = try { controller.read().also(::publish) } catch (_: Exception) { null }
            val record = snapshot?.record ?: try { repository.read() } catch (_: Exception) { null }
            Triple(snapshot?.current, record, manager.state.value)
        }
        diagnostics.load()
        val diagnosticSnapshot = diagnostics.state.value.let { it.copy(entries = it.entries.toList()) }
        val (current, record, connection) = facts
        return buildString {
            appendLine("Audio Policy Override ${dev.aqua.audiopolicy.BuildConfig.VERSION_NAME}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Connection: installed=${connection.installed}, running=${connection.running}, ready=${connection.ready}, granted=${connection.granted}, connected=${connection.connected}")
            appendLine("FOR_SYSTEM: ${current ?: "取得できません（未接続または読み取り失敗）"}")
            appendLine("Recovery: ${record ?: "取得できません"}")
            appendLine("Recording: ${diagnosticSnapshot.enabled}")
            if (diagnosticSnapshot.entries.isNotEmpty()) {
                appendLine("History (latest ${diagnosticSnapshot.entries.size}):")
                diagnosticSnapshot.entries.forEach { appendLine("${it.time} ${it.event}") }
            }
        }
    }

    private fun ensureService() {
        if (automaticStopped || state.value.monitorRunning || serviceStarting) return
        serviceStarting = true
        try { app.startForegroundService(Intent(app, AutomationService::class.java)) }
        catch (e: Exception) { serviceStarting = false; throw e }
    }
    fun attachMonitor() {
        serviceStarting = false
        if (pollJob?.isActive == true) return
        registerScreenReceiver()
        seedGateFromRecovery()
        val generation = ++monitorGeneration
        mutableState.update { it.copy(monitorRunning = true) }
        pollJob = scope.launch {
            var retryAt = 0L
            var screenIdle = false
            while (true) {
                val now = elapsed()
                if (state.value.settingsReady && !manager.state.value.connected && now >= retryAt) {
                    manager.refresh()
                    retryAt = now + 5_000
                }
                if (state.value.settingsReady && manager.state.value.connected &&
                    validatedSession == manager.state.value.session && !automaticStopped && !state.value.automaticSuspended && restoreJob?.isActive != true) {
                    try {
                        mutex.withLock {
                            currentCoroutineContext().ensureActive()
                            if (generation == monitorGeneration && !automaticStopped && !state.value.automaticSuspended && manager.state.value.connected && restoreJob?.isActive != true) {
                                val nowLocked = elapsed()
                                val screenUnavailable = !power.isInteractive || keyguard.isKeyguardLocked
                                val verified = state.value.snapshot
                                if (state.value.manualEnabled && verified?.warning == null) {
                                    screenIdle = false
                                    mutableState.update { it.copy(foregroundPackage = null, targetMatched = false) }
                                    if (nowLocked - lastRead >= 2_000) {
                                        val actual = controller.read()
                                        publish(actual)
                                        check(actual.enabled && actual.record.owner == OverrideOwner.MANUAL && actual.warning == null) {
                                            "手動Overrideの保存状態と実際の値が一致しません。復元情報を保持しています。"
                                        }
                                    }
                                    return@withLock
                                }
                                if (screenUnavailable && verified != null && ForceUse.supported(verified.current) && verified.warning == null && !verified.record.hasRecovery &&
                                    state.value.recoveryIssue == null && !restorePendingInProcess) {
                                    screenIdle = true
                                    mutableState.update { it.copy(foregroundPackage = null, targetMatched = false) }
                                    return@withLock
                                }
                                if (screenIdle && !screenUnavailable) publish(controller.read())
                                screenIdle = false
                                // Observe after taking the lock, not before waiting behind commands.
                                val matched = observeTarget()
                                currentCoroutineContext().ensureActive()
                                if (generation != monitorGeneration || automaticStopped || restoreJob?.isActive == true) return@withLock
                                val observedAt = elapsed()
                                val desired = gate.update(matched, observedAt)
                                mutableState.update { it.copy(targetMatched = desired) }
                                if (matched != null) reconcile(desired, observedAt)
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        report(e, true)
                    }
                }
                val interval = when {
                    screenIdle && screenReceiverRegistered -> 30_000L
                    screenIdle -> 1_000L
                    !power.isInteractive || state.value.automaticSuspended -> 1_000L
                    else -> 250L
                }
                withTimeoutOrNull(interval) { monitorWake.receive() }
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        } catch (_: Exception) { /* retain a one-second screen-state fallback */ }
    }
    private fun seedGateFromRecovery() {
        val snapshot = state.value.snapshot
        if (snapshot?.current == ForceUse.NONE && snapshot.record.owner == OverrideOwner.AUTOMATIC && snapshot.canRestore) {
            gate.update(true, elapsed())
        }
    }
    private suspend fun observeTarget(): Boolean? {
        if (!state.value.automation.enabled || !power.isInteractive || keyguard.isKeyguardLocked) {
            mutableState.update { it.copy(foregroundPackage = null) }
            return false
        }
        val top = manager.getForegroundPackage()
        mutableState.update { it.copy(foregroundPackage = top.takeIf(String::isNotEmpty)) }
        return if (top.isEmpty()) null else top in state.value.automation.packages
    }
    private suspend fun reconcile(desired: Boolean, now: Long) {
        operationSource = "AUTOMATIC"
        val cached = state.value.snapshot
        val wantsAutomatic = desired && state.value.automation.enabled
        val needsEnable = wantsAutomatic && cached?.enabled != true
        val needsRelease = !wantsAutomatic && cached?.record?.owner == OverrideOwner.AUTOMATIC && cached.canRestore
        if (now - lastRead >= 2_000 || needsEnable || needsRelease || cached == null) {
            try { publish(automaticPolicy.reconcile(wantsAutomatic, canApply = { !automaticStopped && restoreJob?.isActive != true }) {
                val latest = observeTarget()
                val latestDesired = gate.update(latest, elapsed())
                mutableState.update { it.copy(targetMatched = latestDesired) }
                latest == false && !latestDesired
            }) } catch (e: Exception) { syncRecovery(if (needsRelease) e.message else null); throw e }
        }
    }
    fun detachMonitor() {
        if (screenReceiverRegistered) {
            screenReceiverRegistered = false
            try { app.unregisterReceiver(screenReceiver) } catch (_: Exception) { /* already removed */ }
        }
        pollJob?.cancel()
        ++monitorGeneration
        pollJob = null
        serviceStarting = false
        gate.reset()
        mutableState.update { it.copy(monitorRunning = false, targetMatched = false) }
        // Best effort on orderly service destruction; abrupt process death retains the backup.
        if (manager.state.value.connected) operate(showBusy = false, suspendAutomatic = true) {
            if (!state.value.monitorRunning) publish(releaseAutomatic())
        }
    }
    private fun publish(snapshot: PolicySnapshot) {
        lastRead = elapsed()
        if (!snapshot.record.hasRecovery) { restoreFailure = null; restorePendingInProcess = false }
        mutableState.update { it.copy(snapshot = if (manager.state.value.connected) snapshot else null,
            recoveryRecord = snapshot.record, recoveryIssue = recoveryIssue(snapshot.record, manager.state.value.connected, restoreFailure)) }
    }
    private fun operate(showBusy: Boolean = true, suspendAutomatic: Boolean = false, start: CoroutineStart = CoroutineStart.DEFAULT, source: String = "UI", block: suspend () -> Unit): Job = scope.launch(start = start) {
        mutex.withLock {
            operationSource = source
            if (showBusy) mutableState.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                report(e, suspendAutomatic)
                try { syncRecovery() } catch (_: Exception) { /* keep the policy error visible */ }
                if (manager.state.value.connected) {
                    try { publish(controller.read()) }
                    catch (readError: Exception) {
                        if (readError is CancellationException) throw readError
                        mutableState.update { it.copy(snapshot = null) }
                    }
                }
            } finally { if (showBusy) mutableState.update { it.copy(busy = false) } }
        }
    }
    private fun report(e: Exception, suspendAutomatic: Boolean) {
        val source = operationSource
        recordDiagnostic(DiagnosticEvent("FAILED", source, failure = e.javaClass.simpleName))
        mutableState.update { it.copy(snapshot = null, error = e.message ?: e.javaClass.simpleName,
            automaticSuspended = it.automaticSuspended || suspendAutomatic) }
    }
    companion object { private const val TAG = "AudioPolicyEngine" }
}
