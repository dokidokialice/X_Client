package com.example.xclient.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class AppConfigLoaderTest {

    @Test
    fun fromProperties_normalizesBaseUrlAndClampsMaxResults() {
        val props = Properties().apply {
            setProperty("list_id", "12345")
            setProperty("access_token", "token")
            setProperty("api_base_url", "https://api.x.com")
            setProperty("max_results", "500")
        }

        val config = AppConfigLoader.fromProperties(props)

        assertEquals("https://api.x.com/", config.apiBaseUrl)
        assertEquals(100, config.maxResults)
    }

    @Test
    fun fromProperties_usesDefaultMaxResultsWhenMissing() {
        val props = Properties().apply {
            setProperty("list_id", "12345")
            setProperty("access_token", "token")
            setProperty("api_base_url", "https://api.x.com/")
        }

        val config = AppConfigLoader.fromProperties(props)

        assertEquals(50, config.maxResults)
    }

    @Test
    fun fromProperties_throwsWhenRequiredFieldsMissing() {
        val props = Properties().apply {
            setProperty("api_base_url", "https://api.x.com/")
        }

        val error = runCatching { AppConfigLoader.fromProperties(props) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
