package vibe.tube.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import vibe.tube.player.YoutubeRequests
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.common.UiEvents
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.player.ResolveResult
import vibe.tube.youtube.player.StreamFormat
import vibe.tube.youtube.player.StreamResolver
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

enum class DownloadKind { Video, Audio }

enum class DownloadPhase { Queued, Resolving, Downloading, Merging, Saving, Done, Failed }

data class DownloadJob(
    val id: Long,
    val videoId: String,
    val title: String,
    val kind: DownloadKind,
    val phase: DownloadPhase = DownloadPhase.Queued,
    val progress: Float = 0f,
    val quality: String? = null,
    val choice: DownloadPlanner.Choice? = null,
) {
    val active: Boolean get() = phase != DownloadPhase.Done && phase != DownloadPhase.Failed
}

/**
 * Saves videos (best video + best audio merged on device) or music (best audio only) to the chosen
 * folder. Jobs run one at a time; [DownloadService] keeps the process alive and shows progress.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: StreamResolver,
    private val session: SessionManager,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val events: UiEvents,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobsState = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = jobsState.asStateFlow()
    private val queue = Channel<DownloadJob>(Channel.UNLIMITED)
    private var nextId = 1L

    init {
        scope.launch { for (job in queue) process(job) }
    }

    /** Resolves the streams of [videoId] and lists the qualities that can be downloaded. */
    suspend fun loadOptions(videoId: String): DownloadPlanner.Options {
        val media = resolveMedia(videoId)
        if (media.isLive) throw IOException("live")
        return DownloadPlanner.options(media, Build.VERSION.SDK_INT)
    }

    private suspend fun resolveMedia(videoId: String): vibe.tube.youtube.player.ResolvedMedia {
        val ctx = session.currentContext()
        val auth = ctx?.let { c -> session.authFor(c.accountId) }?.takeIf { session.authState.value is AuthState.Verified }
        return when (val result = resolver.resolve(videoId, settings.latest.value.contentLocale(), auth)) {
            is ResolveResult.Playable -> result.media
            is ResolveResult.Blocked -> throw IOException("blocked:${result.failure}")
        }
    }

    fun enqueue(video: VideoItem, choice: DownloadPlanner.Choice) {
        val kind = choice.kind
        val job = DownloadJob(nextId++, video.id, video.title, kind, choice = choice)
        jobsState.update { list -> list.filter { it.active } + job }
        queue.trySend(job)
        DownloadService.start(context)
        events.post(R.string.msg_download_started, video.title)
    }

    private fun update(id: Long, transform: (DownloadJob) -> DownloadJob) {
        jobsState.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private suspend fun process(job: DownloadJob) {
        val temp = File(context.cacheDir, "downloads/${job.id}").apply { mkdirs() }
        try {
            update(job.id) { it.copy(phase = DownloadPhase.Resolving) }
            val skip = mutableSetOf<String>()
            var attempt = 0
            while (true) {
                val media = resolveMedia(job.videoId, skip)
                if (media.isLive) throw IOException("live")
                try {
                    runDownload(job, media, temp)
                    break
                } catch (e: IOException) {
                    // Stream URL rejected: try the next InnerTube client, like playback does.
                    if (e.message == "http_403" && attempt++ < 3) {
                        skip += media.clientName
                        update(job.id) { it.copy(phase = DownloadPhase.Resolving, progress = 0f) }
                        continue
                    }
                    throw e
                }
            }
            update(job.id) { it.copy(phase = DownloadPhase.Done, progress = 1f) }
            events.post(R.string.msg_download_done, job.title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(job.id) { it.copy(phase = DownloadPhase.Failed) }
            events.post(R.string.msg_download_failed, job.title)
        } finally {
            temp.deleteRecursively()
        }
    }

    private suspend fun runDownload(job: DownloadJob, media: vibe.tube.youtube.player.ResolvedMedia, temp: File) {
        val choice = job.choice
        val plan = when (job.kind) {
            DownloadKind.Video -> DownloadPlanner.planVideo(media, Build.VERSION.SDK_INT, choice?.height, choice?.fps)
            DownloadKind.Audio -> DownloadPlanner.planAudio(media, choice?.audioItag)
        } ?: throw IOException("no_stream")

        val quality = DownloadPlanner.qualityLabel(plan).takeIf { job.kind == DownloadKind.Video }
        update(job.id) { it.copy(phase = DownloadPhase.Downloading, quality = quality, progress = 0f) }
        val base = DownloadPlanner.safeFileName(media.title ?: job.title)

        when (plan) {
            is DownloadPlan.Single -> {
                val file = File(temp, "single")
                val total = plan.format.contentLength ?: 0L
                fetch(plan.format, media.userAgent, file) { done -> if (total > 0) update(job.id) { it.copy(progress = done.toFloat() / total) } }
                update(job.id) { it.copy(phase = DownloadPhase.Saving, progress = 1f) }
                val name = base + (quality?.let { " [$it]" } ?: "") + "." + DownloadPlanner.extension(plan.format)
                save(name, DownloadPlanner.mimeType(plan)) { fd -> FileOutputStream(fd).use { out -> file.inputStream().use { it.copyTo(out) } } }
            }
            is DownloadPlan.Mux -> {
                val videoFile = File(temp, "video")
                val audioFile = File(temp, "audio")
                val vTotal = plan.video.contentLength ?: 0L
                val aTotal = plan.audio.contentLength ?: 0L
                val all = (vTotal + aTotal).coerceAtLeast(1L)
                fetch(plan.video, media.userAgent, videoFile) { done -> update(job.id) { it.copy(progress = 0.9f * done / all) } }
                fetch(plan.audio, media.userAgent, audioFile) { done -> update(job.id) { it.copy(progress = 0.9f * (vTotal + done) / all) } }
                update(job.id) { it.copy(phase = DownloadPhase.Merging, progress = 0.92f) }
                val ext = if (plan.webm) "webm" else "mp4"
                val name = base + (quality?.let { " [$it]" } ?: "") + "." + ext
                save(name, DownloadPlanner.mimeType(plan)) { fd -> mux(videoFile, audioFile, fd, plan.webm) }
            }
        }
    }

    /**
     * Downloads [format] in [CHUNK]-sized pieces the way NewPipe does: POST `x\0` with the range in a `&range=`
     * query parameter (plain `Range` headers are rejected by googlevideo). [onBytes] gets the running total.
     */
    private suspend fun fetch(format: StreamFormat, userAgent: String, target: File, onBytes: (Long) -> Unit) {
        var total = format.contentLength ?: -1L
        var position = 0L
        var failures = 0
        var requestNumber = 0
        FileOutputStream(target).buffered().use { out ->
            while (total < 0 || position < total) {
                currentCoroutineContext().ensureActive()
                val end = if (total > 0) minOf(position + CHUNK, total) - 1 else -1L
                val request = Request.Builder()
                    .url(YoutubeRequests.rangedUrl(format.url, position, end, requestNumber++))
                    .header("User-Agent", userAgent)
                    .post(YoutubeRequests.body().toRequestBody())
                    .build()
                try {
                    http.newCall(request).execute().use { response ->
                        if (response.code != 206 && response.code != 200) throw IOException("http_${response.code}")
                        val buffer = ByteArray(64 * 1024)
                        val source = response.body.byteStream()
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            position += read
                            failures = 0
                            onBytes(position)
                            currentCoroutineContext().ensureActive()
                        }
                    }
                    if (total < 0) total = position
                } catch (e: IOException) {
                    if (e.message?.startsWith("http_4") == true || ++failures >= MAX_FAILURES) throw e
                    delay(800L * failures)
                }
            }
        }
    }

    /** Opens the destination (chosen folder, or Download/VibeTube), runs [write] on it, and finalizes the file. */
    private fun save(name: String, mime: String, write: (java.io.FileDescriptor) -> Unit) {
        val tree = settings.latest.value.downloadDir.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val resolver = context.contentResolver
        when {
            tree != null -> {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException("create_failed")
                try {
                    resolver.openFileDescriptor(doc, "rw")!!.use { write(it.fileDescriptor) }
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                    throw e
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("insert_failed")
                try {
                    resolver.openFileDescriptor(uri, "rw")!!.use { write(it.fileDescriptor) }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            }
            else -> {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VibeTube").apply { mkdirs() }
                val file = File(dir, name)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                    .use { write(it.fileDescriptor) }
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
            }
        }
    }

    /** Interleaves the samples of a video-only and an audio-only file into one container, without re-encoding. */
    private fun mux(videoFile: File, audioFile: File, fd: java.io.FileDescriptor, webm: Boolean) {
        val muxer = MediaMuxer(fd, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var started = false
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoTrack = trackOf(videoExtractor, "video/")
            val audioTrack = trackOf(audioExtractor, "audio/")
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            val videoOut = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val audioOut = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val videoTime = videoExtractor.sampleTime
                val audioTime = audioExtractor.sampleTime
                if (videoTime < 0 && audioTime < 0) break
                val useVideo = audioTime < 0 || (videoTime in 0..audioTime)
                val extractor = if (useVideo) videoExtractor else audioExtractor
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, extractor.sampleTime, flags)
                muxer.writeSampleData(if (useVideo) videoOut else audioOut, buffer, info)
                extractor.advance()
            }
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun trackOf(extractor: MediaExtractor, prefix: String): Int =
        (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
            ?: throw IOException("no_track_$prefix")

    companion object {
        private const val CHUNK = 8L * 1024 * 1024
        private const val MAX_FAILURES = 4
        private const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024
        const val DEFAULT_RELATIVE_PATH = "Download/VibeTube"
    }
}
