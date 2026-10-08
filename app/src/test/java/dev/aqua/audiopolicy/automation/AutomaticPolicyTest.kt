package dev.aqua.audiopolicy.automation

import dev.aqua.audiopolicy.data.*
import dev.aqua.audiopolicy.shizuku.AudioPolicyPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AutomaticPolicyTest {
    private class Store(var record: RestoreRecord = RestoreRecord()) : RestoreStore {
        override suspend fun read() = record
        override suspend fun write(record: RestoreRecord) { this.record = record }
    }
    private class Port(var current: Int = 11) : AudioPolicyPort {
        val writes = mutableListOf<Int>()
        var result = 0
        override suspend fun getForceUse() = current
        override suspend fun setForceUse(config: Int): Int {
            writes.add(config)
            if (result == 0) current = config
            return result
        }
    }
    @Test fun targetEntryRepeatedChecksAndExitWriteOnlyOnceEach() = runTest {
        val port = Port(); val store = Store()
        val policy = AutomaticPolicy(OverrideController(port, store))
        repeat(8) { policy.reconcile(true) }
        assertEquals(OverrideOwner.AUTOMATIC, store.record.owner)
        repeat(8) { policy.reconcile(false) }
        assertEquals(listOf(0, 11), port.writes)
        assertEquals(RestoreRecord(), store.record)
    }
    @Test fun manualOnSurvivesAutomaticExitAndStop() = runTest {
        val port = Port(); val store = Store()
        val policy = AutomaticPolicy(OverrideController(port, store))
        policy.setManual(true, false)
        policy.reconcile(true); policy.reconcile(false)
        assertEquals(listOf(0), port.writes)
        assertEquals(RestoreRecord(11, true), store.record)
    }
    @Test fun manualOnDuringAutomaticTakesOwnershipWithoutLosingBackup() = runTest {
        val port = Port(); val store = Store()
        val policy = AutomaticPolicy(OverrideController(port, store))
        policy.reconcile(true)
        policy.setManual(true, true)
        policy.reconcile(false)
        assertEquals(11, store.record.originalForceUse)
        assertEquals(OverrideOwner.MANUAL, store.record.owner)
        assertEquals(0, port.current)
        policy.setManual(false, false)
        assertEquals(11, port.current)
    }
    @Test fun manualOffWhileTargetIsActiveHandsBackToAutomatic() = runTest {
        val port = Port(); val store = Store()
        val policy = AutomaticPolicy(OverrideController(port, store))
        policy.setManual(true, false)
        policy.setManual(false, true)
        assertEquals(OverrideOwner.AUTOMATIC, store.record.owner)
        assertEquals(0, port.current)
        policy.reconcile(false)
        assertEquals(11, port.current)
    }
    @Test fun restartRetainsAutomaticOwnershipAndRestoresWhenOutsideTargets() = runTest {
        val port = Port(); val store = Store()
        AutomaticPolicy(OverrideController(port, store)).reconcile(true)
        AutomaticPolicy(OverrideController(port, store)).reconcile(false)
        assertEquals(listOf(0, 11), port.writes)
        assertEquals(RestoreRecord(), store.record)
    }
    @Test fun externallyRestoredValueIsNotOverwritten() = runTest {
        val port = Port(); val store = Store()
        val policy = AutomaticPolicy(OverrideController(port, store))
        policy.reconcile(true)
        port.current = 11
        policy.reconcile(false)
        assertEquals(listOf(0), port.writes)
        assertEquals(RestoreRecord(), store.record)
    }
    @Test fun externalChangeOfOriginalNoneIsNotOverwritten() = runTest {
        val port = Port(0); val store = Store()
        val policy = AutomaticPolicy(OverrideController(port, store))
        policy.reconcile(true)
        port.current = 11
        assertTrue(runCatching { policy.reconcile(false) }.isFailure)
        assertEquals(listOf(0), port.writes)
        assertEquals(0, store.record.originalForceUse)
    }
    @Test fun ambiguousWriteIsNotAutomaticallyReapplied() = runTest {
        val port = Port(0); val store = Store(RestoreRecord(11, false, true, OverrideOwner.AUTOMATIC))
        val policy = AutomaticPolicy(OverrideController(port, store))
        assertTrue(runCatching { policy.reconcile(true) }.isFailure)
        assertTrue(port.writes.isEmpty())
        policy.reconcile(false)
        assertEquals(11, port.current)
    }
    @Test fun unknownCurrentValueCannotBeAutomaticallyChanged() = runTest {
        val port = Port(7); val store = Store()
        val policy = AutomaticPolicy(OverrideController(port, store))
        assertTrue(runCatching { policy.reconcile(true) }.isFailure)
        assertTrue(port.writes.isEmpty())
    }
    @Test fun nonzeroResultKeepsAutomationInactive() = runTest {
        val port = Port().apply { result = -1 }; val store = Store()
        val policy = AutomaticPolicy(OverrideController(port, store))
        assertTrue(runCatching { policy.reconcile(true) }.isFailure)
        assertEquals(11, port.current)
        assertEquals(RestoreRecord(), store.record)
    }
    @Test fun invalidRecoveryRecordStopsAutomation() = runTest {
        val port = Port(0); val store = Store(RestoreRecord(7, true, owner = OverrideOwner.AUTOMATIC))
        val policy = AutomaticPolicy(OverrideController(port, store))
        assertTrue(runCatching { policy.reconcile(true) }.isFailure)
        assertTrue(runCatching { policy.reconcile(false) }.isFailure)
        assertTrue(port.writes.isEmpty())
    }
}
