package com.itube.tv.data

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** A video as shown in lists and handed to the player. */
data class Video(
    val id: String,
    val title: String,
    val channelName: String? = null,
    val channelUrl: String? = null,
    val channelAvatar: String? = null,
    val thumbnail: String? = null,
    val durationSec: Long = -1,
    val views: Long = -1,
    /** "il y a 3 jours" as given by YouTube, used when [uploadedAt] is unknown. */
    val uploadedText: String? = null,
    val uploadedAt: Long = 0,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
}

data class Channel(
    val url: String,
    val name: String,
    val avatar: String? = null,
    val subscribers: Long = -1,
    val videoCount: Long = -1,
    val description: String? = null,
    val verified: Boolean = false,
) {
    val id: String get() = channelIdOf(url)
}

/** One entry of a search result. */
sealed interface SearchResult {
    data class V(val video: Video) : SearchResult
    data class C(val channel: Channel) : SearchResult
}

private val VIDEO_ID = Regex("(?:v=|/shorts/|/live/|youtu\\.be/|/embed/)([A-Za-z0-9_-]{11})")

fun videoIdOf(url: String): String? = VIDEO_ID.find(url)?.groupValues?.get(1)

fun channelIdOf(url: String): String = url.trimEnd('/').substringAfterLast('/')

/** Thumbnails served by YouTube for any video id (hq is 4:3 letterboxed, cropped to 16:9 on display). */
fun thumbnailOf(id: String, high: Boolean = false): String =
    if (high) "https://i.ytimg.com/vi/$id/maxresdefault.jpg" else "https://i.ytimg.com/vi/$id/hqdefault.jpg"

/** Highest resolution image whose width stays reasonable for a TV card. */
fun List<Image>.best(maxWidth: Int = 1280): String? {
    if (isEmpty()) return null
    val known = filter { it.width > 0 }
    if (known.isEmpty()) return last().url
    return (known.filter { it.width <= maxWidth }.maxByOrNull { it.width } ?: known.minByOrNull { it.width })?.url
}

fun StreamInfoItem.toVideo(): Video? {
    val id = videoIdOf(url) ?: return null
    return Video(
        id = id,
        title = name,
        channelName = uploaderName,
        channelUrl = uploaderUrl,
        channelAvatar = uploaderAvatars.best(200),
        thumbnail = thumbnails.best() ?: thumbnailOf(id),
        durationSec = duration,
        views = viewCount,
        uploadedText = textualUploadDate,
        uploadedAt = uploadDate?.instant?.toEpochMilli() ?: 0,
        isLive = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM,
        isShort = isShortFormContent || url.contains("/shorts/"),
    )
}

fun ChannelInfoItem.toChannel() = Channel(
    url = url,
    name = name,
    avatar = thumbnails.best(400),
    subscribers = subscriberCount,
    videoCount = streamCount,
    description = description,
    verified = isVerified,
)

fun List<InfoItem>.videos(): List<Video> = mapNotNull { (it as? StreamInfoItem)?.toVideo() }
