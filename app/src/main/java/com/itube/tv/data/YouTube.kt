package com.itube.tv.data

import android.util.LruCache
import com.itube.tv.data.remote.NewPipeDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException

/** Explore sections, backed by YouTube "kiosks". */
enum class ExploreSection(val label: String, val kioskId: String) {
    LIVE("En direct", "live"),
    MUSIC("Musique", "trending_music"),
    GAMING("Jeux vidéo", "trending_gaming"),
    MOVIES("Films & séries", "trending_movies_and_shows"),
    PODCASTS("Podcasts", "trending_podcasts_episodes"),
}

/** A page of results plus what is needed to load the next one. */
data class Paged<T>(val items: List<T>, val next: Page?, val handler: Any? = null)

data class ChannelPage(
    val channel: Channel,
    val banner: String?,
    val videos: Paged<Video>,
    val liveTab: ListLinkHandler?,
)

/** Thin, coroutine-friendly wrapper around NewPipeExtractor (no API key, no ads). */
object YouTube {
    private val service get() = ServiceList.YouTube
    private val streamCache = LruCache<String, Pair<Long, StreamInfo>>(12)

    fun init(language: String, country: String) {
        NewPipe.init(NewPipeDownloader(), Localization(language, country), ContentCountry(country))
    }

    /** Stream URLs expire after a few hours: cached details are reused for 20 minutes only. */
    suspend fun stream(id: String, fresh: Boolean = false): StreamInfo = io {
        val cached = streamCache.get(id)
        if (!fresh && cached != null && System.currentTimeMillis() - cached.first < 20 * 60_000) return@io cached.second
        StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$id").also {
            streamCache.put(id, System.currentTimeMillis() to it)
        }
    }

    suspend fun explore(section: ExploreSection): List<Video> = io {
        val kiosks = service.kioskList
        val url = kiosks.getListLinkHandlerFactoryByType(section.kioskId).fromId(section.kioskId).url
        KioskInfo.getInfo(service, url).relatedItems.videos()
    }

    suspend fun search(query: String): Paged<SearchResult> = io {
        val handler = service.searchQHFactory.fromQuery(query, listOf("all"), "")
        val info = SearchInfo.getInfo(service, handler)
        Paged(info.relatedItems.toResults(), info.nextPage, handler)
    }

    suspend fun searchMore(previous: Paged<SearchResult>): Paged<SearchResult> = io {
        val handler = previous.handler as SearchQueryHandler
        val page = SearchInfo.getMoreItems(service, handler, previous.next)
        Paged(page.items.toResults(), page.nextPage, handler)
    }

    suspend fun suggestions(query: String): List<String> = io {
        runCatching { service.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    suspend fun channel(url: String): ChannelPage = io {
        val info = ChannelInfo.getInfo(service, url)
        val channel = Channel(
            url = info.url,
            name = info.name,
            avatar = info.avatars.best(400),
            subscribers = info.subscriberCount,
            description = info.description,
            verified = info.isVerified,
        )
        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        val videos = videosTab?.let { tab ->
            val tabInfo = ChannelTabInfo.getInfo(service, tab)
            Paged(tabInfo.relatedItems.videos().map { it.withChannel(channel) }, tabInfo.nextPage, tab)
        } ?: Paged(emptyList(), null)
        ChannelPage(
            channel = channel,
            banner = info.banners.best(1920),
            videos = videos,
            liveTab = info.tabs.firstOrNull { ChannelTabs.LIVESTREAMS in it.contentFilters },
        )
    }

    suspend fun channelTab(tab: ListLinkHandler, channel: Channel): Paged<Video> = io {
        val info = ChannelTabInfo.getInfo(service, tab)
        Paged(info.relatedItems.videos().map { it.withChannel(channel) }, info.nextPage, tab)
    }

    suspend fun channelMore(previous: Paged<Video>, channel: Channel): Paged<Video> = io {
        val tab = previous.handler as ListLinkHandler
        val next = previous.next ?: return@io Paged(emptyList(), null, tab)
        val page = ChannelTabInfo.getMoreItems(service, tab, next)
        Paged(page.items.videos().map { it.withChannel(channel) }, page.nextPage, tab)
    }

    private fun Video.withChannel(c: Channel) = copy(
        channelName = channelName ?: c.name,
        channelUrl = channelUrl ?: c.url,
        channelAvatar = channelAvatar ?: c.avatar,
    )

    private fun List<InfoItem>.toResults(): List<SearchResult> = mapNotNull {
        when (it) {
            is StreamInfoItem -> it.toVideo()?.let { v -> SearchResult.V(v) }
            is ChannelInfoItem -> SearchResult.C(it.toChannel())
            else -> null // playlists are not supported yet
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}

/** User-facing explanation of an extraction failure. */
fun Throwable.describe(): String = when (this) {
    is AgeRestrictedContentException -> "Vidéo soumise à une limite d'âge : impossible de la lire sans compte."
    is GeographicRestrictionException -> "Cette vidéo n'est pas disponible dans votre pays."
    is PrivateContentException -> "Cette vidéo est privée."
    is PaidContentException -> "Cette vidéo est réservée aux membres payants."
    is ContentNotAvailableException -> message?.takeIf { it.isNotBlank() } ?: "Cette vidéo n'est pas disponible."
    is ReCaptchaException -> "YouTube limite temporairement les requêtes. Réessayez dans quelques minutes."
    is IOException -> "Connexion impossible. Vérifiez le réseau."
    else -> "YouTube a peut-être changé : mettez iTube à jour (Réglages › À propos)."
}
