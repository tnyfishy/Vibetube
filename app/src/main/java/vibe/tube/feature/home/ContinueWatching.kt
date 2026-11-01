package vibe.tube.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.data.db.PlaybackHistoryEntity
import vibe.tube.feature.library.toVideoItem
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.VideoCard
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.theme.Spacing

@Composable
internal fun ContinueWatching(entries: List<PlaybackHistoryEntity>, onHistory: () -> Unit) {
    if (entries.isEmpty()) return
    val videos = entries.take(5).map { it.toVideoItem() }
    Column(Modifier.fillMaxWidth().padding(top = 0.dp, bottom = Spacing.xs)) {
        Row(Modifier.fillMaxWidth().padding(start = Spacing.sm, end = 0.dp, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Surface(color = if (vibe.tube.ui.theme.LocalClassicPalette.current) androidx.compose.ui.graphics.Color(0xFFD71A1A) else MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = if (vibe.tube.ui.theme.LocalClassicPalette.current) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onTertiaryContainer,
                shape = androidx.compose.foundation.shape.CircleShape) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val iconSize = with(androidx.compose.ui.platform.LocalDensity.current) { MaterialTheme.typography.titleSmall.fontSize.toDp() }
                    VIconImage(VIcon.History, null, Modifier.size(iconSize))
                    Text(stringResource(R.string.continue_watching), style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.weight(1f))
            TooltipIconButton(VIcon.ChevronRight, stringResource(R.string.library_history), onHistory)
        }
        // The feed grid pads its sides by the gutter; the row grows back over it so cards scroll to the screen edge instead of being cut at the gutter.
        LazyRow(
            modifier = Modifier.layout { measurable, constraints ->
                val bleed = Spacing.gutter.roundToPx()
                val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            },
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            contentPadding = PaddingValues(horizontal = Spacing.gutter + Spacing.xs),
        ) {
            items(videos, key = { it.id }) { video ->
                ElevatedCard(Modifier.width(260.dp), shape = MaterialTheme.shapes.large) {
                    // Even inset on every side: thumbnail edge, text and bottom all sit 8dp from the card edge.
                    VideoCard(video, videos, Modifier.padding(Spacing.sm), uniformTitle = true)
                }
            }
            item(key = "history") {
                Column(Modifier.width(96.dp).height(180.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    TooltipIconButton(VIcon.ArrowForward, stringResource(R.string.library_history), onHistory)
                    Text(stringResource(R.string.library_history), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
