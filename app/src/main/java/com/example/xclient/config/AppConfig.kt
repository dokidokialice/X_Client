package com.example.xclient.config

import android.content.Context
import java.util.Properties

data class AppConfig(
    val listId: String,
    val accessToken: String,
    val refreshToken: String,
    val clientId: String,
    val authRedirectUri: String,
    val authScopes: String,
    val apiBaseUrl: String,
    val maxResults: Int,
    val offlineMode: Boolean
) {
    val canStartOAuthLogin: Boolean
        get() = clientId.isNotBlank() && authRedirectUri.isNotBlank()
}

object AppConfigLoader {
    private const val CONFIG_FILE = "x_config.properties"

    fun load(context: Context): AppConfig {
        val props = Properties()
        context.assets.open(CONFIG_FILE).use { input -> props.load(input) }
        return fromProperties(props)
    }

    internal fun fromProperties(props: Properties): AppConfig {
        val listId = props.cleanProperty("list_id")
        val accessToken = props.cleanProperty("access_token")
        val refreshToken = props.cleanProperty("refresh_token")
        val clientId = props.cleanProperty("client_id")
        val authRedirectUri = props.cleanProperty("auth_redirect_uri")
            .ifBlank { "xclient://oauth/callback" }
        val authScopes = props.cleanProperty("auth_scopes")
            .ifBlank { "tweet.read users.read list.read offline.access" }
        val apiBaseUrl = props.cleanProperty("api_base_url")
        val maxResults = props.getProperty("max_results")?.toIntOrNull() ?: 50
        val offlineMode = props.getProperty("offline_mode")
            ?.trim()
            ?.lowercase()
            ?.let { it == "true" || it == "1" || it == "yes" || it == "on" }
            ?: false

        require(listId.isNotBlank()) { "list_id is required in $CONFIG_FILE" }
        require(apiBaseUrl.isNotBlank()) { "api_base_url is required in $CONFIG_FILE" }
        if (!offlineMode) {
            require(accessToken.isNotBlank() || clientId.isNotBlank()) {
                "Either access_token or client_id is required in $CONFIG_FILE"
            }
        }
        if (refreshToken.isNotBlank()) {
            require(clientId.isNotBlank()) { "client_id is required when refresh_token is set in $CONFIG_FILE" }
        }

        return AppConfig(
            listId = listId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            clientId = clientId,
            authRedirectUri = authRedirectUri,
            authScopes = authScopes,
            apiBaseUrl = if (apiBaseUrl.endsWith('/')) apiBaseUrl else "$apiBaseUrl/",
            maxResults = maxResults.coerceIn(10, 100),
            offlineMode = offlineMode
        )
    }
}

private fun Properties.cleanProperty(name: String): String {
    val value = getProperty(name)?.trim().orEmpty()
    return if (value.isPlaceholderValue()) "" else value
}

private fun String.isPlaceholderValue(): Boolean {
    return uppercase().startsWith("YOUR_")
}
