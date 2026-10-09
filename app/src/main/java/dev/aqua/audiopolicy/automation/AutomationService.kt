package dev.aqua.audiopolicy.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.aqua.audiopolicy.AudioPolicyApplication
import dev.aqua.audiopolicy.MainActivity
import dev.aqua.audiopolicy.RestoreActivity
import dev.aqua.audiopolicy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AutomationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine get() = (application as AudioPolicyApplication).engine
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "アプリの自動切替", NotificationManager.IMPORTANCE_LOW))
        val initial = notification("対象アプリの監視を開始しています")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, initial)
        engine.attachMonitor()
        scope.launch {
            engine.state.map { state ->
                when {
                    !state.settingsReady -> "設定を読み込み中"
                    !state.automation.enabled -> "自動切替を停止しました"
                    !state.connection.connected -> "Shizuku 接続待ち・復元情報を保持中"
                    state.automaticSuspended -> "自動切替が一時停止しています。アプリで確認してください"
                    state.manualEnabled -> "手動 ON を優先しています"
                    state.automaticActive -> "対象アプリの音声ポリシーを無効化中"
                    else -> "対象アプリの起動を待っています"
                }
            }.distinctUntilChanged().collect { notifications.notify(ID, notification(it)) }
        }
        scope.launch {
            engine.state.collect { if (it.settingsReady && !it.automation.enabled) stopSelf() }
        }
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Private direct Activity destination awaits the shared Engine job.
        val stop = PendingIntent.getActivity(this, 1, Intent(this, RestoreActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_audio)
            .setContentTitle("Audio Policy Override").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "復元して停止", stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        engine.detachMonitor()
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "automatic_audio_policy"
        private const val ID = 10
    }
}
