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

data class TimelineUiState(
    val items: List<TimelineItem> = emptyList(),
    val refreshing: Boolean = false,
    val errorMessage: String? = null,
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
            _uiState.update { it.copy(refreshing = true, errorMessage = null) }
            runCatching { repository.refresh() }
                .onSuccess { count ->
                    _uiState.update { it.copy(lastFetchedCount = count, refreshing = false) }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            refreshing = false,
                            errorMessage = throwable.message ?: "unknown error"
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
