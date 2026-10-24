package vibe.tube.feature.library

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.LinearWavyProgressIndicator
import vibe.tube.ui.components.downloadStatus
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vibe.tube.R
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.download.DownloadJob
import vibe.tube.download.DownloadManager
import vibe.tube.download.DownloadPhase
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.StateMessage
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.Spacing
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class DownloadedFile(val uri: Uri, val name: String, val mime: String, val size: Long, val modifiedMs: Long) {
    val isAudio: Boolean get() = mime.startsWith("audio/")

    /** Saved from the Shorts tab (the name carries [DownloadManager.SHORT_TAG] before the extension). */
    val isShort: Boolean get() = name.substringBeforeLast('.').endsWith(DownloadManager.SHORT_TAG)

    /** The name to show: without the Short tag (that is shown as a chip). */
    val title: String
        get() = if (isShort) name.substringBeforeLast('.').removeSuffix(DownloadManager.SHORT_TAG) + name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" } else name
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: DownloadManager,
    private val settings: SettingsRepository,
    private val events: vibe.tube.feature.common.UiEvents,
) : ViewModel() {
    val jobs: StateFlow<List<DownloadJob>> = manager.jobs
    private val mutableFiles = MutableStateFlow<List<DownloadedFile>?>(null)
    val files: StateFlow<List<DownloadedFile>?> = mutableFiles.asStateFlow()

    fun reload() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { scan() }.getOrDefault(emptyList()) }
            mutableFiles.value = found.sortedByDescending { it.modifiedMs }
        }
    }

    fun togglePause(id: Long) = manager.togglePause(id)

    fun cancel(id: Long) = manager.cancel(id)

    /** Deletes the file from the device's storage (not just from this list). */
    fun delete(file: DownloadedFile) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { removeFromStorage(file.uri) }.getOrDefault(false) }
            if (ok) {
                mutableFiles.value = mutableFiles.value?.filterNot { it.uri == file.uri }
                events.post(R.string.msg_download_deleted)
            } else {
                events.post(R.string.msg_delete_failed)
            }
        }
    }

    private fun removeFromStorage(uri: Uri): Boolean {
        val resolver = context.contentResolver
        return when {
            uri.scheme == "file" -> {
                val f = File(requireNotNull(uri.path))
                val deleted = f.delete()
                if (deleted) android.media.MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
                deleted
            }
            DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.deleteDocument(resolver, uri)
            else -> resolver.delete(uri, null, null) > 0
        }
    }

    private fun scan(): List<DownloadedFile> {
        val out = mutableListOf<DownloadedFile>()
        val tree = settings.latest.value.downloadDir.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val resolver = context.contentResolver
        if (tree != null) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(2).orEmpty()
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        out += DownloadedFile(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), c.getString(1).orEmpty(), mime, c.getLong(3), c.getLong(4))
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("Download/VibeTube%"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += DownloadedFile(
                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)),
                        c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), c.getLong(4) * 1000,
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VibeTube")
            dir.listFiles()?.forEach { f ->
                val mime = when (f.extension.lowercase()) { "m4a", "mp3", "opus", "ogg" -> "audio/*"; else -> "video/*" }
                out += DownloadedFile(Uri.fromFile(f), f.name, mime, f.length(), f.lastModified())
            }
        }
        return out
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit, viewModel: DownloadsViewModel = hiltViewModel()) {
    val files by viewModel.files.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val doneCount = jobs.count { it.phase == DownloadPhase.Done }
    LaunchedEffect(doneCount) { viewModel.reload() }
    val context = LocalContext.current
    var pendingDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<DownloadedFile?>(null) }
    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_downloads)) },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
        },
    ) { inner ->
        val active = jobs.filter { it.active }
        val list = files.orEmpty()
        if (files != null && list.isEmpty() && active.isEmpty()) {
            StateMessage(VIcon.Download, stringResource(R.string.downloads_empty_title), stringResource(R.string.downloads_empty_body), modifier = Modifier.padding(inner))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(inner),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (active.isNotEmpty()) {
                    item(key = "header:active") {
                        Text(stringResource(R.string.downloads_in_progress), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = Spacing.xs))
                    }
                }
                items(active, key = { "job:${it.id}" }) { job ->
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(downloadStatus(job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (job.phase == DownloadPhase.Downloading) {
                                LinearWavyProgressIndicator(
                                    progress = { job.progress },
                                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs, end = Spacing.sm),
                                    amplitude = { if (job.paused) 0f else 1f },
                                )
                            } else {
                                LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = Spacing.xs, end = Spacing.sm))
                            }
                        }
                        TooltipIconButton(
                            if (job.paused) VIcon.PlayArrowFilled else VIcon.Pause,
                            stringResource(if (job.paused) R.string.download_resume else R.string.download_pause),
                            { viewModel.togglePause(job.id) },
                        )
                        TooltipIconButton(VIcon.Stop, stringResource(R.string.download_stop), { viewModel.cancel(job.id) })
                    }
                }
                if (active.isNotEmpty() && list.isNotEmpty()) {
                    item(key = "header:saved") {
                        Text(stringResource(R.string.downloads_saved), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs))
                    }
                }
                items(list, key = { it.uri.toString() }) { file ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { open(context, file) }
                            .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        FileThumbnail(file)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(file.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (file.isShort) {
                                    Text(
                                        stringResource(R.string.badge_short),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(MaterialTheme.colorScheme.secondaryContainer)
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                                Text(
                                    listOf(sizeText(file.size), DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.modifiedMs))).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        TooltipIconButton(VIcon.Delete, stringResource(R.string.download_delete), { pendingDelete = file })
                    }
                }
            }
        }
    }
    pendingDelete?.let { file ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.download_delete_title)) },
            text = { Text(stringResource(R.string.download_delete_body, file.title)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.delete(file); pendingDelete = null }) { Text(stringResource(R.string.download_delete)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** A small picture of the saved video (a frame near its start), made on a background thread and kept in memory. */
@Composable
private fun FileThumbnail(file: DownloadedFile) {
    val context = LocalContext.current
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(ThumbCache.get(file.uri.toString()), file.uri) {
        if (file.isAudio || value != null) return@produceState
        value = withContext(ThumbCache.dispatcher) { ThumbCache.load(context, file.uri) }
    }
    Box(
        Modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val frame = bitmap
        if (frame != null) {
            androidx.compose.foundation.Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            VIconImage(if (file.isAudio) VIcon.MusicNote else VIcon.VideoLibrary, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private object ThumbCache {
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(48) {}
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val dispatcher = Dispatchers.IO.limitedParallelism(2)

    fun get(key: String): android.graphics.Bitmap? = cache.get(key)

    fun load(context: Context, uri: Uri): android.graphics.Bitmap? {
        cache.get(uri.toString())?.let { return it }
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // A moment in, so it is not the (often black) first frame.
            val atUs = (durationMs.coerceAtMost(2_000L) / 2) * 1000L
            val frame = retriever.getScaledFrameAtTime(atUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            frame?.also { cache.put(uri.toString(), it) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(java.util.Locale.US, "%d KB", bytes / 1024)
}

private fun open(context: Context, file: DownloadedFile) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
