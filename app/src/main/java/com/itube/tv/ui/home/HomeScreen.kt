package com.itube.tv.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.itube.tv.data.account.Shelf
import com.itube.tv.data.thumbnailOf
import com.itube.tv.ui.LocalNav
import com.itube.tv.ui.LocalShell
import com.itube.tv.ui.Tab
import com.itube.tv.ui.appViewModel
import com.itube.tv.ui.components.Avatar
import com.itube.tv.ui.components.EmptyState
import com.itube.tv.ui.components.Loading
import com.itube.tv.ui.components.PillButton
import com.itube.tv.ui.components.VideoCard
import com.itube.tv.ui.components.VideoMenu
import com.itube.tv.ui.components.tryFocus
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import com.itube.tv.util.metaLine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

/** One row of the home page; [twoLines] shelves show ten videos on two lines instead of a scrolling row. */
data class HomeShelf(val key: String, val title: String, val videos: List<Video>, val twoLines: Boolean = false)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository
    var lastFocused: String? = null

    /** YouTube's shelves (the account's home, or trends and history-based picks when signed out); null while loading. */
    private val remote = MutableStateFlow<List<Shelf>?>(null)
    private var generation = 0

    /** How far each watched video got: a red line on its thumbnail (the player resumes from there). */
    val progress: StateFlow<Map<String, Float>> = repo.history(300)
        .map { l -> l.associate { it.videoId to it.progress } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val shelves: StateFlow<List<HomeShelf>?> = combine(
        remote,
        repo.feed(40),
        repo.watchLater.map { l -> l.map { it.toVideo() } },
    ) { r, feed, later -> r?.let { arrange(it, feed, later) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        load()
        // Signing in or out changes the whole page.
        viewModelScope.launch { c.account.state.map { c.account.signedIn }.distinctUntilChanged().drop(1).collect { load() } }
    }

    fun load() {
        val gen = ++generation
        remote.value = null
        viewModelScope.launch {
            val shelves = runCatching { fetch() }.getOrDefault(emptyList())
            if (gen == generation) remote.value = shelves
        }
    }

    /** Back on the home screen: try again only if YouTube gave nothing. */
    fun refresh() {
        if (remote.value?.isEmpty() == true) load()
    }

    private suspend fun fetch(): List<Shelf> {
        if (c.account.signedIn) {
            return c.accountFeed.home().map { if (it.title == "Suggestions") it.copy(title = "Pour vous") else it }
        }
        // Signed out: what follows the videos watched lately, then the trends of a few sections.
        return coroutineScope {
            val picks = async { runCatching { repo.recommendations() }.getOrDefault(emptyList()) }
            val sections = listOf(ExploreSection.MUSIC, ExploreSection.GAMING, ExploreSection.PODCASTS).map { s ->
                async { Shelf(s.label, runCatching { YouTube.explore(s) }.getOrDefault(emptyList())) }
            }
            listOf(Shelf("Pour vous", picks.await())) + sections.awaitAll()
        }
    }

    /**
     * YouTube's first shelf leads on two lines, then the subscriptions, two more of YouTube's, Watch later,
     * and the rest; the fifth shelf goes on two lines too, so the page alternates blocks and rows.
     */
    private fun arrange(remote: List<Shelf>, feed: List<Video>, later: List<Video>): List<HomeShelf> {
        val hide = c.settings.value.hideShorts
        fun clean(videos: List<Video>) = videos.filter { !(hide && it.isShort) }
        val yt = remote.map { HomeShelf("yt:" + it.title, it.title, clean(it.videos)) }.filter { it.videos.size >= 2 }
        val local = listOf(
            HomeShelf("feed", "Nouveautés de vos abonnements", clean(feed).take(20)),
            HomeShelf("later", "À regarder plus tard", later),
        ).filter { it.videos.isNotEmpty() }
        val ordered = yt.take(1) + local.take(1) + yt.drop(1).take(2) + local.drop(1) + yt.drop(3)
        return ordered.mapIndexed { i, s ->
            if ((i == 0 || i == 4) && s.videos.size >= BlockSize) s.copy(twoLines = true, videos = s.videos.take(BlockSize)) else s
        }
    }
}

private val Gutter = 56.dp
private const val BlockSize = 10
private const val BlockColumns = 5
private val PreviewHeight = 280.dp
/** Where the shelves start under the preview, and how far they rise when the preview folds over a two-line block. */
private val RowsTop = 262.dp
private val Fold = 150.dp

/** The focused shelf goes to the top of the list (the default only scrolls it just into view). */
private object AlignToTop : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset
}

