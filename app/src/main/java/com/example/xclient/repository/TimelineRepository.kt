package com.example.xclient.repository

import android.app.Application
import com.example.xclient.config.AppConfig
import com.example.xclient.config.AppConfigLoader
import com.example.xclient.db.AppDatabase
import com.example.xclient.db.MediaEntity
import com.example.xclient.db.TweetEntity
import com.example.xclient.db.TweetWithMedia
import com.example.xclient.network.XApiService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.time.Instant

class TimelineRepository private constructor(
    private val app: Application,
    private val config: AppConfig,
    private val api: XApiService,
    private val database: AppDatabase,
    private val httpClient: OkHttpClient
) {
    private val dao = database.timelineDao()

    fun observeTimeline(): Flow<List<TweetWithMedia>> = dao.observeTimeline()

    suspend fun refresh(): Int {
        return withContext(Dispatchers.IO) {
            val tweetCount = dao.getTweetCount()
            val isInitialFetch = tweetCount == 0
            val sinceId = if (isInitialFetch) null else dao.getLatestTweetId()
            val maxResults = if (isInitialFetch) 99 else config.maxResults
            val response = api.getListTweets(
                listId = config.listId,
                maxResults = maxResults,
                sinceId = sinceId
            )

            val tweets = response.data.orEmpty()
            if (tweets.isEmpty()) return@withContext 0
            val bookmarkedIds = dao.getBookmarkedTweetIds(tweets.map { it.id }).toSet()

            val users = response.includes?.users.orEmpty().associateBy { it.id }
            val mediaMap = response.includes?.media.orEmpty().associateBy { it.media_key }

            val tweetEntities = tweets.map { tweet ->
                val user = tweet.author_id?.let { users[it] }
                TweetEntity(
                    id = tweet.id,
                    text = tweet.text,
                    authorName = user?.name ?: "Unknown",
                    authorUsername = user?.username ?: "unknown",
                    createdAt = tweet.created_at?.let { Instant.parse(it).toEpochMilli() } ?: 0L,
                    permalink = "https://x.com/i/web/status/${tweet.id}",
                    hasVideo = tweet.attachments?.media_keys.orEmpty().any { key ->
                        val type = mediaMap[key]?.type
                        type == "video" || type == "animated_gif"
                    },
                    isBookmarked = tweet.id in bookmarkedIds,
                    syncedAt = System.currentTimeMillis()
                )
            }

            val mediaEntities = mutableListOf<MediaEntity>()

            tweets.forEach { tweet ->
                tweet.attachments?.media_keys.orEmpty().forEach { key ->
                    val media = mediaMap[key] ?: return@forEach
                    when (media.type) {
                        "photo" -> {
                            val sourceUrl = media.url ?: media.preview_image_url
                            val localPath = sourceUrl?.let { downloadImage(tweet.id, key, it) }
                            mediaEntities += MediaEntity(
                                tweetId = tweet.id,
                                type = "photo",
                                localPath = localPath,
                                remoteUrl = sourceUrl
                            )
                        }

                        "video", "animated_gif" -> {
                            mediaEntities += MediaEntity(
                                tweetId = tweet.id,
                                type = media.type,
                                localPath = null,
                                remoteUrl = "https://x.com/i/web/status/${tweet.id}"
                            )
                        }
                    }
                }
            }

            val tweetIds = tweetEntities.map { it.id }
            dao.upsertTweets(tweetEntities)
            if (tweetIds.isNotEmpty()) {
                dao.deleteMediaByTweetIds(tweetIds)
            }
            if (mediaEntities.isNotEmpty()) {
                dao.insertMedia(mediaEntities)
            }

            tweetEntities.size
        }
    }

    suspend fun setBookmark(tweetId: String, bookmarked: Boolean) {
        withContext(Dispatchers.IO) {
            dao.updateBookmark(tweetId, bookmarked)
        }
    }

    private fun downloadImage(tweetId: String, mediaKey: String, url: String): String? {
        return runCatching {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val dir = File(app.filesDir, "images")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "${tweetId}_${mediaKey}.jpg")
                file.outputStream().use { output -> body.byteStream().copyTo(output) }
                file.absolutePath
            }
        }.getOrNull()
    }

    companion object {
        fun create(application: Application): TimelineRepository {
            val config = AppConfigLoader.load(application)

            val authClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer ${config.accessToken}")
                        .build()
                    chain.proceed(request)
                }
                .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(config.apiBaseUrl)
                .client(authClient)
                .addConverterFactory(
                    MoshiConverterFactory.create(
                        Moshi.Builder()
                            .add(KotlinJsonAdapterFactory())
                            .build()
                    )
                )
                .build()

            return TimelineRepository(
                app = application,
                config = config,
                api = retrofit.create(XApiService::class.java),
                database = AppDatabase.build(application),
                httpClient = OkHttpClient()
            )
        }
    }
}
