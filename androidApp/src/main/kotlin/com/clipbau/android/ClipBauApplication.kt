package com.clipbau.android

import android.app.Application
import android.content.Context
import com.clipbau.android.downloader.AndroidYtDlpEngine
import com.clipbau.core.domain.DownloadEngine

class ClipBauApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val engine: DownloadEngine = AndroidYtDlpEngine(appContext)
}
