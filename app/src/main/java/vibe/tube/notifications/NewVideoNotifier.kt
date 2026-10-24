package vibe.tube.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import vibe.tube.MainActivity
import vibe.tube.R
import vibe.tube.data.repo.AccountActions
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the account's YouTube notifications (new uploads from channels with the bell on) into system notifications.
 * Runs from a periodic job; the first run only notes what is already there, so turning the switch on does not flood.
 */
@Singleton
class NewVideoNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actions: AccountActions,
) {
    private val prefs = context.getSharedPreferences("new_video_notifier", Context.MODE_PRIVATE)

    suspend fun check() {
        // Signed out, offline or expired: nothing to do until the next run.
        val page = runCatching { actions.notifications(null) }.getOrNull() ?: return
        val known = prefs.getStringSet(KNOWN, null)
        val remembered = (page.items.map { it.id } + known.orEmpty()).distinct().take(300).toSet()
        prefs.edit().putStringSet(KNOWN, remembered).apply()
        if (known == null) return
        val fresh = page.items.filter { !it.read && it.id !in known }.take(MAX_AT_ONCE)
        if (fresh.isEmpty() || !canPost()) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_NOTIFICATIONS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fresh.forEach { item ->
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notifications)
                .setContentTitle(item.title)
                .setContentText(item.timeText)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { manager.notify(NOTIFICATION_BASE + (item.id.hashCode() and 0xFFFFF), notification) }
        }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_OPEN_NOTIFICATIONS = "vibe.tube.action.OPEN_NOTIFICATIONS"
        private const val CHANNEL_ID = "new_videos"
        private const val KNOWN = "known"
        private const val MAX_AT_ONCE = 5
        private const val NOTIFICATION_BASE = 20_000
    }
}
