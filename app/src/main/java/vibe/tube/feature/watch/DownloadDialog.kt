package vibe.tube.feature.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import vibe.tube.R
import vibe.tube.download.DownloadKind
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.VideoItem

/** Asks whether to save a video or only its audio, both at the best quality available. */
@Composable
fun DownloadDialog(video: VideoItem, onDismiss: () -> Unit, onChoose: (DownloadKind) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Button(shapes = ButtonDefaults.shapes(), onClick = { onChoose(DownloadKind.Video) }, modifier = Modifier.fillMaxWidth()) {
                    VIconImage(VIcon.VideoLibrary, null)
                    Text(stringResource(R.string.download_video), Modifier.padding(start = Spacing.sm))
                }
                FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = { onChoose(DownloadKind.Audio) }, modifier = Modifier.fillMaxWidth()) {
                    VIconImage(VIcon.MusicNote, null)
                    Text(stringResource(R.string.download_audio), Modifier.padding(start = Spacing.sm))
                }
                Text(
                    stringResource(R.string.download_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
