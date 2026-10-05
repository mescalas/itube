package com.itube.tv.ui.channel

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.itube.tv.AppContainer
import com.itube.tv.data.ChannelPage
import com.itube.tv.data.Paged
import com.itube.tv.data.Video
import com.itube.tv.data.YouTube
import com.itube.tv.data.describe
import com.itube.tv.ui.LocalContainer
import com.itube.tv.ui.LocalNav
import com.itube.tv.ui.Toast
import com.itube.tv.ui.appViewModel
import com.itube.tv.ui.components.Avatar
import com.itube.tv.ui.components.EmptyState
import com.itube.tv.ui.components.Loading
import com.itube.tv.ui.components.PillButton
import com.itube.tv.ui.components.VideoGrid
import com.itube.tv.ui.components.VideoMenu
import com.itube.tv.ui.components.fullWidth
import com.itube.tv.ui.components.tryFocus
import com.itube.tv.ui.explore.Load
import com.itube.tv.ui.explore.SectionPill
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import com.itube.tv.util.formatSubscribers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ChannelViewModel(private val c: AppContainer, private val url: String) : ViewModel() {
    val page = MutableStateFlow<Load<ChannelPage>>(Load.Loading)
    val live = MutableStateFlow(false)
    val videos = MutableStateFlow<Paged<Video>?>(null)
    val subscribed = c.repository.isSubscribed(url)
    private var loadingMore = false

    init { load() }

    fun load() {
        page.value = Load.Loading
        viewModelScope.launch {
            page.value = try {
                val p = YouTube.channel(url)
                videos.value = p.videos
                p.channel.avatar?.let { a -> c.db.subscriptions().fillAvatar(p.channel.id, a) }
                Load.Ready(p)
            } catch (e: Exception) {
                Load.Failed(e.describe())
            }
        }
    }

    fun showLive(on: Boolean) {
        val p = (page.value as? Load.Ready)?.value ?: return
        if (live.value == on) return
        live.value = on
        videos.value = if (!on) p.videos else null
        if (on) viewModelScope.launch {
            val tab = p.liveTab ?: return@launch
            videos.value = runCatching { YouTube.channelTab(tab, p.channel) }.getOrDefault(Paged(emptyList(), null))
        }
    }

    fun loadMore() {
        val p = (page.value as? Load.Ready)?.value ?: return
        val current = videos.value ?: return
        if (loadingMore || current.next == null) return
        loadingMore = true
        viewModelScope.launch {
            runCatching { YouTube.channelMore(current, p.channel) }.onSuccess { next ->
                if (videos.value === current) videos.value = Paged(current.items + next.items, next.next, next.handler)
            }
            loadingMore = false
        }
    }

    suspend fun toggleSubscription(isSubscribed: Boolean): Boolean {
        val p = (page.value as? Load.Ready)?.value ?: return isSubscribed
        if (isSubscribed) c.repository.unsubscribe(p.channel.url) else c.repository.subscribe(p.channel)
        return !isSubscribed
    }
}

@Composable
fun ChannelScreen(url: String) {
    val vm = appViewModel(key = "channel:$url") { ChannelViewModel(it, url) }
    val page by vm.page.collectAsState()
    val videosPage by vm.videos.collectAsState()
    val live by vm.live.collectAsState()
    val subscribed by vm.subscribed.collectAsState(false)
    val nav = LocalNav.current
    val container = LocalContainer.current
    val toast = remember { mutableStateOf<String?>(null) }
    val subscribeFocus = remember { FocusRequester() }
    var menu by remember { mutableStateOf<Video?>(null) }
    val scope = rememberCoroutineScope()
    val hideShorts = container.settings.value.hideShorts

    Box(Modifier.fillMaxSize().background(C.Background)) {
        when (val p = page) {
            Load.Loading -> Loading(Modifier.fillMaxSize())
            is Load.Failed -> EmptyState(Icons.Rounded.ErrorOutline, "Chaîne indisponible", p.message, Modifier.fillMaxSize()) {
                PillButton("Réessayer", onClick = { vm.load() }, primary = true)
            }
            is Load.Ready -> {
                val ch = p.value.channel
                val banner = p.value.banner
                LaunchedEffect(Unit) {
                    delay(80)
                    if (!nav.restoreFocus) subscribeFocus.tryFocus()
                    nav.restoreFocus = false
                }
                if (banner != null) {
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        AsyncImage(model = banner, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.55f, modifier = Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), C.Background))))
                    }
                }
                val videos = videosPage?.items.orEmpty().filter { !(hideShorts && it.isShort) }
                VideoGrid(
                    videos = videos,
                    onClick = { i -> nav.play(videos, i) },
                    showChannel = false,
                    onLongClick = { menu = it },
                    onEndReached = vm::loadMore,
                    contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 56.dp, bottom = 56.dp),
                    header = {
                        fullWidth("header") {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(ch.avatar, 104.dp)
                                    Spacer(Modifier.width(24.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(ch.name, style = T.LargeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                                            if (ch.verified) {
                                                Spacer(Modifier.width(10.dp))
                                                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(22.dp), tint = C.Text2)
                                            }
                                        }
                                        formatSubscribers(ch.subscribers)?.let { Text(it, style = T.Callout, color = C.Text2) }
                                        if (!ch.description.isNullOrBlank()) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(ch.description, style = T.Subhead, color = C.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(620.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                Row(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PillButton(
                                        if (subscribed) "Abonné" else "S'abonner",
                                        onClick = {
                                            scope.launch {
                                                val now = vm.toggleSubscription(subscribed)
                                                toast.value = if (now) "Abonné à ${ch.name}" else "Désabonné de ${ch.name}"
                                            }
                                        },
                                        icon = if (subscribed) Icons.Rounded.Check else Icons.Rounded.Add,
                                        primary = !subscribed,
                                        modifier = Modifier.focusRequester(subscribeFocus),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    SectionPill("Vidéos", !live) { vm.showLive(false) }
                                    if (p.value.liveTab != null) SectionPill("En direct", live) { vm.showLive(true) }
                                }
                            }
                        }
                        if (videosPage == null) fullWidth("loading") { Loading(Modifier.fillMaxWidth().padding(top = 60.dp)) }
                        else if (videos.isEmpty()) fullWidth("empty") {
                            Text("Aucune vidéo.", style = T.Callout, color = C.Text2, modifier = Modifier.padding(top = 40.dp))
                        }
                    },
                )
            }
        }
        Toast(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp))
    }
    menu?.let { v -> VideoMenu(v, onDismiss = { menu = null }, toast = { toast.value = it }) }
}
