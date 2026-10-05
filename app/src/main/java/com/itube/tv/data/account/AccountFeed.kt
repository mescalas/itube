package com.itube.tv.data.account

import com.itube.tv.data.Video
import com.itube.tv.data.thumbnailOf
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** A row of the signed-in home page ("Recommandé", "Musique", "Mix", …). */
data class Shelf(val title: String, val videos: List<Video>)

/** Reads the signed-in user's YouTube (TV client) and reports what they watch so recommendations follow. */
class AccountFeed(private val account: YouTubeAccount) {

    suspend fun home(): List<Shelf> {
        val shelves = ArrayList<Shelf>()
        var r = account.innertube("browse", JSONObject().put("browseId", "default"))
        repeat(3) {
            shelves += parseShelves(r)
            val next = continuation(r) ?: return shelves.merge()
            r = account.innertube("browse", JSONObject().put("continuation", next))
        }
        return shelves.merge()
    }

    /**
     * One page of the signed-in home, flattened into a single list of videos (for the endless grid),
     * plus the token of the next page (null at the end).
     */
    suspend fun homePage(continuation: String?): Pair<List<Video>, String?> {
        val r = account.innertube(
            "browse",
            if (continuation == null) JSONObject().put("browseId", "default") else JSONObject().put("continuation", continuation),
        )
        return videos(r) to (sectionContinuation(r) ?: continuation(r))
    }

