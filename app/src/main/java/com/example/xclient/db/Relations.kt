package com.example.xclient.db

import androidx.room.Embedded
import androidx.room.Relation

data class TweetWithMedia(
    @Embedded val tweet: TweetEntity,
    @Relation(parentColumn = "id", entityColumn = "tweetId")
    val media: List<MediaEntity>
)
