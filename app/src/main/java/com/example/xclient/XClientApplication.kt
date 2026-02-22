package com.example.xclient

import android.app.Application
import com.example.xclient.repository.TimelineRepository

class XClientApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val timelineRepository: TimelineRepository = TimelineRepository.create(application)
}
