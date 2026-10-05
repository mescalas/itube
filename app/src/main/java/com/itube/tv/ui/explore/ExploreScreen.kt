package com.itube.tv.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itube.tv.AppContainer
import com.itube.tv.data.ExploreSection
import com.itube.tv.data.Video
import com.itube.tv.data.YouTube
import com.itube.tv.data.describe
import com.itube.tv.ui.LocalNav
import com.itube.tv.ui.appViewModel
import com.itube.tv.ui.components.EmptyState
import com.itube.tv.ui.components.FocusSurface
import com.itube.tv.ui.components.Loading
import com.itube.tv.ui.components.PillButton
import com.itube.tv.ui.components.VideoGrid
import com.itube.tv.ui.components.VideoMenu
import com.itube.tv.ui.components.fullWidth
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class ExploreViewModel(@Suppress("UNUSED_PARAMETER") c: AppContainer) : ViewModel() {
    val section = MutableStateFlow(ExploreSection.MUSIC)
    val content = MutableStateFlow<Load<List<Video>>>(Load.Loading)
    private val cache = HashMap<ExploreSection, List<Video>>()

    init { select(section.value) }

    fun select(s: ExploreSection, force: Boolean = false) {
        section.value = s
        val cached = cache[s]
        if (cached != null && !force) {
            content.value = Load.Ready(cached)
            return
        }
        content.value = Load.Loading
        viewModelScope.launch {
            content.value = try {
                val list = YouTube.explore(s).filter { !it.isShort }
                cache[s] = list
                if (section.value == s) Load.Ready(list) else return@launch
            } catch (e: Exception) {
                if (section.value == s) Load.Failed(e.describe()) else return@launch
            }
        }
    }
}

@Composable
fun ExploreScreen() {
    val vm = appViewModel { ExploreViewModel(it) }
    val section by vm.section.collectAsState()
    val content by vm.content.collectAsState()
    val nav = LocalNav.current
    var menu by remember { mutableStateOf<Video?>(null) }

    val videos = (content as? Load.Ready)?.value.orEmpty()
    VideoGrid(
        videos = videos,
        onClick = { i -> nav.play(videos, i) },
        onLongClick = { menu = it },
        contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 8.dp, bottom = 56.dp),
        header = {
            fullWidth("sections") {
                LazyRow(
                    modifier = Modifier.focusRestorer(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(ExploreSection.entries, key = { it.name }) { s ->
                        SectionPill(s.label, s == section) { vm.select(s) }
                    }
                }
            }
            when (val c = content) {
                Load.Loading -> fullWidth("loading") { Loading(Modifier.fillMaxWidth().padding(top = 100.dp)) }
                is Load.Failed -> fullWidth("error") {
                    EmptyState(Icons.Rounded.ErrorOutline, "Chargement impossible", c.message, Modifier.fillMaxWidth()) {
                        PillButton("Réessayer", onClick = { vm.select(section, force = true) }, primary = true)
                    }
                }
                is Load.Ready -> if (c.value.isEmpty()) fullWidth("empty") {
                    Text("Rien à afficher dans cette rubrique pour le moment.", style = T.Callout, color = C.Text2, modifier = Modifier.padding(top = 60.dp))
                }
            }
        },
    )
    menu?.let { VideoMenu(it, onDismiss = { menu = null }) }
}

@Composable
fun SectionPill(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) Color(0x33FFFFFF) else Color(0x14FFFFFF),
        contentColor = if (selected) C.Text else C.Text2,
        focusedScale = 1.07f,
        elevation = 12.dp,
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(horizontal = 18.dp)) {
            Text(text, style = T.Callout.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1)
        }
    }
}
