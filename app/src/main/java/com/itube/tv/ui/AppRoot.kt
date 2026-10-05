package com.itube.tv.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itube.tv.App
import com.itube.tv.AppContainer
import com.itube.tv.data.Video
import com.itube.tv.data.thumbnailOf
import com.itube.tv.data.videoIdOf
import com.itube.tv.player.PlayRequest
import com.itube.tv.ui.channel.ChannelScreen
import com.itube.tv.ui.player.PlayerScreen
import com.itube.tv.ui.theme.C

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }
val LocalNav = staticCompositionLocalOf<AppNav> { error("no nav") }

class AppNav(private val nav: NavController, private val container: AppContainer) {
    /** Set when leaving a tab for a full-screen destination, so the tab restores its focus on return. */
    var restoreFocus = false

    fun play(queue: List<Video>, index: Int, startPositionMs: Long = -1) {
        if (queue.isEmpty()) return
        container.playback.request = PlayRequest(queue, index.coerceIn(0, queue.size - 1), startPositionMs)
        restoreFocus = true
        if (nav.currentDestination?.route == "player") {
            // Already in the player (e.g. a related video): replace it.
            nav.navigate("player") { popUpTo("player") { inclusive = true } }
        } else nav.navigate("player")
    }

    fun channel(url: String) {
        restoreFocus = true
        nav.navigate("channel?url=" + Uri.encode(url))
    }

    fun back() { nav.popBackStack() }

    /** Opens a YouTube link shared with the app: a video plays, a channel opens. */
    fun open(link: String) {
        val id = videoIdOf(link)
        when {
            id != null -> play(listOf(Video(id = id, title = "", thumbnail = thumbnailOf(id))), 0)
            Regex("youtube\\.com/(@|channel/|c/|user/)").containsMatchIn(link) -> channel(link)
        }
    }
}

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as App).container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as App).container }
    val navController = rememberNavController()
    val appNav = remember(navController) { AppNav(navController, container) }
    val pendingLink by container.pendingLink.collectAsState()

    CompositionLocalProvider(LocalContainer provides container, LocalNav provides appNav) {
        NavHost(
            navController = navController,
            startDestination = "main",
            modifier = Modifier.fillMaxSize().background(C.Background),
            enterTransition = { fadeIn(tween(160)) },
            exitTransition = { fadeOut(tween(110)) },
            popEnterTransition = { fadeIn(tween(160)) },
            popExitTransition = { fadeOut(tween(110)) },
        ) {
            composable("main") { MainShell() }
            composable(
                "player",
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popExitTransition = { ExitTransition.None },
            ) { PlayerScreen() }
            composable("channel?url={url}", arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" })) {
                ChannelScreen(it.arguments?.getString("url").orEmpty())
            }
        }
    }

    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        container.pendingLink.value = null
        appNav.open(link)
    }
}
