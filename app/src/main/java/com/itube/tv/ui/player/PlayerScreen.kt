package com.itube.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C as MediaC
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.itube.tv.data.Channel
import com.itube.tv.data.SPEEDS
import com.itube.tv.data.Segment
import com.itube.tv.data.Video
import com.itube.tv.data.thumbnailOf
import com.itube.tv.player.VideoSurface
import com.itube.tv.ui.LocalContainer
import com.itube.tv.ui.LocalNav
import com.itube.tv.ui.Toast
import com.itube.tv.ui.appViewModel
import com.itube.tv.ui.components.Avatar
import com.itube.tv.ui.components.FocusSurface
import com.itube.tv.ui.components.PillButton
import com.itube.tv.ui.components.ProgressLine
import com.itube.tv.ui.components.ThumbBadge
import com.itube.tv.ui.components.VideoCard
import com.itube.tv.ui.components.tryFocus
import com.itube.tv.ui.settings.speedLabel
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import com.itube.tv.util.formatClock
import com.itube.tv.util.formatDuration
import com.itube.tv.util.formatLikes
import com.itube.tv.util.formatSubscribers
import com.itube.tv.util.metaLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Panel { NONE, INFO, QUALITY, SUBTITLES, SPEED }

@Composable
fun PlayerScreen() {
    val vm = appViewModel { PlayerViewModel(it) }
    val nav = LocalNav.current
    val container = LocalContainer.current
    if (vm.request == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val pm = vm.pm
    val currentPlayer by pm.playerFlow.collectAsState()
    val player = currentPlayer ?: pm.player
    val scope = rememberCoroutineScope()

    val video by vm.current.collectAsState()
    val details by vm.details.collectAsState()
    val loading by vm.loading.collectAsState()
    val height by vm.height.collectAsState()
    val subtitle by vm.subtitle.collectAsState()
    val speed by vm.speed.collectAsState()
    val notice by vm.notice.collectAsState()
    val finished by vm.finished.collectAsState()
    val error by pm.error.collectAsState()

    var overlay by remember { mutableStateOf(true) }
    var overlayTick by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    val toast = remember { mutableStateOf<String?>(null) }
    val rootFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    var playbackState by remember { mutableIntStateOf(player.playbackState) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    val isLive = details?.isLive == true || video?.isLive == true

    fun showOverlay() {
        overlay = true
        overlayTick++
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_ENDED) {
                    vm.onEnded()
                    if (vm.upNext == null) vm.endOfQueue() else countdown = 8
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(Unit) { onDispose { vm.onExit() } }
    LaunchedEffect(Unit) {
        vm.start()
        rootFocus.tryFocus()
    }
    LaunchedEffect(finished) { if (finished) nav.back() }
    LaunchedEffect(notice) {
        notice?.let {
            toast.value = it
            vm.consumeNotice()
        }
    }
    // A new video starts: controls shown briefly, no countdown left over.
    LaunchedEffect(video?.id) {
        countdown = null
        showOverlay()
    }
    LaunchedEffect(overlay, overlayTick, isPlaying, panel, seekTarget) {
        if (overlay && panel == Panel.NONE && seekTarget == null && isPlaying) {
            delay(4000)
            overlay = false
        }
    }
    // Position ticker: progress bar, SponsorBlock skips, periodic history save.
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != MediaC.TIME_UNSET && it > 0 } ?: 0L
            if (player.isPlaying) {
                vm.segmentToSkip(position)?.let { seg ->
                    player.seekTo(seg.endMs)
                    toast.value = seg.label
                }
                if (++ticks % 30 == 0) vm.saveProgress()
            }
            delay(500)
        }
    }
    LaunchedEffect(countdown) {
        val c = countdown ?: return@LaunchedEffect
        if (c <= 0) {
            countdown = null
            if (!vm.next()) vm.endOfQueue()
        } else {
            delay(1000)
            if (countdown == c) countdown = c - 1
        }
    }
    LaunchedEffect(error) {
        if (error != null) {
            panel = Panel.NONE
            delay(50)
            retryFocus.tryFocus()
        } else rootFocus.tryFocus()
    }
    LaunchedEffect(panel) { if (panel == Panel.NONE && error == null) rootFocus.tryFocus() }

    BackHandler {
        when {
            countdown != null -> { countdown = null; panel = Panel.INFO }
            panel == Panel.QUALITY || panel == Panel.SUBTITLES || panel == Panel.SPEED -> panel = Panel.INFO
            panel != Panel.NONE -> panel = Panel.NONE
            seekTarget != null -> { seekJob?.cancel(); seekTarget = null }
            overlay && isPlaying -> overlay = false
            else -> nav.back()
        }
    }

    fun commitSeekLater() {
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(700)
            seekTarget?.let { player.seekTo(it) }
            seekTarget = null
        }
    }

    fun seekBy(deltaMs: Long) {
        if (isLive) return
        val dur = player.duration.takeIf { it != MediaC.TIME_UNSET && it > 0 } ?: return
        val base = seekTarget ?: player.currentPosition
        seekTarget = (base + deltaMs).coerceIn(0, dur - 1000)
        showOverlay()
        commitSeekLater()
    }

    fun togglePlay() {
        val target = seekTarget
        if (target != null) {
            seekJob?.cancel()
            player.seekTo(target)
            seekTarget = null
            player.play()
        } else if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        showOverlay()
    }

    fun handleKey(code: Int, repeat: Int): Boolean {
        if (countdown != null) {
            when (code) {
                AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { countdown = 0; return true }
            }
            return false
        }
        val step = when {
            repeat > 12 -> 60_000L
            repeat > 4 -> 30_000L
            else -> 10_000L
        }
        when (code) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE,
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> togglePlay()
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); showOverlay() }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); showOverlay() }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-step)
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(step)
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-30_000)
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(30_000)
            AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { if (!vm.next()) toast.value = "Aucune vidéo suivante" }
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_INFO -> if (overlay) overlay = false else showOverlay()
            AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_SETTINGS -> panel = Panel.INFO
            else -> return false
        }
        return true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { ev ->
                if (panel != Panel.NONE || error != null) return@onKeyEvent false
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                handleKey(ev.nativeKeyEvent.keyCode, ev.nativeKeyEvent.repeatCount)
            }
            .focusable()
    ) {
        VideoSurface(player, Modifier.fillMaxSize(), bottomPadding = if (overlay && panel == Panel.NONE) 0.2f else 0.08f)

        // While the streams are being extracted: the thumbnail, dimmed, under the spinner.
        if (loading) {
            video?.let { v ->
                AsyncImage(
                    model = v.thumbnail ?: thumbnailOf(v.id),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.35f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if ((loading || playbackState == Player.STATE_BUFFERING) && error == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(44.dp))
        }

        AnimatedVisibility(overlay && panel == Panel.NONE && error == null && countdown == null, enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
            Overlay(video, position, duration, seekTarget, isPlaying, isLive, vm.segments, height)
        }
        if (!isPlaying && !loading && playbackState == Player.STATE_READY && !overlay && panel == Panel.NONE && error == null) {
            Icon(
                Icons.Rounded.Pause, null,
                Modifier.align(Alignment.Center).size(72.dp).clip(RoundedCornerShape(36.dp)).background(Color(0x80000000)).padding(14.dp),
                tint = Color.White,
            )
        }

        AnimatedVisibility(
            panel == Panel.INFO,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(200)) { it / 3 } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(160)) { it / 3 } + fadeOut(tween(160)),
        ) {
            InfoPanel(
                vm = vm,
                height = height,
                subtitleLabel = subtitle?.let { it.displayLanguageName + if (it.isAutoGenerated) " (auto)" else "" } ?: "Désactivés",
                speed = speed,
                onQuality = { panel = Panel.QUALITY },
                onSubtitles = { panel = Panel.SUBTITLES },
                onSpeed = { panel = Panel.SPEED },
                onChannel = { url -> panel = Panel.NONE; nav.channel(url) },
                onToast = { toast.value = it },
                onRelated = { v -> panel = Panel.NONE; vm.playRelated(v) },
                onNext = { panel = Panel.NONE; if (!vm.next()) toast.value = "Aucune vidéo suivante" },
            )
        }
        val d = details
        SidePanel(panel == Panel.QUALITY, "Qualité") {
            val auto = container.settings.value.maxQuality
            val heights = d?.heights.orEmpty()
            if (heights.isEmpty()) item { Text("Qualité unique pour cette vidéo", style = T.Callout, color = C.Text2, modifier = Modifier.padding(12.dp)) }
            itemsIndexed(heights) { i, h ->
                OptionRow(qualityLabel(h), h == height, first = i == 0) { vm.setQuality(h); panel = Panel.NONE }
            }
            item { Text("Par défaut : ${auto.label} (Réglages › Lecture)", style = T.Footnote, color = C.Text3, modifier = Modifier.padding(12.dp)) }
        }
        SidePanel(panel == Panel.SUBTITLES, "Sous-titres") {
            item { OptionRow("Désactivés", subtitle == null, first = true) { vm.setSubtitle(null); panel = Panel.NONE } }
            itemsIndexed(d?.subtitles.orEmpty()) { _, s ->
                OptionRow(s.displayLanguageName + if (s.isAutoGenerated) " (automatiques)" else "", s == subtitle) { vm.setSubtitle(s); panel = Panel.NONE }
            }
        }
        SidePanel(panel == Panel.SPEED, "Vitesse") {
            itemsIndexed(SPEEDS) { i, s ->
                OptionRow(speedLabel(s), s == speed, first = s == speed || (i == 0 && speed !in SPEEDS)) { vm.setSpeed(s); panel = Panel.NONE }
            }
        }

        countdown?.let { c ->
            vm.upNext?.let { next -> UpNextCard(next, c, Modifier.align(Alignment.BottomEnd).padding(40.dp)) }
        }

        if (error != null) {
            ErrorCard(
                message = error ?: "",
                retryFocus = retryFocus,
                onRetry = { vm.retry() },
                onNext = if (vm.upNext != null) ({ pm.clearError(); vm.next() }) else null,
                onBack = { nav.back() },
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Toast(toast, Modifier.align(Alignment.TopCenter).padding(top = 36.dp))
    }
}

private fun qualityLabel(h: Int) = when {
    h >= 2160 -> "2160p (4K)"
    h >= 1440 -> "1440p (QHD)"
    h >= 1080 -> "1080p (HD)"
    h >= 720 -> "720p (HD)"
    else -> "${h}p"
}

// ============================================================================ overlay

@Composable
private fun Overlay(
    video: Video?,
    position: Long,
    duration: Long,
    seekTarget: Long?,
    isPlaying: Boolean,
    isLive: Boolean,
    segments: List<Segment>,
    height: Int,
) {
    if (video == null) return
    val shown = seekTarget ?: position
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
        Row(Modifier.padding(start = 48.dp, top = 32.dp, end = 160.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(video.channelAvatar, 48.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(video.title, style = T.Title2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(video.metaLine(withChannel = true), style = T.Callout, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(formatClock(System.currentTimeMillis()), style = T.Title3, color = C.Text2, modifier = Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 48.dp))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(180.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 48.dp, vertical = 34.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isPlaying && seekTarget == null) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(30.dp))
                Spacer(Modifier.width(14.dp))
                if (seekTarget != null) {
                    val delta = seekTarget - position
                    Text((if (delta >= 0) "+" else "−") + formatDuration(kotlin.math.abs(delta)), style = T.Headline, color = C.Text2)
                }
                if (isLive) ThumbBadge("EN DIRECT", color = C.YouTube)
                Spacer(Modifier.weight(1f))
                if (height > 0) {
                    Text(qualityLabel(height).substringBefore(' '), style = T.Footnote, color = C.Text3)
                    Spacer(Modifier.width(16.dp))
                }
                Text("▼  Infos, qualité, sous-titres, à suivre", style = T.Footnote, color = C.Text3)
            }
            if (!isLive) {
                Spacer(Modifier.height(12.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    ProgressLine(
                        if (duration > 0) shown.toFloat() / duration else 0f,
                        Modifier.fillMaxWidth(),
                        color = Color.White,
                        height = if (seekTarget != null) 8.dp else 6.dp,
                    )
                    // Sponsored parts, skipped automatically, shown on the bar.
                    if (duration > 0) segments.forEach { s ->
                        val start = maxWidth * (s.startMs.toFloat() / duration)
                        val w = maxWidth * ((s.endMs - s.startMs).toFloat() / duration)
                        Box(Modifier.offset(x = start).width(w).height(if (seekTarget != null) 8.dp else 6.dp).background(Color(0xCC00D400)))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Text(formatDuration(shown), style = T.Callout, color = C.Text2)
                    Spacer(Modifier.weight(1f))
                    if (duration > 0) Text("−" + formatDuration(duration - shown), style = T.Callout, color = C.Text2)
                }
            }
        }
    }
}

// ============================================================================ info panel (▼)

@Composable
private fun InfoPanel(
    vm: PlayerViewModel,
    height: Int,
    subtitleLabel: String,
    speed: Float,
    onQuality: () -> Unit,
    onSubtitles: () -> Unit,
    onSpeed: () -> Unit,
    onChannel: (String) -> Unit,
    onToast: (String) -> Unit,
    onRelated: (Video) -> Unit,
    onNext: () -> Unit,
) {
    val container = LocalContainer.current
    val repo = container.repository
    val details by vm.details.collectAsState()
    val video by vm.current.collectAsState()
    val v = video ?: return
    val scope = rememberCoroutineScope()
    val subscribed by remember(v.channelUrl) { repo.isSubscribed(v.channelUrl.orEmpty()) }.collectAsState(false)
    val inLater by remember(v.id) { repo.isInWatchLater(v.id) }.collectAsState(false)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(40)
        first.tryFocus()
    }
    val d = details
    val related = remember(d) {
        val next = vm.upNext
        (listOfNotNull(next) + d?.related.orEmpty()).distinctBy { it.id }.take(20)
    }

    Box(
        Modifier.fillMaxWidth().height(470.dp)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color(0xD9000000), 1f to Color(0xF7000000)))
    ) {
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 48.dp)) {
                Text(v.title, style = T.Title2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(
                            v.channelName,
                            d?.subscribers?.let { formatSubscribers(it) },
                            v.metaLine().ifBlank { null },
                        ).joinToString("  ·  "),
                        style = T.Callout,
                        color = C.Text2,
                        maxLines = 1,
                    )
                    formatLikes(d?.likes ?: -1)?.let {
                        Spacer(Modifier.width(14.dp))
                        Icon(Icons.Rounded.ThumbUp, null, Modifier.size(14.dp), tint = C.Text2)
                        Spacer(Modifier.width(5.dp))
                        Text(it, style = T.Callout, color = C.Text2)
                    }
                }
                if (!d?.description.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(d!!.description, style = T.Subhead, color = C.Text3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(820.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyRow(
                Modifier.focusRestorer(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (d?.isLive != true) {
                    item {
                        PillButton(
                            "Qualité" + if (height > 0) " · ${height}p" else "",
                            onClick = onQuality,
                            icon = Icons.Rounded.HighQuality,
                            modifier = Modifier.focusRequester(first),
                        )
                    }
                    item { PillButton("Sous-titres · $subtitleLabel", onClick = onSubtitles, icon = Icons.Rounded.ClosedCaption) }
                    item { PillButton("Vitesse · ${speedLabel(speed)}", onClick = onSpeed, icon = Icons.Rounded.Speed) }
                }
                if (vm.upNext != null) item {
                    PillButton("Suivante", onClick = onNext, icon = Icons.Rounded.SkipNext, modifier = if (d?.isLive == true) Modifier.focusRequester(first) else Modifier)
                }
                v.channelUrl?.let { url ->
                    item { PillButton("Chaîne", onClick = { onChannel(url) }, icon = Icons.Rounded.Person) }
                    item {
                        PillButton(
                            if (subscribed) "Abonné" else "S'abonner",
                            onClick = {
                                scope.launch {
                                    if (subscribed) {
                                        repo.unsubscribe(url)
                                        onToast("Désabonné de ${v.channelName.orEmpty()}")
                                    } else {
                                        repo.subscribe(Channel(url, v.channelName.orEmpty(), v.channelAvatar))
                                        onToast("Abonné à ${v.channelName.orEmpty()}")
                                    }
                                }
                            },
                            icon = if (subscribed) Icons.Rounded.Check else Icons.Rounded.Add,
                        )
                    }
                }
                item {
                    PillButton(
                        if (inLater) "Dans « Plus tard »" else "Plus tard",
                        onClick = {
                            scope.launch {
                                val added = repo.toggleWatchLater(v, inLater)
                                onToast(if (added) "Ajoutée à « À regarder plus tard »" else "Retirée de « À regarder plus tard »")
                            }
                        },
                        icon = if (inLater) Icons.Rounded.Check else Icons.Rounded.WatchLater,
                    )
                }
            }
            if (related.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("À suivre", style = T.Headline, modifier = Modifier.padding(start = 48.dp))
                LazyRow(
                    Modifier.focusRestorer(),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    itemsIndexed(related, key = { _, r -> "r" + r.id }) { _, r ->
                        VideoCard(r, width = 216.dp, onClick = { onRelated(r) })
                    }
                }
            }
        }
    }
}

// ============================================================================ side panels

@Composable
private fun SidePanel(visible: Boolean, title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            visible,
            enter = slideInHorizontally(tween(180)) { it / 3 } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(160)) { it / 3 } + fadeOut(tween(160)),
        ) {
            Box(
                Modifier.fillMaxHeight().width(400.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0x00000000), Color(0xE6000000), Color(0xF5000000))))
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(start = 48.dp, end = 28.dp),
                    contentPadding = PaddingValues(top = 32.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item { Text(title, style = T.Title2, modifier = Modifier.padding(bottom = 12.dp, start = 12.dp)) }
                    content()
                }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, first: Boolean = false, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    if (selected || first) {
        LaunchedEffect(Unit) {
            delay(40)
            focus.tryFocus()
        }
    }
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(46.dp).focusRequester(focus),
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        focusedScale = 1.02f,
        elevation = 6.dp,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = T.Callout.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
        }
    }
}

