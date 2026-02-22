package com.example.xclient.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: TimelineDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.timelineDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun latestIdAndCount_workAsExpected() = runBlocking {
        dao.upsertTweets(
            listOf(
                tweet(id = "100"),
                tweet(id = "200"),
                tweet(id = "150")
            )
        )

        assertEquals(3, dao.getTweetCount())
        assertEquals("200", dao.getLatestTweetId())
    }

    @Test
    fun updateBookmark_persistsFlag() = runBlocking {
        dao.upsertTweets(listOf(tweet(id = "100", isBookmarked = false)))

        dao.updateBookmark("100", true)

        val row = dao.observeTimeline().first().first()
        assertTrue(row.tweet.isBookmarked)
    }

    @Test
    fun observeTimeline_returnsCreatedAtDescWithMediaRelation() = runBlocking {
        dao.upsertTweets(
            listOf(
                tweet(id = "100", createdAt = 1_000L),
                tweet(id = "200", createdAt = 2_000L)
            )
        )
        dao.insertMedia(
            listOf(
                MediaEntity(tweetId = "100", type = "photo", localPath = "/tmp/p1.jpg", remoteUrl = null),
                MediaEntity(tweetId = "100", type = "video", localPath = null, remoteUrl = "https://x.com/i/web/status/100")
            )
        )

        val rows = dao.observeTimeline().first()

        assertEquals(listOf("200", "100"), rows.map { it.tweet.id })
        assertEquals(2, rows.last().media.size)
    }

    private fun tweet(
        id: String,
        createdAt: Long = 0L,
        isBookmarked: Boolean = false
    ): TweetEntity {
        return TweetEntity(
            id = id,
            text = "text-$id",
            authorName = "author",
            authorUsername = "user",
            createdAt = createdAt,
            permalink = "https://x.com/i/web/status/$id",
            hasVideo = false,
            isBookmarked = isBookmarked,
            syncedAt = 0L
        )
    }
}
