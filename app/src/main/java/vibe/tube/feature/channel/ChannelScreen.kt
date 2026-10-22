package vibe.tube.feature.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.feature.home.ChannelVideosViewModel
import vibe.tube.feature.home.FeedItemContent
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Avatar
import vibe.tube.ui.components.FeedContent
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.theme.Sizing
import vibe.tube.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    viewModel: ChannelVideosViewModel = hiltViewModel(),
    detailViewModel: ChannelDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    val hideShorts by viewModel.hideShorts.collectAsStateWithLifecycle()
    val detail by detailViewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail.detail?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
        },
    ) { inner ->
        FeedContent(
            state = state,
            hideShorts = hideShorts,
            onRefresh = { viewModel.refresh(); detailViewModel.load() },
            onLoadMore = viewModel::loadMore,
            onLogin = onLogin,
            contentPadding = inner,
            emptyTitle = stringResource(R.string.channel_empty_title),
            emptyBody = null,
            header = {
                item(key = "channel-header", span = { GridItemSpan(maxLineSpan) }) {
                    val d = detail.detail
                    Row(
                        Modifier.padding(vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(d?.avatar, Sizing.avatarHero)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(d?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val meta = listOfNotNull(d?.handle, d?.subscriberText).joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val subscribed = detail.subscribed
                            if (subscribed == true) {
                                FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = { detailViewModel.toggleSubscribe(onLogin) }, enabled = !detail.busy) {
                                    Text(stringResource(R.string.action_subscribed))
                                }
                            } else {
                                Button(shapes = ButtonDefaults.shapes(), onClick = { detailViewModel.toggleSubscribe(onLogin) }, enabled = !detail.busy && d != null) {
                                    Text(stringResource(R.string.action_subscribe))
                                }
                            }
                        }
                    }
                }
            },
        ) { item, queue -> FeedItemContent(item, queue) }
    }
}
