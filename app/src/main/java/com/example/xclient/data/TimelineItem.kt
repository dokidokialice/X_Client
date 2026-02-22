package com.example.xclient.data

data class TimelineItem(
    val id: String,
    val authorName: String,
    val authorUsername: String,
    val text: String,
    val createdAt: Long,
    val isBookmarked: Boolean,
    val imagePaths: List<String>,
    val videoLinks: List<String>,
    val permalink: String
)
