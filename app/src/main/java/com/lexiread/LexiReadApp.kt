package com.lexiread

import android.app.Application
import android.util.Log
import com.lexiread.core.util.CrashLogger
import com.lexiread.presentation.di.AppContainer

class LexiReadApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // Surface the previous crash in logcat so it is easy to report.
        CrashLogger.consumeLastCrash(this)?.let {
            Log.e("LexiReadApp", "Previous crash:\n$it")
        }
        container = AppContainer(this)
        container.initialize()
    }
}
