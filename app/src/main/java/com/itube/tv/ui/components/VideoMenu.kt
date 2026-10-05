package com.itube.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.itube.tv.data.Video
import com.itube.tv.ui.LocalContainer
import com.itube.tv.ui.LocalNav
import com.itube.tv.ui.LocalShell
import kotlinx.coroutines.launch

/** Long-press menu of a video card. [extra] adds screen-specific entries (e.g. "Retirer de l'historique"). */
@Composable
fun VideoMenu(
    video: Video,
    onDismiss: () -> Unit,
    extra: List<DialogAction> = emptyList(),
    toast: (String) -> Unit = LocalShell.current::toast,
) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val inWatchLater by remember(video.id) { container.repository.isInWatchLater(video.id) }.collectAsState(false)
    val actions = buildList {
        add(DialogAction("Lire") { onDismiss(); nav.play(listOf(video), 0) })
        add(
            DialogAction(if (inWatchLater) "Retirer de « À regarder plus tard »" else "À regarder plus tard") {
                onDismiss()
                scope.launch {
                    val added = container.repository.toggleWatchLater(video, inWatchLater)
                    toast(if (added) "Ajoutée à « À regarder plus tard »" else "Retirée de « À regarder plus tard »")
                }
            }
        )
        if (container.account.signedIn) {
            add(DialogAction("J'aime") {
                onDismiss()
                scope.launch {
                    runCatching { container.accountFeed.rate(video.id, like = true) }
                        .onSuccess { toast("Ajoutée aux vidéos « J'aime »") }.onFailure { toast("Action impossible") }
                }
            })
            add(DialogAction("Je n'aime pas") {
                onDismiss()
                scope.launch {
                    runCatching { container.accountFeed.rate(video.id, like = false) }
                        .onSuccess { toast("Noté : moins de vidéos de ce type") }.onFailure { toast("Action impossible") }
                }
            })
        }
        video.channelUrl?.let { url ->
            add(DialogAction("Voir la chaîne" + (video.channelName?.let { " $it" } ?: "")) { onDismiss(); nav.channel(url) })
        }
        addAll(extra)
    }
    ActionDialog(video.title, null, actions, onDismiss)
}
