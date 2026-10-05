package com.itube.tv.ui.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itube.tv.AppContainer
import com.itube.tv.data.Video
import com.itube.tv.data.channelIdOf
import com.itube.tv.data.db.SubscriptionEntity
import com.itube.tv.ui.LocalNav
import com.itube.tv.ui.LocalShell
import com.itube.tv.ui.Tab
import com.itube.tv.ui.appViewModel
import com.itube.tv.ui.components.ActionDialog
import com.itube.tv.ui.components.Avatar
import com.itube.tv.ui.components.DialogAction
import com.itube.tv.ui.components.EmptyState
import com.itube.tv.ui.components.FocusSurface
import com.itube.tv.ui.components.PillButton
import com.itube.tv.ui.components.VideoGrid
import com.itube.tv.ui.components.VideoMenu
import com.itube.tv.ui.components.fullWidth
import com.itube.tv.ui.theme.C
import com.itube.tv.ui.theme.T
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import com.itube.tv.data.account.SignInState
import com.itube.tv.ui.explore.Load
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SubscriptionsViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository
    val signedIn = c.account.state.map { it is SignInState.SignedIn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.account.signedIn)
    /** The signed-in account's subscription feed (null = not loaded / signed out). */
    val accountFeed = MutableStateFlow<Load<List<Video>>?>(null)

    init {
        viewModelScope.launch { signedIn.collect { if (it) loadAccount() else accountFeed.value = null } }
    }

    fun loadAccount() {
        if (accountFeed.value == null) accountFeed.value = Load.Loading
        viewModelScope.launch {
            accountFeed.value = try {
                val hide = c.settings.value.hideShorts
                Load.Ready(c.accountFeed.subscriptions().filter { !(hide && it.isShort) })
            } catch (e: Exception) {
                Load.Failed(e.message ?: "erreur réseau")
            }
        }
    }
    val subscriptions = repo.subscriptions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val feed = repo.feed(300).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val refreshing = repo.feedRefresh

    fun refresh() = repo.refreshFeed()

    fun unsubscribe(s: SubscriptionEntity) {
        viewModelScope.launch { repo.unsubscribe(s.url) }
    }
}

@Composable
fun SubscriptionsScreen() {
    val vm = appViewModel { SubscriptionsViewModel(it) }
    val subs by vm.subscriptions.collectAsState()
    val feed by vm.feed.collectAsState()
    val nav = LocalNav.current
    val shell = LocalShell.current
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<Video?>(null) }
    var channelMenu by remember { mutableStateOf<SubscriptionEntity?>(null) }

    val signedIn by vm.signedIn.collectAsState()
    val account by vm.accountFeed.collectAsState()
    if (signedIn) {
        AccountSubscriptions(account, onRefresh = { vm.loadAccount(); shell.toast("Actualisation…") }, onMenu = { menu = it })
        menu?.let { VideoMenu(it, onDismiss = { menu = null }) }
        return
    }
    val list = subs ?: return
    if (list.isEmpty()) {
        EmptyState(
            Icons.Rounded.Subscriptions,
            "Aucun abonnement",
            "Abonnez-vous à des chaînes depuis la recherche : leurs nouvelles vidéos s'afficheront ici. " +
                "Les abonnements restent sur ce téléviseur, sans compte Google.",
            Modifier.fillMaxSize(),
        ) {
            PillButton("Rechercher une chaîne", onClick = { shell.openTab(Tab.SEARCH) }, icon = Icons.Rounded.Search, primary = true)
        }
        return
    }
    val videos = if (filter == null) feed else feed.filter { channelIdOf(it.channelUrl.orEmpty()) == filter }

    VideoGrid(
        videos = videos,
        onClick = { i -> nav.play(videos, i) },
        showChannel = filter == null,
        onLongClick = { menu = it },
        contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 8.dp, bottom = 56.dp),
        header = {
            fullWidth("channels") {
                LazyRow(
                    modifier = Modifier.focusRestorer(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item("all") {
                        ChannelChip(null, "Toutes", filter == null, onClick = { filter = null })
                    }
                    items(list, key = { it.channelId }) { s ->
                        ChannelChip(s.avatar, s.name, filter == s.channelId, onClick = {
                            filter = if (filter == s.channelId) null else s.channelId
                        }, onLongClick = { channelMenu = s })
                    }
                    item("refresh") {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
                            FocusSurface(
                                onClick = { vm.refresh(); shell.toast("Actualisation des abonnements…") },
                                modifier = Modifier.size(64.dp),
                                shape = CircleShape,
                                color = C.Surface,
                                focusedScale = 1.1f,
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Refresh, null, Modifier.size(26.dp)) }
                            Spacer(Modifier.height(8.dp))
                            Text("Actualiser", style = T.Caption, color = C.Text2)
                        }
                    }
                }
            }
            if (videos.isEmpty()) fullWidth("empty") {
                Box(Modifier.padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    Text("Aucune vidéo récente pour l'instant.", style = T.Callout, color = C.Text2, textAlign = TextAlign.Center)
                }
            }
        },
    )

    menu?.let { VideoMenu(it, onDismiss = { menu = null }) }
    channelMenu?.let { s ->
        ActionDialog(
            s.name,
            null,
            listOf(
                DialogAction("Voir la chaîne") { channelMenu = null; nav.channel(s.url) },
                DialogAction("Se désabonner", destructive = true) {
                    channelMenu = null
                    if (filter == s.channelId) filter = null
                    vm.unsubscribe(s)
                    shell.toast("Désabonné de ${s.name}")
                },
            ),
        ) { channelMenu = null }
    }
}

@Composable
private fun ChannelChip(avatar: String?, name: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = if (selected) Color(0x33FFFFFF) else C.Surface,
            focusedColor = C.Surface,
            focusedContentColor = C.Text,
            focusedScale = 1.12f,
            contentAlignment = Alignment.Center,
        ) {
            if (avatar == null) Icon(Icons.Rounded.GridView, null, Modifier.size(26.dp))
            else Avatar(avatar, 64.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            style = T.Caption.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = if (selected) C.Text else C.Text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AccountSubscriptions(state: Load<List<Video>>?, onRefresh: () -> Unit, onMenu: (Video) -> Unit) {
    val nav = LocalNav.current
    val videos = (state as? Load.Ready)?.value.orEmpty()
    VideoGrid(
        videos = videos,
        onClick = { i -> nav.play(videos, i) },
        onLongClick = onMenu,
        contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 8.dp, bottom = 56.dp),
        header = {
            fullWidth("title") {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Abonnements de votre compte YouTube", style = T.Title3, modifier = Modifier.weight(1f))
                    PillButton("Actualiser", onClick = onRefresh, icon = Icons.Rounded.Refresh)
                }
            }
            when (state) {
                null, Load.Loading -> fullWidth("loading") { com.itube.tv.ui.components.Loading(Modifier.fillMaxSize().padding(top = 80.dp)) }
                is Load.Failed -> fullWidth("error") {
                    Text("Chargement impossible : ${state.message}", style = T.Callout, color = C.Text2, modifier = Modifier.padding(top = 60.dp))
                }
                is Load.Ready -> if (state.value.isEmpty()) fullWidth("empty") {
                    Text("Aucune vidéo récente.", style = T.Callout, color = C.Text2, modifier = Modifier.padding(top = 60.dp))
                }
            }
        },
    )
}
