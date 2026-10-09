package dev.aqua.audiopolicy.automation
import dev.aqua.audiopolicy.data.*
import dev.aqua.audiopolicy.shizuku.ConnectionState
import org.junit.Assert.*
import org.junit.Test
class TilePresentationTest {
    private val ready = PolicyUiState(connection = ConnectionState(running = true, ready = true, granted = true, connected = true), settingsReady = true)
    @Test fun verifiedStatesAndPendingLabels() {
        val inactive = ready.copy(snapshot = PolicySnapshot(11, RestoreRecord()))
        assertEquals("Override 無効", tileLabel(inactive))
        val manual = ready.copy(snapshot = PolicySnapshot(0, RestoreRecord(11, true)))
        assertEquals("手動 Override 有効", tileLabel(manual))
        val auto = manual.copy(snapshot = PolicySnapshot(0, RestoreRecord(11, true, owner = OverrideOwner.AUTOMATIC)))
        assertEquals("自動 Override 有効", tileLabel(auto))
        assertFalse(tileCanOperate(manual.copy(busy = true)))
        assertEquals("操作中／復元待ち", tileLabel(manual.copy(busy = true)))
        for (record in listOf(RestoreRecord(11, true, true), RestoreRecord(9, true), RestoreRecord(11), RestoreRecord(11, true, restoreRequested = true))) {
            assertFalse(tileCanOperate(ready.copy(snapshot = PolicySnapshot(0, record))))
        }
        assertFalse(tileCanOperate(manual.copy(connection = ready.connection.copy(granted = false))))
        assertFalse(tileCanOperate(ready))
        assertFalse(tileCanOperate(ready.copy(snapshot = PolicySnapshot(99, RestoreRecord()))))
    }
    @Test fun recoveryFactsDoNotInferFailureFromNormalManualDisconnect() {
        val record = RestoreRecord(11, true)
        assertNull(recoveryIssue(record, false, null))
        assertEquals(RecoveryKind.UNCONFIRMED, recoveryIssue(record.copy(changePending = true), false, null)?.kind)
        assertEquals(RecoveryKind.RECONNECT_REQUIRED, recoveryIssue(record.copy(restoreRequested = true), false, null)?.kind)
        assertEquals(RecoveryKind.INVALID_RECORD, recoveryIssue(RestoreRecord(overrideActive = true), true, null)?.kind)
        assertNull(recoveryIssue(RestoreRecord(), false, "old failure"))
    }
}
