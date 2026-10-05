package com.itube.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        color = if (primary) Color(0x4DFFFFFF) else Color(0x2EFFFFFF),
        focusedScale = 1.06f,
        onFocusChange = onFocusChange,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = T.Headline, maxLines = 1)
        }
    }
}

@Composable
fun IconPill(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, tint: Color? = null) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = C.Surface2,
        focusedScale = 1.1f,
        contentAlignment = Alignment.Center,
    ) { focused ->
        Icon(icon, null, Modifier.size(size * 0.45f), tint = if (!focused && tint != null) tint else LocalContentColor.current)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = T.Title3, color = C.Text, modifier = modifier.padding(bottom = 12.dp))
}

@Composable
fun Loading(modifier: Modifier = Modifier, message: String? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = C.Text, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(message, style = T.Callout, color = C.Text2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(C.Surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(34.dp), tint = C.Text2)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = T.Title3, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(message, style = T.Subhead, color = C.Text2, textAlign = TextAlign.Center, modifier = Modifier.width(420.dp))
        }
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            action()
        }
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = C.Text, track: Color = C.Separator, height: Dp = 4.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(height)).background(track)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(height))
                .background(color)
        )
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = C.Surface3, textColor: Color = C.Text) {
    Text(
        text,
        style = T.Caption.copy(fontWeight = FontWeight.SemiBold),
        color = textColor,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Outlined pill for a detail pulled out of a title ("4K", "HDR", "VOSTFR"), Apple TV style. */
@Composable
fun InfoPill(text: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current.copy(alpha = 0.7f)) {
    Text(
        text,
        style = T.Caption.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.3.sp),
        color = color,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, color.copy(alpha = color.alpha * 0.75f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun InfoPills(items: List<String>, modifier: Modifier = Modifier, color: Color = LocalContentColor.current.copy(alpha = 0.7f)) {
    if (items.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { InfoPill(it, color = color) }
    }
}

@Composable
fun SideListItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: String? = null,
    onFocused: (() -> Unit)? = null,
    tag: String? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0x2EFFFFFF) else Color.Transparent,
        contentColor = if (selected) C.Text else C.Text2,
        focusedScale = 1.03f,
        elevation = 8.dp,
        onFocusChange = { if (it) onFocused?.invoke() },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            if (tag != null) {
                Text(tag, style = T.Caption.copy(fontWeight = FontWeight.Bold), color = LocalContentColor.current.copy(alpha = 0.5f))
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text,
                style = T.Callout.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) Text(trailing, style = T.Caption, color = LocalContentColor.current.copy(alpha = 0.6f))
        }
    }
}

val ScreenPadding = PaddingValues(horizontal = 48.dp)
