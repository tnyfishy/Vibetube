package vibe.tube.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import vibe.tube.R
import javax.inject.Inject
import kotlin.math.roundToInt

/** Foreground service that keeps downloads alive in the background and shows their progress. */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var manager: DownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watcher == null) {
            watcher = scope.launch {
                manager.jobs.collect { jobs ->
                    val active = jobs.filter { it.active }
                    if (active.isEmpty()) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(active))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(active: List<DownloadJob>?): Notification {
        val list = active.orEmpty()
        val current = list.firstOrNull()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openDownloadsIntent(this))
        if (current != null) {
            val extra = (list.size - 1).takeIf { it > 0 }?.let { " (+$it)" }.orEmpty()
            builder.setContentText(current.title + extra + (if (current.paused) " · " + getString(R.string.download_paused) else ""))
            when (current.phase) {
                DownloadPhase.Downloading -> builder.setProgress(100, (current.progress * 100).roundToInt(), false)
                else -> builder.setProgress(0, 0, true)
            }
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.download_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val DONE_CHANNEL_ID = "downloads_done"
        private const val NOTIFICATION_ID = 4107
        const val ACTION_OPEN_DOWNLOADS = "vibe.tube.action.OPEN_DOWNLOADS"

        /** Tapping a download notification lands straight on the Downloaded screen. */
        fun openDownloadsIntent(context: Context): android.app.PendingIntent {
            val intent = Intent(context, vibe.tube.MainActivity::class.java)
                .setAction(ACTION_OPEN_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** A "saved" / "failed" notification that stays after the foreground one is gone; tapping it opens Downloaded. */
        fun notifyFinished(context: Context, title: String, ok: Boolean) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(DONE_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(DONE_CHANNEL_ID, context.getString(R.string.download_done_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val notification = NotificationCompat.Builder(context, DONE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(context.getString(if (ok) R.string.download_done_title else R.string.download_failed_title))
                .setContentText(title)
                .setContentIntent(openDownloadsIntent(context))
                .setAutoCancel(true)
                .build()
            runCatching { manager.notify((System.nanoTime() and 0x3FFFFFFF).toInt() + 5000, notification) }
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
