package vibe.tube.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.ui.theme.Spacing
import vibe.tube.ui.theme.VibeShape
import vibe.tube.youtube.model.VideoItem

@Composable
fun ShortSearchCard(video: VideoItem, actions: VideoActions = LocalVideoActions.current) {
    Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(VibeShape.card)
        .bouncyClickable(VibeShape.card) { actions.play(video, listOf(video)) }) {
        RemoteImage(
            urls = remember(video.id) { thumbnailCandidates(video) },
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.matchParentSize().drawWithContent {
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)), startY = size.height * 0.55f))
        })
        Text(video.title, color = Color.White, style = MaterialTheme.typography.labelLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.sm))
    }
}

@Composable
fun ShortsShelf(shorts: List<VideoItem>, modifier: Modifier = Modifier, actions: VideoActions = LocalVideoActions.current) {
    Column(modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.xs)) {
        VIconImage(vibe.tube.ui.VIcon.Shorts, null, Modifier.then(Modifier), tint = Color.Red)
        Text(
            stringResource(R.string.nav_shorts),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
        )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(shorts, key = { it.id }) { video ->
                Box(
                    Modifier
                        .width(132.dp)
                        .aspectRatio(9f / 16f)
                        .clip(VibeShape.card)
                        .bouncyClickable(VibeShape.card) {
                            // Start at the tapped short and keep the rest of the shelf in order, so the viewer can swipe through it.
                            val index = shorts.indexOf(video).coerceAtLeast(0)
                            actions.play(video, shorts.drop(index) + shorts.take(index))
                        },
                ) {
                    RemoteImage(
                        urls = remember(video.id) { thumbnailCandidates(video) },
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        Modifier.matchParentSize().drawWithContent {
                            drawContent()
                            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)), startY = size.height * 0.55f))
                        },
                    )
                    Text(
                        video.title,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.sm),
                    )
                }
            }
        }
    }
}
