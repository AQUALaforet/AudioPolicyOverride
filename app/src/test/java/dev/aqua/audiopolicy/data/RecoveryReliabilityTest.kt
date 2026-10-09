package dev.aqua.audiopolicy.data

import dev.aqua.audiopolicy.shizuku.AudioPolicyPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecoveryReliabilityTest {
    private class Store(var record: RestoreRecord = RestoreRecord()) : RestoreStore {
        var failCommit = false
        override suspend fun read() = record
        override suspend fun write(record: RestoreRecord) {
            if (failCommit && !record.changePending) error("commit failed")
            this.record = record
        }
    }
    private class Port(var current: Int = 11) : AudioPolicyPort {
        var reads = 0
        var failReadAt = Int.MAX_VALUE
        var connected = true
        var ignoreWrite = false
        var loseReply = false
        var result = 0
        var pause: CompletableDeferred<Unit>? = null
        var inside = 0
        var maxInside = 0
        val writes = mutableListOf<Int>()
        override suspend fun getForceUse(): Int {
            check(connected && ++reads != failReadAt) { "Binder/get failure" }
            return current
        }
        override suspend fun setForceUse(config: Int): Int {
            check(connected)
            inside++
            maxInside = maxOf(maxInside, inside)
            try {
                pause?.await()
                writes += config
                if (!ignoreWrite) current = config
                if (loseReply) error("reply lost")
                return result
            } finally { inside-- }
        }
    }
    @Test fun initialGetFailureDoesNotChangeStoreOrSystem() = runTest {
        val port = Port().apply { failReadAt = 1 }; val store = Store()
        assertTrue(runCatching { OverrideController(port, store).enable() }.isFailure)
        assertEquals(RestoreRecord(), store.record)
        assertTrue(port.writes.isEmpty())
    }
    @Test fun applyReadbackFailureRemainsPendingAndInactive() = runTest {
        val port = Port().apply { failReadAt = 2 }; val store = Store()
        assertTrue(runCatching { OverrideController(port, store).enable() }.isFailure)
        assertEquals(RestoreRecord(11, false, true), store.record)
        assertFalse(OverrideController(port, store).read().enabled)
    }
    @Test fun applyReadbackMismatchDoesNotCommitActive() = runTest {
        val port = Port().apply { ignoreWrite = true }; val store = Store()
        assertTrue(runCatching { OverrideController(port, store).enable() }.isFailure)
        assertEquals(RestoreRecord(11, false, true), store.record)
    }
    @Test fun commitFailureKeepsPendingBackup() = runTest {
        val port = Port(); val store = Store().apply { failCommit = true }
        assertTrue(runCatching { OverrideController(port, store).enable() }.isFailure)
        assertEquals(RestoreRecord(11, false, true), store.record)
        assertEquals(0, port.current)
    }
    @Test fun staleActiveRecordCannotOverwriteOriginalWithExternalValue() = runTest {
        val port = Port(11); val saved = RestoreRecord(0, true); val store = Store(saved)
        assertTrue(runCatching { OverrideController(port, store).enable() }.isFailure)
        assertEquals(saved, store.record)
        assertTrue(port.writes.isEmpty())
    }
    @Test fun pendingApplyCannotBeReappliedOrTransferred() = runTest {
        val port = Port(0); val saved = RestoreRecord(11, false, true, OverrideOwner.AUTOMATIC)
        val store = Store(saved)
        assertTrue(runCatching { OverrideController(port, store).enable() }.isFailure)
        assertEquals(saved, store.record)
        assertTrue(port.writes.isEmpty())
    }
    @Test fun missingOriginalNeverRestoresOrReportsEnabled() = runTest {
        val port = Port(0); val store = Store(RestoreRecord(overrideActive = true))
        val controller = OverrideController(port, store)
        assertFalse(controller.read().enabled)
        assertTrue(runCatching { controller.restore() }.isFailure)
        assertTrue(port.writes.isEmpty())
    }
    @Test fun disconnectedRestoreKeepsBackupUntilReconnectionAndReadback() = runTest {
        val port = Port(0).apply { failReadAt = 2 }; val store = Store(RestoreRecord(11, true))
        assertTrue(runCatching { OverrideController(port, store).restore() }.isFailure)
        assertEquals(RestoreRecord(11, true, true), store.record)
        port.connected = false
        assertTrue(runCatching { OverrideController(port, store).read() }.isFailure)
        assertEquals(11, store.record.originalForceUse)
        port.connected = true
        OverrideController(port, store).restore()
        assertEquals(RestoreRecord(), store.record)
        assertEquals(11, port.current)
    }
    @Test fun processRecreationAfterLostRestoreReplyCanFinishRecovery() = runTest {
        val port = Port(0).apply { loseReply = true }; val store = Store(RestoreRecord(11, true))
        assertTrue(runCatching { OverrideController(port, store).restore() }.isFailure)
        assertEquals(11, store.record.originalForceUse)
        assertTrue(store.record.changePending)
        port.loseReply = false
        OverrideController(port, store).restore()
        assertEquals(RestoreRecord(), store.record)
    }
    @Test fun cancelledWriteFinishesBeforeNextRestoreStarts() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = Port().apply { pause = gate }; val store = Store()
        val controller = OverrideController(port, store)
        val applying = launch { controller.enable() }
        runCurrent()
        applying.cancel()
        val restoring = launch { controller.restore() }
        runCurrent()
        assertEquals(1, port.inside)
        assertTrue(port.writes.isEmpty())
        gate.complete(Unit)
        applying.join(); restoring.join()
        assertEquals(listOf(0, 11), port.writes)
        assertEquals(1, port.maxInside)
        assertEquals(RestoreRecord(), store.record)
    }
    @Test fun simultaneousRepeatedApplyWritesOnlyOnce() = runTest {
        val port = Port(); val store = Store(); val controller = OverrideController(port, store)
        val jobs = List(20) { launch { controller.enable() } }
        jobs.forEach { it.join() }
        assertEquals(listOf(0), port.writes)
        assertEquals(11, store.record.originalForceUse)
    }
    @Test fun automaticEnableCannotStealManualOwnership() = runTest {
        val port = Port(); val store = Store(); val controller = OverrideController(port, store)
        controller.enable()
        controller.enable(OverrideOwner.AUTOMATIC)
        controller.releaseAutomatic()
        assertEquals(OverrideOwner.MANUAL, store.record.owner)
        assertEquals(listOf(0), port.writes)
    }
    @Test fun orphanOriginalIsNotAutomaticallyRestored() = runTest {
        val port = Port(0); val store = Store(RestoreRecord(11, owner = OverrideOwner.AUTOMATIC))
        assertTrue(runCatching { OverrideController(port, store).releaseAutomatic() }.isFailure)
        assertTrue(port.writes.isEmpty())
        assertEquals(11, store.record.originalForceUse)
    }
    @Test fun lastMomentReturnCancelsAutomaticRestoreWithoutClearingRecord() = runTest {
        val port = Port(); val store = Store(); val controller = OverrideController(port, store)
        controller.enable(OverrideOwner.AUTOMATIC)
        val before = store.record
        controller.releaseAutomatic { false }
        assertEquals(listOf(0), port.writes)
        assertEquals(before, store.record)
        controller.releaseAutomatic { true }
        assertEquals(listOf(0, 11), port.writes)
    }
    @Test fun errorReturnWithChangedRealityRetainsOriginal() = runTest {
        val port = Port().apply { result = -1 }; val store = Store()
        assertTrue(runCatching { OverrideController(port, store).enable() }.isFailure)
        assertEquals(0, port.current)
        assertEquals(RestoreRecord(11, false, true), store.record)
    }
    @Test fun errorReturnAndFailedVerificationRetainsOriginal() = runTest {
        val port = Port().apply { result = -1; failReadAt = 2 }; val store = Store()
        assertTrue(runCatching { OverrideController(port, store).enable() }.isFailure)
        assertEquals(RestoreRecord(11, false, true), store.record)
    }
}
