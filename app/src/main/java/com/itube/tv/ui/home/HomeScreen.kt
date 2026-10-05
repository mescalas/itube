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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.itube.tv.ui.LocalContainer
import com.itube.tv.ui.LocalNav
import com.itube.tv.ui.LocalShell
import com.itube.tv.ui.appViewModel
import com.itube.tv.ui.components.Avatar
import com.itube.tv.ui.components.DialogAction
import com.itube.tv.ui.components.EmptyState
import com.itube.tv.ui.components.PillButton
import com.itube.tv.ui.components.VideoCard
import com.itube.tv.ui.components.VideoMenu
import com.itube.tv.ui.components.shelf
import com.itube.tv.ui.components.tryFocus
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import com.itube.tv.util.metaLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class HeroItem(val video: Video, val label: String)

class HomeViewModel(c: AppContainer) : ViewModel() {
    private val repo = c.repository
    var lastFocused: String? = null
    var heroIndex = 0

    val continueWatching = repo.continueWatching().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val feed = repo.feed(40).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val watchLater = repo.watchLater.map { l -> l.map { it.toVideo() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recommended = MutableStateFlow<List<Video>>(emptyList())
    val music = MutableStateFlow<List<Video>>(emptyList())

    val hero: StateFlow<List<HeroItem>> = combine(feed, recommended, music) { f, r, m ->
        val fromFeed = f.orEmpty().filter { !it.isShort }.take(5).map { HeroItem(it, "NOUVEAUTÉ · ${it.channelName.orEmpty().uppercase()}") }
        val fill = r.take(5 - fromFeed.size.coerceAtMost(5)).map { HeroItem(it, "RECOMMANDÉ POUR VOUS") }
        val more = if (fromFeed.size + fill.size < 3) m.take(4).map { HeroItem(it, "TENDANCE · MUSIQUE") } else emptyList()
        (fromFeed + fill + more).take(6)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { recommended.value = runCatching { repo.recommendations() }.getOrDefault(recommended.value) }
        viewModelScope.launch {
            if (music.value.isEmpty()) music.value = runCatching { YouTube.explore(ExploreSection.MUSIC) }.getOrDefault(emptyList())
        }
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

@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel(it) }
    val cw by vm.continueWatching.collectAsState()
    val feed by vm.feed.collectAsState()
    val later by vm.watchLater.collectAsState()
    val recommended by vm.recommended.collectAsState()
    val music by vm.music.collectAsState()
    val hero by vm.hero.collectAsState()
    val nav = LocalNav.current
    val shell = LocalShell.current
    val restoreRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
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
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex > 0 }.collect { shell.homeScrolled.value = it }
    }
    LaunchedEffect(shell.barFocused.value) {
        if (shell.barFocused.value && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
            listState.animateScrollToItem(0)
        }
    }
    DisposableEffect(Unit) { onDispose { shell.homeScrolled.value = false } }

    fun focusMod(key: String): Modifier = if (key == vm.lastFocused) Modifier.focusRequester(restoreRequester) else Modifier

    if (!loaded) return

    val nothing = hero.isEmpty() && cw.orEmpty().isEmpty() && later.isEmpty() && music.isEmpty()
    if (nothing) {
        EmptyState(
            Icons.Rounded.Search,
            "Bienvenue dans iTube",
            "Recherchez une chaîne et abonnez-vous : ses nouvelles vidéos apparaîtront ici, sans publicité.",
            Modifier.fillMaxSize().padding(top = 76.dp),
        )
        return
    }

    fun videoRow(title: String, key: String, videos: List<Video>, scope: androidx.compose.foundation.lazy.LazyListScope) {
        if (videos.isEmpty()) return
        scope.shelf(title, key, hint = "Maintenez OK pour plus d'options") {
            itemsIndexed(videos, key = { _, v -> key + v.id }) { i, v ->
                val k = "$key:${v.id}"
                VideoCard(
                    v,
                    modifier = focusMod(k),
                    onFocused = { vm.lastFocused = k },
                    onClick = { nav.play(videos, i) },
                    onLongClick = { menu = v to null },
                )
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(shell.homeEntry)
            // Scrolled back to the top, the hero sits under the tab bar: "up" finds nothing above it, so go to the bar.
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || ev.key != Key.DirectionUp) return@onPreviewKeyEvent false
                if (!focusManager.moveFocus(FocusDirection.Up)) shell.focusTabs()
                true
            },
        contentPadding = PaddingValues(bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        item("hero") {
            if (hero.isNotEmpty()) {
                Hero(hero, vm, focusMod("hero")) { vm.lastFocused = "hero" }
            } else {
                Column(Modifier.padding(start = 56.dp, top = 96.dp)) {
                    Text(greeting(), style = T.LargeTitle)
                    Text("Que regardons-nous aujourd'hui ?", style = T.Title3, color = C.Text2)
                }
            }
        }
        val resume = cw.orEmpty()
        if (resume.isNotEmpty()) shelf("Reprendre la lecture", "cw", hint = "Maintenez OK pour plus d'options") {
            itemsIndexed(resume, key = { _, h -> "cw" + h.videoId }) { _, h ->
                val k = "cw:${h.videoId}"
                val v = h.toVideo()
                VideoCard(
                    v,
                    progress = h.progress,
                    modifier = focusMod(k),
                    onFocused = { vm.lastFocused = k },
                    onClick = { nav.play(listOf(v), 0, h.positionMs) },
                    onLongClick = { menu = v to h },
                )
            }
        }
        videoRow("Nouveautés de vos abonnements", "feed", feed.orEmpty().take(30), this)
        videoRow("Recommandé pour vous", "reco", recommended, this)
        videoRow("À regarder plus tard", "later", later, this)
        videoRow("Tendances musique", "music", music, this)
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
