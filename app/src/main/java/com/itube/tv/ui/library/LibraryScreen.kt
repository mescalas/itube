package com.itube.tv.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itube.tv.AppContainer
import com.itube.tv.data.Channel
import com.itube.tv.data.Video
import com.itube.tv.ui.LocalNav
import com.itube.tv.ui.LocalShell
import com.itube.tv.ui.appViewModel
import com.itube.tv.ui.components.ActionDialog
import com.itube.tv.ui.components.ChannelCard
import com.itube.tv.ui.components.DialogAction
import com.itube.tv.ui.components.EmptyState
import com.itube.tv.ui.components.PillButton
import com.itube.tv.ui.components.VideoGrid
import com.itube.tv.ui.components.VideoMenu
import com.itube.tv.ui.components.fullWidth
import com.itube.tv.ui.explore.SectionPill
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Shelf(val label: String) { HISTORY("Historique"), LATER("À regarder plus tard"), CHANNELS("Chaînes suivies") }

class LibraryViewModel(c: AppContainer) : ViewModel() {
    private val repo = c.repository
    val history = repo.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val later = repo.watchLater.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val channels = repo.subscriptions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeHistory(id: String) = viewModelScope.launch { repo.removeFromHistory(id) }
    fun clearHistory() = viewModelScope.launch { repo.clearHistory() }
    fun removeLater(id: String) = viewModelScope.launch { repo.removeFromWatchLater(id) }
}

@Composable
fun LibraryScreen() {
    val vm = appViewModel { LibraryViewModel(it) }
    val history by vm.history.collectAsState()
    val later by vm.later.collectAsState()
    val channels by vm.channels.collectAsState()
    val nav = LocalNav.current
    val shell = LocalShell.current
    var shelf by rememberSaveable { mutableStateOf(Shelf.HISTORY) }
    var menu by remember { mutableStateOf<Video?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val pills: @Composable () -> Unit = {
        Row(Modifier.focusRestorer().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shelf.entries.forEach { s -> SectionPill(s.label, s == shelf) { shelf = s } }
            if (shelf == Shelf.HISTORY && history.isNotEmpty()) {
                Spacer(Modifier.width(20.dp))
                PillButton("Effacer l'historique", onClick = { confirmClear = true })
            }
        }
    }
    val padding = PaddingValues(start = 56.dp, end = 56.dp, top = 8.dp, bottom = 56.dp)

    when (shelf) {
        Shelf.HISTORY, Shelf.LATER -> {
            val isHistory = shelf == Shelf.HISTORY
            val videos = if (isHistory) history.map { it.toVideo() } else later.map { it.toVideo() }
            val progress = history.associate { it.videoId to it.progress }
            VideoGrid(
                videos = videos,
                onClick = { i ->
                    val start = if (isHistory) history.getOrNull(i)?.takeIf { it.progress < 0.94f }?.positionMs ?: -1 else -1
                    nav.play(videos, i, start)
                },
                progress = { progress[it.id] },
                onLongClick = { menu = it },
                contentPadding = padding,
                header = {
                    fullWidth("pills") { pills() }
                    if (videos.isEmpty()) fullWidth("empty") {
                        EmptyState(
                            if (isHistory) Icons.Rounded.History else Icons.Rounded.WatchLater,
                            if (isHistory) "Aucune vidéo regardée" else "Liste vide",
                            if (isHistory) "Les vidéos que vous regardez apparaîtront ici."
                            else "Maintenez OK sur une vidéo puis choisissez « À regarder plus tard ».",
                            Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }
        Shelf.CHANNELS -> LazyVerticalGrid(
            columns = GridCells.Adaptive(180.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            fullWidth("pills") { pills() }
            if (channels.isEmpty()) fullWidth("empty") {
                EmptyState(Icons.Rounded.Person, "Aucune chaîne suivie", "Abonnez-vous à une chaîne depuis sa page.", Modifier.fillMaxWidth())
            }
            items(channels, key = { it.channelId }) { s ->
                ChannelCard(Channel(s.url, s.name, s.avatar), onClick = { nav.channel(s.url) })
            }
        }
    }

    menu?.let { v ->
        VideoMenu(
            v,
            onDismiss = { menu = null },
            extra = listOf(
                if (shelf == Shelf.HISTORY) DialogAction("Retirer de l'historique", destructive = true) { menu = null; vm.removeHistory(v.id) }
                else DialogAction("Retirer de la liste", destructive = true) { menu = null; vm.removeLater(v.id) }
            ),
        )
    }
    if (confirmClear) {
        ActionDialog(
            "Effacer l'historique ?",
            "Les positions de lecture seront oubliées et les recommandations repartiront de zéro.",
            listOf(
                DialogAction("Effacer", destructive = true) { confirmClear = false; vm.clearHistory(); shell.toast("Historique effacé") },
                DialogAction("Annuler") { confirmClear = false },
            ),
        ) { confirmClear = false }
    }
}

