package com.lexiread.data.remote

import android.content.Context
import com.lexiread.BuildConfig
import com.lexiread.data.remote.api.ClaudeApi
import com.lexiread.data.remote.api.DictionaryApi
import com.lexiread.data.remote.api.InternetArchiveApi
import com.lexiread.data.remote.api.MyLibApi
import com.lexiread.data.remote.api.PgaApi
import com.lexiread.data.remote.api.StandardEbooksApi
import com.lexiread.data.remote.api.GeminiApi
import com.lexiread.data.remote.api.OpenAiChatApi
import com.lexiread.data.remote.api.TranslationApi
import com.lexiread.data.remote.googlebooks.GoogleBooksApi
import com.lexiread.data.remote.gutendex.GutendexApi
import com.lexiread.data.remote.openlibrary.OpenLibraryApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private const val HTTP_CACHE_BYTES = 10L * 1024 * 1024
    private const val HTTP_CACHE_MAX_AGE_SECONDS = 60 * 60 * 6
    private const val CACHE_DIRECTORY = "http_cache"

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Volatile
    private var httpCache: Cache? = null
    private val cacheLock = Any()

    /**
     * Installs the shared OkHttp disk cache. Call once from
     * [android.app.Application.onCreate] before anything touches a service.
     *
     * The cache needs a directory, and an `object` cannot receive a Context in a
     * constructor, so installation is explicit rather than lazy. Every client
     * below picks the cache up whenever it is first built, which is always after
     * Application.onCreate.
     */
    fun installCache(context: Context) {
        if (httpCache != null) return
        synchronized(cacheLock) {
            if (httpCache == null) {
                httpCache = Cache(
                    File(context.applicationContext.cacheDir, CACHE_DIRECTORY),
                    HTTP_CACHE_BYTES
                )
            }
        }
    }

    /**
     * The catalogues return no `Cache-Control` header, so OkHttp would store
     * nothing and every scroll would re-hit the network. This adds one for small
     * JSON responses only: caching book files as well would blow the 10 MB budget
     * and evict the metadata we actually want to keep.
     */
    private class CatalogCacheInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val isJson = response.header("Content-Type").orEmpty().contains("json", ignoreCase = true)
            if (request.method != "GET" || !isJson || response.header("Cache-Control") != null) {
                return response
            }
            return response.newBuilder()
                .header("Cache-Control", "public, max-age=$HTTP_CACHE_MAX_AGE_SECONDS")
                .removeHeader("Pragma")
                .build()
        }
    }

    private fun OkHttpClient.Builder.applyCache(): OkHttpClient.Builder = apply {
        httpCache?.let { cache(it) }
    }

    /** Catalogue reads: cached, so a repeated query or a back-navigation is instant. */
    private val catalogOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(CatalogCacheInterceptor())
            .addInterceptor(HttpLoggingInterceptor { message ->
                val sanitized = message.replace(Regex("(?i)(key=)[^&\\s]+"), "$1[REDACTED]")
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("RetrofitClient", sanitized)
                }
            }.apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            })
            .applyCache()
            .build()
    }

    // Primary API Client (REST APIs), logging only in debug builds
    private val apiOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor { message ->
                // Mask API keys if present in URL or query params
                val sanitized = message.replace(Regex("(?i)(key=)[^&\\s]+"), "$1[REDACTED]")
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("RetrofitClient", sanitized)
                }
            }.apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            })
            .build()
    }

    // Dedicated File Download Client (No body logging to prevent leaks / OOM, longer timeouts)
    val downloadOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            })
            .build()
    }

    // --- Book catalogues ---

    val gutendexApi: GutendexApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://gutendex.com/")
            .client(catalogOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GutendexApi::class.java)
    }

    val openLibraryApi: OpenLibraryApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://openlibrary.org/")
            .client(catalogOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenLibraryApi::class.java)
    }

    val googleBooksApi: GoogleBooksApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .client(catalogOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GoogleBooksApi::class.java)
    }

    val standardEbooksApi: StandardEbooksApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://standardebooks.org/")
            .client(catalogOkHttpClient)
            .build()
            .create(StandardEbooksApi::class.java)
    }

    val internetArchiveApi: InternetArchiveApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://archive.org/")
            .client(catalogOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(InternetArchiveApi::class.java)
    }

    val pgaApi: PgaApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://gutenberg.net.au/")
            .client(catalogOkHttpClient)
            .build()
            .create(PgaApi::class.java)
    }

    /**
     * Scraped HTML, so no converter: the raw body goes straight to Jsoup. The
     * base URL only anchors relative links and is never requested directly.
     */
    val myLibApi: MyLibApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://${com.lexiread.data.source.MyLibConfig.DEFAULT_HOST}/")
            .client(catalogOkHttpClient)
            .build()
            .create(MyLibApi::class.java)
    }

    val dictionaryApi: DictionaryApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.dictionaryapi.dev/")
            .client(apiOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DictionaryApi::class.java)
    }

    val translationApi: TranslationApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.mymemory.translated.net/")
            .client(apiOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TranslationApi::class.java)
    }

    val geminiApi: GeminiApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(apiOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApi::class.java)
    }

    val openAiApi: OpenAiChatApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(apiOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenAiChatApi::class.java)
    }

    val deepSeekApi: OpenAiChatApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(apiOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenAiChatApi::class.java)
    }

    val claudeApi: ClaudeApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(apiOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ClaudeApi::class.java)
    }
}

