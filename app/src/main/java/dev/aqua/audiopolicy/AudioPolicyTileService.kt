package dev.aqua.audiopolicy
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.aqua.audiopolicy.automation.tileCanOperate
import dev.aqua.audiopolicy.automation.tileLabel
import kotlinx.coroutines.*
class AudioPolicyTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine get() = (application as AudioPolicyApplication).engine
    private var listening: Job? = null
    private var click: Job? = null
    private var unlocking = false
    private var sequence = 0L
    override fun onStartListening() {
        super.onStartListening()
        engine.refreshForTile()
        listening?.cancel()
        listening = scope.launch {
            engine.state.collect { policy ->
                qsTile?.apply {
                    label = tileLabel(policy)
                    contentDescription = label
                    if (Build.VERSION.SDK_INT >= 29) subtitle = "Audio Policy Override"
                    state = when {
                        policy.busy -> Tile.STATE_UNAVAILABLE
                        tileCanOperate(policy) && (policy.manualEnabled || policy.automaticActive) -> Tile.STATE_ACTIVE
                        else -> Tile.STATE_INACTIVE
                    }
                    updateTile()
                }
            }
        }
    }
    override fun onStopListening() {
        listening?.cancel(); listening = null; unlocking = false
        super.onStopListening()
    }
    override fun onClick() {
        super.onClick()
        if (unlocking || click?.isActive == true || engine.state.value.busy) return
        val request = ++sequence
        val action = Runnable {
            unlocking = false
            if (request == sequence) click = scope.launch {
                if (!engine.toggleFromTile().await()) openApp()
            }
        }
        if (isLocked) { unlocking = true; unlockAndRun(action) } else action.run()
    }
    // PendingIntent overload exists only on API 34+. The old call stays below that guard.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 21, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else startActivityAndCollapse(intent)
    }
    override fun onDestroy() { ++sequence; scope.cancel(); super.onDestroy() }
}
