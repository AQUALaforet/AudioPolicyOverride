package dev.aqua.audiopolicy.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import dev.aqua.audiopolicy.aidl.IAudioPolicyService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ShizukuManagerTest {
    private class Access : ShizukuAccess {
        var running = true
        var permission = true
        var permissionChecks = 0
        lateinit var received: Shizuku.OnBinderReceivedListener
        lateinit var dead: Shizuku.OnBinderDeadListener
        val bindings = mutableListOf<ServiceConnection>()
        val removals = mutableListOf<Pair<ServiceConnection, Boolean>>()
        override fun ping() = running
        override fun version() = 13
        override fun compatible() = true
        override fun granted(): Boolean { permissionChecks++; return permission }
        override fun rationale() = false
        override fun request(code: Int) = Unit
        override fun bind(args: Shizuku.UserServiceArgs, connection: ServiceConnection) { bindings += connection }
        override fun unbind(args: Shizuku.UserServiceArgs, connection: ServiceConnection, remove: Boolean) { removals += connection to remove }
        override fun listen(received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener,
                            permission: Shizuku.OnRequestPermissionResultListener) { this.received = received; this.dead = dead }
        override fun stopListening(received: Shizuku.OnBinderReceivedListener, dead: Shizuku.OnBinderDeadListener,
                                   permission: Shizuku.OnRequestPermissionResultListener) = Unit
    }
    private class Remote : IAudioPolicyService.Stub() {
        lateinit var death: IBinder.DeathRecipient
        var fail = false
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { death = recipient }
        override fun getForceUse(): Int { if (fail) throw android.os.DeadObjectException(); return 11 }
        override fun setForceUse(config: Int) = 0
        override fun getForegroundPackage() = "camera"
        override fun destroy() = Unit
    }
    private val name = ComponentName("test", "service")
    private fun connected(access: Access, manager: ShizukuManager): Remote {
        access.received.onBinderReceived()
        return Remote().also { access.bindings.last().onServiceConnected(name, it) }
    }
    @Test fun refreshBeforeHandshakeDoesNotTreatPermissionAsDeniedOrBind() {
        val access = Access(); val manager = ShizukuManager(RuntimeEnvironment.getApplication(), access)
        assertFalse(manager.state.value.ready)
        assertEquals(0, access.permissionChecks)
        assertTrue(access.bindings.isEmpty())
        manager.close()
    }
    @Test fun repeatedRefreshAndConnectedCallbackDoNotDoubleBindOrAdvanceSession() {
        val access = Access(); val manager = ShizukuManager(RuntimeEnvironment.getApplication(), access)
        val remote = connected(access, manager)
        repeat(10) { manager.refresh(); access.bindings.last().onServiceConnected(name, remote) }
        assertEquals(1, access.bindings.size)
        assertEquals(1L, manager.state.value.session)
        manager.close(); manager.close()
        assertEquals(listOf(true, false), access.removals.map { it.second })
    }
    @Test fun binderDeathRetriesWithoutAutomaticMonitorAndRejectsOldCallback() {
        val access = Access(); val manager = ShizukuManager(RuntimeEnvironment.getApplication(), access)
        val remote = connected(access, manager); val old = access.bindings.last()
        remote.death.binderDied()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(manager.state.value.connected)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2, access.bindings.size)
        old.onServiceConnected(name, remote)
        assertFalse(manager.state.value.connected)
        access.bindings.last().onServiceConnected(name, Remote())
        assertTrue(manager.state.value.connected)
        assertEquals(2L, manager.state.value.session)
        manager.close()
    }
    @Test fun shizukuRestartRebindsAndClearsDisconnectedState() {
        val access = Access(); val manager = ShizukuManager(RuntimeEnvironment.getApplication(), access)
        connected(access, manager)
        access.running = false; access.dead.onBinderDead()
        assertFalse(manager.state.value.running)
        assertFalse(manager.state.value.granted)
        assertFalse(manager.state.value.connected)
        access.running = true
        connected(access, manager)
        assertTrue(manager.state.value.connected)
        assertEquals(2, access.bindings.size)
        manager.close()
    }
    @Test fun bindTimeoutDiscardsOldConnectionAndRetries() {
        val access = Access(); val manager = ShizukuManager(RuntimeEnvironment.getApplication(), access)
        access.received.onBinderReceived()
        val old = access.bindings.last()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
        assertFalse(manager.state.value.connecting)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2, access.bindings.size)
        old.onServiceDisconnected(name)
        assertTrue(manager.state.value.connecting)
        manager.close()
    }
    @Test fun binderCallExceptionInvalidatesConnection() = runTest {
        val access = Access(); val manager = ShizukuManager(RuntimeEnvironment.getApplication(), access)
        connected(access, manager).fail = true
        assertTrue(runCatching { manager.getForceUse() }.isFailure)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(manager.state.value.connected)
        manager.close()
    }
    @Test fun permissionRevocationDetachesOnlyOnceAndReportsDenied() {
        val access = Access(); val manager = ShizukuManager(RuntimeEnvironment.getApplication(), access)
        connected(access, manager)
        access.permission = false
        repeat(5) { manager.refresh() }
        assertFalse(manager.state.value.granted)
        assertFalse(manager.state.value.connected)
        assertEquals(listOf(true, false), access.removals.map { it.second })
        manager.close()
    }
}
