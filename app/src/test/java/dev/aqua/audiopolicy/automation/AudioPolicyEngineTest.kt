package dev.aqua.audiopolicy.automation

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.PowerManager
import dev.aqua.audiopolicy.aidl.IAudioPolicyService
import dev.aqua.audiopolicy.data.*
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
        var record = RestoreRecord()
        override suspend fun read() = record
        override suspend fun write(record: RestoreRecord) { this.record = record }
        override suspend fun setAutomationEnabled(enabled: Boolean) { automation.value = automation.value.copy(enabled = enabled) }
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
        var current = 11
        var top = "camera.a"
        var onRead: (() -> Unit)? = null
        val writes = mutableListOf<Int>()
        override fun getForceUse(): Int { onRead?.invoke(); return current }
        override fun setForceUse(config: Int): Int { writes += config; current = config; return 0 }
        override fun getForegroundPackage() = top
        override fun destroy() = Unit
    }
    private class Fixture(scope: TestScope) {
        val context = RuntimeEnvironment.getApplication() as Context
        val access = Access()
        val manager = ShizukuManager(context, access, StandardTestDispatcher(scope.testScheduler))
        val store = Store()
        val remote = Remote()
        val engine = AudioPolicyEngine(context, store, manager, scope.backgroundScope) { scope.testScheduler.currentTime }
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
}
