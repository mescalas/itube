package com.itube.tv.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.itube.tv.AppContainer
import com.itube.tv.data.ExploreSection
import com.itube.tv.data.Video
import com.itube.tv.data.YouTube
import com.itube.tv.data.db.HistoryEntity
import com.itube.tv.data.thumbnailOf
import com.itube.tv.data.videos
import com.itube.tv.ui.LocalContainer
import com.itube.tv.ui.LocalNav
import com.itube.tv.ui.LocalShell
import com.itube.tv.ui.appViewModel
import com.itube.tv.ui.components.Avatar
import com.itube.tv.ui.components.DialogAction
import com.itube.tv.ui.components.EmptyState
import com.itube.tv.ui.components.Loading
import com.itube.tv.ui.components.PillButton
import com.itube.tv.ui.components.VideoCard
import com.itube.tv.ui.components.VideoMenu
import com.itube.tv.ui.components.tryFocus
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import com.itube.tv.util.metaLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class HeroItem(val video: Video, val label: String)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository
    var lastFocused: String? = null
    var heroIndex = 0

    val continueWatching = repo.continueWatching().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val feed = repo.feed(40).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val watchLater = repo.watchLater.map { l -> l.map { it.toVideo() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ------------------------------------------------------------------ endless "Recommandé pour vous" grid

    /** The recommendations grid: the account's home when signed in, else picks from the local history. */
    val grid = MutableStateFlow<List<Video>>(emptyList())
    val gridLoading = MutableStateFlow(true)
    private val gridIds = HashSet<String>()
    private var nextToken: String? = null
    private val usedTokens = HashSet<String>()
    /** Videos whose related videos have not been used yet: they keep the grid going once a source runs dry. */
    private val seeds = ArrayDeque<String>()
    private var loadingMore = false
    private var generation = 0

    val hero: StateFlow<List<HeroItem>> = combine(feed, grid) { f, g ->
        val signedIn = c.account.signedIn
        val fromFeed = f.orEmpty().filter { !it.isShort }.take(3).map { HeroItem(it, "NOUVEAUTÉ · ${it.channelName.orEmpty().uppercase()}") }
        val label = if (signedIn) "POUR VOUS" else "RECOMMANDÉ POUR VOUS"
        val fromGrid = g.filter { !it.isLive }.take(6 - fromFeed.size).map { HeroItem(it, label + (it.channelName?.let { n -> " · " + n.uppercase() } ?: "")) }
        // Signed in, YouTube's own picks come first.
        (if (signedIn) fromGrid + fromFeed else fromFeed + fromGrid).take(6)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        resetGrid()
        // Signing in or out changes the whole page.
        viewModelScope.launch { c.account.state.map { c.account.signedIn }.distinctUntilChanged().drop(1).collect { resetGrid() } }
    }

    private fun resetGrid() {
        generation++
        grid.value = emptyList()
        gridIds.clear()
        usedTokens.clear()
        seeds.clear()
        nextToken = null
        loadingMore = false
        loadMore()
    }

    private fun accept(videos: List<Video>): List<Video> {
        val hide = c.settings.value.hideShorts
        return videos.filter { !(hide && it.isShort) && gridIds.add(it.id) }
    }

    /** Called as the focus nears the end of the grid. */
    fun loadMore() {
        if (loadingMore) return
        loadingMore = true
        val gen = generation
        gridLoading.value = true
        viewModelScope.launch {
            val added = runCatching { nextBatch() }.getOrDefault(emptyList())
            if (gen != generation) return@launch
            grid.value = grid.value + added
            added.forEach { seeds.addLast(it.id) }
            loadingMore = false
            gridLoading.value = false
        }
    }

    private suspend fun nextBatch(): List<Video> {
        val signedIn = c.account.signedIn
        // 1. The account's home, page after page (a few pages per batch when they are small).
        if (signedIn && (grid.value.isEmpty() || nextToken != null)) {
            val batch = ArrayList<Video>()
            var first = grid.value.isEmpty()
            while (batch.size < 12 && (first || nextToken != null)) {
                val (videos, next) = c.accountFeed.homePage(if (first) null else nextToken)
                first = false
                nextToken = next?.takeIf { usedTokens.add(it) }
                batch += accept(videos)
                if (usedTokens.size > 40) nextToken = null
            }
            return batch.ifEmpty { fromSeeds() }
        }
        // 2. Signed out, first page: what follows the videos watched lately (or the music trends).
        if (!signedIn && grid.value.isEmpty()) {
            val first = accept(repo.recommendations())
            if (first.isNotEmpty()) return first
            return accept(YouTube.explore(ExploreSection.MUSIC))
        }
        // 3. Then endlessly: videos related to the ones already in the grid.
        return fromSeeds()
    }

    private suspend fun fromSeeds(): List<Video> {
        val out = ArrayList<Video>()
        var tries = 0
        while (out.size < 12 && seeds.isNotEmpty() && tries < 4) {
            tries++
            val id = seeds.removeFirst()
            val related = runCatching { YouTube.stream(id).relatedItems.videos() }.getOrDefault(emptyList())
            out += accept(related.filter { !it.isLive })
        }
        return out
    }

    fun refresh() {
        // Back on the home screen: new picks only if the grid has nothing yet.
        if (grid.value.isEmpty() && !loadingMore) resetGrid()
    }

    fun removeFromHistory(h: HistoryEntity) {
        viewModelScope.launch { repo.removeFromHistory(h.videoId) }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Bonjour"
    in 12..17 -> "Bon après-midi"
    else -> "Bonsoir"
}

private val Gutter = 56.dp

/** Full-width cell that also covers the grid's side padding, so rows scroll from edge to edge. */
private fun Modifier.bleed(): Modifier = layout { measurable, constraints ->
    val extra = Gutter.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.maxWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra)
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel(it) }
    val cw by vm.continueWatching.collectAsState()
    val feed by vm.feed.collectAsState()
    val later by vm.watchLater.collectAsState()
    val grid by vm.grid.collectAsState()
    val gridLoading by vm.gridLoading.collectAsState()
    val hero by vm.hero.collectAsState()
    val nav = LocalNav.current
    val shell = LocalShell.current
    val restoreRequester = remember { FocusRequester() }
    val state = rememberLazyGridState()
    val focusManager = LocalFocusManager.current
    var menu by remember { mutableStateOf<Pair<Video, HistoryEntity?>?>(null) }
    val loaded = cw != null && feed != null

    LaunchedEffect(loaded) {
        if (loaded && nav.restoreFocus) {
            delay(80)
            if (!restoreRequester.tryFocus()) shell.focusTabs()
            nav.restoreFocus = false
            vm.refresh()
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex > 0 }.collect { shell.homeScrolled.value = it }
    }
    // Endless grid: the next batch loads while the focus approaches the end.
    LaunchedEffect(state) {
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to state.layoutInfo.totalItemsCount }
            .distinctUntilChanged()
            .collect { (last, total) -> if (total > 0 && last >= total - 12) vm.loadMore() }
    }
    LaunchedEffect(shell.barFocused.value) {
        if (shell.barFocused.value && (state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0)) {
            state.animateScrollToItem(0)
        }
    }
    DisposableEffect(Unit) { onDispose { shell.homeScrolled.value = false } }

    fun focusMod(key: String): Modifier = if (key == vm.lastFocused) Modifier.focusRequester(restoreRequester) else Modifier

    if (!loaded) return

    val nothing = hero.isEmpty() && cw.orEmpty().isEmpty() && later.isEmpty() && grid.isEmpty() && !gridLoading
    if (nothing) {
        EmptyState(
            Icons.Rounded.Search,
            "Bienvenue dans iTube",
            "Recherchez une chaîne et abonnez-vous, ou connectez votre compte YouTube dans les Réglages.",
            Modifier.fillMaxSize().padding(top = 76.dp),
        )
        return
    }

    fun LazyGridScope.row(title: String, key: String, videos: List<Video>, history: List<HistoryEntity>? = null) {
        if (videos.isEmpty()) return
        item(key = key, span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.bleed()) {
                Text(title, style = T.Title3, modifier = Modifier.padding(start = Gutter, bottom = 2.dp))
                LazyRow(
                    modifier = Modifier.focusRestorer(),
                    contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    rowItemsIndexed(videos, key = { _, v -> key + v.id }) { i, v ->
                        val k = "$key:${v.id}"
                        val h = history?.getOrNull(i)
                        VideoCard(
                            v,
                            progress = h?.progress,
                            modifier = focusMod(k),
                            onFocused = { vm.lastFocused = k },
                            onClick = { if (h != null) nav.play(listOf(v), 0, h.positionMs) else nav.play(videos, i) },
                            onLongClick = { menu = v to h },
                        )
                    }
                }
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = state,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(shell.homeEntry)
            // Scrolled back to the top, the hero sits under the tab bar: "up" finds nothing above it, so go to the bar.
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || ev.key != Key.DirectionUp) return@onPreviewKeyEvent false
                if (!focusManager.moveFocus(FocusDirection.Up)) shell.focusTabs()
                true
            },
        contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.bleed()) {
                if (hero.isNotEmpty()) {
                    Hero(hero, vm, focusMod("hero")) { vm.lastFocused = "hero" }
                } else {
                    Column(Modifier.padding(start = Gutter, top = 96.dp)) {
                        Text(greeting(), style = T.LargeTitle)
                        Text("Que regardons-nous aujourd'hui ?", style = T.Title3, color = C.Text2)
                    }
                }
            }
        }
        val resume = cw.orEmpty()
        row("Reprendre la lecture", "cw", resume.map { it.toVideo() }, resume)
        row("Nouveautés de vos abonnements", "feed", feed.orEmpty().take(30))
        row("À regarder plus tard", "later", later)

        // What the hero already shows is not repeated below.
        val heroIds = hero.map { it.video.id }.toSet()
        val picks = grid.filter { it.id !in heroIds }
        if (picks.isNotEmpty()) item(key = "gridTitle", span = { GridItemSpan(maxLineSpan) }) {
            Text("Recommandé pour vous", style = T.Title3, modifier = Modifier.padding(top = 4.dp))
        }
        itemsIndexed(picks, key = { _, v -> "g" + v.id }) { i, v ->
            val k = "g:${v.id}"
            VideoCard(
                v,
                width = null,
                modifier = focusMod(k),
                onFocused = { vm.lastFocused = k },
                onClick = { nav.play(picks, i) },
                onLongClick = { menu = v to null },
            )
        }
        if (gridLoading) item(key = "gridLoading", span = { GridItemSpan(maxLineSpan) }) {
            Loading(Modifier.fillMaxWidth().padding(24.dp))
        }
    }

    menu?.let { (v, h) ->
        VideoMenu(
            v,
            onDismiss = { menu = null },
            extra = if (h != null) listOf(DialogAction("Retirer de « Reprendre la lecture »", destructive = true) {
                menu = null
                vm.removeFromHistory(h)
            }) else emptyList(),
        )
    }
}

