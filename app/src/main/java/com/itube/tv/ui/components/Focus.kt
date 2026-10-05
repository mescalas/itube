package com.itube.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.itube.tv.ui.theme.C

fun FocusRequester.tryFocus(): Boolean = try {
    requestFocus()
    true
} catch (_: Exception) {
    false
}

/**
 * The base focusable element of the UI (tvOS-like): lifts and scales on focus,
 * turns white with dark content, no ripple. Handles D-pad center / Enter and long press.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    color: Color = C.Surface,
    focusedColor: Color = C.Focus,
    contentColor: Color = C.Text,
    focusedContentColor: Color = C.OnFocus,
    focusedScale: Float = 1.04f,
    elevation: Dp = 14.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    onFocusChange: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(140), label = "scale")
    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .then(if (onFocusChange != null) Modifier.onFocusChanged { onFocusChange(it.isFocused) } else Modifier)
            // Focusable before the scale layer: the bounds used for focus search and for scrolling the focused
            // element into view stay unscaled. Otherwise the TV pivot scrolling follows the growing card and the
            // whole list hops up and down on every move.
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focused && elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
            .clip(shape)
            .background(if (focused) focusedColor else color),
        contentAlignment = contentAlignment,
    ) {
        val scope = this
        CompositionLocalProvider(LocalContentColor provides if (focused) focusedContentColor else contentColor) {
            scope.content(focused)
        }
    }
}
