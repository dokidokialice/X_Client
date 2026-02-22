package com.example.xclient.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineDao {
    @Transaction
    @Query("SELECT * FROM tweets ORDER BY createdAt DESC")
    fun observeTimeline(): Flow<List<TweetWithMedia>>

    @Query("SELECT id FROM tweets ORDER BY CAST(id AS INTEGER) DESC LIMIT 1")
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

    @Query("UPDATE tweets SET isBookmarked = :bookmarked WHERE id = :tweetId")
    suspend fun updateBookmark(tweetId: String, bookmarked: Boolean)
}
