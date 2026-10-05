package com.itube.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T

/** A titled horizontal row of cards inside a LazyColumn (Apple TV "shelf"). */
fun LazyListScope.shelf(
    title: String,
    key: String,
    hint: String? = null,
    start: Dp = 56.dp,
    content: LazyListScope.() -> Unit,
) {
    item(key) {
        var rowFocused by remember { mutableStateOf(false) }
        Column {
            Row(Modifier.padding(start = start, bottom = 2.dp)) {
                Text(title, style = T.Title3, modifier = Modifier.alignByBaseline())
                if (hint != null) {
                    // Shown only while the row has focus, to keep the shelf titles clean.
                    val alpha by animateFloatAsState(if (rowFocused) 1f else 0f, tween(200), label = "hint")
                    Spacer(Modifier.width(14.dp))
                    Text(hint, style = T.Footnote, color = C.Text3, modifier = Modifier.alignByBaseline().graphicsLayer { this.alpha = alpha })
                }
            }
            LazyRow(
                modifier = Modifier.onFocusChanged { rowFocused = it.hasFocus }.focusRestorer(),
                contentPadding = PaddingValues(start = start, end = start, top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                content = content,
            )
        }
    }
}
