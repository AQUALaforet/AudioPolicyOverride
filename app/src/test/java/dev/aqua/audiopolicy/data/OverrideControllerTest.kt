package dev.aqua.audiopolicy.data

import dev.aqua.audiopolicy.shizuku.AudioPolicyPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OverrideControllerTest {
    private class Store(var record: RestoreRecord = RestoreRecord()) : RestoreStore {
        var failWrite = false
        override suspend fun read() = record
        override suspend fun write(record: RestoreRecord) {
            if (failWrite) error("disk failure")
            this.record = record
        }
    }
    private class Port(var current: Int = 11) : AudioPolicyPort {
        var result = 0
        var loseReply = false
        var writes = mutableListOf<Int>()
        var ignoreWrite = false
        override suspend fun getForceUse() = current
        override suspend fun setForceUse(config: Int): Int {
            writes.add(config)
            if (result == 0 && !ignoreWrite) current = config
            if (loseReply) error("Binder reply lost")
            return result
        }
    }
    @Test fun enableAndRestoreAfterRestartPreservesOriginal() = runTest {
        val port = Port(); val store = Store()
        OverrideController(port, store).enable()
        assertEquals(RestoreRecord(11, true), store.record)
        val restarted = OverrideController(port, store)
        restarted.enable() // Must not overwrite 11 with current 0.
        restarted.restore()
        assertEquals(listOf(0, 0, 11), port.writes)
        assertEquals(11, port.current)
        assertEquals(RestoreRecord(), store.record)
    }
    @Test fun nonzeroResultDoesNotMarkOverrideActive() = runTest {
        val port = Port().apply { result = -1 }; val store = Store()
        assertTrue(runCatching { OverrideController(port, store).enable() }.isFailure)
        assertEquals(11, port.current)
        assertEquals(RestoreRecord(), store.record)
    }
    @Test fun unknownValueNeverWrites() = runTest {
        val port = Port(7); val store = Store(RestoreRecord(11, true))
        val controller = OverrideController(port, store)
        assertTrue(runCatching { controller.enable() }.isFailure)
        assertTrue(runCatching { controller.restore() }.isFailure)
        assertTrue(port.writes.isEmpty())
        assertNotNull(controller.read().warning)
    }
    @Test fun failedPersistencePreventsSystemChange() = runTest {
        val port = Port(); val store = Store().apply { failWrite = true }
        assertTrue(runCatching { OverrideController(port, store).enable() }.isFailure)
        assertTrue(port.writes.isEmpty())
    }
    @Test fun lostReplyRetainsRecoveryRecord() = runTest {
        val port = Port().apply { loseReply = true }; val store = Store()
        val controller = OverrideController(port, store)
        assertTrue(runCatching { controller.enable() }.isFailure)
        assertEquals(RestoreRecord(11, false, true), store.record)
        assertEquals(0, controller.read().current)
        assertNotNull(controller.read().warning)
        port.loseReply = false
        OverrideController(port, store).restore()
        assertEquals(11, port.current)
    }
    @Test fun actualStateWinsOverSavedActiveFlag() = runTest {
        val port = Port(11); val store = Store(RestoreRecord(11, true))
        val snapshot = OverrideController(port, store).read()
        assertFalse(snapshot.enabled)
        assertNotNull(snapshot.warning)
        assertTrue(port.writes.isEmpty())
    }
    @Test fun alreadyNoneCanRestoreNone() = runTest {
        val port = Port(0); val store = Store()
        val controller = OverrideController(port, store)
        controller.enable(); controller.restore()
        assertEquals(listOf(0, 0), port.writes)
        assertEquals(RestoreRecord(), store.record)
    }
    @Test fun failedRestoreKeepsBackup() = runTest {
        val port = Port(0).apply { result = -2 }; val store = Store(RestoreRecord(11, true))
        assertTrue(runCatching { OverrideController(port, store).restore() }.isFailure)
        assertEquals(RestoreRecord(11, true), store.record)
    }
    @Test fun restoreReadbackMismatchKeepsRecoveryRecord() = runTest {
        val port = Port(0).apply { ignoreWrite = true }; val store = Store(RestoreRecord(11, true))
        assertTrue(runCatching { OverrideController(port, store).restore() }.isFailure)
        assertEquals(RestoreRecord(11, true, true), store.record)
    }
    @Test fun invalidBackupCannotRestore() = runTest {
        val port = Port(0); val store = Store(RestoreRecord(7, true))
        assertTrue(runCatching { OverrideController(port, store).restore() }.isFailure)
        assertTrue(port.writes.isEmpty())
    }
}
