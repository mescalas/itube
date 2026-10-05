package com.itube.tv.data

import android.util.Xml
import com.itube.tv.data.db.AppDatabase
import com.itube.tv.data.db.FeedEntity
import com.itube.tv.data.db.HistoryEntity
import com.itube.tv.data.db.SubscriptionEntity
import com.itube.tv.data.db.WatchLaterEntity
import com.itube.tv.data.account.AccountFeed
import com.itube.tv.data.account.YouTubeAccount
import com.itube.tv.data.remote.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.time.OffsetDateTime

sealed interface FeedState {
    data object Idle : FeedState
    data class Refreshing(val done: Int, val total: Int) : FeedState
    data class Failed(val message: String) : FeedState
}

class Repository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val account: YouTubeAccount,
    private val accountFeed: AccountFeed,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val feedState = MutableStateFlow<FeedState>(FeedState.Idle)
    val feedRefresh: StateFlow<FeedState> = feedState.asStateFlow()
    private var refreshJob: Job? = null
    private var lastRefresh = 0L

    // ------------------------------------------------------------------ subscriptions

    val subscriptions: Flow<List<SubscriptionEntity>> = db.subscriptions().all()

    fun isSubscribed(channelUrl: String): Flow<Boolean> = db.subscriptions().isSubscribed(channelIdOf(channelUrl))

    suspend fun subscribe(channel: Channel) {
        db.subscriptions().insert(
            SubscriptionEntity(channel.id, channel.url, channel.name, channel.avatar, System.currentTimeMillis())
        )
        scope.launch { runCatching { refreshChannel(channel.id) } }
        syncToAccount { accountFeed.subscribe(channel.id, true) }
    }

    /** Mirrors a change to the signed-in YouTube account (best effort: the local change always stands). */
    private fun syncToAccount(action: suspend () -> Unit) {
        if (account.signedIn) scope.launch { runCatching { action() } }
    }

    suspend fun unsubscribe(channelUrl: String) {
        val id = channelIdOf(channelUrl)
        db.subscriptions().delete(id)
        db.feed().deleteChannel(id)
        syncToAccount { accountFeed.subscribe(id, false) }
    }

    /** Latest uploads of all followed channels (avatars joined in), shorts hidden unless enabled. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun feed(limit: Int = 200): Flow<List<Video>> =
        settings.flow.map { it.hideShorts }.distinctUntilChanged().flatMapLatest { hide ->
            combine(db.feed().latest(!hide, limit), subscriptions) { items, subs ->
                val avatars = subs.associate { it.channelId to it.avatar }
                items.map { it.toVideo(avatars[it.channelId]) }
            }
        }

    fun refreshFeedIfStale() {
        if (System.currentTimeMillis() - lastRefresh > 20 * 60_000) refreshFeed()
    }

    fun refreshFeed() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            val subs = db.subscriptions().list()
            if (subs.isEmpty()) return@launch
            lastRefresh = System.currentTimeMillis()
            var done = 0
            var failures = 0
            feedState.value = FeedState.Refreshing(0, subs.size)
            val gate = Semaphore(6)
            subs.map { s ->
                async {
                    gate.withPermit {
                        if (runCatching { refreshChannel(s.channelId) }.isFailure) failures++
                        done++
                        feedState.value = FeedState.Refreshing(done, subs.size)
                    }
                }
            }.awaitAll()
            db.feed().deleteOrphans()
            feedState.value = if (failures == subs.size) FeedState.Failed("Impossible d'actualiser les abonnements.") else FeedState.Idle
        }
    }

    private suspend fun refreshChannel(channelId: String) {
        val items = Http.get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId").use { resp ->
            parseFeed(resp.body!!.byteStream(), channelId)
        }
        if (items.isNotEmpty()) db.feed().replaceChannel(channelId, items)
    }

    /** Parses a channel's Atom feed (15 latest uploads, shorts recognisable by their link). */
    private fun parseFeed(input: java.io.InputStream, channelId: String): List<FeedEntity> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val out = ArrayList<FeedEntity>()
        var channelName = ""
        var inEntry = false
        var id = ""
        var title = ""
        var published = 0L
        var views = -1L
        var short = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> {
                        inEntry = true; id = ""; title = ""; published = 0; views = -1; short = false
                    }
                    "yt:videoId" -> if (inEntry) id = parser.nextText()
                    "title" -> if (inEntry) title = parser.nextText() else if (channelName.isEmpty()) channelName = parser.nextText()
                    "name" -> if (inEntry && channelName.isEmpty()) channelName = parser.nextText()
                    "link" -> if (inEntry) short = parser.getAttributeValue(null, "href")?.contains("/shorts/") == true
                    "published" -> if (inEntry) published = runCatching {
                        OffsetDateTime.parse(parser.nextText()).toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                    "media:statistics" -> views = parser.getAttributeValue(null, "views")?.toLongOrNull() ?: -1
                }
                XmlPullParser.END_TAG -> if (parser.name == "entry") {
                    inEntry = false
                    if (id.isNotEmpty()) out += FeedEntity(id, channelId, channelName, title, published, views, short)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ history

    fun continueWatching(limit: Int = 20): Flow<List<HistoryEntity>> = db.history().inProgress(limit)

    fun history(limit: Int = 200): Flow<List<HistoryEntity>> = db.history().recent(limit)

    suspend fun historyOf(id: String): HistoryEntity? = db.history().get(id)

    suspend fun saveProgress(video: Video, positionMs: Long, durationSec: Long) {
        db.history().upsert(
            HistoryEntity(
                videoId = video.id,
                title = video.title,
                channelName = video.channelName,
                channelUrl = video.channelUrl,
                thumbnail = video.thumbnail,
                durationSec = if (durationSec > 0) durationSec else video.durationSec,
                positionMs = positionMs,
                watchedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun removeFromHistory(id: String) = db.history().delete(id)

    suspend fun clearHistory() = db.history().clear()

    // ------------------------------------------------------------------ watch later

    val watchLater: Flow<List<WatchLaterEntity>> = db.watchLater().all()

    fun isInWatchLater(id: String): Flow<Boolean> = db.watchLater().contains(id)

    /** Returns true when the video was added, false when it was removed. */
    suspend fun toggleWatchLater(v: Video, present: Boolean): Boolean {
        syncToAccount { accountFeed.setWatchLater(v.id, !present) }
        if (present) {
            db.watchLater().delete(v.id)
            return false
        }
        db.watchLater().upsert(
            WatchLaterEntity(v.id, v.title, v.channelName, v.channelUrl, v.thumbnail, v.durationSec, System.currentTimeMillis())
        )
        return true
    }

    suspend fun removeFromWatchLater(id: String) {
        db.watchLater().delete(id)
        syncToAccount { accountFeed.setWatchLater(id, false) }
    }

    // ------------------------------------------------------------------ recommendations

    /**
     * "Recommended for you" without an account: videos related to the last ones watched,
     * interleaved, minus what was already seen.
     */
    suspend fun recommendations(): List<Video> = withContext(Dispatchers.IO) {
        val recent = db.history().recent(4).first()
        if (recent.isEmpty()) return@withContext emptyList()
        val seen = db.history().ids().toHashSet()
        val lists = recent.take(3).map { h ->
            async { runCatching { YouTube.stream(h.videoId).relatedItems.videos() }.getOrDefault(emptyList()) }
        }.awaitAll()
        val out = LinkedHashMap<String, Video>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) {
            val v = l.getOrNull(i) ?: continue
            if (v.id !in seen && !v.isShort && !v.isLive) out.putIfAbsent(v.id, v)
        }
        out.values.take(30)
    }
}
