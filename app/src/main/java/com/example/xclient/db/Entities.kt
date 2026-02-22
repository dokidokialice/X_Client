package com.example.xclient.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tweets")
data class TweetEntity(
    @PrimaryKey val id: String,
    val text: String,
    val authorName: String,
    val authorUsername: String,
    val createdAt: Long,
    val permalink: String,
    val hasVideo: Boolean,
    val isBookmarked: Boolean = false,
    val syncedAt: Long
)

@Entity(
    tableName = "media",
    foreignKeys = [
        ForeignKey(
            entity = TweetEntity::class,
            parentColumns = ["id"],
            childColumns = ["tweetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tweetId")]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tweetId: String,
    val type: String,
    val localPath: String?,
    val remoteUrl: String?
)
