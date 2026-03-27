package com.openclaw.musicworker.data

import android.content.Context
import com.openclaw.musicworker.data.api.MusicApiClient
import com.openclaw.musicworker.data.settings.AppSettingsStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore = AppSettingsStore(appContext)
    private val apiClient = MusicApiClient(settingsStore)
    val repository = MusicRepository(context = appContext, apiClient = apiClient, settingsStore = settingsStore)
}
