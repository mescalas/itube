package com.itube.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import kotlinx.coroutines.delay

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(600.dp).clip(RoundedCornerShape(24.dp)).background(C.Surface).padding(28.dp)
        ) {
            Text(title, style = T.Title3)
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(hint, style = T.Subhead, color = C.Text2)
            }
            Spacer(Modifier.height(18.dp))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = T.Title3.copy(color = C.Text),
                cursorBrush = SolidColor(C.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(value.text.trim()) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(RoundedCornerShape(12.dp))
                    .background(C.Surface2)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            )
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                PillButton("Annuler", onClick = onDismiss)
                PillButton("Valider", onClick = { onDone(value.text.trim()) }, primary = true)
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(120)
        focus.tryFocus()
        keyboard?.show()
    }
}

data class DialogAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
fun ActionDialog(
    title: String,
    message: String? = null,
    actions: List<DialogAction>,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // Opened by a long press on OK: that press is still going on. Its repeats and its release must not
    // click the first action, so OK is ignored until a fresh press starts.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(460.dp)
                .onPreviewKeyEvent { ev ->
                    if (armed || !ev.isConfirmKey()) return@onPreviewKeyEvent false
                    if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) {
                        armed = true
                        false
                    } else true
                }
                .clip(RoundedCornerShape(24.dp))
                .background(C.Surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = T.Title3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(message, style = T.Subhead, color = C.Text2)
            }
            Spacer(Modifier.height(18.dp))
            actions.forEachIndexed { i, a ->
                FocusSurface(
                    onClick = { a.onClick() },
                    modifier = Modifier.fillMaxWidth().height(50.dp).then(if (i == 0) Modifier.focusRequester(focus) else Modifier),
                    shape = RoundedCornerShape(14.dp),
                    color = C.Surface2,
                    contentColor = if (a.destructive) C.Red else C.Text,
                    focusedContentColor = if (a.destructive) Color(0xFFD70015) else C.OnFocus,
                    focusedScale = 1.03f,
                    contentAlignment = Alignment.Center,
                ) {
                    Text(a.label, style = T.Headline)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        focus.tryFocus()
    }
}

private fun KeyEvent.isConfirmKey(): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter || key == Key.ButtonA
