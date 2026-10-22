package vibe.tube.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    visibilityScope: AnimatedVisibilityScope? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
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

    // Open the keyboard only once the pill has finished expanding into the screen.
    LaunchedEffect(Unit) {
        if (ui.submitted == null) {
            delay(380)
            runCatching { focus.requestFocus() }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize().searchBounds(visibilityScope),
    ) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            HeaderSurface(scrolled) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Spacing.xs, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs).staggeredReveal(0),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack)
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        SearchBarDefaults.InputField(
                            query = ui.input,
                            onQueryChange = viewModel::onInput,
                            onSearch = viewModel::submit,
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = { Text(stringResource(R.string.search_placeholder)) },
                            leadingIcon = { VIconImage(VIcon.Search, null) },
                            trailingIcon = {
                                if (ui.input.isNotEmpty()) {
                                    TooltipIconButton(VIcon.Close, stringResource(R.string.action_clear), { viewModel.onInput("") })
                                }
                            },
                            modifier = Modifier.focusRequester(focus),
                        )
                    }
                }
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
                                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
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
        },
    ) { inner ->
        val state = results
        if (ui.submitted != null && state != null) {
            FeedContent(
                state = state,
                hideShorts = hideShorts,
                onRefresh = viewModel::refresh,
                onLoadMore = viewModel::loadMore,
                onLogin = onLogin,
                gridState = gridState,
                contentPadding = inner,
                singleColumn = true,
                emptyTitle = stringResource(R.string.search_empty_title),
                emptyBody = stringResource(R.string.search_empty_body),
            ) { item, queue ->
                // One full-width card per result: large, highest-quality thumbnail.
                FeedItemContent(item, queue)
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
                            Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.xs).revealFirst(1),
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

/** Only the first screenful animates in; later rows (scrolled into view) appear normally. */
@Composable
private fun Modifier.revealFirst(index: Int): Modifier = if (index <= 12) staggeredReveal(index) else this

private fun sortLabel(sort: SearchSort): Int = when (sort) {
    SearchSort.Relevance -> R.string.search_sort_relevance
    SearchSort.UploadDate -> R.string.search_sort_date
    SearchSort.ViewCount -> R.string.search_sort_views
    SearchSort.Rating -> R.string.search_sort_rating
}
