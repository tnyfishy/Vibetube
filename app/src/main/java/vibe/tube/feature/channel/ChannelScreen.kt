package vibe.tube.feature.channel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { detailViewModel.load() }
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    val hideShorts by viewModel.hideShorts.collectAsStateWithLifecycle()
    val detail by detailViewModel.state.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(detail.detail?.contentTabs) {
        val available = detail.detail?.contentTabs.orEmpty()
        if (available.isNotEmpty() && tab != "about" && tab !in available) viewModel.setTab(available.first())
    }
    var showAvatar by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(tab) { detailViewModel.loadAbout() }
    detail.detail?.avatar?.let { if (showAvatar) vibe.tube.ui.components.AvatarViewer(it) { showAvatar = false } }
    androidx.compose.runtime.CompositionLocalProvider(vibe.tube.ui.components.LocalCurrentChannelId provides detail.detail?.id) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail.detail?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
        },
    ) { inner ->
        FeedContent(
            state = if (tab == "about") vibe.tube.data.repo.FeedState(phase = vibe.tube.data.repo.FeedPhase.Idle) else state,
            hideShorts = hideShorts,
            onRefresh = { if (tab == "about") detailViewModel.loadAbout(force = true) else { viewModel.refresh(); detailViewModel.load() } },
            onLoadMore = { if (tab != "about") viewModel.loadMore() },
            onLogin = onLogin,
            contentPadding = inner,
            emptyTitle = stringResource(R.string.channel_empty_title),
            emptyBody = null,
            showEmptyState = tab != "about",
            header = {
                item(key = "channel-header", span = { GridItemSpan(maxLineSpan) }) {
                    val d = detail.detail
                    Column {
                    if (d?.banner != null) {
                        coil3.compose.AsyncImage(model = d.banner, contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(112.dp).clip(MaterialTheme.shapes.medium),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    }
                    Row(
                        Modifier.padding(vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            d?.avatar,
                            Sizing.avatarHero,
                            channelId = d?.id,
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .clickable(enabled = d?.avatar != null) { showAvatar = true },
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            vibe.tube.ui.components.ChannelMetaText(
                                d?.name.orEmpty(),
                                d?.badge ?: vibe.tube.youtube.model.ChannelBadge.None,
                                emptyList(),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                            )
                            Text(listOfNotNull(d?.handle, d?.subscriberText?.let { raw -> if (raw.toLongOrNull() != null) stringResource(vibe.tube.R.string.metadata_subscribers, vibe.tube.feature.watch.compactCount(raw).orEmpty()) else raw }, d?.aboutDetails?.firstOrNull { it.first == "videoCountText" }?.second).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            vibe.tube.ui.components.SubscribeButton(
                                subscribed = detail.subscribed == true,
                                enabled = !detail.busy && d != null,
                                notifyLevels = d?.notify?.params?.keys.orEmpty(),
                                notifyLevel = detail.notifyLevel,
                                onToggle = { detailViewModel.toggleSubscribe(onLogin) },
                                onNotify = detailViewModel::setNotify,
                            )
                        }
                    }
                    }
                }
                run {
                    val tabs = detail.detail?.contentTabs.orEmpty().ifEmpty { listOf("videos") } + "about"
                    item(key = "channel-tabs", span = { GridItemSpan(maxLineSpan) }) {
                        androidx.compose.material3.ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), edgePadding = 0.dp) {
                            tabs.forEach { item ->
                                androidx.compose.material3.Tab(selected = item == tab, onClick = { viewModel.setTab(item) }, text = {
                                    Text(stringResource(when (item) { "about" -> R.string.channel_tab_about; "shorts" -> R.string.channel_tab_shorts; "livestreams" -> R.string.channel_tab_live; "playlists" -> R.string.channel_tab_playlists; else -> R.string.channel_tab_videos }))
                                })
                            }
                        }
                    }
                }
                item(key = "channel-sort", span = { GridItemSpan(maxLineSpan) }) {
                  vibe.tube.ui.components.ExpandingSection(visible = tab == "videos") {
                    Row(Modifier.padding(bottom = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        for (option in vibe.tube.youtube.model.ChannelSort.entries) {
                            FilterChip(
                                selected = sort == option,
                                onClick = { viewModel.setSort(option) },
                                label = {
                                    Text(
                                        stringResource(
                                            when (option) {
                                                vibe.tube.youtube.model.ChannelSort.Latest -> R.string.channel_sort_latest
                                                vibe.tube.youtube.model.ChannelSort.Popular -> R.string.channel_sort_popular
                                                vibe.tube.youtube.model.ChannelSort.Oldest -> R.string.channel_sort_oldest
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                  }
                }
                if (tab == "about") item(key = "channel-about", span = { GridItemSpan(maxLineSpan) }) {
                    ChannelAbout(detail, onRetry = { detailViewModel.loadAbout(force = true) })
                }
            },
        ) { item, queue ->
            val d = detail.detail
            val shown = if (item is vibe.tube.youtube.model.VideoItem && d != null) {
                androidx.compose.runtime.remember(item, d.id, d.avatar, d.name, d.badge) {
                    item.copy(
                        channelAvatar = item.channelAvatar ?: d.avatar,
                        channelName = item.channelName ?: d.name,
                        channelId = item.channelId ?: d.id,
                        channelBadge = if (item.channelName == null) d.badge else item.channelBadge,
                    )
                }
            } else {
                item
            }
            FeedItemContent(shown, queue)
        }
    }
    }
}
