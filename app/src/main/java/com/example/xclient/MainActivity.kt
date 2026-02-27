package com.example.xclient

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.xclient.config.AppConfig
import com.example.xclient.config.AppConfigLoader
import com.example.xclient.data.TimelineItem
import com.example.xclient.ui.MainViewModel
import com.example.xclient.ui.MainViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var appConfig: AppConfig
    private val authState = MutableStateFlow(AuthGateState())
    private var loopbackJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appConfig = runCatching { AppConfigLoader.load(applicationContext) }.getOrElse { throwable ->
            authState.value = AuthGateState(
                phase = AuthPhase.BLOCKED,
                message = "設定読み込みに失敗しました: ${throwable.message}"
            )
            return
        }
        initAuthGate()
        handleAuthCallback(intent)

        setContent {
            MaterialTheme {
                AuthGateScreen(
                    viewModelFactory = MainViewModelFactory(
                        (application as XClientApplication).appContainer.timelineRepository
                    ),
                    authState = authState.asStateFlow().collectAsState().value,
                    onStartLogin = { startOAuthLogin() },
                    onClose = { finishAffinity() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    private fun initAuthGate() {
        val hasToken = hasStoredAccessToken() || appConfig.accessToken.isNotBlank()
        authState.value = when {
            hasToken -> AuthGateState(phase = AuthPhase.READY)
            appConfig.canStartOAuthLogin -> AuthGateState(phase = AuthPhase.NEEDS_LOGIN)
            else -> AuthGateState(
                phase = AuthPhase.BLOCKED,
                message = "access_token が未設定です。OAuth を使う場合は client_id を設定してください。"
            )
        }
    }

    private fun hasStoredAccessToken(): Boolean {
        val prefs = getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_ACCESS_TOKEN, "").isNullOrBlank()
    }

    private fun startOAuthLogin() {
        if (authState.value.phase !in setOf(AuthPhase.NEEDS_LOGIN, AuthPhase.BLOCKED)) return
        if (!appConfig.canStartOAuthLogin) {
            authState.value = AuthGateState(
                phase = AuthPhase.BLOCKED,
                message = "OAuth 設定が不足しています（client_id / auth_redirect_uri）"
            )
            return
        }
        val redirectUri = Uri.parse(appConfig.authRedirectUri)
        val host = redirectUri.host.orEmpty()
        val isLoopback = redirectUri.scheme == "http" && (host == "127.0.0.1" || host == "localhost")
        val port = redirectUri.port
        if (!isLoopback || port <= 0) {
            authState.value = AuthGateState(
                phase = AuthPhase.BLOCKED,
                message = "auth_redirect_uri は http://127.0.0.1:<port>/callback 形式で設定してください。"
            )
            return
        }
        val redirectPath = redirectUri.path.orEmpty().ifBlank { "/" }

        val state = randomToken(24)
        val verifier = randomToken(64)
        val challenge = verifier.toCodeChallenge()
        getSharedPreferences(PREFS_AUTH_FLOW, Context.MODE_PRIVATE).edit()
            .putString(KEY_OAUTH_STATE, state)
            .putString(KEY_OAUTH_VERIFIER, verifier)
            .apply()

        val authUri = Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", appConfig.clientId)
            .appendQueryParameter("redirect_uri", appConfig.authRedirectUri)
            .appendQueryParameter("scope", appConfig.authScopes)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        loopbackJob?.cancel()
        loopbackJob = lifecycleScope.launch {
            Log.i(TAG_OAUTH, "Loopback listener starting host=$host port=$port path=$redirectPath")
            val callback = withContext(Dispatchers.IO) {
                awaitLoopbackCallback(
                    host = host,
                    port = port,
                    expectedPath = redirectPath
                )
            }
            if (callback == null) {
                Log.e(TAG_OAUTH, "Loopback callback not received")
                authState.value = AuthGateState(
                    phase = AuthPhase.BLOCKED,
                    message = "OAuth コールバックを受信できませんでした。redirect_uri とポート設定を確認してください。"
                )
                return@launch
            }
            Log.i(TAG_OAUTH, "Loopback callback received state=${callback.state} hasCode=${callback.code.isNotBlank()} error=${callback.error}")
            processOAuthCallback(
                callback = callback,
                expectedState = state,
                verifier = verifier
            )
        }

        val browserIntent = Intent(Intent.ACTION_VIEW, authUri)
        runCatching { startActivity(browserIntent) }
            .onSuccess {
                Log.i(TAG_OAUTH, "Browser opened for OAuth authorize URL")
                authState.value = AuthGateState(
                    phase = AuthPhase.WAITING_CALLBACK,
                    message = "X の認証をブラウザで完了してください。完了後に自動でトークン取得します。"
                )
            }
            .onFailure { throwable ->
                Log.e(TAG_OAUTH, "Failed to open browser", throwable)
                loopbackJob?.cancel()
                authState.value = AuthGateState(
                    phase = AuthPhase.BLOCKED,
                    message = "認証ページを開けませんでした: ${throwable.message}"
                )
            }
    }

    private fun handleAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.toString().startsWith(appConfig.authRedirectUri).not()) return
        val flowPrefs = getSharedPreferences(PREFS_AUTH_FLOW, Context.MODE_PRIVATE)
        val expectedState = flowPrefs.getString(KEY_OAUTH_STATE, "").orEmpty()
        val verifier = flowPrefs.getString(KEY_OAUTH_VERIFIER, "").orEmpty()
        val callback = OAuthCallbackPayload(
            code = data.getQueryParameter("code").orEmpty(),
            state = data.getQueryParameter("state").orEmpty(),
            error = data.getQueryParameter("error")
        )
        processOAuthCallback(callback, expectedState, verifier)
    }

    private fun processOAuthCallback(
        callback: OAuthCallbackPayload,
        expectedState: String,
        verifier: String
    ) {
        Log.i(
            TAG_OAUTH,
            "Processing callback hasCode=${callback.code.isNotBlank()} stateMatch=${callback.state == expectedState} hasError=${!callback.error.isNullOrBlank()}"
        )
        if (!callback.error.isNullOrBlank()) {
            authState.value = AuthGateState(
                phase = AuthPhase.BLOCKED,
                message = "OAuth 認証に失敗しました: ${callback.error}"
            )
            return
        }
        if (callback.code.isBlank() || verifier.isBlank() || callback.state.isBlank() || callback.state != expectedState) {
            authState.value = AuthGateState(
                phase = AuthPhase.BLOCKED,
                message = "OAuth コールバックの検証に失敗しました。"
            )
            return
        }

        authState.value = AuthGateState(phase = AuthPhase.EXCHANGING, message = "トークン交換中...")
        lifecycleScope.launch {
            Log.i(TAG_OAUTH, "Exchanging token with X")
            val tokenResult = withContext(Dispatchers.IO) {
                exchangeToken(callback.code, verifier)
            }
            if (tokenResult == null) {
                Log.e(TAG_OAUTH, "Token exchange failed: network or unexpected exception")
                authState.value = AuthGateState(
                    phase = AuthPhase.BLOCKED,
                    message = "アクセストークンの取得に失敗しました（通信失敗）。"
                )
                return@launch
            }
            if (tokenResult.errorMessage != null) {
                Log.e(TAG_OAUTH, "Token exchange error: ${tokenResult.errorMessage}")
                authState.value = AuthGateState(
                    phase = AuthPhase.BLOCKED,
                    message = tokenResult.errorMessage
                )
                return@launch
            }
            val tokenResponse = tokenResult.token ?: run {
                authState.value = AuthGateState(
                    phase = AuthPhase.BLOCKED,
                    message = "アクセストークンの取得に失敗しました。"
                )
                return@launch
            }

            getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE).edit()
                .putString(KEY_ACCESS_TOKEN, tokenResponse.accessToken)
                .putString(KEY_REFRESH_TOKEN, tokenResponse.refreshToken)
                .apply()
            getSharedPreferences(PREFS_AUTH_FLOW, Context.MODE_PRIVATE).edit()
                .remove(KEY_OAUTH_STATE)
                .remove(KEY_OAUTH_VERIFIER)
                .apply()
            Log.i(TAG_OAUTH, "OAuth completed. Token stored.")
            authState.value = AuthGateState(phase = AuthPhase.READY)
            recreate()
        }
    }

    private fun awaitLoopbackCallback(
        host: String,
        port: Int,
        expectedPath: String
    ): OAuthCallbackPayload? {
        return try {
            ServerSocket(port, 1, InetAddress.getByName(host)).use { server ->
                server.soTimeout = 180_000
                Log.i(TAG_OAUTH, "Listening on $host:$port")
                server.accept().use { socket ->
                    Log.i(TAG_OAUTH, "Loopback connection accepted from ${socket.inetAddress.hostAddress}")
                    socket.soTimeout = 15_000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val requestLine = reader.readLine().orEmpty()
                    Log.i(TAG_OAUTH, "Loopback requestLine=$requestLine")
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isBlank()) break
                    }
                    val target = requestLine.split(" ").getOrNull(1).orEmpty()
                    val requestUri = Uri.parse("http://$host:$port$target")
                    val ok = requestUri.path == expectedPath

                    val responseBody = if (ok) {
                        "<html><body><h3>OAuth success</h3><p>アプリに戻ってください。</p></body></html>"
                    } else {
                        "<html><body><h3>OAuth failed</h3><p>callback path mismatch</p></body></html>"
                    }
                    BufferedWriter(OutputStreamWriter(socket.getOutputStream())).use { writer ->
                        writer.write("HTTP/1.1 200 OK\r\n")
                        writer.write("Content-Type: text/html; charset=utf-8\r\n")
                        writer.write("Connection: close\r\n\r\n")
                        writer.write(responseBody)
                        writer.flush()
                    }
                    if (!ok) return null
                    OAuthCallbackPayload(
                        code = requestUri.getQueryParameter("code").orEmpty(),
                        state = requestUri.getQueryParameter("state").orEmpty(),
                        error = requestUri.getQueryParameter("error")
                    )
                }
            }
        } catch (_: SocketTimeoutException) {
            Log.e(TAG_OAUTH, "Loopback wait timeout")
            null
        } catch (t: Throwable) {
            Log.e(TAG_OAUTH, "Loopback callback exception", t)
            null
        }
    }

    private fun exchangeToken(code: String, verifier: String): OAuthTokenExchangeResult? {
        val client = OkHttpClient.Builder()
            .connectTimeout(180, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", appConfig.authRedirectUri)
            .add("client_id", appConfig.clientId)
            .add("code_verifier", verifier)
            .build()
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(form)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return OAuthTokenExchangeResult(
                        token = null,
                        errorMessage = "OAuthトークン交換失敗: HTTP ${response.code} ${response.message} ${bodyText.take(300)}"
                    )
                }
                val json = JSONObject(bodyText)
                val accessToken = json.optString("access_token").trim()
                if (accessToken.isBlank()) {
                    return OAuthTokenExchangeResult(
                        token = null,
                        errorMessage = "OAuthトークン交換失敗: access_token がレスポンスにありません。${bodyText.take(300)}"
                    )
                }
                val refreshToken = json.optString("refresh_token").trim()
                OAuthTokenExchangeResult(
                    token = OAuthTokenResponse(
                        accessToken = accessToken,
                        refreshToken = refreshToken
                    ),
                    errorMessage = null
                )
            }
        }.onFailure { t ->
            Log.e(TAG_OAUTH, "Token exchange exception", t)
        }.getOrNull()
    }
}

