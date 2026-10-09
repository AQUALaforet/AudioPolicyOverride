package dev.aqua.audiopolicy.diagnostics
import android.content.ContextWrapper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FileDiagnosticStorageTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun realFilePersistsOffOnHistoryAndExplicitDeletion() = runTest {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = folder.root
        }
        val first = DiagnosticRecorder(FileDiagnosticStorage(context))
        first.load(); assertFalse(first.state.value.enabled)
        first.setEnabled(true); first.record(DiagnosticEvent("RESTORE", "NOTIFICATION", 11, 0, 11))
        val second = DiagnosticRecorder(FileDiagnosticStorage(context)); second.load()
        assertTrue(second.state.value.enabled); assertEquals(1, second.state.value.entries.size)
        second.setEnabled(false)
        val third = DiagnosticRecorder(FileDiagnosticStorage(context)); third.load()
        assertFalse(third.state.value.enabled); assertEquals(1, third.state.value.entries.size)
        third.clear()
        val fourth = DiagnosticRecorder(FileDiagnosticStorage(context)); fourth.load()
        assertFalse(fourth.state.value.enabled); assertTrue(fourth.state.value.entries.isEmpty())
    }
}
