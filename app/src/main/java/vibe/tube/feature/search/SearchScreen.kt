package vibe.tube.feature.search

import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.State
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import vibe.tube.ui.components.HeaderSurface
import vibe.tube.ui.components.LocalMiniPlayerPadding
import vibe.tube.ui.components.searchBounds
import vibe.tube.ui.components.searchOpenMs
import vibe.tube.ui.components.staggeredReveal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.feature.home.FeedItemContent
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.ConnectedChoiceGroup
import vibe.tube.ui.components.FeedContent
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.VideoRow
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.SearchKind
import vibe.tube.youtube.model.SearchSort
import vibe.tube.youtube.model.VideoItem
import vibe.tube.ui.components.ShortsShelf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    visibilityScope: AnimatedVisibilityScope? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { viewModel.refreshFeaturedSubscription() }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val featured by viewModel.featuredChannel.collectAsStateWithLifecycle()
    val featuredVideos by viewModel.featuredVideos.collectAsStateWithLifecycle()
    val featuredBusy by viewModel.featuredBusy.collectAsStateWithLifecycle()
    val featuredSubscription by viewModel.featuredSubscription.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val hideShorts by viewModel.hideShorts.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    var sortMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val showingResults = ui.submitted != null && results != null
    val scrolled by remember(showingResults) {
        derivedStateOf {
            if (showingResults) gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
            else listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(ui.submitted) {
        if (ui.submitted != null) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    LaunchedEffect(Unit) {
        if (ui.submitted == null) {
            delay(searchOpenMs(160).toLong())
            runCatching { focus.requestFocus() }
        }
    }

    val transition = visibilityScope?.transition
    val bodyAlpha: State<Float> = if (transition != null) {
        transition.animateFloat(transitionSpec = { tween(if (targetState == EnterExitState.Visible) searchOpenMs(220) else vibe.tube.ui.components.searchCloseMs(220)) }, label = "searchBody") { if (it == EnterExitState.Visible) 1f else 0f }
    } else {
        remember { mutableStateOf(1f) }
    }
    val leaving = transition != null && transition.targetState == EnterExitState.PostExit && !transition.isSeeking
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Box(Modifier.graphicsLayer { alpha = if (leaving) 0f else 1f }) {
            HeaderSurface(scrolled) {
                SearchCapsule(
                    query = ui.input,
                    onQueryChange = viewModel::onInput,
                    onSearch = viewModel::submit,
                    onBack = onBack,
                    focus = focus,
                    modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs),
                )
                if (ui.submitted != null) {
                    LazyRow(
                        modifier = Modifier.padding(bottom = Spacing.sm),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        item {
                            ConnectedChoiceGroup(
                                options = SearchKind.entries.toList(),
                                selected = ui.kind,
                                onSelect = viewModel::setKind,
                                label = { kind ->
                                    stringResource(
                                        when (kind) {
                                            SearchKind.All -> R.string.search_filter_all
                                            SearchKind.Video -> R.string.search_filter_video
                                            SearchKind.Shorts -> R.string.channel_tab_shorts
                                            SearchKind.Channel -> R.string.search_filter_channel
                                            SearchKind.Playlist -> R.string.search_filter_playlist
                                        },
                                    )
                                },
                            )
                        }
                        if (ui.kind == SearchKind.All || ui.kind == SearchKind.Video) {
                            item {
                                Box {
                                    FilterChip(
                                        selected = ui.sort != SearchSort.Relevance,
                                        onClick = { sortMenu = true },
                                        label = { Text(stringResource(sortLabel(ui.sort))) },
                                        trailingIcon = { VIconImage(VIcon.ExpandMore, null) },
                                    )
                                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                                        SearchSort.entries.forEach { sort ->
                                            DropdownMenuItem(
                                                text = { Text(stringResource(sortLabel(sort))) },
                                                onClick = { sortMenu = false; viewModel.setSort(sort) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        },
    ) { inner ->
        val state = results
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = bodyAlpha.value }.background(MaterialTheme.colorScheme.surface)) {
        if (ui.submitted != null && state != null) {
            
            LaunchedEffect(state.items, ui.kind, ui.submitted) {
                if (ui.kind == SearchKind.All) {
                    pickFeaturedChannel(ui.submitted.orEmpty(), state.items.filterIsInstance<vibe.tube.youtube.model.ChannelItem>())?.let(viewModel::offerFeatured)
                }
            }
            val arranged = remember(state) { state.copy(items = prioritiseChannelVideos(state.items)) }
            val shortsGrid = ui.kind == SearchKind.Shorts
            val allShorts = remember(arranged) { arranged.items.filterIsInstance<VideoItem>().filter { it.isShort } }
            FeedContent(
                state = if (shortsGrid) arranged.copy(items = allShorts) else arranged,
                hideShorts = hideShorts,
                itemFilter = { item -> item.id != featured?.id && (shortsGrid || !(item is VideoItem && item.isShort)) },
                onRefresh = viewModel::refresh,
                onLoadMore = viewModel::loadMore,
                onLogin = onLogin,
                gridState = gridState,
                contentPadding = inner,
                singleColumn = !shortsGrid,
                fixedColumns = if (shortsGrid) 2 else null,
                header = {
                    featured?.let { channel ->
                        item(key = "featured-channel", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            FeaturedChannelCard(
                                channel = channel,
                                subscription = featuredSubscription,
                                videos = featuredVideos,
                                busy = featuredBusy,
                                onSubscribe = { viewModel.toggleFeatured(onLogin) },
                                onNotify = viewModel::setFeaturedNotify,
                            )
                        }
                    }
                    if (ui.kind == SearchKind.All && allShorts.isNotEmpty()) item(key = "search-shorts-shelf", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        ShortsShelf(allShorts, Modifier.padding(top = Spacing.xs, bottom = Spacing.xl))
                    }
                },
                emptyTitle = stringResource(R.string.search_empty_title),
                emptyBody = stringResource(R.string.search_empty_body),
            ) { item, queue ->
                
                if (shortsGrid && item is VideoItem) vibe.tube.ui.components.ShortSearchCard(item)
                else if (item is VideoItem && item.isShort) ShortsShelf(allShorts) else FeedItemContent(item, queue)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(inner),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = LocalMiniPlayerPadding.current + Spacing.lg),
            ) {
                if (ui.input.trim().length >= 2 && suggestions.isNotEmpty()) {
                    itemsIndexed(suggestions, key = { _, it -> "s:$it" }) { index, suggestion ->
                        ListItem(
                            modifier = Modifier.clickable(role = Role.Button) { viewModel.submit(suggestion) }.revealFirst(index + 1),
                            leadingContent = { VIconImage(VIcon.Search, null) },
                            headlineContent = { Text(suggestion) },
                        )
                    }
                } else if (recent.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.sm).revealFirst(1),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.search_recent), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirmClear = true }) { Text(stringResource(R.string.action_clear)) }
                        }
                    }
                    itemsIndexed(recent, key = { _, it -> "r:$it" }) { index, query ->
                        ListItem(
                            modifier = Modifier.clickable(role = Role.Button) { viewModel.submit(query) }.revealFirst(index + 2),
                            leadingContent = { VIconImage(VIcon.History, null) },
                            headlineContent = { Text(query) },
                            trailingContent = {
                                TooltipIconButton(VIcon.Close, stringResource(R.string.action_remove), { viewModel.removeRecent(query) })
                            },
                        )
                    }
                }
            }
        }
        }
    }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.dialog_clear_search_title)) },
            text = { Text(stringResource(R.string.dialog_clear_search_body)) },
            confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirmClear = false; viewModel.clearRecent() }) { Text(stringResource(R.string.action_clear)) } },
            dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Modifier.revealFirst(index: Int): Modifier = if (index <= 12) staggeredReveal(index) else this

