package com.example.xclient.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.xclient.data.TimelineItem
import com.example.xclient.db.TweetWithMedia
import com.example.xclient.repository.TimelineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class TimelineUiState(
    val items: List<TimelineItem> = emptyList(),
    val refreshing: Boolean = false,
    val errorMessage: String? = null,
    val blockingErrorMessage: String? = null,
    val lastFetchedCount: Int = 0
)

class MainViewModel(private val repository: TimelineRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    init {
        observeTimeline()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, errorMessage = null, blockingErrorMessage = null) }
            runCatching { repository.refresh() }
                .onSuccess { count ->
                    _uiState.update { it.copy(lastFetchedCount = count, refreshing = false, blockingErrorMessage = null) }
                }
                .onFailure { throwable ->
                    val blockingMessage = toBillingBlockingMessageOrNull(throwable)
                        ?: toInitialAuthBlockingMessageOrNull(
                            throwable = throwable,
                            hasAnyLocalItems = _uiState.value.items.isNotEmpty()
                        )
                    _uiState.update {
                        it.copy(
                            refreshing = false,
                            errorMessage = if (blockingMessage == null) throwable.message ?: "unknown error" else null,
                            blockingErrorMessage = blockingMessage
                        )
                    }
                }
        }
    }

    fun toggleBookmark(item: TimelineItem) {
        viewModelScope.launch {
            runCatching {
                repository.setBookmark(item.id, !item.isBookmarked)
            }.onFailure { throwable ->
                _uiState.update { it.copy(errorMessage = throwable.message ?: "bookmark update failed") }
            }
        }
    }

    private fun observeTimeline() {
        viewModelScope.launch {
            repository.observeTimeline().collect { rows ->
                _uiState.update { it.copy(items = rows.map { row -> row.toTimelineItem() }) }
            }
        }
    }

    private fun TweetWithMedia.toTimelineItem(): TimelineItem {
        return TimelineItem(
            id = tweet.id,
            authorName = tweet.authorName,
            authorUsername = tweet.authorUsername,
            text = tweet.text,
            createdAt = tweet.createdAt,
            isBookmarked = tweet.isBookmarked,
            imagePaths = media.filter { it.type == "photo" }.mapNotNull { it.localPath },
            videoLinks = media.filter { it.type == "video" || it.type == "animated_gif" }
                .mapNotNull { it.remoteUrl }
                .distinct(),
            permalink = tweet.permalink
        )
    }

    private fun toBillingBlockingMessageOrNull(throwable: Throwable): String? {
        val http = throwable as? HttpException ?: return null
        if (http.code() !in listOf(400, 401, 402, 403, 429)) return null

        val bodyText = runCatching { http.response()?.errorBody()?.string().orEmpty() }.getOrDefault("")
        val combined = "${http.message().orEmpty()} $bodyText".lowercase()
        val billingKeywords = listOf(
            "payment",
            "billing",
            "card",
            "subscription",
            "plan",
            "project",
            "not enrolled",
            "not authorized for this endpoint"
        )
        val matched = billingKeywords.any { it in combined }
        if (!matched) return null

        return "X APIの利用には支払い設定が必要です。Developer Portalで支払い方法を設定してから再試行してください。"
    }

    private fun toInitialAuthBlockingMessageOrNull(
        throwable: Throwable,
        hasAnyLocalItems: Boolean
    ): String? {
        if (hasAnyLocalItems) return null

        val http = throwable as? HttpException ?: return null
        if (http.code() !in listOf(400, 401, 403)) return null

        val bodyText = runCatching { http.response()?.errorBody()?.string().orEmpty() }.getOrDefault("")
        val combined = "${http.message().orEmpty()} $bodyText".lowercase()
        val authKeywords = listOf(
            "unauthorized",
            "invalid token",
            "invalid_token",
            "expired",
            "access token",
            "authentication"
        )
        val matched = authKeywords.any { it in combined }
        if (!matched) return null

        return "初回認証に失敗しました。access_token（必要なら refresh_token / client_id）を確認して再起動してください。"
    }
}

class MainViewModelFactory(private val repository: TimelineRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
