package dev.aqua.audiopolicy.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.aqua.audiopolicy.MainActivity
import dev.aqua.audiopolicy.R
import dev.aqua.audiopolicy.automation.RecoveryIssue

/** One fixed notification; no permission prompt and no policy mutation here. */
class RecoveryNotifier(context: Context) {
    private val app = context.applicationContext
    private val notifications = app.getSystemService(NotificationManager::class.java)
    fun update(issue: RecoveryIssue?): Boolean {
        val allowed = notifications.areNotificationsEnabled()
        try {
            if (issue == null) notifications.cancel(ID)
            else if (allowed) {
                notifications.createNotificationChannel(NotificationChannel(CHANNEL, "復元待ち・復元エラー", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(app, 20, Intent(app, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                notifications.notify(ID, Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_stat_audio)
                    .setContentTitle(issue.title).setContentText(issue.detail)
                    .setStyle(Notification.BigTextStyle().bigText(issue.detail))
                    .setContentIntent(open).setAutoCancel(false).setOnlyAlertOnce(true).build())
            }
        } catch (e: SecurityException) {
            return false
        }
        return allowed
    }
    companion object { const val ID = 11; const val CHANNEL = "audio_policy_recovery" }
}
