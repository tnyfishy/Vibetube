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

@Singleton
class NewVideoNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actions: AccountActions,
) {
    private val prefs = context.getSharedPreferences("new_video_notifier", Context.MODE_PRIVATE)

    suspend fun check() {
        
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
        fresh.forEach { item ->
            val id = NOTIFICATION_BASE + (item.id.hashCode() and 0xFFFFF)

            val open = PendingIntent.getActivity(context, id, openIntent(context, item), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notifications)
                .setContentTitle(item.title)
                .setContentText(item.timeText)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { manager.notify(id, notification) }
        }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_OPEN_NOTIFICATIONS = "vibe.tube.action.OPEN_NOTIFICATIONS"
        const val ACTION_OPEN_TARGET = "vibe.tube.action.OPEN_TARGET"
        const val EXTRA_TITLE = "vibe.tube.extra.TITLE"
        const val EXTRA_THUMBNAIL = "vibe.tube.extra.THUMBNAIL"

        fun openIntent(context: Context, item: vibe.tube.youtube.model.NotificationItem): Intent {
            val base = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val target = item.target ?: return base.setAction(ACTION_OPEN_NOTIFICATIONS)
            return base.setAction(ACTION_OPEN_TARGET)
                .setData(android.net.Uri.parse(target.toUrl()))
                .putExtra(EXTRA_TITLE, item.title)
                .putExtra(EXTRA_THUMBNAIL, item.thumbnail)
        }
        private const val CHANNEL_ID = "new_videos"
        private const val KNOWN = "known"
        private const val MAX_AT_ONCE = 5
        private const val NOTIFICATION_BASE = 20_000
    }
}
