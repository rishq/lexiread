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
        // Truncated: logcat drops very long messages, and a stack trace can
        // otherwise embed excerpts of the user's book text.
        CrashLogger.consumeLastCrash(this)?.let { crash ->
            Log.e("LexiReadApp", "Previous crash:\n${crash.take(MAX_LOGGED_CRASH_CHARS)}")
        }
        container = AppContainer(this)
        container.initialize()
    }

    private companion object {
        const val MAX_LOGGED_CRASH_CHARS = 4_000
    }
}
