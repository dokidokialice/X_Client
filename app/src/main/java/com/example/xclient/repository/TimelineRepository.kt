package com.example.xclient.repository

import android.app.Application
import android.content.Context
import com.example.xclient.config.AppConfig
import com.example.xclient.config.AppConfigLoader
import com.example.xclient.db.AppDatabase
import com.example.xclient.db.MediaEntity
import com.example.xclient.db.TimelineDao
import com.example.xclient.db.TweetEntity
import com.example.xclient.db.TweetWithMedia
import com.example.xclient.network.XApiService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class TimelineRepository internal constructor(
    private val app: Application,
    private val config: AppConfig,
    private val api: XApiService,
    private val dao: TimelineDao,
    private val imageDownloader: (String, String, String) -> String?
) {
    private val maxStoredTweets = 4999
    private val maxStoredImageBytes = 1L * 1024 * 1024 * 1024 // 1GB

    fun observeTimeline(): Flow<List<TweetWithMedia>> = dao.observeTimeline()

    suspend fun refresh(): Int {
        return withContext(Dispatchers.IO) {
            if (config.offlineMode) {
                dao.trimTweetsToLimit(maxStoredTweets)
                trimImagesToLimit(maxStoredImageBytes)
                return@withContext 0
            }

            val tweetCount = dao.getTweetCount()
            val isInitialFetch = tweetCount == 0
            val latestLocalTweetId = if (isInitialFetch) null else dao.getLatestTweetId()
            val maxResults = if (isInitialFetch) 99 else config.maxResults
            val tweets = mutableListOf<com.example.xclient.network.TweetDto>()
            val users = linkedMapOf<String, com.example.xclient.network.UserDto>()
            val mediaMap = linkedMapOf<String, com.example.xclient.network.MediaDto>()
            val seenNextTokens = mutableSetOf<String>()
            var paginationToken: String? = null

            while (true) {
                val response = api.getListTweets(
                    listId = config.listId,
                    maxResults = maxResults,
                    sinceId = null,
                    paginationToken = paginationToken
                )

                val pageTweets = response.data.orEmpty()
                val newTweets = if (latestLocalTweetId == null) {
                    pageTweets
                } else {
                    pageTweets.filter { isNewerTweetId(it.id, latestLocalTweetId) }
                }
                tweets += newTweets
                response.includes?.users.orEmpty().forEach { users[it.id] = it }
                response.includes?.media.orEmpty().forEach { mediaMap[it.media_key] = it }

                if (isInitialFetch) break
                if (latestLocalTweetId != null && newTweets.size < pageTweets.size) break
                val nextToken = response.meta?.next_token?.trim().orEmpty()
                if (nextToken.isBlank() || !seenNextTokens.add(nextToken)) break
                paginationToken = nextToken
            }

            if (tweets.isEmpty()) {
                dao.trimTweetsToLimit(maxStoredTweets)
                trimImagesToLimit(maxStoredImageBytes)
                return@withContext 0
            }
            val bookmarkedIds = dao.getBookmarkedTweetIds(tweets.map { it.id }).toSet()

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

            tweets.forEach tweetLoop@{ tweet ->
                for (key in tweet.attachments?.media_keys.orEmpty()) {
                    val media = mediaMap[key] ?: continue
                    when (media.type) {
                        "photo" -> {
                            val sourceUrl = media.url ?: media.preview_image_url
                            val localPath = sourceUrl?.let { imageDownloader(tweet.id, key, it) }
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
            dao.trimTweetsToLimit(maxStoredTweets)
            trimImagesToLimit(maxStoredImageBytes)

            tweetEntities.size
        }
    }

    suspend fun setBookmark(tweetId: String, bookmarked: Boolean) {
        withContext(Dispatchers.IO) {
            dao.updateBookmark(tweetId, bookmarked)
        }
    }

    private suspend fun trimImagesToLimit(maxBytes: Long) {
        val dir = File(app.filesDir, "images")
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return

        var totalBytes = files.sumOf { it.length() }
        if (totalBytes <= maxBytes) return

        for (file in files) {
            val fileSize = file.length()
            if (file.delete()) {
                dao.clearMediaLocalPath(file.absolutePath)
                totalBytes -= fileSize
                if (totalBytes <= maxBytes) break
            }
        }
    }

    companion object {
        fun create(application: Application): TimelineRepository {
            val config = AppConfigLoader.load(application)
            val tokenClient = OkHttpClient.Builder()
                .connectTimeout(180, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS)
                .build()
            val tokenManager = TokenManager(
                context = application,
                config = config,
                tokenClient = tokenClient
            )

            val authClient = OkHttpClient.Builder()
                .connectTimeout(180, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer ${tokenManager.currentAccessToken()}")
                        .build()
                    chain.proceed(request)
                }
                .authenticator(Authenticator { _: Route?, response: Response ->
                    if (responseCount(response) >= 2) return@Authenticator null
                    val usedToken = response.request.header("Authorization")
                        ?.removePrefix("Bearer ")
                        ?.trim()
                    val refreshedToken = tokenManager.refreshTokenAndGetAccessToken(usedToken)
                        ?: return@Authenticator null
                    response.request.newBuilder()
                        .header("Authorization", "Bearer $refreshedToken")
                        .build()
                })
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
            val mediaClient = OkHttpClient.Builder()
                .connectTimeout(180, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS)
                .build()

            return TimelineRepository(
                app = application,
                config = config,
                api = retrofit.create(XApiService::class.java),
                dao = AppDatabase.build(application).timelineDao(),
                imageDownloader = { tweetId, mediaKey, url ->
                    downloadImage(application, mediaClient, tweetId, mediaKey, url)
                }
            )
        }

        private fun downloadImage(
            app: Application,
            httpClient: OkHttpClient,
            tweetId: String,
            mediaKey: String,
            url: String
        ): String? {
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

        private fun responseCount(response: Response): Int {
            var count = 1
            var prior = response.priorResponse
            while (prior != null) {
                count++
                prior = prior.priorResponse
            }
            return count
        }

        private fun isNewerTweetId(candidate: String, baseline: String): Boolean {
            val a = candidate.trimStart('0')
            val b = baseline.trimStart('0')
            if (a.length != b.length) return a.length > b.length
            return a > b
        }
    }
}

private class TokenManager(
    context: Context,
    private val config: AppConfig,
    private val tokenClient: OkHttpClient
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var accessToken: String = prefs.getString(KEY_ACCESS_TOKEN, null)
        ?.takeIf { it.isNotBlank() }
        ?: config.accessToken

    @Volatile
    private var refreshToken: String = prefs.getString(KEY_REFRESH_TOKEN, null)
        ?.takeIf { it.isNotBlank() }
        ?: config.refreshToken

    fun currentAccessToken(): String = accessToken

    @Synchronized
    fun refreshTokenAndGetAccessToken(usedToken: String?): String? {
        if (usedToken != null && usedToken != accessToken) {
            return accessToken
        }
        if (refreshToken.isBlank() || config.clientId.isBlank()) {
            return null
        }

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", config.clientId)
            .build()
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .build()

        val result = runCatching {
            tokenClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string().orEmpty())
                val newAccessToken = json.optString("access_token").trim()
                if (newAccessToken.isBlank()) return null
                val newRefreshToken = json.optString("refresh_token").trim().ifBlank { refreshToken }
                newAccessToken to newRefreshToken
            }
        }.getOrNull() ?: return null

        accessToken = result.first
        refreshToken = result.second
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()

        return accessToken
    }

    companion object {
        private const val TOKEN_ENDPOINT = "https://api.x.com/2/oauth2/token"
        private const val PREFS_NAME = "auth_tokens"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
