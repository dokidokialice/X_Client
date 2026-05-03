package com.example.xclient.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.xclient.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

enum class AuthPhase { READY, NEEDS_LOGIN, WAITING_CALLBACK, EXCHANGING, BLOCKED }

data class AuthGateState(
    val phase: AuthPhase = AuthPhase.READY,
    val message: String? = null
)

private data class OAuthTokenResponse(val accessToken: String, val refreshToken: String)
private data class OAuthCallbackPayload(val code: String, val state: String, val error: String?)
private data class OAuthTokenExchangeResult(val token: OAuthTokenResponse?, val errorMessage: String?)

class OAuthManager(
    private val context: Context,
    private val config: AppConfig,
    private val scope: CoroutineScope,
    private val onStartActivity: (Intent) -> Unit
) {
    private val _authState = MutableStateFlow(AuthGateState())
    val authState: StateFlow<AuthGateState> = _authState.asStateFlow()

    private var loopbackJob: Job? = null

    // トークン保存用: EncryptedSharedPreferences で暗号化
    private val authPrefs: SharedPreferences by lazy { createEncryptedPrefs(context, PREFS_AUTH) }

    // OAuth フロー中の一時データ (state/verifier) は平文で問題なし
    private val flowPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_AUTH_FLOW, Context.MODE_PRIVATE)
    }

    fun init() {
        if (config.offlineMode) {
            _authState.value = AuthGateState(phase = AuthPhase.READY)
            return
        }
        val hasToken = !authPrefs.getString(KEY_ACCESS_TOKEN, "").isNullOrBlank()
            || config.accessToken.isNotBlank()
        _authState.value = when {
            hasToken -> AuthGateState(phase = AuthPhase.READY)
            config.canStartOAuthLogin -> AuthGateState(phase = AuthPhase.NEEDS_LOGIN)
            else -> AuthGateState(
                phase = AuthPhase.BLOCKED,
                message = "access_token が未設定です。OAuth を使う場合は client_id を設定してください。"
            )
        }
    }

    fun handleAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.toString().startsWith(config.authRedirectUri)) return
        val expectedState = flowPrefs.getString(KEY_OAUTH_STATE, "").orEmpty()
        val verifier = flowPrefs.getString(KEY_OAUTH_VERIFIER, "").orEmpty()
        val callback = OAuthCallbackPayload(
            code = data.getQueryParameter("code").orEmpty(),
            state = data.getQueryParameter("state").orEmpty(),
            error = data.getQueryParameter("error")
        )
        processOAuthCallback(callback, expectedState, verifier)
    }

    fun startLogin() {
        if (config.offlineMode) {
            _authState.value = AuthGateState(phase = AuthPhase.READY)
            return
        }
        if (_authState.value.phase !in setOf(AuthPhase.NEEDS_LOGIN, AuthPhase.BLOCKED, AuthPhase.READY)) return
        if (!config.canStartOAuthLogin) {
            _authState.value = AuthGateState(
                phase = AuthPhase.BLOCKED,
                message = "OAuth 設定が不足しています（client_id / auth_redirect_uri）"
            )
            return
        }
        val redirectUri = Uri.parse(config.authRedirectUri)
        val host = redirectUri.host.orEmpty()
        val isLoopback = redirectUri.scheme == "http" && (host == "127.0.0.1" || host == "localhost")
        val isAppCallback = redirectUri.scheme == "xclient" && host == "oauth"
        val port = redirectUri.port
        if (!isLoopback && !isAppCallback) {
            _authState.value = AuthGateState(
                phase = AuthPhase.BLOCKED,
                message = "auth_redirect_uri は xclient://oauth/callback または http://127.0.0.1:<port>/callback 形式で設定してください。"
            )
            return
        }
        if (isLoopback && port <= 0) {
            _authState.value = AuthGateState(
                phase = AuthPhase.BLOCKED,
                message = "loopback を使う場合の auth_redirect_uri は http://127.0.0.1:<port>/callback 形式で設定してください。"
            )
            return
        }
        val redirectPath = redirectUri.path.orEmpty().ifBlank { "/" }

        val state = randomToken(24)
        val verifier = randomToken(64)
        val challenge = verifier.toCodeChallenge()
        flowPrefs.edit()
            .putString(KEY_OAUTH_STATE, state)
            .putString(KEY_OAUTH_VERIFIER, verifier)
            .apply()

        val authUri = Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("redirect_uri", config.authRedirectUri)
            .appendQueryParameter("scope", config.authScopes)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        Log.i(TAG, "Authorize URL redirect_uri=${config.authRedirectUri} scopes=${config.authScopes} hasState=${state.isNotBlank()} hasChallenge=${challenge.isNotBlank()}")

        loopbackJob?.cancel()
        if (isLoopback) {
            loopbackJob = scope.launch {
                Log.i(TAG, "Loopback listener starting host=$host port=$port path=$redirectPath")
                val callback = withContext(Dispatchers.IO) {
                    awaitLoopbackCallback(host = host, port = port, expectedPath = redirectPath)
                }
                if (callback == null) {
                    Log.e(TAG, "Loopback callback not received")
                    _authState.value = AuthGateState(
                        phase = AuthPhase.BLOCKED,
                        message = "OAuth コールバックを受信できませんでした。redirect_uri とポート設定を確認してください。"
                    )
                    return@launch
                }
                Log.i(TAG, "Loopback callback received state=${callback.state}")
                processOAuthCallback(callback = callback, expectedState = state, verifier = verifier)
            }
        }

        val openResult = buildBrowserIntents(authUri)
            .firstNotNullOfOrNull { intent ->
                runCatching { onStartActivity(intent) }
                    .onFailure { throwable -> Log.w(TAG, "OAuth browser intent failed package=${intent.`package`}", throwable) }
                    .takeIf { it.isSuccess }
            } ?: Result.failure(IllegalStateException("No browser could open OAuth authorize URL"))
        openResult
            .onSuccess {
                Log.i(TAG, "Browser opened for OAuth authorize URL")
                _authState.value = AuthGateState(
                    phase = AuthPhase.WAITING_CALLBACK,
                    message = if (isLoopback) {
                        "X の認証をブラウザで完了してください。完了後に自動でトークン取得します。"
                    } else {
                        "X の認証をブラウザで完了してください。完了後にアプリへ戻ってトークン取得します。"
                    }
                )
            }
            .onFailure { throwable ->
                Log.e(TAG, "Failed to open browser", throwable)
                loopbackJob?.cancel()
                _authState.value = AuthGateState(
                    phase = AuthPhase.BLOCKED,
                    message = "認証ページを開けませんでした: ${throwable.message}"
                )
            }
    }

    private fun processOAuthCallback(
        callback: OAuthCallbackPayload,
        expectedState: String,
        verifier: String
    ) {
        Log.i(TAG, "Processing callback hasCode=${callback.code.isNotBlank()} stateMatch=${callback.state == expectedState}")
        if (!callback.error.isNullOrBlank()) {
            _authState.value = AuthGateState(phase = AuthPhase.BLOCKED, message = "OAuth 認証に失敗しました: ${callback.error}")
            return
        }
        if (callback.code.isBlank() || verifier.isBlank() || callback.state.isBlank() || callback.state != expectedState) {
            _authState.value = AuthGateState(phase = AuthPhase.BLOCKED, message = "OAuth コールバックの検証に失敗しました。")
            return
        }

        _authState.value = AuthGateState(phase = AuthPhase.EXCHANGING, message = "トークン交換中...")
        scope.launch {
            Log.i(TAG, "Exchanging token with X")
            val tokenResult = withContext(Dispatchers.IO) { exchangeToken(callback.code, verifier) }
            if (tokenResult == null) {
                Log.e(TAG, "Token exchange failed: network or unexpected exception")
                _authState.value = AuthGateState(phase = AuthPhase.BLOCKED, message = "アクセストークンの取得に失敗しました（通信失敗）。")
                return@launch
            }
            if (tokenResult.errorMessage != null) {
                Log.e(TAG, "Token exchange error: ${tokenResult.errorMessage}")
                _authState.value = AuthGateState(phase = AuthPhase.BLOCKED, message = tokenResult.errorMessage)
                return@launch
            }
            val tokenResponse = tokenResult.token ?: run {
                _authState.value = AuthGateState(phase = AuthPhase.BLOCKED, message = "アクセストークンの取得に失敗しました。")
                return@launch
            }
            authPrefs.edit()
                .putString(KEY_ACCESS_TOKEN, tokenResponse.accessToken)
                .putString(KEY_REFRESH_TOKEN, tokenResponse.refreshToken)
                .apply()
            flowPrefs.edit().remove(KEY_OAUTH_STATE).remove(KEY_OAUTH_VERIFIER).apply()
            Log.i(TAG, "OAuth completed. Token stored.")
            _authState.value = AuthGateState(phase = AuthPhase.READY)
        }
    }

    private fun awaitLoopbackCallback(host: String, port: Int, expectedPath: String): OAuthCallbackPayload? {
        return try {
            ServerSocket(port, 1, InetAddress.getByName(host)).use { server ->
                server.soTimeout = 180_000
                Log.i(TAG, "Listening on $host:$port")
                server.accept().use { socket ->
                    Log.i(TAG, "Loopback connection accepted from ${socket.inetAddress.hostAddress}")
                    socket.soTimeout = 15_000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val requestLine = reader.readLine().orEmpty()
                    Log.i(TAG, "Loopback requestLine=$requestLine")
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
            Log.e(TAG, "Loopback wait timeout")
            null
        } catch (t: Throwable) {
            Log.e(TAG, "Loopback callback exception", t)
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
            .add("redirect_uri", config.authRedirectUri)
            .add("client_id", config.clientId)
            .add("code_verifier", verifier)
            .build()
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(form).build()
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
                OAuthTokenExchangeResult(
                    token = OAuthTokenResponse(
                        accessToken = accessToken,
                        refreshToken = json.optString("refresh_token").trim()
                    ),
                    errorMessage = null
                )
            }
        }.onFailure { t -> Log.e(TAG, "Token exchange exception", t) }.getOrNull()
    }

    private fun buildBrowserIntents(authUri: Uri): List<Intent> {
        val baseIntent = Intent(Intent.ACTION_VIEW, authUri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val browserIntents = PREFERRED_BROWSER_PACKAGES.map { packageName ->
            Intent(baseIntent).setPackage(packageName)
        }
        return browserIntents + Intent.createChooser(baseIntent, "Open OAuth in browser")
    }

    companion object {
        const val PREFS_AUTH = "auth_tokens"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val PREFS_AUTH_FLOW = "oauth_flow"
        private const val KEY_OAUTH_STATE = "oauth_state"
        private const val KEY_OAUTH_VERIFIER = "oauth_verifier"
        private const val AUTHORIZE_ENDPOINT = "https://x.com/i/oauth2/authorize"
        private const val TOKEN_ENDPOINT = "https://api.x.com/2/oauth2/token"
        private const val TAG = "OAuthFlow"
        private val PREFERRED_BROWSER_PACKAGES = listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.opera.browser",
            "com.sec.android.app.sbrowser",
            "com.android.browser"
        )
    }
}

/** モジュール内で共有する EncryptedSharedPreferences ファクトリ */
internal fun createEncryptedPrefs(context: Context, name: String): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        name,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
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
