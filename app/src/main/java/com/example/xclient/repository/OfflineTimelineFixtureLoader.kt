package com.example.xclient.repository

import android.content.Context
import com.example.xclient.network.ListTweetsResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

internal object OfflineTimelineFixtureLoader {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(ListTweetsResponse::class.java)

    private val fixtureFiles = listOf(
        "fixtures/x_api/large_delta_200_page_1.json",
        "fixtures/x_api/large_delta_200_page_2.json"
    )

    fun load(context: Context): List<ListTweetsResponse> {
        return fixtureFiles.map { path ->
            val json = context.assets.open(path).bufferedReader().use { it.readText() }
            checkNotNull(adapter.fromJson(json)) {
                "Failed to parse offline fixture: $path"
            }
        }
    }

    fun tweetIds(context: Context): List<String> {
        return load(context).flatMap { response -> response.data.orEmpty().map { it.id } }
    }
}
