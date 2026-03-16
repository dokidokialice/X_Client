package com.example.xclient

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.xclient.data.TimelineItem
import com.example.xclient.ui.AppTheme
import com.example.xclient.ui.TimelineUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val prefsName = "timeline_screen_test_prefs"

    @After
    fun tearDown() {
        composeRule.activity
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun largeBatchArrival_keepsSavedAnchorAtTop() {
        val prefs = composeRule.activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val existingItems = createItems(
            startId = 1800000000000000120L,
            count = 120,
            label = "existing"
        )
        val requestedAnchorItem = existingItems[80]
        val newItems = createItems(
            startId = 1900000000000002200L,
            count = 200,
            label = "new"
        )
        val listState = LazyListState()
        var state by mutableStateOf(TimelineUiState())
        var restoredAnchorId: String? = null

        prefs.edit().putString("anchor_tweet_id", requestedAnchorItem.id).commit()

        composeRule.setContent {
            AppTheme(dynamicColor = false) {
                TimelineScreenContent(
                    state = state,
                    onRefresh = {},
                    onToggleBookmark = {},
                    onVisibleItemsChanged = {},
                    onStartLogin = {},
                    onClose = {},
                    prefs = prefs,
                    listState = listState
                )
            }
        }

        composeRule.runOnIdle {
            state = TimelineUiState(items = existingItems)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val visibleKeys = listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
            restoredAnchorId = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
            assertTrue(restoredAnchorId != null && visibleKeys.contains(restoredAnchorId))
        }

        composeRule.runOnIdle {
            state = TimelineUiState(
                items = newItems + existingItems
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val visibleKeys = listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
            assertTrue(restoredAnchorId != null && visibleKeys.contains(restoredAnchorId))
        }
    }

    @Test
    fun initialRestore_keepsSavedAnchorWhenVisibleCallbackPrependsItems() {
        val prefs = composeRule.activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val existingItems = createItems(
            startId = 1800000000000000120L,
            count = 120,
            label = "existing"
        )
        val requestedAnchorItem = existingItems[80]
        val newItems = createItems(
            startId = 1900000000000002200L,
            count = 200,
            label = "new"
        )
        val listState = LazyListState()
        var state by mutableStateOf(TimelineUiState())
        var hasPrependedNewItems by mutableStateOf(false)

        prefs.edit().putString("anchor_tweet_id", requestedAnchorItem.id).commit()

        composeRule.setContent {
            AppTheme(dynamicColor = false) {
                TimelineScreenContent(
                    state = state,
                    onRefresh = {},
                    onToggleBookmark = {},
                    onVisibleItemsChanged = {
                        if (!hasPrependedNewItems) {
                            hasPrependedNewItems = true
                            state = state.copy(
                                items = newItems + state.items,
                                unreadPostsCount = newItems.size,
                                oldestUnreadPostId = newItems.lastOrNull()?.id
                            )
                        }
                    },
                    onStartLogin = {},
                    onClose = {},
                    prefs = prefs,
                    listState = listState
                )
            }
        }

        composeRule.runOnIdle {
            state = TimelineUiState(items = existingItems)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val visibleKeys = listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
            assertTrue(visibleKeys.contains(requestedAnchorItem.id))
        }
    }

    @Test
    fun initialLaunch_withoutSavedAnchor_scrollsToOldestUnread() {
        val prefs = composeRule.activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val items = createItems(
            startId = 1800000000000000120L,
            count = 120,
            label = "existing"
        )
        val oldestUnreadItem = items[119]
        val listState = LazyListState()
        var state by mutableStateOf(TimelineUiState())

        composeRule.setContent {
            AppTheme(dynamicColor = false) {
                TimelineScreenContent(
                    state = state,
                    onRefresh = {},
                    onToggleBookmark = {},
                    onVisibleItemsChanged = {},
                    onStartLogin = {},
                    onClose = {},
                    prefs = prefs,
                    listState = listState
                )
            }
        }

        composeRule.runOnIdle {
            state = TimelineUiState(
                items = items,
                unreadPostsCount = 20,
                oldestUnreadPostId = oldestUnreadItem.id
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val visibleKeys = listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
            assertTrue(visibleKeys.contains(oldestUnreadItem.id))
        }
    }

    private fun createItems(
        startId: Long,
        count: Int,
        label: String
    ): List<TimelineItem> {
        return (0 until count).map { index ->
            val id = (startId - index).toString()
            TimelineItem(
                id = id,
                authorName = "$label author",
                authorUsername = "${label}_user",
                authorProfileImageUrl = null,
                text = "$label post ${index + 1}",
                createdAt = startId - index,
                isBookmarked = false,
                imagePaths = emptyList(),
                videoLinks = emptyList(),
                permalink = "https://x.com/i/web/status/$id"
            )
        }
    }
}
