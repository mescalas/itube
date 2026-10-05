package com.itube.tv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    suspend fun list(): List<SubscriptionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE channelId = :id)")
    fun isSubscribed(id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: SubscriptionEntity)

    @Query("UPDATE subscriptions SET avatar = :avatar WHERE channelId = :id AND (avatar IS NULL OR avatar = '')")
    suspend fun fillAvatar(id: String, avatar: String)

    @Query("DELETE FROM subscriptions WHERE channelId = :id")
    suspend fun delete(id: String)
}

@Dao
interface FeedDao {
    @Query("SELECT * FROM feed WHERE (:shorts OR isShort = 0) ORDER BY publishedAt DESC LIMIT :limit")
    fun latest(shorts: Boolean, limit: Int): Flow<List<FeedEntity>>

    @Upsert
    suspend fun upsert(items: List<FeedEntity>)

    @Query("DELETE FROM feed WHERE channelId = :channelId")
    suspend fun deleteChannel(channelId: String)

    @Query("DELETE FROM feed WHERE channelId NOT IN (SELECT channelId FROM subscriptions)")
    suspend fun deleteOrphans()

    @Transaction
    suspend fun replaceChannel(channelId: String, items: List<FeedEntity>) {
        deleteChannel(channelId)
        upsert(items)
    }
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY watchedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<HistoryEntity>>

    /** Started but not finished, most recent first. */
    @Query(
        "SELECT * FROM history WHERE positionMs > 20000 AND durationSec > 0 " +
            "AND positionMs < durationSec * 1000 * 0.94 ORDER BY watchedAt DESC LIMIT :limit"
    )
    fun inProgress(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE videoId = :id")
    suspend fun get(id: String): HistoryEntity?

    @Query("SELECT videoId FROM history")
    suspend fun ids(): List<String>

    @Upsert
    suspend fun upsert(h: HistoryEntity)

    @Query("DELETE FROM history WHERE videoId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface WatchLaterDao {
    @Query("SELECT * FROM watch_later ORDER BY addedAt DESC")
    fun all(): Flow<List<WatchLaterEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watch_later WHERE videoId = :id)")
    fun contains(id: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(w: WatchLaterEntity)

    @Query("DELETE FROM watch_later WHERE videoId = :id")
    suspend fun delete(id: String)
}
