package com.example.xclient.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineDao {
    // Snowflake ID は数値文字列なので長さ降順 → 辞書順降順 で新着順ソート
    @Transaction
    @Query("SELECT * FROM tweets ORDER BY length(id) DESC, id DESC")
    fun observeTimeline(): Flow<List<TweetWithMedia>>

    @Query("SELECT id FROM tweets ORDER BY length(id) DESC, id DESC LIMIT 1")
    suspend fun getLatestTweetId(): String?

    @Query("SELECT COUNT(*) FROM tweets")
    suspend fun getTweetCount(): Int

    @Query("SELECT id FROM tweets WHERE id IN (:tweetIds) AND isBookmarked = 1")
    suspend fun getBookmarkedTweetIds(tweetIds: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTweets(tweets: List<TweetEntity>)

    @Query("DELETE FROM media WHERE tweetId IN (:tweetIds)")
    suspend fun deleteMediaByTweetIds(tweetIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(media: List<MediaEntity>)

    @Query("UPDATE media SET localPath = NULL WHERE localPath = :localPath")
    suspend fun clearMediaLocalPath(localPath: String)

    // 新着 :limit 件を残し、超過分を削除。LIMIT -1 OFFSET の代わりに NOT IN を使用
    @Query(
        "DELETE FROM tweets WHERE id NOT IN (" +
            "SELECT id FROM tweets ORDER BY length(id) DESC, id DESC LIMIT :limit" +
            ")"
    )
    suspend fun trimTweetsToLimit(limit: Int)

    @Query("UPDATE tweets SET isBookmarked = :bookmarked WHERE id = :tweetId")
    suspend fun updateBookmark(tweetId: String, bookmarked: Boolean)
}