private fun sortLabel(sort: SearchSort): Int = when (sort) {
    SearchSort.Relevance -> R.string.search_sort_relevance
    SearchSort.UploadDate -> R.string.search_sort_date
    SearchSort.ViewCount -> R.string.search_sort_views
    SearchSort.Rating -> R.string.search_sort_rating
}

private fun ageMinutes(text: String?): Long {
    if (text == null) return Long.MAX_VALUE
    val n = Regex("""\d+""").find(text)?.value?.toLongOrNull() ?: return Long.MAX_VALUE
    val t = text.lowercase()
    val unit = when {
        "giây" in t || "second" in t -> 0L
        "phút" in t || "minute" in t -> 1L
        "giờ" in t || "hour" in t -> 60L
        "ngày" in t || "day" in t -> 60L * 24
        "tuần" in t || "week" in t -> 60L * 24 * 7
        "tháng" in t || "month" in t -> 60L * 24 * 30
        "năm" in t || "year" in t -> 60L * 24 * 365
        else -> return Long.MAX_VALUE
    }
    return n * unit
}

private fun prioritiseChannelVideos(items: List<vibe.tube.youtube.model.FeedItem>): List<vibe.tube.youtube.model.FeedItem> {
    val channels = items.filterIsInstance<vibe.tube.youtube.model.ChannelItem>()
    if (channels.isEmpty()) return items
    val moved = HashSet<String>()
    val groups = channels.associate { channel ->
        channel.id to items.filterIsInstance<VideoItem>()
            .filter { it.channelId == channel.id && !it.isShort && !it.isLive }
            .sortedBy { ageMinutes(it.publishedText) }
            .also { list -> list.forEach { moved += it.id } }
    }
    val out = ArrayList<vibe.tube.youtube.model.FeedItem>(items.size)
    for (item in items) {
        when {
            item is VideoItem && item.id in moved -> Unit
            else -> {
                out += item
                if (item is vibe.tube.youtube.model.ChannelItem) groups[item.id]?.let(out::addAll)
            }
        }
    }
    return out
}