/** Whatever card inside takes the focus, the list is asked to show the whole shelf, title included. */
private fun Modifier.wholeShelfIntoView(): Modifier = composed {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val responder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect(Offset.Zero, size.toSize())
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }
    onSizeChanged { size = it }.bringIntoViewResponder(responder)
}

@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel(it) }
    val shelves by vm.shelves.collectAsState()
    val progress by vm.progress.collectAsState()
    val nav = LocalNav.current
    val shell = LocalShell.current
    val restoreRequester = remember { FocusRequester() }
    val firstRequester = remember { FocusRequester() }
    val state = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    var menu by remember { mutableStateOf<Video?>(null) }
    var preview by remember { mutableStateOf<Pair<Video, String>?>(null) }
    var inBlock by remember { mutableStateOf(false) }
    var refocus by remember { mutableStateOf(false) }
    val loaded = shelves != null

    LaunchedEffect(loaded) {
        if (loaded && nav.restoreFocus) {
            delay(80)
            if (!restoreRequester.tryFocus()) shell.focusTabs()
            nav.restoreFocus = false
            vm.refresh()
        }
        // "Actualiser": the new page starts on its first video.
        if (loaded && refocus) {
            delay(80)
            firstRequester.tryFocus()
            refocus = false
        }
    }
    LaunchedEffect(shell.barFocused.value) {
        if (shell.barFocused.value) inBlock = false
        if (shell.barFocused.value && (state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0)) {
            state.animateScrollToItem(0)
        }
    }

    val list = shelves ?: run {
        Loading(Modifier.fillMaxSize().padding(top = 76.dp))
        return
    }
    if (list.isEmpty()) {
        EmptyState(
            Icons.Rounded.Search,
            "Bienvenue dans iTube",
            "Recherchez une chaîne et abonnez-vous, ou connectez votre compte YouTube dans les Réglages.",
            Modifier.fillMaxSize().padding(top = 76.dp),
        )
        return
    }

    fun focusMod(key: String): Modifier = if (key == vm.lastFocused) Modifier.focusRequester(restoreRequester) else Modifier

    @Composable
    fun Card(shelf: HomeShelf, i: Int, v: Video, modifier: Modifier = Modifier, width: Dp? = 196.dp) {
        val k = "${shelf.key}:${v.id}"
        VideoCard(
            v,
            width = width,
            progress = progress[v.id],
            modifier = modifier.then(focusMod(k)).then(if (shelf === list.first() && i == 0) Modifier.focusRequester(firstRequester) else Modifier),
            onFocused = {
                vm.lastFocused = k
                preview = v to shelf.title
            },
            onClick = { nav.play(shelf.videos, i) },
            onLongClick = { menu = v },
        )
    }

    val fold by animateFloatAsState(if (inBlock) 1f else 0f, tween(250), label = "fold")
    val default = LocalBringIntoViewSpec.current

    Box(Modifier.fillMaxSize()) {
        val (video, label) = preview ?: (list.first().videos.first() to list.first().title)
        Preview(video, label) { fold }

        CompositionLocalProvider(LocalBringIntoViewSpec provides AlignToTop) {
            LazyColumn(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = RowsTop - Fold)
                    // The list is laid out for the folded preview and slides down while the preview shows.
                    .graphicsLayer { translationY = Fold.toPx() * (1f - fold) }
                    .focusRequester(shell.homeEntry)
                    // "Up" from the first shelf finds nothing above it in the list: go to the tab bar.
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown || ev.key != Key.DirectionUp) return@onPreviewKeyEvent false
                        if (!focusManager.moveFocus(FocusDirection.Up)) shell.focusTabs()
                        true
                    },
                contentPadding = PaddingValues(bottom = 300.dp),
            ) {
                items(list, key = { it.key }) { shelf ->
                    // Inside a shelf, rows scroll the usual way.
                    CompositionLocalProvider(LocalBringIntoViewSpec provides default) {
                        Column(
                            Modifier
                                .wholeShelfIntoView()
                                .onFocusChanged { if (it.hasFocus) inBlock = shelf.twoLines }
                                .padding(bottom = 6.dp)
                        ) {
                            Text(shelf.title, style = T.Title3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Gutter))
                            if (shelf.twoLines) {
                                Column(
                                    Modifier.focusRestorer().padding(start = Gutter, end = Gutter, top = 14.dp, bottom = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    shelf.videos.chunked(BlockColumns).forEachIndexed { line, videos ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                            videos.forEachIndexed { j, v -> Card(shelf, line * BlockColumns + j, v, Modifier.weight(1f), width = null) }
                                            repeat(BlockColumns - videos.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            } else {
                                LazyRow(
                                    modifier = Modifier.focusRestorer(),
                                    contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 14.dp, bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                                ) {
                                    itemsIndexed(shelf.videos, key = { _, v -> shelf.key + v.id }) { i, v -> Card(shelf, i, v) }
                                }
                            }
                        }
                    }
                }
                item(key = "end") {
                    Row(
                        Modifier
                            .wholeShelfIntoView()
                            .onFocusChanged { if (it.hasFocus) inBlock = false }
                            .padding(horizontal = Gutter)
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(C.Surface)
                            .padding(horizontal = 24.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Vous avez fait le tour de l'accueil", style = T.Headline)
                            Text("Les rubriques reviennent avec de nouvelles vidéos à chaque visite.", style = T.Footnote, color = C.Text2)
                        }
                        PillButton("Explorer d'autres sujets", onClick = { shell.openTab(Tab.EXPLORE) })
                        PillButton("Actualiser", icon = Icons.Rounded.Refresh, onClick = {
                            refocus = true
                            preview = null
                            inBlock = false
                            vm.lastFocused = null
                            vm.load()
                        })
                    }
                }
            }
        }
    }

    menu?.let { v -> VideoMenu(v, onDismiss = { menu = null }) }
}

