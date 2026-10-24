package vibe.tube.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.download.DownloadJob
import vibe.tube.download.DownloadPhase
import vibe.tube.ui.VIcon
import vibe.tube.ui.theme.Spacing
import kotlin.math.roundToInt

/** Toasts in the app's own colours (the primary container) instead of the stock inverse-surface grey. */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionColor = MaterialTheme.colorScheme.primary,
            dismissActionContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** The bottom toast area: the live download card (while a download is being shown) above the ordinary toasts. */
@Composable
fun ToastHost(
    snackbar: SnackbarHostState,
    download: DownloadJob?,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        DownloadToast(download, onTogglePause, onCancel, onOpenDownloads)
        AppSnackbarHost(snackbar)
    }
}

/**
 * Progress toast for a running download: title, status, a progress bar, and three buttons: pause / resume, stop, and an
 * arrow that opens the Downloaded screen.
 */
@Composable
fun DownloadToast(job: DownloadJob?, onTogglePause: () -> Unit, onCancel: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    // Keeps showing the last job while the card slides out, so it does not collapse into an empty box.
    var last by remember { mutableStateOf(job) }
    if (job != null) last = job
    AnimatedVisibility(
        visible = job != null,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(260)) { it / 2 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it / 2 },
    ) {
        val shown = last ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs).widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(shown.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        downloadStatus(shown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                    if (shown.phase == DownloadPhase.Downloading && !shown.paused) {
                        LinearWavyProgressIndicator(
                            progress = { shown.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs, end = Spacing.sm),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                        )
                    } else if (shown.paused) {
                        LinearWavyProgressIndicator(
                            progress = { shown.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs, end = Spacing.sm),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                            amplitude = { 0f },
                        )
                    } else {
                        LinearWavyProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs, end = Spacing.sm),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                        )
                    }
                }
                TooltipIconButton(
                    if (shown.paused) VIcon.PlayArrowFilled else VIcon.Pause,
                    stringResource(if (shown.paused) R.string.download_resume else R.string.download_pause),
                    onTogglePause,
                )
                TooltipIconButton(VIcon.Stop, stringResource(R.string.download_stop), onCancel)
                TooltipIconButton(VIcon.ArrowForward, stringResource(R.string.download_open_list), onOpen)
            }
        }
    }
}

@Composable
fun downloadStatus(job: DownloadJob): String = when {
    job.paused -> stringResource(R.string.download_paused) + " · " + (job.progress * 100).roundToInt() + "%"
    job.phase == DownloadPhase.Downloading -> stringResource(R.string.download_status_downloading, (job.progress * 100).roundToInt())
    job.phase == DownloadPhase.Merging -> stringResource(R.string.download_status_merging)
    job.phase == DownloadPhase.Saving -> stringResource(R.string.download_status_saving)
    job.phase == DownloadPhase.Failed -> stringResource(R.string.download_status_failed)
    job.phase == DownloadPhase.Done -> stringResource(R.string.download_status_done)
    else -> stringResource(R.string.download_status_preparing)
}