// ============================================================================ end of video, errors

@Composable
private fun UpNextCard(next: Video, seconds: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.width(520.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xF21C1C1E)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(192.dp).height(108.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface2)) {
            AsyncImage(model = next.thumbnail ?: thumbnailOf(next.id), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("À SUIVRE DANS $seconds S", style = T.Caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp), color = C.Text2)
            Spacer(Modifier.height(4.dp))
            Text(next.title, style = T.Headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
            next.channelName?.let { Text(it, style = T.Footnote, color = C.Text2, maxLines = 1) }
            Spacer(Modifier.height(6.dp))
            Text("OK : lire maintenant  ·  Retour : annuler", style = T.Caption, color = C.Text3)
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    retryFocus: FocusRequester,
    onRetry: () -> Unit,
    onNext: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(500.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF21C1C1E)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(40.dp), tint = C.Red)
        Spacer(Modifier.height(12.dp))
        Text("Lecture impossible", style = T.Title3)
        Spacer(Modifier.height(6.dp))
        Text(message, style = T.Subhead, color = C.Text2)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Réessayer", onClick = onRetry, primary = true, modifier = Modifier.focusRequester(retryFocus))
            if (onNext != null) PillButton("Vidéo suivante", onClick = onNext)
            PillButton("Retour", onClick = onBack)
        }
    }
}
