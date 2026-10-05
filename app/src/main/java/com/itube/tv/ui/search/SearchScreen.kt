package com.itube.tv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itube.tv.AppContainer
import com.itube.tv.data.Paged
import com.itube.tv.data.SearchResult
import com.itube.tv.data.Video
import com.itube.tv.data.YouTube
import com.itube.tv.data.describe
import com.itube.tv.ui.LocalNav
import com.itube.tv.ui.appViewModel
import com.itube.tv.ui.components.ChannelCard
import com.itube.tv.ui.components.EmptyState
import com.itube.tv.ui.components.FocusSurface
import com.itube.tv.ui.components.Loading
import com.itube.tv.ui.components.SectionTitle
import com.itube.tv.ui.components.TextInputDialog
import com.itube.tv.ui.components.VideoCard
import com.itube.tv.ui.components.VideoMenu
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val page: Paged<SearchResult>? = null,
    val results: List<SearchResult> = emptyList(),
    val loadingMore: Boolean = false,
) {
    val videos: List<Video> get() = results.mapNotNull { (it as? SearchResult.V)?.video }
    val channels get() = results.mapNotNull { (it as? SearchResult.C)?.channel }
}

@OptIn(FlowPreview::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val suggestions = MutableStateFlow<List<String>>(emptyList())
    val state = MutableStateFlow(SearchState())
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            query.debounce(250).distinctUntilChanged().collect { q ->
                suggestions.value = if (q.trim().length < 2) emptyList() else YouTube.suggestions(q.trim()).take(8)
            }
        }
        viewModelScope.launch {
            // Typing pauses for a moment: search what is there.
            query.debounce(900).distinctUntilChanged().collect { q -> if (q.trim().length >= 2) search(q) }
        }
    }

    fun search(q: String) {
        val text = q.trim()
        if (text.isEmpty() || (text == state.value.query && state.value.error == null && state.value.page != null)) return
        searchJob?.cancel()
        state.value = SearchState(query = text, loading = true)
        searchJob = viewModelScope.launch {
            state.value = try {
                val page = YouTube.search(text)
                SearchState(text, page = page, results = page.results(c))
            } catch (e: Exception) {
                SearchState(text, error = e.describe())
            }
        }
    }

    fun loadMore() {
        val s = state.value
        val page = s.page ?: return
        if (s.loadingMore || page.next == null) return
        state.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            state.value = try {
                val next = YouTube.searchMore(page)
                state.value.copy(page = next, results = (state.value.results + next.results(c)).distinctBy { it.key() }, loadingMore = false)
            } catch (e: Exception) {
                state.value.copy(loadingMore = false)
            }
        }
    }

    private fun Paged<SearchResult>.results(c: AppContainer): List<SearchResult> {
        val hideShorts = c.settings.value.hideShorts
        return items.filter { !(hideShorts && it is SearchResult.V && it.video.isShort) }
    }

    private fun SearchResult.key() = when (this) {
        is SearchResult.V -> "v" + video.id
        is SearchResult.C -> "c" + channel.url
    }
}

private val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890".map { it.toString() }

