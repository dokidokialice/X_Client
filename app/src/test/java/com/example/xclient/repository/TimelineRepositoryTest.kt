package com.example.xclient.repository

import android.app.Application
import android.content.SharedPreferences
import android.content.res.AssetManager
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class TimelineRepositoryTest {

    private val assetManager: AssetManager = mockk()
    private val sharedPreferences = InMemorySharedPreferences()
    private val app: Application = mockk {
        every { filesDir } returns Files.createTempDirectory("repo_test_").toFile()
        every { assets } returns assetManager
        every { getSharedPreferences(any(), any()) } returns sharedPreferences
    }

    @Test
    fun refresh_initialFetch_requests99AndNoSinceId() = runBlocking {
        val dao = FakeTimelineDao()
        val api = RecordingApiService(FixtureLoader.loadListTweetsResponse("initial_fetch_99.json"))
        val repository = newRepository(dao, api)

        val count = repository.refresh()

        assertEquals(99, count)
        assertEquals(99, api.lastMaxResults)
        assertEquals(99, dao.insertedTweets.size)
    }

    @Test
    fun refresh_deltaFetch_usesConfiguredMaxResults() = runBlocking {
        val dao = FakeTimelineDao().apply {
            existingTweets["1900000000000000100"] = TweetEntity(
                id = "1900000000000000100",
                text = "existing",
                authorName = "ex",
                authorUsername = "ex",
                authorProfileImageUrl = null,
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
    fun refresh_mapsAuthorProfileImageUrl() = runBlocking {
        val dao = FakeTimelineDao()
        val api = RecordingApiService(FixtureLoader.loadListTweetsResponse("mixed_media_delta.json"))
        val repository = newRepository(dao, api)

        repository.refresh()

        val tweetById = dao.insertedTweets.associateBy { it.id }
        // user 2001 has profile_image_url in fixture
        assertEquals(
            "https://pbs.twimg.com/profile_images/2001/photo.jpg",
            tweetById.getValue("1900000000000000201").authorProfileImageUrl
        )
        // user 2002 has no profile_image_url in fixture
        assertEquals(null, tweetById.getValue("1900000000000000202").authorProfileImageUrl)
    }

    @Test
    fun refresh_callsTrimAfterInsert() = runBlocking {
        val dao = FakeTimelineDao()
        val api = RecordingApiService(FixtureLoader.loadListTweetsResponse("mixed_media_delta.json"))
        val repository = newRepository(dao, api)

        repository.refresh()

        assertTrue(dao.trimCallCount > 0)
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

    @Test
    fun refresh_deltaFetch_paginatesAndStores200Posts() = runBlocking {
        val dao = FakeTimelineDao().apply {
            existingTweets["1900000000000002000"] = TweetEntity(
                id = "1900000000000002000",
                text = "existing anchor",
                authorName = "anchor",
                authorUsername = "anchor",
                authorProfileImageUrl = null,
                createdAt = 1L,
                permalink = "https://x.com/i/web/status/1900000000000002000",
                hasVideo = false,
                isBookmarked = false,
                syncedAt = 1L
            )
        }
        val api = PagingApiService(
            responsesByToken = mapOf(
                null to FixtureLoader.loadListTweetsResponse("large_delta_200_page_1.json"),
                "page_2" to FixtureLoader.loadListTweetsResponse("large_delta_200_page_2.json")
            )
        )
        val repository = newRepository(dao, api, maxResults = 100)

        val count = repository.refresh()

        assertEquals(200, count)
        assertEquals(listOf(null, "page_2"), api.requestedTokens)
        assertEquals(200, dao.insertedTweets.size)
        assertEquals("1900000000000002200", dao.insertedTweets.first().id)
        assertEquals("1900000000000002001", dao.insertedTweets.last().id)
    }

    @Test
    fun refresh_offlineMode_transitionFromOnline_keepsLatest99AndAddsFixture() = runBlocking {
        installOfflineFixtures()

        val dao = FakeTimelineDao().apply {
            (1..120).forEach { index ->
                val id = (1800000000000000000L + index).toString()
                existingTweets[id] = TweetEntity(
                    id = id,
                    text = "existing $index",
                    authorName = "stale",
                    authorUsername = "stale",
                    authorProfileImageUrl = null,
                    createdAt = index.toLong(),
                    permalink = "https://x.com/i/web/status/$id",
                    hasVideo = false,
                    isBookmarked = false,
                    syncedAt = index.toLong()
                )
            }
        }
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
                maxResults = 100,
                offlineMode = true
            ),
            api = RecordingApiService(FixtureLoader.loadListTweetsResponse("no_new_posts.json")),
            dao = dao,
            imageDownloader = { _, _, _ -> error("offline mode should not download images") }
        )

        val count = repository.refresh()

        assertEquals(200, count)
        assertEquals(299, dao.existingTweets.size)
        assertTrue("1800000000000000001" !in dao.existingTweets)
        assertTrue("1800000000000000021" !in dao.existingTweets)
        assertTrue("1800000000000000022" in dao.existingTweets)
        assertTrue("1800000000000000120" in dao.existingTweets)
        assertTrue("1900000000000002200" in dao.existingTweets)
    }

    @Test
    fun refresh_onlineAfterOffline_removesFixtureTweetsAndKeepsRealOnes() = runBlocking {
        installOfflineFixtures()
        sharedPreferences.edit().putBoolean("last_refresh_was_offline", true).apply()

        val dao = FakeTimelineDao().apply {
            existingTweets["1900000000000002200"] = tweetEntity("1900000000000002200")
            existingTweets["1900000000000002199"] = tweetEntity("1900000000000002199")
            existingTweets["1800000000000000120"] = tweetEntity("1800000000000000120")
        }
        val repository = newRepository(
            dao = dao,
            api = RecordingApiService(FixtureLoader.loadListTweetsResponse("no_new_posts.json"))
        )

        val count = repository.refresh()

        assertEquals(0, count)
        assertEquals(setOf("1800000000000000120"), dao.existingTweets.keys)
    }

    private fun newRepository(
        dao: FakeTimelineDao,
        api: XApiService,
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
        var lastMaxResults: Int? = null

        override suspend fun getListTweets(
            listId: String,
            maxResults: Int,
            paginationToken: String?,
            expansions: String,
            tweetFields: String,
            userFields: String,
            mediaFields: String
        ): ListTweetsResponse {
            lastMaxResults = maxResults
            return response
        }
    }

    private class PagingApiService(
        private val responsesByToken: Map<String?, ListTweetsResponse>
    ) : XApiService {
        val requestedTokens: MutableList<String?> = mutableListOf()

        override suspend fun getListTweets(
            listId: String,
            maxResults: Int,
            paginationToken: String?,
            expansions: String,
            tweetFields: String,
            userFields: String,
            mediaFields: String
        ): ListTweetsResponse {
            requestedTokens += paginationToken
            return checkNotNull(responsesByToken[paginationToken]) {
                "No fixture prepared for token=$paginationToken"
            }
        }
    }

    private class FakeTimelineDao : TimelineDao {
        val existingTweets: MutableMap<String, TweetEntity> = linkedMapOf()
        val bookmarkedIds: MutableSet<String> = linkedSetOf()
        val insertedTweets: MutableList<TweetEntity> = mutableListOf()
        val insertedMedia: MutableList<MediaEntity> = mutableListOf()
        var trimCallCount: Int = 0

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

        override suspend fun clearTimeline() {
            existingTweets.clear()
            insertedTweets.clear()
            insertedMedia.clear()
        }

        override suspend fun deleteTweetsByIds(tweetIds: List<String>) {
            val idsToDelete = tweetIds.toSet()
            existingTweets.keys.removeAll(idsToDelete)
            insertedTweets.removeAll { it.id in idsToDelete }
            insertedMedia.removeAll { it.tweetId in idsToDelete }
        }

        override suspend fun trimTweetsToLimit(limit: Int) {
            trimCallCount++
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

    private fun installOfflineFixtures() {
        every { assetManager.open(any()) } answers {
            ByteArrayInputStream(FixtureLoader.loadRaw(firstArg<String>()).toByteArray())
        }
    }

    private fun tweetEntity(id: String): TweetEntity {
        return TweetEntity(
            id = id,
            text = "existing",
            authorName = "name",
            authorUsername = "user",
            authorProfileImageUrl = null,
            createdAt = 1L,
            permalink = "https://x.com/i/web/status/$id",
            hasVideo = false,
            isBookmarked = false,
            syncedAt = 1L
        )
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values: MutableMap<String, Any?> = mutableMapOf()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? {
            return values[key] as? String ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (values[key] as? MutableSet<String>) ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int {
            return values[key] as? Int ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return values[key] as? Long ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            return values[key] as? Float ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return values[key] as? Boolean ?: defValue
        }

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private class Editor(
            private val values: MutableMap<String, Any?>
        ) : SharedPreferences.Editor {
            private val staged: MutableMap<String, Any?> = mutableMapOf()
            private var clearRequested: Boolean = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = applyChange(key, value)

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                applyChange(key, values)

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = applyChange(key, value)

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = applyChange(key, value)

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = applyChange(key, value)

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = applyChange(key, value)

            override fun remove(key: String?): SharedPreferences.Editor = applyChange(key, null)

            override fun clear(): SharedPreferences.Editor {
                clearRequested = true
                staged.clear()
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) {
                    values.clear()
                }
                staged.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }

            private fun applyChange(key: String?, value: Any?): SharedPreferences.Editor {
                if (key != null) {
                    staged[key] = value
                }
                return this
            }
        }
    }
}
