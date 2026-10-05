package com.itube.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.itube.tv.data.Channel
import com.itube.tv.data.Video
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import com.itube.tv.util.formatDuration
import com.itube.tv.util.formatSubscribers
import com.itube.tv.util.metaLine

private val CardShape = RoundedCornerShape(14.dp)
private val Hairline = Color(0x1AFFFFFF)

/** tvOS-like specular highlight that appears on the focused artwork. */
@Composable
fun BoxScope.Sheen(focused: Boolean) {
    val alpha by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "sheen")
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { this.alpha = alpha }
            .background(
                Brush.linearGradient(
                    0f to Color(0x33FFFFFF),
                    0.38f to Color(0x0AFFFFFF),
                    0.55f to Color.Transparent,
                    start = Offset.Zero,
                    end = Offset.Infinite,
                )
            )
    )
}

@Composable
private fun BoxScope.Frame() {
    Box(Modifier.matchParentSize().border(1.dp, Hairline, CardShape))
}

/** Small dark pill over a thumbnail (duration, "EN DIRECT"). */
@Composable
fun ThumbBadge(text: String, modifier: Modifier = Modifier, color: Color = Color(0xCC000000)) {
    Text(
        text,
        style = T.Caption.copy(fontWeight = FontWeight.SemiBold),
        color = Color.White,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 16:9 video lockup: thumbnail that lifts on focus, title and channel always readable below. */
@Composable
fun VideoCard(
    video: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 256.dp,
    progress: Float? = null,
    showChannel: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(if (width != null) modifier.width(width) else modifier.fillMaxWidth()) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardShape,
            color = C.Surface,
            focusedColor = C.Surface,
            focusedContentColor = C.Text,
            focusedScale = 1.08f,
            elevation = 28.dp,
            onFocusChange = {
                focused = it
                if (it) onFocused?.invoke()
            },
        ) { f ->
            Box(Modifier.fillMaxSize().background(C.Surface2))
            if (!video.thumbnail.isNullOrBlank()) {
                AsyncImage(model = video.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            when {
                video.isLive -> ThumbBadge("EN DIRECT", Modifier.align(Alignment.BottomEnd).padding(8.dp), color = C.YouTube)
                video.durationSec > 0 -> ThumbBadge(formatDuration(video.durationSec * 1000), Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
            if (progress != null && progress > 0f) {
                ProgressLine(
                    progress,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color = C.YouTube,
                    track = Color(0x66FFFFFF),
                    height = 4.dp,
                )
            }
            Frame()
            Sheen(f)
        }
        val shift by animateFloatAsState(if (focused) 10f else 0f, tween(180), label = "shift")
        Column(Modifier.graphicsLayer { translationY = shift * density }.padding(top = 10.dp, start = 2.dp, end = 2.dp)) {
            Text(
                video.title,
                style = T.Callout.copy(fontWeight = FontWeight.SemiBold),
                color = if (focused) C.Text else C.Text.copy(alpha = 0.88f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = video.metaLine(withChannel = showChannel)
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(meta, style = T.Footnote, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Round avatar with a person glyph as placeholder. */
@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(C.Surface2), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Person, null, Modifier.size(size * 0.5f), tint = C.Text3)
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Channel lockup: round avatar that lifts on focus, name and subscribers under it. */
@Composable
fun ChannelCard(
    channel: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(size + 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.size(size),
            shape = CircleShape,
            color = C.Surface,
            focusedColor = C.Surface,
            focusedContentColor = C.Text,
            focusedScale = 1.1f,
            elevation = 24.dp,
            onFocusChange = { focused = it },
        ) { f ->
            Avatar(channel.avatar, size)
            Sheen(f)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(
                channel.name,
                style = T.Callout.copy(fontWeight = FontWeight.SemiBold),
                color = if (focused) C.Text else C.Text.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, false),
            )
            if (channel.verified) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(13.dp), tint = C.Text2)
            }
        }
        formatSubscribers(channel.subscribers)?.let {
            Text(it, style = T.Footnote, color = C.Text2, maxLines = 1)
        }
    }
}
