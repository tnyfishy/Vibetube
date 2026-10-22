package vibe.tube.feature.library

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.foundation.clickable
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
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: DownloadManager,
    private val settings: SettingsRepository,
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
                items(active, key = { "job:${it.id}" }) { job ->
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
                        Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        androidx.compose.material3.LinearWavyProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs))
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
                        VIconImage(if (file.isAudio) VIcon.MusicNote else VIcon.VideoLibrary, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(
                                listOf(sizeText(file.size), DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.modifiedMs))).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
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
