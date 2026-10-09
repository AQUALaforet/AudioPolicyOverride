package dev.aqua.audiopolicy.diagnostics

import dev.aqua.audiopolicy.data.*
import dev.aqua.audiopolicy.shizuku.AudioPolicyPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRecorderTest {
    private class Storage : DiagnosticStorage {
        var saved = DiagnosticState()
        var fail = false
        override suspend fun read() = saved
        override suspend fun write(state: DiagnosticState) { if (fail) throw java.io.IOException(); saved = state }
    }
    private val event = DiagnosticEvent("APPLY", "TILE", 0, 0, 0)
    @Test fun defaultOffOnOffPersistenceAndLogcatGating() = runTest {
        val store = Storage(); val logcat = mutableListOf<DiagnosticEntry>()
        val recorder = DiagnosticRecorder(store, logcat = { logcat += it })
        recorder.load(); assertFalse(recorder.state.value.enabled)
        recorder.record(event); assertTrue(store.saved.entries.isEmpty()); assertTrue(logcat.isEmpty())
        recorder.setEnabled(true); recorder.record(event)
        assertEquals(1, logcat.size)
        val restarted = DiagnosticRecorder(store); restarted.load(); assertTrue(restarted.state.value.enabled)
        recorder.setEnabled(false)
        repeat(10) { recorder.record(event) }
        assertEquals(1, store.saved.entries.size); assertEquals(1, logcat.size)
        val stopped = DiagnosticRecorder(store); stopped.load(); assertFalse(stopped.state.value.enabled)
        assertEquals(1, stopped.state.value.entries.size)
    }
    @Test fun limitKeepsLatest100AndExplicitDeleteRetainsSetting() = runTest {
        val store = Storage(); var timestamp = 0
        val recorder = DiagnosticRecorder(store, now = { (++timestamp).toString() })
        recorder.setEnabled(true)
        repeat(120) { recorder.record(event) }
        assertEquals(100, store.saved.entries.size)
        assertEquals("21", store.saved.entries.first().time)
        assertEquals("120", store.saved.entries.last().time)
        recorder.clear(); assertTrue(store.saved.enabled); assertTrue(store.saved.entries.isEmpty())
        recorder.record(event); recorder.setEnabled(false); recorder.clear()
        assertFalse(store.saved.enabled); assertTrue(store.saved.entries.isEmpty())
    }
    @Test fun failedDiagnosticStorageCannotBlockApplyOrRestore() = runTest {
        val storage = Storage(); storage.saved = DiagnosticState(enabled = true); storage.fail = true
        val recorder = DiagnosticRecorder(storage)
        val store = object : RestoreStore {
            var record = RestoreRecord()
            override suspend fun read() = record
            override suspend fun write(record: RestoreRecord) { this.record = record }
        }
        val port = object : AudioPolicyPort {
            var actual = 11
            override suspend fun getForceUse() = actual
            override suspend fun setForceUse(config: Int): Int { actual = config; return 0 }
        }
        val controller = OverrideController(port, store) { recorder.record(it.copy(source = "UI")) }
        assertTrue(controller.enable().enabled)
        assertEquals(11, controller.restore().current)
        assertEquals(RestoreRecord(), store.record)
        assertNotNull(recorder.state.value.error)
        recorder.setEnabled(false); assertFalse(recorder.state.value.enabled)
        recorder.record(event); assertTrue(recorder.state.value.entries.isEmpty())
    }
    @Test fun returningNonzeroAndReadBackValuesAreRecordedOnlyWhileEnabled() = runTest {
        val storage = Storage(); val recorder = DiagnosticRecorder(storage); recorder.setEnabled(true)
        val store = object : RestoreStore {
            var record = RestoreRecord()
            override suspend fun read() = record
            override suspend fun write(record: RestoreRecord) { this.record = record }
        }
        val port = object : AudioPolicyPort {
            override suspend fun getForceUse() = 11
            override suspend fun setForceUse(config: Int) = -7
        }
        val controller = OverrideController(port, store) { recorder.record(it.copy(source = "NOTIFICATION")) }
        try { controller.enable() } catch (_: IllegalStateException) { }
        assertEquals(-7, storage.saved.entries.first().event.result)
        assertEquals(11, storage.saved.entries.last().event.readBack)
        assertEquals("NOTIFICATION", storage.saved.entries.last().event.source)
        recorder.setEnabled(false); val before = storage.saved.entries
        try { controller.enable() } catch (_: IllegalStateException) { }
        assertEquals(before, storage.saved.entries)
    }
}
