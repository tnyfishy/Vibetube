package vibe.tube.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import vibe.tube.MainActivity
import vibe.tube.R
import vibe.tube.ui.VIcon
import javax.inject.Inject
import javax.inject.Singleton

/** Tells the user, once per release, that a newer version is published. */
@Singleton
class UpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: UpdateSource,
) {
    private val prefs = context.getSharedPreferences("update_notifier", Context.MODE_PRIVATE)

    /**
     * Returns a release the user has not been told about yet, and marks it as told.
     * Checks at most every [minIntervalMs]; any failure just means "nothing new".
     */
    suspend fun unannounced(minIntervalMs: Long = 0L): UpdateManifest? {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST_CHECK, 0L) < minIntervalMs) return null
        val manifest = runCatching { source.newer() }.getOrNull()
        prefs.edit().putLong(LAST_CHECK, now).apply()
        if (manifest == null || manifest.versionCode <= prefs.getLong(ANNOUNCED, 0L)) return null
        prefs.edit().putLong(ANNOUNCED, manifest.versionCode).apply()
        return manifest
    }

    /** Background path: posts a system notification that opens the update sheet. */
    suspend fun checkAndNotify() {
        val manifest = unannounced(MIN_BACKGROUND_INTERVAL) ?: return
        if (!canPost()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID, openIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = manifest.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: context.getString(R.string.update_notify_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(VIcon.Download)
            .setContentTitle(context.getString(R.string.update_notify_title, manifest.versionName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(manifest.notes.ifBlank { text }))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_OPEN_UPDATE = "vibe.tube.action.OPEN_UPDATE"
        /** The app checks on open at most this often; the background job at most [MIN_BACKGROUND_INTERVAL]. */
        const val MIN_FOREGROUND_INTERVAL = 3 * 60 * 60 * 1000L
        private const val MIN_BACKGROUND_INTERVAL = 60 * 60 * 1000L
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 4210
        private const val LAST_CHECK = "last_check"
        private const val ANNOUNCED = "announced_version"

        fun openIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@Singleton
class UpdatePoller @Inject constructor(@ApplicationContext private val context: Context) {
    fun schedule() {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6 * 60 * 60 * 1000L)
            .setPersisted(false)
            .build()
        runCatching { scheduler.schedule(job) }
    }

    private companion object {
        const val JOB_ID = 4109
    }
}

@AndroidEntryPoint
class UpdateJobService : JobService() {
    @Inject lateinit var notifier: UpdateNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            runCatching { notifier.checkAndNotify() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
