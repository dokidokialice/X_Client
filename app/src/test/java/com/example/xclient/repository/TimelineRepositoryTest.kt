package com.example.xclient.repository

import android.app.Application
import com.example.xclient.config.AppConfig
import com.example.xclient.db.MediaEntity
import com.example.xclient.db.TimelineDao
import com.example.xclient.db.TweetEntity
import com.example.xclient.db.TweetWithMedia
import com.example.xclient.network.ListTweetsResponse
import com.example.xclient.network.XApiService
import com.example.xclient.testutil.FixtureLoader
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TimelineRepositoryTest {

    private val app: Application = mockk {
        every { filesDir } returns Files.createTempDirectory("repo_test_").toFile()
    }

    @Test
    fun refresh_initialFetch_requests99AndNoSinceId() = runBlocking {
        val dao = FakeTimelineDao()
        val api = RecordingApiService(FixtureLoader.loadListTweetsResponse("initial_fetch_99.json"))
        val repository = newRepository(dao, api)

        val count = repository.refresh()

        assertEquals(99, count)
        assertEquals(99, api.lastMaxResults)
        assertNull(api.lastSinceId)
        assertEquals(99, dao.insertedTweets.size)
    }

    @Test
    fun refresh_deltaFetch_usesConfiguredMaxResults_withoutSinceIdParam() = runBlocking {
        val dao = FakeTimelineDao().apply {
            existingTweets["1900000000000000100"] = TweetEntity(
                id = "1900000000000000100",
                text = "existing",
                authorName = "ex",
                authorUsername = "ex",
                createdAt = 1L,
                permalink = "https://x.com/i/web/status/1900000000000000100",
                hasVideo = false,
                isBookmarked = false,
                syncedAt = 1L
            )
        }
        val api = RecordingApiService(FixtureLoader.loadListTweetsResponse("mixed_media_delta.json"))
        val repository = newRepository(dao, api, maxResults = 40)

        repository.refresh()

        assertNull(api.lastSinceId)
        assertEquals(40, api.lastMaxResults)
    }

    @Test
    fun refresh_noNewPosts_returnsZeroAndDoesNotWrite() = runBlocking {
        val dao = FakeTimelineDao()
        val api = RecordingApiService(FixtureLoader.loadListTweetsResponse("no_new_posts.json"))
        val repository = newRepository(dao, api)

        val count = repository.refresh()

        assertEquals(0, count)
        assertTrue(dao.insertedTweets.isEmpty())
        assertTrue(dao.insertedMedia.isEmpty())
    }

    @Test
    fun refresh_mixedMedia_mapsPhotoAndVideoAsExpected() = runBlocking {
        val dao = FakeTimelineDao()
        val api = RecordingApiService(FixtureLoader.loadListTweetsResponse("mixed_media_delta.json"))
        val repository = TimelineRepository(
            app = app,
            config = AppConfig(
                listId = "test-list",
                accessToken = "token",
                refreshToken = "",
                clientId = "",
                authRedirectUri = "xclient://oauth/callback",
                authScopes = "tweet.read users.read list.read offline.access",
                apiBaseUrl = "https://api.x.com/",
                maxResults = 50,
                offlineMode = false
            ),
            api = api,
            dao = dao,
            imageDownloader = { tweetId, mediaKey, _ ->
                File(app.filesDir, "${tweetId}_${mediaKey}.jpg").absolutePath
            }
        )

        repository.refresh()

        val photos = dao.insertedMedia.filter { it.type == "photo" }
        val videos = dao.insertedMedia.filter { it.type == "video" || it.type == "animated_gif" }

        assertEquals(2, photos.size)
        assertTrue(photos.all { !it.localPath.isNullOrBlank() })
        assertEquals(2, videos.size)
        assertTrue(videos.all { it.localPath == null })
        assertTrue(videos.all { it.remoteUrl?.startsWith("https://x.com/i/web/status/") == true })

        val tweetById = dao.insertedTweets.associateBy { it.id }
        assertTrue(tweetById.getValue("1900000000000000202").hasVideo)
        assertTrue(tweetById.getValue("1900000000000000203").hasVideo)
    }

    @Test
    fun refresh_preservesBookmarkStateFromDao() = runBlocking {
        val dao = FakeTimelineDao().apply {
            bookmarkedIds += "1900000000000000201"
        }
        val api = RecordingApiService(FixtureLoader.loadListTweetsResponse("mixed_media_delta.json"))
        val repository = newRepository(dao, api)

        repository.refresh()

        val tweet = dao.insertedTweets.first { it.id == "1900000000000000201" }
        assertTrue(tweet.isBookmarked)
    }

    private fun newRepository(
        dao: FakeTimelineDao,
        api: RecordingApiService,
        maxResults: Int = 50
    ): TimelineRepository {
        return TimelineRepository(
            app = app,
            config = AppConfig(
                listId = "test-list",
                accessToken = "token",
                refreshToken = "",
                clientId = "",
                authRedirectUri = "xclient://oauth/callback",
                authScopes = "tweet.read users.read list.read offline.access",
                apiBaseUrl = "https://api.x.com/",
                maxResults = maxResults,
                offlineMode = false
            ),
            api = api,
            dao = dao,
            imageDownloader = { _, _, _ -> null }
        )
    }

    private class RecordingApiService(
        private val response: ListTweetsResponse
    ) : XApiService {
        var lastSinceId: String? = null
        var lastMaxResults: Int? = null

        override suspend fun getListTweets(
            listId: String,
            maxResults: Int,
            sinceId: String?,
            paginationToken: String?,
            expansions: String,
            tweetFields: String,
            userFields: String,
            mediaFields: String
        ): ListTweetsResponse {
            lastMaxResults = maxResults
            lastSinceId = sinceId
            return response
        }
    }

    private class FakeTimelineDao : TimelineDao {
        val existingTweets: MutableMap<String, TweetEntity> = linkedMapOf()
        val bookmarkedIds: MutableSet<String> = linkedSetOf()
        val insertedTweets: MutableList<TweetEntity> = mutableListOf()
        val insertedMedia: MutableList<MediaEntity> = mutableListOf()

        override fun observeTimeline(): Flow<List<TweetWithMedia>> = flowOf(emptyList())

        override suspend fun getLatestTweetId(): String? {
            return existingTweets.keys.maxByOrNull { it.toLong() }
        }

        override suspend fun getTweetCount(): Int = existingTweets.size

        override suspend fun getBookmarkedTweetIds(tweetIds: List<String>): List<String> {
            return tweetIds.filter { it in bookmarkedIds }
        }

        override suspend fun upsertTweets(tweets: List<TweetEntity>) {
            insertedTweets += tweets
            tweets.forEach { existingTweets[it.id] = it }
        }

        override suspend fun deleteMediaByTweetIds(tweetIds: List<String>) {
            insertedMedia.removeAll { it.tweetId in tweetIds }
        }

        override suspend fun insertMedia(media: List<MediaEntity>) {
            insertedMedia += media
        }

        override suspend fun clearMediaLocalPath(localPath: String) {
            insertedMedia.replaceAll { media ->
                if (media.localPath == localPath) media.copy(localPath = null) else media
            }
        }

        override suspend fun trimTweetsToLimit(limit: Int) {
            val keepIds = existingTweets.keys
                .sortedWith(compareByDescending<String> { it.length }.thenByDescending { it })
                .take(limit)
                .toSet()
            existingTweets.keys.retainAll(keepIds)
            insertedTweets.retainAll { it.id in keepIds }
            insertedMedia.retainAll { it.tweetId in keepIds }
        }

        override suspend fun updateBookmark(tweetId: String, bookmarked: Boolean) {
            if (bookmarked) bookmarkedIds += tweetId else bookmarkedIds -= tweetId
        }
    }
}
