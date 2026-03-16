package com.example.xclient

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.xclient.auth.AuthGateState
import com.example.xclient.auth.AuthPhase
import com.example.xclient.auth.OAuthManager
import com.example.xclient.config.AppConfigLoader
import com.example.xclient.data.TimelineItem
import com.example.xclient.ui.AppTheme
import com.example.xclient.ui.MainViewModel
import com.example.xclient.ui.MainViewModelFactory
import com.example.xclient.ui.TimelineUiState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var oauthManager: OAuthManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appConfig = runCatching { AppConfigLoader.load(applicationContext) }.getOrElse { throwable ->
            setContent {
                AppTheme(dynamicColor = false) {
                    CenterMessage(
                        message = "設定読み込みに失敗しました: ${throwable.message}",
                        primaryActionLabel = "閉じる",
                        onPrimaryAction = { finishAffinity() }
                    )
                }
            }
            return
        }

        val manager = OAuthManager(
            context = applicationContext,
            config = appConfig,
            scope = lifecycleScope,
            onStartActivity = { intent -> startActivity(intent) }
        ).also { oauthManager = it }
        manager.init()
        manager.handleAuthCallback(intent)

        setContent {
            AppTheme(dynamicColor = false) {
                AuthGateScreen(
                    viewModelFactory = MainViewModelFactory(
                        (application as XClientApplication).appContainer.timelineRepository
                    ),
                    authState = manager.authState.collectAsState().value,
                    onStartLogin = { manager.startLogin() },
                    onClose = { finishAffinity() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        oauthManager?.handleAuthCallback(intent)
    }
}

@Composable
private fun AuthGateScreen(
    viewModelFactory: MainViewModelFactory,
    authState: AuthGateState,
    onStartLogin: () -> Unit,
    onClose: () -> Unit
) {
    when (authState.phase) {
        AuthPhase.READY -> {
            val viewModel: MainViewModel = viewModel(factory = viewModelFactory)
            TimelineScreen(viewModel = viewModel, onStartLogin = onStartLogin)
        }
        AuthPhase.NEEDS_LOGIN -> {
            LaunchedEffect(Unit) { onStartLogin() }
            CenterMessage(
                message = "初回認証を開始します。ブラウザが開かない場合は手動で開始してください。",
                primaryActionLabel = "Xでログイン",
                onPrimaryAction = onStartLogin
            )
        }
        AuthPhase.WAITING_CALLBACK, AuthPhase.EXCHANGING -> {
            CenterMessage(message = authState.message ?: "認証中です...")
        }
        AuthPhase.BLOCKED -> {
            CenterMessage(
                message = authState.message ?: "認証エラーが発生しました。",
                primaryActionLabel = "閉じる",
                onPrimaryAction = onClose
            )
        }
    }
}

@Composable
private fun CenterMessage(
    message: String,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            M3Text(text = message, style = MaterialTheme.typography.bodyLarge)
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Button(onClick = onPrimaryAction) { M3Text(text = primaryActionLabel) }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun TimelineScreen(viewModel: MainViewModel = viewModel(), onStartLogin: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val prefs = remember(appContext) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    TimelineScreenContent(
        state = state,
        onRefresh = { viewModel.refresh() },
        onToggleBookmark = { item -> viewModel.toggleBookmark(item) },
        onMarkAsRead = { viewModel.markAsRead() },
        onStartLogin = onStartLogin,
        onClose = { (context as? Activity)?.finishAffinity() },
        prefs = prefs
    )
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
internal fun TimelineScreenContent(
    state: TimelineUiState,
    onRefresh: () -> Unit,
    onToggleBookmark: (TimelineItem) -> Unit,
    onMarkAsRead: () -> Unit,
    onStartLogin: () -> Unit,
    onClose: () -> Unit,
    prefs: android.content.SharedPreferences,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val latestState by rememberUpdatedState(state)
    var initialPositionRestoreStarted by rememberSaveable { mutableStateOf(false) }
    var initialPositionRestoreCompleted by rememberSaveable { mutableStateOf(false) }
    var viewportAnchorId by rememberSaveable { mutableStateOf<String?>(null) }
    var viewportAnchorOffset by rememberSaveable { mutableStateOf(0) }
    var viewportWasAtTop by rememberSaveable { mutableStateOf(true) }
    var expandedImagePath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.errorMessage, state.blockingErrorMessage) {
        if (state.blockingErrorMessage != null) return@LaunchedEffect
        val message = state.errorMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "同期エラー: $message",
            actionLabel = "再ログイン"
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            onStartLogin()
        }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = state.refreshing,
        onRefresh = onRefresh
    )

    // state.items を key にすると、多数の投稿到着時に scrollToItem 中にキャンセルされて
    // restoredInitialPosition が true にならず先頭に飛ぶ競合が起きる。
    // LaunchedEffect(Unit) で一度だけ起動し、snapshotFlow で最初の非空リストを待つことで解決する。
    LaunchedEffect(Unit) {
        snapshotFlow { latestState.items }.first { it.isNotEmpty() }
        if (initialPositionRestoreStarted) return@LaunchedEffect
        initialPositionRestoreStarted = true  // scrollToItem の前にセットして再入を防ぐ
        val anchorTweetId = prefs.getString(KEY_ANCHOR_TWEET_ID, null)
        val anchorIndex = anchorTweetId
            ?.let { targetId -> latestState.items.indexOfFirst { it.id == targetId } }
            ?.takeIf { it >= 0 }
            ?: 0
        listState.scrollToItem(anchorIndex)
        initialPositionRestoreCompleted = true
    }

    LaunchedEffect(state.items, initialPositionRestoreCompleted) {
        if (!initialPositionRestoreCompleted || viewportWasAtTop) return@LaunchedEffect
        val anchorId = viewportAnchorId ?: return@LaunchedEffect
        val targetIndex = latestState.items.indexOfFirst { it.id == anchorId }
        if (targetIndex >= 0 && targetIndex != listState.firstVisibleItemIndex) {
            listState.scrollToItem(targetIndex, viewportAnchorOffset)
        }
    }

    // state.items をキーにすると投稿追加のたびに再起動し、保存タイミングがずれる。
    // state は読み取り時点の最新値を返すため、キーから除外しても正しく動作する。
    LaunchedEffect(listState, initialPositionRestoreCompleted) {
        if (!initialPositionRestoreCompleted) return@LaunchedEffect
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            viewportWasAtTop = index == 0
            viewportAnchorOffset = offset
            if (index == 0) onMarkAsRead()
            val anchorId = latestState.items.getOrNull(index)?.id ?: return@collect
            viewportAnchorId = anchorId
            prefs.edit().putString(KEY_ANCHOR_TWEET_ID, anchorId).apply()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        val blockingMessage = state.blockingErrorMessage
        if (blockingMessage != null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    M3Text(text = blockingMessage, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onStartLogin) {
                        M3Text(text = "再ログイン")
                    }
                    Button(onClick = onClose) {
                        M3Text(text = "閉じる")
                    }
                }
            }
            return@Scaffold
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .pullRefresh(pullRefreshState)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TIMELINE_LIST_TAG),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.items, key = { it.id }) { item ->
                    TimelineCard(
                        item = item,
                        onToggleBookmark = { onToggleBookmark(item) },
                        onImageTap = { path -> expandedImagePath = path }
                    )
                }
            }

            PullRefreshIndicator(
                refreshing = state.refreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            if (state.newPostsCount > 0 && !state.refreshing) {
                Button(
                    onClick = {
                        onMarkAsRead()
                        scope.launch { listState.scrollToItem(0) }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                ) {
                    M3Text(text = "↑ ${state.newPostsCount}件の新着")
                }
            }
        }

        expandedImagePath?.let { path ->
            FullscreenImageDialog(path = path, onDismiss = { expandedImagePath = null })
        }
    }
}

