package com.itube.tv.player

import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.itube.tv.data.Video

/** What to play: a queue of videos (search results, a channel, a shelf…) and where to start in it. */
data class PlayRequest(val queue: List<Video>, val index: Int, val startPositionMs: Long = -1)

/** In-memory hand-off between screens and the player (avoids serialising lists in routes). */
class PlaybackHolder {
    var request: PlayRequest? = null
}

/** tvOS-like captions: plain white text on a soft translucent box. */
private val captionStyle = CaptionStyleCompat(
    Color.WHITE, 0x99161618.toInt(), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.TRANSPARENT, Typeface.DEFAULT,
)

@Composable
fun VideoSurface(player: Player?, modifier: Modifier = Modifier, bottomPadding: Float = 0.08f) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setShutterBackgroundColor(Color.BLACK)
                setKeepContentOnPlayerReset(true)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                keepScreenOn = true
                subtitleView?.apply {
                    setStyle(captionStyle)
                    setApplyEmbeddedStyles(false)
                    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f)
                }
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.subtitleView?.setBottomPaddingFraction(bottomPadding)
        },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}
