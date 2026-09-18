package com.lexiread

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.lexiread.core.util.CrashLogger
import com.lexiread.data.remote.RetrofitClient
import com.lexiread.presentation.di.AppContainer

class LexiReadApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    /**
     * The image loader every `AsyncImage` call resolves to.
     *
     * `CoverUrls.sanitize` checks the URL a screen hands to Coil, but that is the
     * *entry* URL only: the request can be answered with a redirect, and Coil's
     * default client would follow it to any host, so an open redirect on an
     * allow-listed image host would turn a validated cover URL into an arbitrary
     * fetch. Routing Coil through [RetrofitClient.imageOkHttpClient] re-validates
     * each hop against the image allow-list, exactly as the catalogue clients do.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(RetrofitClient.imageOkHttpClient)
        .build()

    override fun onCreate() {
        super.onCreate()
        // Must happen before any service is built, otherwise the catalogue
        // clients are constructed without a disk cache and never retry.
        RetrofitClient.installCache(this)
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
