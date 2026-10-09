package dev.aqua.audiopolicy.diagnostics

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** App-private bounded file; AtomicFile preserves the previous settings/history on failure. */
class FileDiagnosticStorage(context: Context) : DiagnosticStorage {
    private val file = AtomicFile(File(context.filesDir, "diagnostics.json"))
    override suspend fun read(): DiagnosticState = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext DiagnosticState()
        val json = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        val entries = json.optJSONArray("entries") ?: JSONArray()
        DiagnosticState(enabled = json.optBoolean("enabled", false), entries = (maxOf(0, entries.length() - DiagnosticRecorder.LIMIT) until entries.length()).map { index ->
            val e = entries.getJSONObject(index)
            fun number(key: String): Int? = if (e.isNull(key)) null else e.getInt(key)
            DiagnosticEntry(e.getString("time"), DiagnosticEvent(e.getString("action"), e.getString("source"),
                number("config"), number("result"), number("readBack"), if (e.isNull("failure")) null else e.getString("failure")))
        })
    }
    override suspend fun write(state: DiagnosticState) = withContext(Dispatchers.IO) {
        val entries = JSONArray()
        state.entries.takeLast(DiagnosticRecorder.LIMIT).forEach { entry ->
            entries.put(JSONObject().put("time", entry.time).put("action", entry.event.action).put("source", entry.event.source)
                .put("config", entry.event.config ?: JSONObject.NULL).put("result", entry.event.result ?: JSONObject.NULL)
                .put("readBack", entry.event.readBack ?: JSONObject.NULL).put("failure", entry.event.failure ?: JSONObject.NULL))
        }
        val bytes = JSONObject().put("enabled", state.enabled).put("entries", entries).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