private val SearchMorphIn = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = 0.001f)
private val SearchMorphOut = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 900f * 1.10f * 1.10f, visibilityThreshold = 0.02f)

@Composable
internal fun SearchBackGlyph(progress: () -> Float, tint: androidx.compose.ui.graphics.Color, description: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.semantics { contentDescription = description }) {
        val u = size.minDimension / 24f
        val p = progress().coerceIn(0f, 1f)
        fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun pt(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * u, y * u)
        val stroke = 2f * u
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        val tipX = 5.2f
        
        val sx = mix(13.8f, tipX, p)
        val sy = mix(13.8f, 12f, p)
        val ex = 19.6f
        val ey = mix(19.6f, 12f, p)
        drawLine(tint, pt(sx, sy), pt(ex, ey), stroke, cap)
        
        val lens = 1f - ((p - 0.05f) / 0.6f).coerceIn(0f, 1f)
        if (lens > 0f) {
            val len = kotlin.math.hypot(ex - sx, ey - sy).coerceAtLeast(0.001f)
            val dx = (ex - sx) / len
            val dy = (ey - sy) / len
            val r = 5.6f * (0.3f + 0.7f * lens)
            val cx = sx - dx * r
            val cy = sy - dy * r
            val hook = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val gap = 360f * (1f - lens)
            drawArc(
                color = tint,
                startAngle = hook + gap / 2f,
                sweepAngle = 360f - gap,
                useCenter = false,
                topLeft = pt(cx - r, cy - r),
                size = androidx.compose.ui.geometry.Size(2f * r * u, 2f * r * u),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = cap),
            )
        }
        
        val arms = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
        if (arms > 0f) {
            val reach = 6.6f * arms
            drawLine(tint, pt(tipX, 12f), pt(tipX + reach, 12f - reach), stroke, cap)
            drawLine(tint, pt(tipX, 12f), pt(tipX + reach, 12f + reach), stroke, cap)
        }
    }
}

@Composable
internal fun SearchCapsule(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    
    val morph = remember { androidx.compose.animation.core.Animatable(0f) }
    val reduced = vibe.tube.ui.theme.LocalReducedMotion.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (reduced) morph.snapTo(1f) else morph.animateTo(1f, SearchMorphIn)
    }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(48.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {

            val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            val pressScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (pressed && !reduced) 0.86f else 1f,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "backPress",
            )
            var leaving by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(
                        interactionSource = press,
                        indication = null,
                        enabled = !leaving,
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.action_back),
                    ) {
                        leaving = true
                        scope.launch {
                            
                            if (!reduced) morph.animateTo(0f, SearchMorphOut)
                            onBack()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                SearchBackGlyph(
                    progress = { morph.value },
                    tint = tint,
                    modifier = Modifier
                        .size(vibe.tube.ui.components.HeaderGlyphSize)
                        .graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        },
                    description = stringResource(R.string.action_back),
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(stringResource(R.string.search_placeholder), style = MaterialTheme.typography.bodyLarge, color = tint, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                TooltipIconButton(VIcon.Close, stringResource(R.string.action_clear), { onQueryChange("") })
            }
        }
    }
}
