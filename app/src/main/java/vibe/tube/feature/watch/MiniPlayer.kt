package vibe.tube.feature.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.player.PlayerController
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.RemoteImage
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VibeLinearProgress
import vibe.tube.ui.theme.Sizing
import vibe.tube.ui.theme.Spacing
import vibe.tube.ui.theme.VibeMotion
import vibe.tube.ui.theme.VibeShape
import vibe.tube.youtube.model.bestFor

@Composable
fun MiniPlayer(controller: PlayerController, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val state by controller.state.collectAsStateWithLifecycle()
    val show = enabled && state.visible && !state.expanded && state.current != null && !state.inPip
    var drag by remember { mutableFloatStateOf(0f) }
    AnimatedVisibility(
        visible = show,
        enter = slideInVertically(VibeMotion.spatial()) { it } + fadeIn(VibeMotion.effects()),
        exit = slideOutVertically(VibeMotion.spatial()) { it } + fadeOut(VibeMotion.effects()),
        modifier = modifier,
    ) {
        val video = state.current ?: return@AnimatedVisibility
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { drag += it },
                    onDragStopped = {
                        if (drag < -48f) controller.expand() else if (drag > 96f) controller.dismiss()
                        drag = 0f
                    },
                ),
            shape = VibeShape.miniPlayer,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(Sizing.miniPlayerHeight)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_expand_player)) { controller.expand() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Box(
                        Modifier
                            .padding(start = Spacing.sm)
                            .width(Sizing.miniPlayerThumbnailWidth)
                            .aspectRatio(Sizing.THUMBNAIL_ASPECT)
                            .clip(RoundedCornerShape(8.dp)),
                    ) {
                        RemoteImage(video.thumbnails.bestFor(240), null, Modifier.matchParentSize())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        video.channelName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TooltipIconButton(
                        if (state.isPlaying) VIcon.Pause else VIcon.PlayArrowFilled,
                        stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                        controller::togglePlay,
                        animateIcon = true,
                    )
                    TooltipIconButton(VIcon.Close, stringResource(R.string.action_close_player), controller::dismiss)
                }
                val fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                VibeLinearProgress(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm))
            }
        }
    }
}
