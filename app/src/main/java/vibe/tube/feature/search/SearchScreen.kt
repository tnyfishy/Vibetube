package vibe.tube.feature.search

import androidx.compose.foundation.clickable
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
fun SearchScreen(onBack: () -> Unit, onLogin: () -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val hideShorts by viewModel.hideShorts.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    var sortMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (ui.submitted == null) focus.requestFocus() }

    Scaffold(
        topBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack)
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
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                }
                if (ui.submitted != null) {
                    LazyRow(
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
                contentPadding = inner,
                singleColumn = true,
                emptyTitle = stringResource(R.string.search_empty_title),
                emptyBody = stringResource(R.string.search_empty_body),
            ) { item, queue ->
                if (item is VideoItem) VideoRow(item, queue) else FeedItemContent(item, queue)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(inner)) {
                if (ui.input.trim().length >= 2 && suggestions.isNotEmpty()) {
                    items(suggestions, key = { "s:$it" }) { suggestion ->
                        ListItem(
                            modifier = Modifier.clickable(role = Role.Button) { viewModel.submit(suggestion) },
                            leadingContent = { VIconImage(VIcon.Search, null) },
                            headlineContent = { Text(suggestion) },
                        )
                    }
                } else if (recent.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.search_recent), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirmClear = true }) { Text(stringResource(R.string.action_clear)) }
                        }
                    }
                    items(recent, key = { "r:$it" }) { query ->
                        ListItem(
                            modifier = Modifier.clickable(role = Role.Button) { viewModel.submit(query) },
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

private fun sortLabel(sort: SearchSort): Int = when (sort) {
    SearchSort.Relevance -> R.string.search_sort_relevance
    SearchSort.UploadDate -> R.string.search_sort_date
    SearchSort.ViewCount -> R.string.search_sort_views
    SearchSort.Rating -> R.string.search_sort_rating
}
