package vibe.tube.feature.watch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.download.DownloadKind
import vibe.tube.download.DownloadPlanner
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.VideoItem
import java.util.Locale

/** Lets the user pick the video resolution or the audio quality to save, from what the video actually offers. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadDialog(
    video: VideoItem,
    onDismiss: () -> Unit,
    loadOptions: suspend () -> Result<DownloadPlanner.Options>,
    onChoose: (DownloadPlanner.Choice) -> Unit,
    videoOnly: Boolean = false,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    val options by produceState<Result<DownloadPlanner.Options>?>(null, attempt) {
        value = null
        value = loadOptions()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val loaded = options
                when {
                    loaded == null -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            ContainedLoadingIndicator()
                            Text(stringResource(R.string.download_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    loaded.isFailure -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(stringResource(R.string.download_options_failed), style = MaterialTheme.typography.bodyMedium)
                        TextButton(shapes = ButtonDefaults.shapes(), onClick = { attempt++ }) { Text(stringResource(R.string.action_retry)) }
                    }
                    else -> {
                        val data = loaded.getOrThrow()
                        if (!videoOnly) {
                            PrimaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.download_tab_video)) })
                                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.download_tab_audio)) })
                            }
                        }
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            if (tab == 0 || videoOnly) {
                                items(data.videos, key = { "${it.height}-${it.fps}" }) { option ->
                                    OptionRow(
                                        title = option.label,
                                        detail = listOfNotNull(option.container, option.sizeBytes?.let(::formatSize)).joinToString(" · "),
                                    ) { onChoose(DownloadPlanner.Choice(DownloadKind.Video, option.height, option.fps)) }
                                }
                            } else {
                                items(data.audios, key = { it.itag }) { option ->
                                    OptionRow(
                                        title = "${option.bitrateKbps} kbps · ${option.codec}",
                                        detail = listOfNotNull(option.container, option.trackName, option.sizeBytes?.let(::formatSize)).joinToString(" · "),
                                    ) { onChoose(DownloadPlanner.Choice(DownloadKind.Audio, audioItag = option.itag)) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun OptionRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024L * 1024L) return String.format(Locale.US, "%d KB", maxOf(1L, (bytes + 512) / 1024))
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.0f MB", mb)
}