@Composable
fun SearchScreen() {
    val vm = appViewModel { SearchViewModel(it) }
    val nav = LocalNav.current
    val query by vm.query.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val state by vm.state.collectAsState()
    var systemKeyboard by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Video?>(null) }

    Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 24.dp, top = 8.dp)) {
        // ---- on-screen keyboard (fast with a remote, no IME needed) and suggestions
        Column(Modifier.width(312.dp)) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = C.Text2)
                Spacer(Modifier.width(10.dp))
                Text(
                    query.ifEmpty { "Rechercher sur YouTube" },
                    style = T.Title3,
                    color = if (query.isEmpty()) C.Text3 else C.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.focusRestorer(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KEYS.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k -> Key(k, Modifier.size(width = 47.dp, height = 42.dp)) { vm.query.value += k } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyIcon(Icons.Rounded.SpaceBar, Modifier.size(width = 100.dp, height = 42.dp)) { vm.query.value += " " }
                    KeyIcon(Icons.AutoMirrored.Rounded.Backspace, Modifier.size(width = 100.dp, height = 42.dp)) { vm.query.value = vm.query.value.dropLast(1) }
                    Key("Effacer", Modifier.size(width = 100.dp, height = 42.dp)) { vm.query.value = "" }
                }
                FocusSurface(
                    onClick = { systemKeyboard = true },
                    modifier = Modifier.width(312.dp).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = C.Surface,
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Keyboard, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Clavier / dictée vocale", style = T.Callout)
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Column(Modifier.focusRestorer(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    suggestions.take(5).forEach { s ->
                        FocusSurface(
                            onClick = { vm.query.value = s; vm.search(s) },
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            contentColor = C.Text2,
                            focusedScale = 1.03f,
                            elevation = 6.dp,
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Search, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(s, style = T.Callout, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(28.dp))

        // ---- results
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                state.query.isEmpty() -> EmptyState(Icons.Rounded.Search, "Rechercher", "Vidéos et chaînes YouTube, sans publicité.", Modifier.fillMaxSize())
                state.loading -> Loading(Modifier.fillMaxSize())
                state.error != null -> EmptyState(Icons.Rounded.ErrorOutline, "Recherche impossible", state.error, Modifier.fillMaxSize())
                state.results.isEmpty() -> EmptyState(Icons.Rounded.Search, "Aucun résultat", "Rien ne correspond à « ${state.query} ».", Modifier.fillMaxSize())
                else -> Results(state, onPlay = { videos, i -> nav.play(videos, i) }, onChannel = { nav.channel(it) }, onMenu = { menu = it }, onEnd = vm::loadMore)
            }
        }
    }

    if (systemKeyboard) {
        TextInputDialog(
            title = "Rechercher",
            initial = query,
            onDone = { vm.query.value = it; vm.search(it); systemKeyboard = false },
            onDismiss = { systemKeyboard = false },
        )
    }
    menu?.let { VideoMenu(it, onDismiss = { menu = null }) }
}

@Composable
private fun Results(
    state: SearchState,
    onPlay: (List<Video>, Int) -> Unit,
    onChannel: (String) -> Unit,
    onMenu: (Video) -> Unit,
    onEnd: () -> Unit,
) {
    val grid = rememberLazyGridState()
    val videos = state.videos
    val channels = state.channels
    LaunchedEffect(grid, videos.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= grid.layoutInfo.totalItemsCount - 6) onEnd() }
    }
    LaunchedEffect(state.query) {
        delay(10)
        grid.scrollToItem(0)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = grid,
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(start = 8.dp, end = 32.dp, top = 8.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (channels.isNotEmpty()) {
            item("channels", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    SectionTitle("Chaînes")
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(channels, key = { it.url }) { ch ->
                            ChannelCard(ch, onClick = { onChannel(ch.url) }, size = 104.dp)
                        }
                    }
                }
            }
            if (videos.isNotEmpty()) item("vtitle", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Vidéos", Modifier.padding(top = 4.dp)) }
        }
        itemsIndexed(videos, key = { i, v -> v.id + "_" + i }) { i, v ->
            VideoCard(v, width = null, onClick = { onPlay(videos, i) }, onLongClick = { onMenu(v) })
        }
        if (state.loadingMore) item("more", span = { GridItemSpan(maxLineSpan) }) { Loading(Modifier.fillMaxWidth().padding(16.dp)) }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp), color = C.Surface, focusedScale = 1.1f, contentAlignment = Alignment.Center) {
        Text(if (label.length == 1) label.uppercase() else label, style = T.Headline)
    }
}

@Composable
private fun KeyIcon(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp), color = C.Surface, focusedScale = 1.08f, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp))
    }
}