@Composable
private fun TimelineCard(
    item: TimelineItem,
    onToggleBookmark: () -> Unit,
    onImageTap: (String) -> Unit
) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = item.authorProfileImageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    M3Text(text = item.authorName, style = MaterialTheme.typography.titleMedium)
                    M3Text(
                        text = "@${item.authorUsername}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Light
                    )
                }
                M3Text(
                    text = formatDate(item.createdAt),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinkifiedPostText(text = item.text)
            Spacer(modifier = Modifier.height(8.dp))

            item.imagePaths.forEach { path ->
                LocalImage(path = path, onClick = { onImageTap(path) })
                Spacer(modifier = Modifier.height(8.dp))
            }

            item.videoLinks.forEach { link ->
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                }) {
                    M3Text(text = "動画をXで開く")
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onToggleBookmark) {
                    M3Text(text = if (item.isBookmarked) "しおり解除" else "しおり追加")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { openInXClient(context, item.permalink) }) {
                    M3Text(text = "X公式で開く")
                }
            }
        }
    }
}

@Composable
private fun LinkifiedPostText(text: String) {
    val linkRegex = Regex("https?://[A-Za-z0-9./?%&=+#:_~\\-]+")
    val annotated = buildAnnotatedString {
        var current = 0
        linkRegex.findAll(text).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            if (start > current) append(text.substring(current, start))
            val url = match.value
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = Color(0xFF4EA3FF),
                            textDecoration = TextDecoration.Underline
                        )
                    )
                )
            ) { append(url) }
            current = end
        }
        if (current < text.length) append(text.substring(current))
    }
    M3Text(text = annotated, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun LocalImage(path: String, onClick: () -> Unit) {
    AsyncImage(
        model = File(path),
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun FullscreenImageDialog(path: String, onDismiss: () -> Unit) {
    var scale by remember(path) { mutableStateOf(1f) }
    var offsetX by remember(path) { mutableStateOf(0f) }
    var offsetY by remember(path) { mutableStateOf(0f) }
    var containerSize by remember(path) { mutableStateOf(IntSize.Zero) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss))
            AsyncImage(
                model = File(path),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .pointerInput(path) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                } else {
                                    scale = 2f; offsetX = 0f; offsetY = 0f
                                }
                            }
                        )
                    }
                    .pointerInput(path) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            val scaledWidth = containerSize.width * newScale
                            val scaledHeight = containerSize.height * newScale
                            val maxX = ((scaledWidth - containerSize.width) / 2f).coerceAtLeast(0f)
                            val maxY = ((scaledHeight - containerSize.height) / 2f).coerceAtLeast(0f)
                            scale = newScale
                            if (newScale <= 1f) {
                                offsetX = 0f; offsetY = 0f
                            } else {
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            }
                        }
                    }
                    .onSizeChanged { containerSize = it }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offsetX; translationY = offsetY
                    },
                contentScale = ContentScale.Fit
            )
        }
    }
}

private fun formatDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))
}

private const val PREFS_NAME = "timeline_prefs"
private const val KEY_ANCHOR_TWEET_ID = "anchor_tweet_id"
internal const val TIMELINE_LIST_TAG = "timeline_list"

private fun openInXClient(context: Context, permalink: String) {
    val tweetId = extractTweetId(permalink)
    val appIntents = mutableListOf<Intent>()
    if (tweetId != null) {
        appIntents += Intent(Intent.ACTION_VIEW, Uri.parse("twitter://status?id=$tweetId"))
        appIntents += Intent(Intent.ACTION_VIEW, Uri.parse("twitter://status?status_id=$tweetId"))
    }
    appIntents += Intent(Intent.ACTION_VIEW, Uri.parse(permalink))

    for (baseIntent in appIntents) {
        val appIntent = baseIntent.apply { setPackage("com.twitter.android") }
        try {
            context.startActivity(appIntent)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(permalink)))
}

private fun extractTweetId(permalink: String): String? {
    return Regex("/status/(\\d+)").find(permalink)?.groupValues?.getOrNull(1)
}
