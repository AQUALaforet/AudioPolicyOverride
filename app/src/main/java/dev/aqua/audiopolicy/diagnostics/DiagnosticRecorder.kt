package dev.aqua.audiopolicy.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

// Values are policy facts only; never foreground package names or exception messages.
data class DiagnosticEvent(val action: String, val source: String, val config: Int? = null,
    val result: Int? = null, val readBack: Int? = null, val failure: String? = null)
data class DiagnosticEntry(val time: String, val event: DiagnosticEvent)
data class DiagnosticState(val enabled: Boolean = false, val entries: List<DiagnosticEntry> = emptyList(),
    val ready: Boolean = false, val error: String? = null)
interface DiagnosticStorage {
    suspend fun read(): DiagnosticState
    suspend fun write(state: DiagnosticState)
}
class DiagnosticRecorder(private val storage: DiagnosticStorage,
    private val now: () -> String = { Instant.now().toString() },
    private val logcat: (DiagnosticEntry) -> Unit = {}) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(DiagnosticState())
    val state = mutableState.asStateFlow()
    private var loaded = false
    private suspend fun loadLocked() {
        if (loaded) return
        val saved = storage.read()
        mutableState.value = saved.copy(ready = true, entries = saved.entries.takeLast(LIMIT))
        loaded = true
    }
    private suspend fun safe(block: suspend () -> Unit) {
        try { block() } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "診断ログの保存・読み込みに失敗しました。音声操作と復元は継続します。設定変更が保存できない場合、再起動後は以前の設定に戻ります。")
        }
    }
    suspend fun load() = mutex.withLock { safe { loadLocked() } }
    suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        safe {
            loadLocked()
            // Stop immediately, even if persisting OFF fails. Keep the existing history.
            if (!enabled) mutableState.value = mutableState.value.copy(enabled = false)
            val next = mutableState.value.copy(enabled = enabled, error = null)
            storage.write(next)
            mutableState.value = next
        }
    }
    suspend fun clear() = mutex.withLock {
        safe {
            try { loadLocked() } catch (_: Exception) { loaded = true; mutableState.value = DiagnosticState(ready = true) }
            val next = mutableState.value.copy(entries = emptyList(), error = null)
            storage.write(next); mutableState.value = next }
    }
    suspend fun record(event: DiagnosticEvent, time: String = now()) = mutex.withLock {
        safe {
            loadLocked()
            if (!mutableState.value.enabled) return@safe
            val entry = DiagnosticEntry(time, event)
            val next = mutableState.value.copy(entries = (mutableState.value.entries + entry).takeLast(LIMIT), error = null)
            storage.write(next)
            mutableState.value = next
            logcat(entry)
        }
    }
    companion object { const val LIMIT = 100 }
}