/** The focused video, large, at the top of the page; it folds away while a two-line shelf has the focus. */
@Composable
private fun Preview(video: Video, label: String, fold: () -> Float) {
    Box(Modifier.fillMaxWidth().height(PreviewHeight)) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.64f)
                .fillMaxHeight()
                .graphicsLayer { alpha = 1f - 0.7f * fold() }
        ) {
            Crossfade(targetState = video.id, animationSpec = tween(250), label = "preview") { id ->
                Box(Modifier.fillMaxSize().background(C.Surface)) {
                    // maxresdefault is missing for some videos: hq (always present) sits underneath.
                    AsyncImage(model = thumbnailOf(id), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    AsyncImage(model = thumbnailOf(id, high = true), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(0f to Color.Black, 0.25f to Color(0xBF000000), 0.7f to Color(0x1A000000), 1f to Color.Transparent)
                )
            )
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 0.95f to C.Background)))
        }
        Column(
            Modifier
                .padding(start = Gutter, top = 84.dp)
                .width(440.dp)
                .graphicsLayer {
                    // Gone in the first part of the fold, so it never shows over the rising shelves.
                    alpha = (1f - 2.5f * fold()).coerceAtLeast(0f)
                    translationY = -12.dp.toPx() * fold()
                }
        ) {
            Text(
                (label + (video.channelName?.let { " · $it" } ?: "")).uppercase(),
                style = T.Caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
                color = C.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.height(68.dp), contentAlignment = Alignment.BottomStart) {
                Text(video.title, style = T.Title1.copy(fontSize = 26.sp, lineHeight = 34.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(video.channelAvatar, 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(video.metaLine(withChannel = true), style = T.Callout, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