@Composable
private fun Hero(items: List<HeroItem>, vm: HomeViewModel, playMod: Modifier, onFocusHero: () -> Unit) {
    var index by remember { mutableIntStateOf(vm.heroIndex.coerceIn(0, items.size - 1)) }
    var focused by remember { mutableStateOf(false) }
    var focusedButton by remember { mutableIntStateOf(0) }
    val item = items[index.coerceIn(0, items.size - 1)]
    val video = item.video
    val nav = LocalNav.current
    val shell = LocalShell.current
    val repo = LocalContainer.current.repository
    val scope = rememberCoroutineScope()
    val inLater by remember(video.id) { repo.isInWatchLater(video.id) }.collectAsState(false)
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun go(delta: Int) {
        index = (index + delta + items.size) % items.size
        vm.heroIndex = index
    }

    // Focusing a hero button asks the list to show the whole hero, which keeps the list scrolled to the top.
    val showWholeHero = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect(Offset.Zero, size.toSize())
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }

    // Auto-advance like the Apple TV top shelf, paused while the hero has focus.
    LaunchedEffect(index, focused, items.size) {
        if (!focused && items.size > 1) {
            delay(8000)
            go(1)
        }
    }

    Box(Modifier.fillMaxWidth().height(420.dp).onSizeChanged { size = it }.bringIntoViewResponder(showWholeHero)) {
        Crossfade(targetState = video.id, animationSpec = tween(600), label = "hero") { id ->
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2A1416), Color(0xFF101014))))) {
                // maxresdefault is missing for some videos: hq (always present) sits underneath.
                AsyncImage(model = thumbnailOf(id), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                AsyncImage(model = thumbnailOf(id, high = true), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0xF2000000), 0.38f to Color(0xB3000000), 0.72f to Color(0x1A000000), 1f to Color.Transparent)
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.25f to Color.Transparent, 0.62f to Color.Transparent, 1f to C.Background)))

        Column(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 22.dp).width(600.dp)) {
            Text(
                item.label,
                style = T.Caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
                color = C.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.height(92.dp), contentAlignment = Alignment.BottomStart) {
                Text(video.title, style = T.LargeTitle.copy(fontSize = 36.sp, lineHeight = 44.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(video.channelAvatar, 24.dp)
                Spacer(Modifier.width(10.dp))
                Text(video.metaLine(withChannel = true), style = T.Callout, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier
                    .onFocusChanged {
                        focused = it.hasFocus
                        if (it.hasFocus) onFocusHero()
                    }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            ev.key == Key.DirectionLeft && focusedButton == 0 && items.size > 1 -> { go(-1); true }
                            ev.key == Key.DirectionRight && focusedButton == 1 && items.size > 1 -> { go(1); true }
                            else -> false
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PillButton(
                    "Lecture",
                    onClick = { nav.play(items.map { it.video }, index) },
                    icon = Icons.Rounded.PlayArrow,
                    primary = true,
                    modifier = playMod,
                    onFocusChange = { if (it) focusedButton = 0 },
                )
                PillButton(
                    if (inLater) "Dans « Plus tard »" else "Plus tard",
                    onClick = {
                        scope.launch {
                            val added = repo.toggleWatchLater(video, inLater)
                            shell.toast(if (added) "Ajoutée à « À regarder plus tard »" else "Retirée de « À regarder plus tard »")
                        }
                    },
                    icon = if (inLater) Icons.Rounded.Check else Icons.Rounded.Add,
                    onFocusChange = { if (it) focusedButton = 1 },
                )
            }
        }
        if (items.size > 1) {
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 34.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(width = if (i == index) 20.dp else 6.dp, height = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (i == index) Color.White else Color(0x59FFFFFF))
                    )
                }
            }
        }
    }
}
