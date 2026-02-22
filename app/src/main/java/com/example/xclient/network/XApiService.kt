package com.example.xclient.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class ListTweetsResponse(
    val data: List<TweetDto>?,
    val includes: IncludesDto?
)

data class TweetDto(
    val id: String,
    val text: String,
    val author_id: String?,
    val created_at: String?,
    val attachments: AttachmentsDto?
)

data class AttachmentsDto(
    val media_keys: List<String>?
)

data class IncludesDto(
    val users: List<UserDto>?,
    val media: List<MediaDto>?
)

data class UserDto(
    val id: String,
    val name: String,
    val username: String
)

data class MediaDto(
    val media_key: String,
    val type: String,
    val url: String?,
    val preview_image_url: String?
)

interface XApiService {
    @GET("2/lists/{id}/tweets")
    suspend fun getListTweets(
        @Path("id") listId: String,
        @Query("max_results") maxResults: Int,
        @Query("since_id") sinceId: String?,
        @Query("expansions") expansions: String = "attachments.media_keys,author_id",
        @Query("tweet.fields") tweetFields: String = "id,text,author_id,created_at,attachments",
        @Query("user.fields") userFields: String = "id,name,username",
        @Query("media.fields") mediaFields: String = "media_key,type,url,preview_image_url"
    ): ListTweetsResponse
}
