package com.example.xclient.config

import android.content.Context
import java.util.Properties

data class AppConfig(
    val listId: String,
    val accessToken: String,
    val apiBaseUrl: String,
    val maxResults: Int
)

object AppConfigLoader {
    private const val CONFIG_FILE = "x_config.properties"

    fun load(context: Context): AppConfig {
        val props = Properties()
        context.assets.open(CONFIG_FILE).use { input -> props.load(input) }

        val listId = props.getProperty("list_id")?.trim().orEmpty()
        val accessToken = props.getProperty("access_token")?.trim().orEmpty()
        val apiBaseUrl = props.getProperty("api_base_url")?.trim().orEmpty()
        val maxResults = props.getProperty("max_results")?.toIntOrNull() ?: 50

        require(listId.isNotBlank()) { "list_id is required in $CONFIG_FILE" }
        require(accessToken.isNotBlank()) { "access_token is required in $CONFIG_FILE" }
        require(apiBaseUrl.isNotBlank()) { "api_base_url is required in $CONFIG_FILE" }

        return AppConfig(
            listId = listId,
            accessToken = accessToken,
            apiBaseUrl = if (apiBaseUrl.endsWith('/')) apiBaseUrl else "$apiBaseUrl/",
            maxResults = maxResults.coerceIn(10, 100)
        )
    }
}
