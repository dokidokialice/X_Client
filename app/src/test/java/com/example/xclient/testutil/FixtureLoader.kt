package com.example.xclient.testutil

import com.example.xclient.network.ListTweetsResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object FixtureLoader {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val responseAdapter = moshi.adapter(ListTweetsResponse::class.java)

    fun loadRaw(path: String): String {
        return checkNotNull(FixtureLoader::class.java.classLoader?.getResourceAsStream(path)) {
            "Fixture not found: $path"
        }.bufferedReader().use { it.readText() }
    }

    fun loadListTweetsResponse(fileName: String): ListTweetsResponse {
        val json = loadRaw("fixtures/x_api/$fileName")
        return checkNotNull(responseAdapter.fromJson(json)) {
            "Failed to parse fixture: $fileName"
        }
    }
}