    /** Next page of the vertical list of rows (not of one row's horizontal list). */
    private fun sectionContinuation(r: JSONObject): String? {
        val list = (Json.findFirst(r, "sectionListContinuation") ?: Json.findFirst(r, "sectionListRenderer")) as? JSONObject
        val conts = list?.optJSONArray("continuations") ?: return null
        for (i in 0 until conts.length()) {
            val c = conts.optJSONObject(i) ?: continue
            c.optJSONObject("nextContinuationData")
                ?.optString("continuation")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    suspend fun subscriptions(): List<Video> = flat("FEsubscriptions", pages = 3)
    suspend fun history(): List<Video> = flat("FEhistory", pages = 2)
    suspend fun watchLater(): List<Video> = flat("VLWL", pages = 2)

    private suspend fun flat(browseId: String, pages: Int): List<Video> {
        val out = LinkedHashMap<String, Video>()
        var r = account.innertube("browse", JSONObject().put("browseId", browseId))
        for (i in 0 until pages) {
            videos(r).forEach { out.putIfAbsent(it.id, it) }
            val next = continuation(r) ?: break
            r = account.innertube("browse", JSONObject().put("continuation", next))
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------ actions

    suspend fun subscribe(channelId: String, on: Boolean) {
        account.innertube(
            if (on) "subscription/subscribe" else "subscription/unsubscribe",
            JSONObject().put("channelIds", JSONArray().put(channelId)).put("params", ""),
        )
    }

    /** "J'aime" / "Je n'aime pas" (a dislike also steers the recommendations away). */
    suspend fun rate(videoId: String, like: Boolean) {
        account.innertube(if (like) "like/like" else "like/dislike", JSONObject().put("target", JSONObject().put("videoId", videoId)))
    }

    suspend fun setWatchLater(videoId: String, add: Boolean) {
        val action = if (add) JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId)
        else JSONObject().put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID").put("removedVideoId", videoId)
        account.innertube("browse/edit_playlist", JSONObject().put("playlistId", "WL").put("actions", JSONArray().put(action)))
    }

    /** One watch session reported to the account's history (what makes the recommendations learn). */
    inner class WatchReport(private val playbackUrl: String, private val watchtimeUrl: String, private val lengthSec: Double) {
        private val cpn = randomCpn()
        private var lastSec = 0.0
        private var started = false

        suspend fun update(positionSec: Double) {
            if (!started) {
                started = true
                account.ping("$playbackUrl&ver=2&cpn=$cpn&cmt=${fmt(positionSec)}&len=${fmt(lengthSec)}")
                lastSec = positionSec
                return
            }
            account.ping(
                "$watchtimeUrl&ver=2&cpn=$cpn&cmt=${fmt(positionSec)}&st=${fmt(lastSec)}&et=${fmt(positionSec)}&len=${fmt(lengthSec)}"
            )
            lastSec = positionSec
        }
    }

    suspend fun startWatch(videoId: String, lengthSec: Double): WatchReport? {
        val r = account.innertube("player", JSONObject().put("videoId", videoId))
        val tracking = r.optJSONObject("playbackTracking") ?: return null
        val playback = tracking.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl")?.takeIf { it.isNotBlank() } ?: return null
        val watchtime = tracking.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl")?.takeIf { it.isNotBlank() } ?: return null
        return WatchReport(playback, watchtime, lengthSec)
    }

    // ------------------------------------------------------------------ parsing

    /** Rows sharing a title (YouTube splits untitled suggestions into rows of three) become one. */
    private fun List<Shelf>.merge(): List<Shelf> {
        val byTitle = LinkedHashMap<String, LinkedHashMap<String, Video>>()
        for (s in this) {
            val row = byTitle.getOrPut(s.title) { LinkedHashMap() }
            s.videos.forEach { row.putIfAbsent(it.id, it) }
        }
        return byTitle.map { (title, videos) -> Shelf(title, videos.values.toList()) }.filter { it.videos.size >= 2 }
    }

    internal fun parseShelves(r: JSONObject): List<Shelf> = Json.findAll(r, "shelfRenderer").mapNotNull { shelf ->
        // Some rows come untitled (a blank header): they are suggestions.
        val title = Json.text(shelf.opt("title"))?.trim()?.takeIf { it.isNotEmpty() }
            ?: Json.findText(shelf.optJSONObject("headerRenderer"), "title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Suggestions"
        Shelf(title, videos(shelf))
    }

    /** Every video tile under [node], in order (TV tiles and web renderers alike). */
    fun videos(node: Any?): List<Video> {
        val out = LinkedHashMap<String, Video>()
        collect(node, out)
        return out.values.toList()
    }

    private fun collect(o: Any?, out: LinkedHashMap<String, Video>) {
        when (o) {
            is JSONObject -> for (k in o.keys()) {
                val v = o.get(k)
                val video = when (k) {
                    "tileRenderer" -> (v as? JSONObject)?.let(::fromTile)
                    "lockupViewModel" -> (v as? JSONObject)?.let(::fromLockup)
                    "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" -> (v as? JSONObject)?.let(::fromWeb)
                    else -> null
                }
                if (video != null) out.putIfAbsent(video.id, video)
                else if (k != "menu" && k != "onLongPressCommand") collect(v, out)
            }
            is JSONArray -> for (i in 0 until o.length()) collect(o.get(i), out)
        }
    }

    private fun fromTile(t: JSONObject): Video? {
        val id = (Json.findFirst(t.optJSONObject("onSelectCommand"), "watchEndpoint") as? JSONObject)?.optString("videoId")
            ?.takeIf { it.length == 11 } ?: return null
        val meta = t.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
        val title = Json.text(meta?.opt("title")) ?: return null
        // Lines: "Channel" / "1,2 M de vues • il y a 3 jours" (each line made of items).
        val lines = meta?.optJSONArray("lines")?.let { arr ->
            (0 until arr.length()).map { i ->
                val items = (Json.findFirst(arr.opt(i), "items") as? JSONArray)
                if (items == null) "" else (0 until items.length()).mapNotNull { j -> Json.findText(items.opt(j), "text") }.joinToString(" ")
            }.filter { it.isNotBlank() }
        }.orEmpty()
        val header = t.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
        val overlays = header?.optJSONArray("thumbnailOverlays")
        var duration = -1L
        var live = false
        var short = t.optString("style") == "TILE_STYLE_YTLR_SHORTS"
        var progress = 0
        if (overlays != null) for (i in 0 until overlays.length()) {
            val ov = overlays.optJSONObject(i) ?: continue
            ov.optJSONObject("thumbnailOverlayTimeStatusRenderer")?.let { s ->
                when (s.optString("style")) {
                    "LIVE" -> live = true
                    "SHORTS" -> short = true
                }
                duration = parseDuration(Json.text(s.opt("text"))) ?: duration
            }
            ov.optJSONObject("thumbnailOverlayResumePlaybackRenderer")?.let { progress = it.optInt("percentDurationWatched") }
        }
        val channelId = Json.findAll(t, "browseEndpoint").map { it.optString("browseId") }.firstOrNull { it.startsWith("UC") }
        return Video(
            id = id,
            title = title,
            channelName = lines.getOrNull(0),
            channelUrl = channelId?.let { "https://www.youtube.com/channel/$it" },
            thumbnail = header?.let { Json.bestThumbnail(it.opt("thumbnail")) } ?: thumbnailOf(id),
            durationSec = duration,
            uploadedText = lines.drop(1).joinToString(" · ").ifBlank { null },
            isLive = live,
            isShort = short,
        ).also { if (progress > 0) watched[id] = progress }
    }

    /** Newer "lockup" format, used by the TV client next to the tiles. */
    private fun fromLockup(l: JSONObject): Video? {
        val type = l.optString("contentType")
        if (type != "LOCKUP_CONTENT_TYPE_VIDEO" && type != "LOCKUP_CONTENT_TYPE_SHORT" && type != "LOCKUP_CONTENT_TYPE_MUSIC") return null
        val id = l.optString("contentId").takeIf { it.length == 11 }
            ?: (Json.findFirst(l.optJSONObject("rendererContext"), "watchEndpoint") as? JSONObject)?.optString("videoId")?.takeIf { it.length == 11 }
            ?: return null
        val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = Json.text(meta?.opt("title")) ?: return null
        val rows = (Json.findFirst(meta?.opt("metadata"), "metadataRows") as? JSONArray)
        val parts = ArrayList<String>()
        if (rows != null) for (i in 0 until rows.length()) {
            val ps = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
            for (j in 0 until ps.length()) Json.text(ps.optJSONObject(j)?.opt("text"))?.let { parts += it }
        }
        val image = l.optJSONObject("contentImage")
        val sources = (Json.findFirst(image, "sources") as? JSONArray)
        val thumb = sources?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.maxByOrNull { it.optInt("width") }?.optString("url") }
        var duration = -1L
        var live = false
        Json.findAll(image, "thumbnailBadgeViewModel").forEach { b ->
            if (b.optString("badgeStyle").endsWith("LIVE")) live = true
            duration = parseDuration(b.optString("text")) ?: duration
        }
        (Json.findFirst(image, "thumbnailOverlayProgressBarViewModel") as? JSONObject)?.optInt("startPercent")?.takeIf { it > 0 }?.let { watched[id] = it }
        // The channel link sits in the long-press menu ("Accéder à la chaîne").
        val channelId = Json.findAll(l, "browseEndpoint").map { it.optString("browseId") }.firstOrNull { it.startsWith("UC") }
        return Video(
            id = id,
            title = title,
            channelName = parts.getOrNull(0),
            channelUrl = channelId?.let { "https://www.youtube.com/channel/$it" },
            thumbnail = thumb ?: thumbnailOf(id),
            durationSec = duration,
            uploadedText = parts.drop(1).joinToString(" · ").ifBlank { null },
            isLive = live,
            isShort = type == "LOCKUP_CONTENT_TYPE_SHORT",
        )
    }

    private fun fromWeb(v: JSONObject): Video? {
        val id = v.optString("videoId").takeIf { it.length == 11 } ?: return null
        val channel = Json.text(v.opt("ownerText")) ?: Json.text(v.opt("shortBylineText")) ?: Json.text(v.opt("longBylineText"))
        val channelId = Json.findAll(v.opt("ownerText") ?: v.opt("shortBylineText"), "browseEndpoint")
            .map { it.optString("browseId") }.firstOrNull { it.startsWith("UC") }
        return Video(
            id = id,
            title = Json.text(v.opt("title")) ?: return null,
            channelName = channel,
            channelUrl = channelId?.let { "https://www.youtube.com/channel/$it" },
            thumbnail = Json.bestThumbnail(v.opt("thumbnail")) ?: thumbnailOf(id),
            durationSec = parseDuration(Json.text(v.opt("lengthText"))) ?: -1,
            uploadedText = listOfNotNull(Json.text(v.opt("shortViewCountText")), Json.text(v.opt("publishedTimeText"))).joinToString(" · ").ifBlank { null },
        )
    }

    /** Percent watched per video id, as shown by YouTube's red resume bar. */
    val watched = HashMap<String, Int>()

    private fun continuation(r: JSONObject): String? =
        (Json.findFirst(r, "nextContinuationData") as? JSONObject)?.optString("continuation")?.takeIf { it.isNotBlank() }
            ?: (Json.findFirst(r, "continuationCommand") as? JSONObject)?.optString("token")?.takeIf { it.isNotBlank() }

    private fun parseDuration(text: String?): Long? {
        val parts = text?.trim()?.split(':')?.map { it.toLongOrNull() ?: return null } ?: return null
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p }
    }

    private fun fmt(d: Double) = String.format(java.util.Locale.ROOT, "%.3f", d)

    private fun randomCpn(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val rnd = SecureRandom()
        return (1..16).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}
