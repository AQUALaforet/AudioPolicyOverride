package dev.aqua.audiopolicy
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
/** Private direct notification destination; the job belongs to the Application. */
class RestoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "保存した値に復元して、自動切替を停止しています…"; setPadding(32, 64, 32, 32) })
        val job = (application as AudioPolicyApplication).engine.restore("NOTIFICATION")
        lifecycleScope.launch {
            job.join()
            startActivity(Intent(this@RestoreActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }
}