private enum class AuthPhase {
    READY,
    NEEDS_LOGIN,
    WAITING_CALLBACK,
    EXCHANGING,
    BLOCKED
}

private data class AuthGateState(
    val phase: AuthPhase = AuthPhase.READY,
    val message: String? = null
)

private data class OAuthTokenResponse(
    val accessToken: String,
    val refreshToken: String
)

private data class OAuthCallbackPayload(
    val code: String,
    val state: String,
    val error: String?
)

private data class OAuthTokenExchangeResult(
    val token: OAuthTokenResponse?,
    val errorMessage: String?
)

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
            TimelineScreen(viewModel = viewModel)
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
            CenterMessage(
                message = authState.message ?: "認証中です..."
            )
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
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            M3Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge
            )
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Button(onClick = onPrimaryAction) {
                    M3Text(text = primaryActionLabel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun TimelineScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val prefs = remember(appContext) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var restoredInitialPosition by rememberSaveable { mutableStateOf(false) }
    var expandedImagePath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.errorMessage, state.blockingErrorMessage) {
        if (state.blockingErrorMessage != null) return@LaunchedEffect
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar("同期エラー: $message")
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = state.refreshing,
        onRefresh = { viewModel.refresh() }
    )

    LaunchedEffect(state.items, restoredInitialPosition) {
        if (restoredInitialPosition || state.items.isEmpty()) return@LaunchedEffect
        val anchorTweetId = prefs.getString(KEY_ANCHOR_TWEET_ID, null)
        val anchorIndex = anchorTweetId
            ?.let { targetId -> state.items.indexOfFirst { it.id == targetId } }
            ?.takeIf { it >= 0 }
            ?: 0
        listState.scrollToItem(anchorIndex)
        restoredInitialPosition = true
    }

    LaunchedEffect(listState, state.items) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val anchorId = state.items.getOrNull(index)?.id ?: return@collect
            prefs.edit().putString(KEY_ANCHOR_TWEET_ID, anchorId).apply()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        val blockingMessage = state.blockingErrorMessage
        if (blockingMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    M3Text(
                        text = blockingMessage,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(onClick = { (context as? Activity)?.finishAffinity() }) {
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.items, key = { it.id }) { item ->
                    TimelineCard(
                        item = item,
                        onToggleBookmark = { viewModel.toggleBookmark(item) },
                        onImageTap = { path -> expandedImagePath = path }
                    )
                }
            }

            PullRefreshIndicator(
                refreshing = state.refreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        expandedImagePath?.let { path ->
            FullscreenImageDialog(
                path = path,
                onDismiss = { expandedImagePath = null }
            )
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
                Button(onClick = onToggleBookmark) {
                    M3Text(text = if (item.isBookmarked) "しおり解除" else "しおり追加")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { openInXClient(context, item.permalink) }) {
                    M3Text(text = "X公式で開く")
                }
                Spacer(modifier = Modifier.width(8.dp))
                M3Text(
                    text = formatDate(item.createdAt),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            M3Text(text = item.authorName, style = MaterialTheme.typography.titleMedium)
            M3Text(
                text = "@${item.authorUsername}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Light
            )
            Spacer(modifier = Modifier.height(6.dp))
            LinkifiedPostText(text = item.text)
            Spacer(modifier = Modifier.height(8.dp))

            item.imagePaths.forEach { path ->
                LocalImage(
                    path = path,
                    onClick = { onImageTap(path) }
                )
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
        }
    }
}

@Composable
private fun LinkifiedPostText(text: String) {
    val context = LocalContext.current
    val linkRegex = Regex("https?://[A-Za-z0-9./?%&=+#:_~\\-]+")
    val annotated = buildAnnotatedString {
        var current = 0
        linkRegex.findAll(text).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            if (start > current) append(text.substring(current, start))
            val url = match.value
            pushStringAnnotation(tag = "URL", annotation = url)
            withStyle(
                style = SpanStyle(
                    color = Color(0xFF4EA3FF),
                    textDecoration = TextDecoration.Underline
                )
            ) { append(url) }
            pop()
            current = end
        }
        if (current < text.length) append(text.substring(current))
    }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge
    ) { offset ->
        annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
            .firstOrNull()
            ?.let { openInBrowser(context, it.item) }
    }
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onDismiss)
            )
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
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2f
                                    offsetX = 0f
                                    offsetY = 0f
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
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            }
                        }
                    }
                    .onSizeChanged { containerSize = it }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
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

private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}

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
            // Try next candidate.
        } catch (_: SecurityException) {
            // Try next candidate.
        }
    }

    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(permalink)))
}

private fun extractTweetId(permalink: String): String? {
    val regex = Regex("/status/(\\d+)")
    return regex.find(permalink)?.groupValues?.getOrNull(1)
}

private fun randomToken(bytes: Int): String {
    val random = ByteArray(bytes)
    SecureRandom().nextBytes(random)
    return Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private fun String.toCodeChallenge(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private const val PREFS_AUTH = "auth_tokens"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val PREFS_AUTH_FLOW = "oauth_flow"
private const val KEY_OAUTH_STATE = "oauth_state"
private const val KEY_OAUTH_VERIFIER = "oauth_verifier"
private const val AUTHORIZE_ENDPOINT = "https://x.com/i/oauth2/authorize"
private const val TOKEN_ENDPOINT = "https://api.x.com/2/oauth2/token"
private const val TAG_OAUTH = "OAuthFlow"
