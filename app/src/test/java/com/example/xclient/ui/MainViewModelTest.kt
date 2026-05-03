package com.example.xclient.ui

import com.example.xclient.db.TweetEntity
import com.example.xclient.db.TweetWithMedia
import com.example.xclient.repository.TimelineRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun unreadCount_decreasesWhenVisiblePostsAreReported() = runTest(dispatcher) {
        val timelineFlow = MutableStateFlow(rows("100", "99", "98"))
        val repository = mockk<TimelineRepository> {
            every { observeTimeline() } returns timelineFlow
            coEvery { refresh() } returns 0
            every { isOfflineModeEnabled() } returns false
            every { getOfflineFixturePostIds() } returns emptySet()
        }
        val viewModel = MainViewModel(repository)

        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.unreadPostsCount)

        timelineFlow.value = rows("102", "101", "100", "99", "98")
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.unreadPostsCount)
        assertEquals("101", viewModel.uiState.value.oldestUnreadPostId)

        viewModel.markVisiblePosts(setOf("102"))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.unreadPostsCount)
        assertEquals("101", viewModel.uiState.value.oldestUnreadPostId)

        viewModel.markVisiblePosts(setOf("101"))
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.unreadPostsCount)
        assertEquals(null, viewModel.uiState.value.oldestUnreadPostId)
    }

    @Test
    fun offlineMode_marksFixturePostsUnreadOnInitialLoad() = runTest(dispatcher) {
        val timelineFlow = MutableStateFlow(rows("202", "201", "200", "199"))
        val repository = mockk<TimelineRepository> {
            every { observeTimeline() } returns timelineFlow
            coEvery { refresh() } returns 0
            every { isOfflineModeEnabled() } returns true
            every { getOfflineFixturePostIds() } returns setOf("202", "201")
        }
        val viewModel = MainViewModel(repository)

        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.unreadPostsCount)
        assertEquals("201", viewModel.uiState.value.oldestUnreadPostId)
    }

    @Test
    fun refresh_402ShowsCreditAndBillingMessage() = runTest(dispatcher) {
        val repository = mockk<TimelineRepository> {
            every { observeTimeline() } returns MutableStateFlow(emptyList())
            coEvery { refresh() } throws HttpException(
                Response.error<Unit>(402, "payment required".toResponseBody())
            )
            every { isOfflineModeEnabled() } returns false
            every { getOfflineFixturePostIds() } returns emptySet()
        }
        val viewModel = MainViewModel(repository)

        advanceUntilIdle()

        val message = viewModel.uiState.value.blockingErrorMessage.orEmpty()
        assertTrue(message.contains("クレジット"))
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    private fun rows(vararg ids: String): List<TweetWithMedia> {
        return ids.mapIndexed { index, id ->
            TweetWithMedia(
                tweet = TweetEntity(
                    id = id,
                    text = "post $id",
                    authorName = "author",
                    authorUsername = "user",
                    authorProfileImageUrl = null,
                    createdAt = index.toLong(),
                    permalink = "https://x.com/i/web/status/$id",
                    hasVideo = false,
                    isBookmarked = false,
                    syncedAt = 0L
                ),
                media = emptyList()
            )
        }
    }
}
