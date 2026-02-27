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
        val listId = props.getProperty("list_id")?.trim().orEmpty()
        val accessToken = props.getProperty("access_token")?.trim().orEmpty()
        val refreshToken = props.getProperty("refresh_token")?.trim().orEmpty()
        val clientId = props.getProperty("client_id")?.trim().orEmpty()
        val authRedirectUri = props.getProperty("auth_redirect_uri")
            ?.trim()
            .orEmpty()
            .ifBlank { "http://127.0.0.1:8080/callback" }
        val authScopes = props.getProperty("auth_scopes")
            ?.trim()
            .orEmpty()
            .ifBlank { "tweet.read users.read list.read offline.access" }
        val apiBaseUrl = props.getProperty("api_base_url")?.trim().orEmpty()
        val maxResults = props.getProperty("max_results")?.toIntOrNull() ?: 50
        val offlineMode = props.getProperty("offline_mode")
            ?.trim()
            ?.lowercase()
            ?.let { it == "true" || it == "1" || it == "yes" || it == "on" }
            ?: false

        require(listId.isNotBlank()) { "list_id is required in $CONFIG_FILE" }
        require(apiBaseUrl.isNotBlank()) { "api_base_url is required in $CONFIG_FILE" }
        require(accessToken.isNotBlank() || clientId.isNotBlank()) {
            "Either access_token or client_id is required in $CONFIG_FILE"
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
