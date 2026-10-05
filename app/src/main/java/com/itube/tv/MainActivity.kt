package com.itube.tv

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.itube.tv.ui.AppRoot
import com.itube.tv.ui.theme.ITubeTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as App).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) handleLink(intent)
        setContent { ITubeTheme { AppRoot() } }
        container.repository.refreshFeedIfStale()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** "Open with iTube" / "Share to iTube" from another app. */
    private fun handleLink(intent: Intent?) {
        val link = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("https?://\\S+").find(it)?.value }
            else -> null
        }
        if (!link.isNullOrBlank()) container.pendingLink.value = link
    }

    override fun onStart() {
        super.onStart()
        container.player.onForeground()
        container.updater.checkIfDue()
        container.repository.refreshFeedIfStale()
    }

    override fun onStop() {
        super.onStop()
        container.player.onBackground()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) container.player.stop()
    }
}
