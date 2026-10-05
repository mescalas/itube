package com.itube.tv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.itube.tv.data.Video
import com.itube.tv.data.thumbnailOf

/** A followed channel (local subscriptions: no Google account needed). */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val url: String,
    val name: String,
    val avatar: String?,
    val addedAt: Long,
)

/** Latest uploads of followed channels, read from their RSS feeds. */
@Entity(tableName = "feed", indices = [Index("channelId"), Index("publishedAt")])
data class FeedEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val publishedAt: Long,
    val views: Long,
    val isShort: Boolean,
) {
    fun toVideo(avatar: String?) = Video(
        id = videoId,
        title = title,
        channelName = channelName,
        channelUrl = "https://www.youtube.com/channel/$channelId",
        channelAvatar = avatar,
        thumbnail = thumbnailOf(videoId),
        views = views,
        uploadedAt = publishedAt,
        isShort = isShort,
    )
}

@Entity(tableName = "history", indices = [Index("watchedAt")])
data class HistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelName: String?,
    val channelUrl: String?,
    val thumbnail: String?,
    val durationSec: Long,
    val positionMs: Long,
    val watchedAt: Long,
) {
    val progress: Float get() = if (durationSec > 0) (positionMs / 1000f / durationSec).coerceIn(0f, 1f) else 0f

    fun toVideo() = Video(
        id = videoId, title = title, channelName = channelName, channelUrl = channelUrl,
        thumbnail = thumbnail ?: thumbnailOf(videoId), durationSec = durationSec,
    )
}

@Entity(tableName = "watch_later", indices = [Index("addedAt")])
data class WatchLaterEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelName: String?,
    val channelUrl: String?,
    val thumbnail: String?,
    val durationSec: Long,
    val addedAt: Long,
) {
    fun toVideo() = Video(
        id = videoId, title = title, channelName = channelName, channelUrl = channelUrl,
        thumbnail = thumbnail ?: thumbnailOf(videoId), durationSec = durationSec,
    )
}
