package com.itube.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import com.itube.tv.data.Video
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Grid of video cards (4 per row on a TV). [onEndReached] loads the next page when the focus gets near
 * the end; [header] spans the whole width above the videos.
 */
@Composable
fun VideoGrid(
    videos: List<Video>,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    progress: (Video) -> Float? = { null },
    showChannel: Boolean = true,
    onLongClick: ((Video) -> Unit)? = null,
    onEndReached: (() -> Unit)? = null,
    itemModifier: (Int, Video) -> Modifier = { _, _ -> Modifier },
    onFocused: (Int, Video) -> Unit = { _, _ -> },
    contentPadding: PaddingValues = PaddingValues(start = 56.dp, end = 56.dp, top = 20.dp, bottom = 56.dp),
    header: (LazyGridScope.() -> Unit)? = null,
    footer: (LazyGridScope.() -> Unit)? = null,
) {
    if (onEndReached != null) {
        LaunchedEffect(state, videos.size) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .distinctUntilChanged()
                .collect { last -> if (videos.isNotEmpty() && last >= state.layoutInfo.totalItemsCount - 6) onEndReached() }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = state,
        modifier = modifier.fillMaxSize().focusRestorer(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        header?.invoke(this)
        itemsIndexed(videos, key = { i, v -> "v" + v.id + "_" + i }) { i, v ->
            VideoCard(
                v,
                width = null,
                progress = progress(v),
                showChannel = showChannel,
                modifier = itemModifier(i, v),
                onClick = { onClick(i) },
                onLongClick = onLongClick?.let { { it(v) } },
                onFocused = { onFocused(i, v) },
            )
        }
        footer?.invoke(this)
    }
}

/** Full-width cell of a [VideoGrid]. */
fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}
