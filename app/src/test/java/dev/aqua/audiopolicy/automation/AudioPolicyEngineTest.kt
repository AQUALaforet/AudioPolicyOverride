package dev.aqua.audiopolicy.automation

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import android.os.PowerManager
import dev.aqua.audiopolicy.aidl.IAudioPolicyService
import dev.aqua.audiopolicy.data.*
import dev.aqua.audiopolicy.diagnostics.*
import dev.aqua.audiopolicy.shizuku.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioPolicyEngineTest {
    private class Store : PolicySettingsStore {
        override val automation = MutableStateFlow(AutomationSettings(true, setOf("camera.a", "camera.b")))
        var failRead = false
        var failRequest = false
        var failOff = false
        var offAttempts = 0
        var onRecordWrite: ((RestoreRecord) -> Unit)? = null
        var record = RestoreRecord()
        override suspend fun read(): RestoreRecord { if (failRead) throw java.io.IOException("record read failed"); return record }
        override suspend fun write(record: RestoreRecord) {
            if (failRequest && record.restoreRequested && !record.changePending) throw java.io.IOException("request save failed")
            this.record = record
            onRecordWrite?.invoke(record)
        }
        override suspend fun setAutomationEnabled(enabled: Boolean) {
            if (!enabled) { offAttempts++; if (failOff) throw java.io.IOException("OFF save failed") }
            automation.value = automation.value.copy(enabled = enabled)
        }
        override suspend fun setTargets(packages: Set<String>) { automation.value = automation.value.copy(packages = packages) }
    }
    private class Access : ShizukuAccess {
        lateinit var received: Shizuku.OnBinderReceivedListener
        lateinit var dead: Shizuku.OnBinderDeadListener
        lateinit var connection: ServiceConnection
        var running = true
        override fun ping() = running
        override fun version() = 13
        override fun compatible() = true
        override fun granted() = true
        override fun rationale() = false
        override fun request(code: Int) = Unit
        override fun bind(args: Shizuku.UserServiceArgs, connection: ServiceConnection) { this.connection = connection }
        override fun unbind(args: Shizuku.UserServiceArgs, connection: ServiceConnection, remove: Boolean) = Unit
        override fun listen(received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener,
                            permission: Shizuku.OnRequestPermissionResultListener) { this.received = received; this.dead = dead }
        override fun stopListening(received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener,
                                   permission: Shizuku.OnRequestPermissionResultListener) = Unit
    }
    private class Remote : IAudioPolicyService.Stub() {
        var reads = 0
        var foregroundReads = 0
        var current = 11
        var top = "camera.a"
        var onRead: (() -> Unit)? = null
        var onWrite: (() -> Unit)? = null
        var setResult = 0
        var readBackWrong = false
        val writes = mutableListOf<Int>()
        override fun getForceUse(): Int { reads++; onRead?.invoke(); return current }
        override fun setForceUse(config: Int): Int { writes += config; if (setResult == 0 && !readBackWrong) current = config; onWrite?.invoke(); return setResult }
        override fun getForegroundPackage(): String { foregroundReads++; return top }
        override fun destroy() = Unit
    }
    private class Fixture(scope: TestScope, diagnostics: DiagnosticRecorder = DiagnosticRecorder(object : DiagnosticStorage {
        var saved = DiagnosticState()
        override suspend fun read() = saved
        override suspend fun write(state: DiagnosticState) { saved = state }
    })) {
        val context = RuntimeEnvironment.getApplication() as Context
        val access = Access()
        val manager = ShizukuManager(context, access, StandardTestDispatcher(scope.testScheduler))
        val store = Store()
        val remote = Remote()
        val engine = AudioPolicyEngine(context, store, manager, scope.backgroundScope, diagnostics) { scope.testScheduler.currentTime }
        init {
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
            shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
            connect()
        }
        fun connect() {
            access.running = true
            access.received.onBinderReceived()
            access.connection.onServiceConnected(ComponentName("test", "service"), remote)
        }
        fun disconnect() { access.running = false; access.dead.onBinderDead() }
    }
    private fun TestScope.start(f: Fixture) {
        f.engine.attachMonitor()
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
    }
    @Test fun shortHomeAndTargetToTargetTransitionsNeverRestore() = runTest {
        val f = Fixture(this)
        start(f)
        assertEquals(listOf(0), f.remote.writes)
        f.remote.top = "home"; advanceTimeBy(250); runCurrent()
        f.remote.top = "camera.b"; advanceTimeBy(250); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf(0), f.remote.writes)
        f.remote.top = "home"; advanceTimeBy(1250); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        f.manager.close()
    }
    @Test fun unknownTaskDoesNotRestoreAndReturnCancelsExitDelay() = runTest {
        val f = Fixture(this)
        start(f)
        f.remote.top = "home"; advanceTimeBy(250); runCurrent()
        f.remote.top = ""; advanceTimeBy(1000); runCurrent()
        assertEquals(listOf(0), f.remote.writes)
        f.remote.top = "camera.a"; advanceTimeBy(1000); runCurrent()
        assertEquals(listOf(0), f.remote.writes)
        f.manager.close()
    }
    @Test fun lockAndScreenOffReleaseAutomaticButNeverManual() = runTest {
        val f = Fixture(this)
        start(f)
        shadowOf(f.context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        advanceTimeBy(1250); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        f.engine.setManual(true); runCurrent()
        shadowOf(f.context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        advanceTimeBy(3000); runCurrent()
        assertEquals(listOf(0, 11, 0), f.remote.writes)
        assertEquals(OverrideOwner.MANUAL, f.store.record.owner)
        f.manager.close()
    }
    @Test fun reconnectedOutsideTargetRestoresSavedAutomaticRecord() = runTest {
        val f = Fixture(this)
        start(f)
        f.disconnect(); runCurrent()
        assertNull(f.engine.state.value.snapshot)
        assertEquals(11, f.store.record.originalForceUse)
        f.remote.top = "home"
        f.connect(); runCurrent(); advanceTimeBy(1250); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        assertEquals(RestoreRecord(), f.store.record)
        f.manager.close()
    }
    @Test fun stoppedMonitorCleanupCannotRestoreNewMonitorOverride() = runTest {
        val f = Fixture(this)
        start(f)
        f.engine.detachMonitor()
        f.engine.attachMonitor()
        runCurrent(); advanceTimeBy(1250); runCurrent()
        assertEquals(listOf(0), f.remote.writes)
        assertTrue(f.engine.state.value.monitorRunning)
        f.manager.close()
    }
    @Test fun manualOffUsesFreshForegroundInsteadOfOldTargetMatch() = runTest {
        val f = Fixture(this)
        start(f)
        f.engine.setManual(true); runCurrent()
        f.remote.top = "home"
        f.engine.setManual(false); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        assertEquals(RestoreRecord(), f.store.record)
        f.manager.close()
    }
    @Test fun targetReturnDuringRestoreReadCancelsTheActualWrite() = runTest {
        val f = Fixture(this)
        start(f)
        f.remote.top = "home"
        advanceTimeBy(250); runCurrent()
        // The queued restore reads AudioSystem before its final foreground confirmation.
        f.remote.onRead = { f.remote.top = "camera.b" }
        advanceTimeBy(1250); runCurrent()
        assertEquals(listOf(0), f.remote.writes)
        assertEquals(11, f.store.record.originalForceUse)
        assertTrue(f.engine.state.value.targetMatched)
        f.manager.close()
    }
    @Test fun changedTargetsExcludingForegroundWaitForNormalExitDelay() = runTest {
        val f = Fixture(this)
        start(f)
        f.engine.selectPackages(setOf("camera.b")); runCurrent()
        advanceTimeBy(250); runCurrent() // First outside observation at 500ms.
        assertEquals(listOf(0), f.remote.writes)
        advanceTimeBy(750); runCurrent() // 750ms outside is still below 800ms.
        assertEquals(listOf(0), f.remote.writes)
        advanceTimeBy(250); runCurrent() // Next monitor tick after the deadline.
        assertEquals(listOf(0, 11), f.remote.writes)
        assertEquals(RestoreRecord(), f.store.record)
        f.manager.close()
    }
    @Test fun changedTargetsStillIncludingForegroundKeepOverrideAndRecord() = runTest {
        val f = Fixture(this)
        start(f)
        val before = f.store.record
        f.engine.selectPackages(setOf("camera.a")); runCurrent()
        advanceTimeBy(2000); runCurrent()
        assertEquals(listOf(0), f.remote.writes)
        assertEquals(before, f.store.record)
        f.manager.close()
    }
    @Test fun returnToNewTargetDuringListChangeExitDelayCancelsRestore() = runTest {
        val f = Fixture(this)
        start(f)
        f.engine.selectPackages(setOf("camera.b")); runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf(0), f.remote.writes)
        f.remote.top = "camera.b"
        advanceTimeBy(2000); runCurrent()
        assertEquals(listOf(0), f.remote.writes)
        assertEquals(11, f.store.record.originalForceUse)
        f.manager.close()
    }
    @Test fun resavingSameTargetsDoesNotShortenOrExtendPendingExit() = runTest {
        val f = Fixture(this)
        start(f)
        f.remote.top = "home"
        advanceTimeBy(250); runCurrent() // Exit begins at 500ms.
        repeat(3) {
            f.engine.selectPackages(setOf("camera.a", "camera.b")); runCurrent()
            advanceTimeBy(250); runCurrent()
            assertEquals(listOf(0), f.remote.writes)
        }
        advanceTimeBy(250); runCurrent() // 1500ms, original exit deadline is unchanged.
        assertEquals(listOf(0, 11), f.remote.writes)
        f.manager.close()
    }
    @Test fun repeatedlyChangingNonemptyTargetsDoesNotPostponePendingExit() = runTest {
        val f = Fixture(this)
        start(f)
        f.remote.top = "home"
        advanceTimeBy(250); runCurrent()
        repeat(3) { index ->
            f.engine.selectPackages(setOf("camera.$index")); runCurrent()
            advanceTimeBy(250); runCurrent()
            assertEquals(listOf(0), f.remote.writes)
        }
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        f.manager.close()
    }
    @Test fun unknownForegroundAfterListChangeDoesNotCountAsExit() = runTest {
        val f = Fixture(this)
        start(f)
        f.remote.top = ""
        f.engine.selectPackages(setOf("camera.b")); runCurrent()
        advanceTimeBy(2000); runCurrent()
        assertEquals(listOf(0), f.remote.writes)
        f.remote.top = "home"
        advanceTimeBy(250); runCurrent()
        advanceTimeBy(750); runCurrent()
        assertEquals(listOf(0), f.remote.writes)
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        f.manager.close()
    }
    @Test fun emptyTargetsAndExplicitOffStillRestoreImmediately() = runTest {
        val f = Fixture(this)
        start(f)
        f.engine.selectPackages(emptySet()); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        assertFalse(f.engine.state.value.automation.enabled)
        f.engine.selectPackages(setOf("camera.a")); runCurrent()
        f.engine.setAutomation(true); runCurrent()
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf(0, 11, 0), f.remote.writes)
        f.engine.setAutomation(false); runCurrent()
        assertEquals(listOf(0, 11, 0, 11), f.remote.writes)
        f.manager.close()
    }
    @Test fun targetChangesAndEmptyTargetsNeverAlterManualOwnershipOrBackup() = runTest {
        val f = Fixture(this)
        start(f)
        f.engine.setManual(true); runCurrent()
        val before = f.store.record
        f.engine.selectPackages(setOf("camera.b")); runCurrent()
        advanceTimeBy(2000); runCurrent()
        assertEquals(before, f.store.record)
        f.engine.selectPackages(emptySet()); runCurrent()
        assertEquals(before, f.store.record)
        assertEquals(OverrideOwner.MANUAL, f.store.record.owner)
        assertEquals(listOf(0), f.remote.writes)
        f.manager.close()
    }

    @Test fun tileManualOnOffAndAutomaticOwnershipHandoff() = runTest {
        val f = Fixture(this); start(f)
        val first = f.engine.toggleFromTile(); runCurrent(); assertTrue(first.await())
        assertEquals(OverrideOwner.MANUAL, f.store.record.owner)
        assertNull(f.engine.state.value.recoveryIssue)
        val second = f.engine.toggleFromTile(); runCurrent(); assertTrue(second.await())
        assertEquals(OverrideOwner.AUTOMATIC, f.store.record.owner)
        assertEquals(listOf(0), f.remote.writes)
        f.remote.top = "home"
        f.engine.setManual(true); runCurrent()
        val third = f.engine.toggleFromTile(); runCurrent(); assertTrue(third.await())
        assertEquals(listOf(0, 11), f.remote.writes)
        f.manager.close()
    }
    @Test fun tileOnFromInactiveAndDuplicateRequestsCoalesce() = runTest {
        val f = Fixture(this); runCurrent()
        val a = f.engine.toggleFromTile(); val b = f.engine.toggleFromTile()
        assertSame(a, b); runCurrent(); assertTrue(a.await())
        assertEquals(listOf(0), f.remote.writes)
        assertTrue(f.engine.state.value.manualEnabled)
        f.manager.close()
    }
    @Test fun tileRejectsDisconnectedPendingAndInvalidRecords() = runTest {
        val f = Fixture(this); runCurrent()
        for (record in listOf(RestoreRecord(11, true, true), RestoreRecord(9, true), RestoreRecord(restoreRequested = true))) {
            f.store.record = record; f.engine.refreshForTile(); runCurrent()
            assertFalse(tileCanOperate(f.engine.state.value))
            val action = f.engine.toggleFromTile(); runCurrent(); assertFalse(action.await())
        }
        f.disconnect(); runCurrent()
        val action = f.engine.toggleFromTile(); runCurrent(); assertFalse(action.await())
        assertTrue(f.remote.writes.isEmpty())
        f.manager.close()
    }
    @Test fun notificationRestoreStopsAutoReleasesManualAndFencesQueuedOn() = runTest {
        val f = Fixture(this); start(f)
        f.engine.setManual(true); runCurrent()
        f.engine.setManual(true)
        val a = f.engine.restore(); val b = f.engine.restore()
        assertSame(a, b)
        f.engine.setManual(true); f.engine.setAutomation(true); f.engine.toggleFromTile()
        runCurrent(); advanceTimeBy(3000); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        assertEquals(RestoreRecord(), f.store.record)
        assertFalse(f.engine.state.value.automation.enabled)
        assertNull(f.engine.state.value.recoveryIssue)
        f.manager.close()
    }
    @Test fun notificationWithoutRecordOnlyReadsAndStops() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.restore(); runCurrent()
        assertTrue(f.remote.writes.isEmpty())
        assertFalse(f.engine.state.value.automation.enabled)
        assertEquals(11, f.engine.state.value.snapshot?.current)
        f.manager.close()
    }
    @Test fun failedRestoreKeepsIntentUpdatesNotificationAndReconnectClears() = runTest {
        val f = Fixture(this); start(f)
        f.engine.setManual(true); runCurrent()
        f.remote.setResult = -1
        f.engine.restore(); runCurrent()
        assertEquals(11, f.store.record.originalForceUse)
        assertTrue(f.store.record.restoreRequested)
        assertEquals(RecoveryKind.RESTORE_FAILED, f.engine.state.value.recoveryIssue?.kind)
        val notifications = shadowOf(f.context.getSystemService(android.app.NotificationManager::class.java))
        assertNotNull(notifications.getNotification(dev.aqua.audiopolicy.notifications.RecoveryNotifier.ID))
        f.disconnect(); runCurrent()
        assertEquals(RecoveryKind.RECONNECT_REQUIRED, f.engine.state.value.recoveryIssue?.kind)
        f.remote.setResult = 0; f.connect(); runCurrent()
        assertEquals(RestoreRecord(), f.store.record)
        assertNull(f.engine.state.value.recoveryIssue)
        assertNull(notifications.getNotification(dev.aqua.audiopolicy.notifications.RecoveryNotifier.ID))
        f.manager.close()
    }
    @Test fun readBackMismatchKeepsBackupAndReportsRestoreFailure() = runTest {
        val f = Fixture(this); start(f)
        f.remote.readBackWrong = true
        f.engine.restore(); runCurrent()
        assertTrue(f.store.record.changePending)
        assertTrue(f.store.record.restoreRequested)
        assertEquals(11, f.store.record.originalForceUse)
        assertEquals(RecoveryKind.RESTORE_FAILED, f.engine.state.value.recoveryIssue?.kind)
        f.manager.close()
    }
    @Test fun normalManualOnAndDisconnectAreNotRestoreFailure() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setManual(true); runCurrent(); assertNull(f.engine.state.value.recoveryIssue)
        f.disconnect(); runCurrent(); assertNull(f.engine.state.value.recoveryIssue)
        assertFalse(tileCanOperate(f.engine.state.value))
        f.manager.close()
    }
    @Test fun noNotificationPermissionStillKeepsUiIssueAndRecord() = runTest {
        val f = Fixture(this)
        val notifications = shadowOf(f.context.getSystemService(android.app.NotificationManager::class.java))
        notifications.setNotificationsEnabled(false)
        start(f); f.remote.setResult = -1; f.engine.restore(); runCurrent()
        assertFalse(f.engine.state.value.notificationsEnabled)
        assertNotNull(f.engine.state.value.recoveryIssue)
        assertTrue(f.store.record.restoreRequested)
        assertNull(notifications.getNotification(dev.aqua.audiopolicy.notifications.RecoveryNotifier.ID))
        f.manager.close()
    }
    @Test fun processRecoveryHonorsExplicitRestoreButNotNormalManual() = runTest {
        val f = Fixture(this)
        f.store.record = RestoreRecord(11, true, false, OverrideOwner.MANUAL, true)
        f.remote.current = 0; runCurrent()
        assertEquals(listOf(11), f.remote.writes)
        assertEquals(RestoreRecord(), f.store.record)
        assertFalse(f.engine.state.value.automation.enabled)
        f.manager.close()
    }

    @Test fun disconnectDuringRestoreReadBackRetainsRecordUntilReconnected() = runTest {
        val f = Fixture(this); start(f)
        f.engine.setManual(true); runCurrent()
        f.remote.onWrite = { f.disconnect() }
        f.engine.restore(); runCurrent()
        assertTrue(f.store.record.changePending)
        assertTrue(f.store.record.restoreRequested)
        assertEquals(RecoveryKind.RECONNECT_REQUIRED, f.engine.state.value.recoveryIssue?.kind)
        f.remote.onWrite = null; f.connect(); runCurrent()
        assertEquals(RestoreRecord(), f.store.record)
        assertNull(f.engine.state.value.recoveryIssue)
        f.manager.close()
    }
    @Test fun startupUnconfirmedManualRecordIsNotAutomaticallyRestored() = runTest {
        val f = Fixture(this)
        f.store.record = RestoreRecord(11, true, true, OverrideOwner.MANUAL)
        f.remote.current = 0; runCurrent()
        assertTrue(f.remote.writes.isEmpty())
        assertEquals(RecoveryKind.UNCONFIRMED, f.engine.state.value.recoveryIssue?.kind)
        assertFalse(tileCanOperate(f.engine.state.value))
        f.manager.close()
    }
    @Test fun automaticRestoreFailureHasOneNotificationAndClearsAfterRecovery() = runTest {
        val f = Fixture(this); start(f)
        f.remote.setResult = -1; f.remote.top = "home"
        advanceTimeBy(1500); runCurrent()
        assertEquals(RecoveryKind.RESTORE_FAILED, f.engine.state.value.recoveryIssue?.kind)
        val notifications = shadowOf(f.context.getSystemService(android.app.NotificationManager::class.java))
        assertEquals(1, notifications.size())
        advanceTimeBy(2000); runCurrent(); assertEquals(1, notifications.size())
        f.remote.setResult = 0; f.engine.reload(); runCurrent()
        advanceTimeBy(2500); runCurrent() // suspended monitor had a one-second sleep
        assertEquals(RestoreRecord(), f.store.record)
        assertEquals(0, notifications.size())
        f.manager.close()
    }
    @Test fun invalidRecordExplicitStopNeverGuessesAValue() = runTest {
        val f = Fixture(this); runCurrent()
        f.store.record = RestoreRecord(9, true)
        f.engine.restore(); runCurrent()
        assertEquals(9, f.store.record.originalForceUse)
        assertTrue(f.remote.writes.isEmpty())
        assertFalse(f.engine.state.value.automation.enabled)
        assertEquals(RecoveryKind.INVALID_RECORD, f.engine.state.value.recoveryIssue?.kind)
        f.manager.close()
    }

    @Test fun failedExplicitRestoreIsNotRepeatedByOffCollectorOrServiceTeardown() = runTest {
        val f = Fixture(this); start(f)
        f.remote.setResult = -1
        f.engine.restore(); runCurrent()
        f.engine.detachMonitor(); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        assertTrue(f.store.record.restoreRequested)
        assertEquals(RecoveryKind.RESTORE_FAILED, f.engine.state.value.recoveryIssue?.kind)
        f.manager.close()
    }
    @Test fun tileRefreshDoesNotResetExitDeadline() = runTest {
        val f = Fixture(this); start(f)
        f.remote.top = "home"; advanceTimeBy(250); runCurrent()
        repeat(3) {
            f.engine.refreshForTile(); runCurrent()
            advanceTimeBy(250); runCurrent()
            assertEquals(listOf(0), f.remote.writes)
        }
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        f.manager.close()
    }
    @Test fun verifiedManualOnSupersedesEarlierAutomaticExitFailure() = runTest {
        val f = Fixture(this); start(f)
        f.remote.setResult = -1; f.remote.top = "home"
        advanceTimeBy(1500); runCurrent()
        assertNotNull(f.engine.state.value.recoveryIssue)
        f.engine.setManual(true); runCurrent()
        assertTrue(f.engine.state.value.manualEnabled)
        assertNull(f.engine.state.value.recoveryIssue)
        assertEquals(11, f.store.record.originalForceUse)
        f.manager.close()
    }

    @Test fun diagnosticOffCopyGetsCurrentFactsWithoutRecordingHistory() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.setManual(true); runCurrent()
        val info = f.engine.diagnosticInfo()
        assertTrue(info.contains("FOR_SYSTEM: 0"))
        assertTrue(info.contains("originalForceUse=11"))
        assertTrue(info.contains("Android:"))
        assertFalse(info.contains("History ("))
        assertTrue(f.engine.diagnostics.state.value.entries.isEmpty())
        f.engine.restore(); runCurrent(); assertEquals(RestoreRecord(), f.store.record)
        f.manager.close()
    }
    @Test fun diagnosticOnRecordsOperationsButNotMonitorOrUnselectedAppHistory() = runTest {
        val f = Fixture(this); runCurrent()
        f.engine.diagnostics.setEnabled(true)
        start(f)
        assertTrue(f.engine.diagnostics.state.value.entries.any { it.event.action == "APPLY" && it.event.source == "AUTOMATIC" })
        val count = f.engine.diagnostics.state.value.entries.size
        advanceTimeBy(5000); runCurrent()
        assertEquals(count, f.engine.diagnostics.state.value.entries.size)
        f.remote.top = "private.unselected.app"; advanceTimeBy(1500); runCurrent()
        assertTrue(f.engine.diagnostics.state.value.entries.any { it.event.action == "RESTORE" })
        f.remote.top = "another.unselected.app"; advanceTimeBy(2000); runCurrent()
        val info = f.engine.diagnosticInfo()
        assertFalse(info.contains("private.unselected.app")); assertFalse(info.contains("another.unselected.app"))
        val history = f.engine.diagnostics.state.value.entries
        f.engine.diagnostics.setEnabled(false)
        f.engine.setManual(true); f.disconnect(); runCurrent()
        assertEquals(history, f.engine.diagnostics.state.value.entries)
        assertTrue(f.engine.diagnosticInfo().contains("History ("))
        f.manager.close()
    }
    @Test fun engineRestoresAndNotifiesWithFailingDiagnosticStorage() = runTest {
        val recorder = DiagnosticRecorder(object : DiagnosticStorage {
            override suspend fun read() = DiagnosticState(enabled = true)
            override suspend fun write(state: DiagnosticState) { throw java.io.IOException() }
        })
        val f = Fixture(this, recorder); start(f)
        f.remote.setResult = -1; f.engine.restore("NOTIFICATION"); runCurrent()
        assertTrue(f.store.record.restoreRequested)
        assertNotNull(f.engine.state.value.recoveryIssue)
        f.remote.setResult = 0; f.engine.restore("NOTIFICATION"); runCurrent()
        assertEquals(RestoreRecord(), f.store.record)
        assertNull(f.engine.state.value.recoveryIssue)
        assertNotNull(recorder.state.value.error)
        f.manager.close()
    }

    @Test fun restoreRequestSaveFailureStillAttemptsPersistentOff() = runTest {
        val f = Fixture(this); start(f); f.store.failRequest = true
        f.engine.restore(); runCurrent()
        assertTrue(f.store.offAttempts > 0)
        assertFalse(f.engine.state.value.automation.enabled)
        assertNotNull(f.engine.state.value.error)
        assertEquals(listOf(0, 11), f.remote.writes)
        f.manager.close()
    }
    @Test fun manualOnSkipsForegroundCallsButOffUsesLatestForeground() = runTest {
        val f = Fixture(this); start(f); f.engine.setManual(true); runCurrent()
        val foreground = f.remote.foregroundReads; val reads = f.remote.reads
        advanceTimeBy(4000); runCurrent()
        assertEquals(foreground, f.remote.foregroundReads)
        assertTrue(f.remote.reads > reads)
        assertNull(f.engine.state.value.foregroundPackage)
        f.remote.top = "camera.b"; f.engine.setManual(false); runCurrent()
        assertTrue(f.remote.foregroundReads > foreground)
        assertEquals(OverrideOwner.AUTOMATIC, f.store.record.owner)
        assertEquals(listOf(0), f.remote.writes)
        f.manager.close()
    }
    @Test fun screenOffRestoresThenStopsPeriodicAudioReadsUntilWake() = runTest {
        val f = Fixture(this); start(f)
        shadowOf(f.context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        advanceTimeBy(2500); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        assertEquals(RestoreRecord(), f.store.record)
        val reads = f.remote.reads; val foreground = f.remote.foregroundReads
        advanceTimeBy(10000); runCurrent()
        assertEquals(reads, f.remote.reads); assertEquals(foreground, f.remote.foregroundReads)
        shadowOf(f.context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        f.context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON)); shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(listOf(0, 11, 0), f.remote.writes)
        f.manager.close()
    }
    @Test fun slowDiagnosticCopyDoesNotHoldAudioOperationLock() = runTest {
        val release = CompletableDeferred<Unit>()
        val recorder = DiagnosticRecorder(object : DiagnosticStorage {
            override suspend fun read(): DiagnosticState { release.await(); return DiagnosticState() }
            override suspend fun write(state: DiagnosticState) = Unit
        })
        val f = Fixture(this, recorder); runCurrent()
        val copy = async { f.engine.diagnosticInfo() }; runCurrent()
        val manual = f.engine.setManual(true); runCurrent()
        val completedBeforeRelease = manual.isCompleted
        release.complete(Unit); runCurrent()
        assertTrue(completedBeforeRelease)
        assertTrue(copy.await().contains("FOR_SYSTEM: 11"))
        assertTrue(f.engine.state.value.manualEnabled)
        f.manager.close()
    }

    @Test fun bothSavesFailButStoredOnCannotReviveAutomaticProcessing() = runTest {
        val f = Fixture(this); start(f)
        f.store.failRequest = true; f.store.failOff = true
        f.engine.setManual(true) // queued before stop: must be invalidated
        f.engine.setAutomation(true)
        f.engine.restore(); f.engine.setAutomation(true); runCurrent()
        assertTrue(f.store.automation.value.enabled)
        assertTrue(f.store.offAttempts > 0)
        assertFalse(f.engine.state.value.automation.enabled)
        assertTrue(f.engine.state.value.automaticStoppedInProcess)
        assertNotNull(f.engine.state.value.stopPersistenceError)
        val writes = f.remote.writes.toList()
        // A new emission whose stored enabled value is still true.
        f.store.automation.value = f.store.automation.value.copy(packages = setOf("camera.a"))
        runCurrent(); f.engine.reload(); runCurrent()
        f.disconnect(); runCurrent(); f.connect(); runCurrent()
        f.engine.foreground(true); runCurrent(); advanceTimeBy(5000); runCurrent()
        assertFalse(f.engine.state.value.automation.enabled)
        assertTrue(f.engine.state.value.automaticStoppedInProcess)
        assertNotNull(f.engine.state.value.stopPersistenceError)
        assertEquals(writes, f.remote.writes)
        // Explicit ON is the permitted release path, after settings storage recovers.
        f.store.failRequest = false; f.store.failOff = false
        f.engine.setAutomation(true); runCurrent(); advanceTimeBy(250); runCurrent()
        assertTrue(f.engine.state.value.automation.enabled)
        assertFalse(f.engine.state.value.automaticStoppedInProcess)
        assertNull(f.engine.state.value.stopPersistenceError)
        assertEquals(writes + 0, f.remote.writes)
        f.manager.close()
    }
    @Test fun stopDuringOnReadPreventsItsDelayedWrite() = runTest {
        val f = Fixture(this); runCurrent()
        var interrupted = false
        f.remote.onRead = {
            if (!interrupted) { interrupted = true; f.engine.restore() }
        }
        f.engine.setManual(true); runCurrent()
        assertTrue(interrupted)
        assertFalse(f.remote.writes.contains(0))
        assertTrue(f.engine.state.value.automaticStoppedInProcess)
        assertFalse(f.engine.state.value.automation.enabled)
        f.manager.close()
    }
    @Test fun stopDuringAutomaticForegroundReadDoesNotApply() = runTest {
        val f = Fixture(this); runCurrent()
        f.remote.onRead = {
            f.remote.onRead = null
            f.engine.restore()
        }
        f.engine.attachMonitor(); runCurrent(); advanceTimeBy(250); runCurrent()
        assertFalse(f.remote.writes.contains(0))
        assertFalse(f.engine.state.value.automation.enabled)
        f.manager.close()
    }
    @Test fun manualVerificationStillDetectsExternalChangeAndPreservesBackup() = runTest {
        val f = Fixture(this); start(f); f.engine.setManual(true); runCurrent()
        val foreground = f.remote.foregroundReads
        f.remote.current = 11; advanceTimeBy(2250); runCurrent()
        assertFalse(f.engine.state.value.manualEnabled)
        assertTrue(f.engine.state.value.automaticSuspended)
        assertEquals(11, f.store.record.originalForceUse)
        assertEquals(OverrideOwner.MANUAL, f.store.record.owner)
        assertEquals(foreground, f.remote.foregroundReads)
        assertNotNull(f.engine.state.value.error)
        f.manager.close()
    }
    @Test fun pendingManualRecordIsNotUsedToSkipSafetyChecks() = runTest {
        val f = Fixture(this)
        f.store.record = RestoreRecord(11, true, true, OverrideOwner.MANUAL)
        f.remote.current = 0; start(f)
        assertFalse(f.engine.state.value.manualEnabled)
        assertTrue(f.engine.state.value.automaticSuspended)
        assertTrue(f.remote.foregroundReads > 0)
        assertTrue(f.store.record.changePending)
        f.manager.close()
    }
    @Test fun lockRestoresOnceAndDuplicateWakeEventsDoNotApplyUntilUnlocked() = runTest {
        val f = Fixture(this); start(f)
        shadowOf(f.context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        advanceTimeBy(1750); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        val reads = f.remote.reads
        repeat(3) { f.context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON)) }
        shadowOf(Looper.getMainLooper()).idle(); runCurrent(); advanceTimeBy(5000); runCurrent()
        assertEquals(reads, f.remote.reads)
        shadowOf(f.context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        f.context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(listOf(0, 11, 0), f.remote.writes)
        f.manager.close()
    }
    @Test fun manualOnWhileScreenIdleWakesPeriodicVerificationWithoutForegroundReads() = runTest {
        val f = Fixture(this); start(f)
        shadowOf(f.context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        advanceTimeBy(2500); runCurrent()
        f.engine.setManual(true); runCurrent()
        val reads = f.remote.reads; val foreground = f.remote.foregroundReads
        advanceTimeBy(4000); runCurrent()
        assertTrue(f.remote.reads > reads)
        assertEquals(foreground, f.remote.foregroundReads)
        assertTrue(f.engine.state.value.manualEnabled)
        assertEquals(OverrideOwner.MANUAL, f.store.record.owner)
        f.manager.close()
    }
    @Test fun screenOffFailedRestoreIsRetainedAndReloadCanCompleteIt() = runTest {
        val f = Fixture(this); start(f); f.remote.setResult = -1
        shadowOf(f.context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        advanceTimeBy(2500); runCurrent()
        assertTrue(f.store.record.hasRecovery)
        assertNotNull(f.engine.state.value.recoveryIssue)
        assertTrue(f.engine.state.value.automaticSuspended)
        f.remote.setResult = 0; f.engine.reload(); runCurrent()
        advanceTimeBy(3500); runCurrent()
        assertEquals(RestoreRecord(), f.store.record)
        val reads = f.remote.reads
        advanceTimeBy(10000); runCurrent(); assertEquals(reads, f.remote.reads)
        f.manager.close()
    }
    @Test fun slowDiagnosticCopyDoesNotBlockExplicitRestoreOrMixPolicyFacts() = runTest {
        val release = CompletableDeferred<Unit>()
        val recorder = DiagnosticRecorder(object : DiagnosticStorage {
            override suspend fun read(): DiagnosticState { release.await(); return DiagnosticState() }
            override suspend fun write(state: DiagnosticState) = Unit
        })
        val f = Fixture(this, recorder); start(f)
        val copy = async { f.engine.diagnosticInfo() }; runCurrent()
        val restore = f.engine.restore(); runCurrent()
        val completedBeforeRelease = restore.isCompleted
        release.complete(Unit); runCurrent()
        assertTrue(completedBeforeRelease)
        val text = copy.await()
        assertTrue(text.contains("FOR_SYSTEM: 0"))
        assertTrue(text.contains("originalForceUse=11"))
        assertEquals(RestoreRecord(), f.store.record)
        assertEquals(11, f.remote.current)
        f.manager.close()
    }

    @Test fun stopAfterPendingSaveButBeforeNativeWriteCancelsOn() = runTest {
        val f = Fixture(this); runCurrent()
        f.store.onRecordWrite = { record ->
            if (record.changePending && !record.restoreRequested) {
                f.store.onRecordWrite = null
                f.engine.restore()
            }
        }
        f.engine.setManual(true); runCurrent()
        assertFalse(f.remote.writes.contains(0))
        assertEquals(RestoreRecord(), f.store.record)
        assertTrue(f.engine.state.value.automaticStoppedInProcess)
        f.manager.close()
    }
    @Test fun staleAutomaticOnCannotClearStopAfterItsSuspendedRead() = runTest {
        val f = Fixture(this); runCurrent()
        f.remote.onRead = { f.remote.onRead = null; f.engine.restore() }
        f.engine.setAutomation(true); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertTrue(f.engine.state.value.automaticStoppedInProcess)
        assertFalse(f.engine.state.value.automation.enabled)
        assertFalse(f.remote.writes.contains(0))
        f.manager.close()
    }
    @Test fun monitorReattachAndDuplicateScreenEventsKeepOneOwnerAndBackup() = runTest {
        val f = Fixture(this); start(f); f.engine.attachMonitor(); runCurrent()
        f.engine.detachMonitor(); runCurrent()
        assertEquals(listOf(0, 11), f.remote.writes)
        f.context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON)); shadowOf(Looper.getMainLooper()).idle()
        advanceTimeBy(1000); runCurrent(); assertEquals(listOf(0, 11), f.remote.writes)
        f.engine.attachMonitor(); f.engine.attachMonitor(); runCurrent()
        repeat(3) { f.context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)) }
        shadowOf(Looper.getMainLooper()).idle(); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(listOf(0, 11, 0), f.remote.writes)
        assertEquals(11, f.store.record.originalForceUse)
        assertEquals(OverrideOwner.AUTOMATIC, f.store.record.owner)
        f.engine.detachMonitor(); runCurrent(); f.manager.close()
    }

    @Test fun unreadableBackupStillStopsAndRetainsLastKnownRecoveryNotice() = runTest {
        val f = Fixture(this); start(f)
        f.store.failRead = true; f.engine.restore(); runCurrent()
        assertTrue(f.store.offAttempts > 0)
        assertTrue(f.engine.state.value.automaticStoppedInProcess)
        assertFalse(f.engine.state.value.automation.enabled)
        assertNotNull(f.engine.state.value.recoveryIssue)
        assertNotNull(f.engine.state.value.stopPersistenceError)
        assertEquals(11, f.store.record.originalForceUse)
        assertEquals(listOf(0), f.remote.writes)
        f.store.failRead = false; f.engine.reload(); runCurrent()
        assertEquals(RestoreRecord(), f.store.record)
        assertEquals(listOf(0, 11), f.remote.writes)
        assertNull(f.engine.state.value.recoveryIssue)
        f.manager.close()
    }
}
