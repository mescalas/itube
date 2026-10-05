package com.itube.tv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.itube.tv.data.Repository
import com.itube.tv.data.SettingsStore
import com.itube.tv.data.YouTube
import com.itube.tv.data.db.AppDatabase
import com.itube.tv.data.remote.Http
import com.itube.tv.player.PlaybackHolder
import com.itube.tv.player.PlayerManager
import com.itube.tv.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Path.Companion.toOkioPath

class AppContainer(app: Application) {
    val settings = SettingsStore(app)
    val db = AppDatabase.create(app)
    val repository = Repository(db, settings)
    val player = PlayerManager(app, settings)
    val playback = PlaybackHolder()
    val updater = Updater(app, settings)

    /** YouTube link opened with iTube from another app, waiting for the UI to handle it. */
    val pendingLink = MutableStateFlow<String?>(null)
}

class App : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val region = container.settings.value.region
        YouTube.init(region.language, region.country)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .crossfade(140)
            .allowRgb565(true)
            .build()
}
