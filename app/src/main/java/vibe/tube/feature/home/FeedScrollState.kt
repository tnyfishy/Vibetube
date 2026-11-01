package vibe.tube.feature.home

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

private val GridPositions = listSaver<MutableMap<String, LazyGridState>, Any>(
    save = { map -> map.flatMap { (key, grid) -> listOf(key, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) } },
    restore = { values -> values.chunked(3).associate { it[0] as String to LazyGridState(it[1] as Int, it[2] as Int) }.toMutableMap() },
)

@Composable
internal fun savedFeedGrid(key: String): LazyGridState {
    val positions = rememberSaveable(saver = GridPositions) { mutableMapOf<String, LazyGridState>() }
    return positions.getOrPut(key) { LazyGridState() }
}
