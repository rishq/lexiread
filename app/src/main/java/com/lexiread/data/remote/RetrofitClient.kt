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
    private const val MAX_REDIRECTS = 3

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

    /**
     * Which allow-list a redirect hop is checked against.
     *
     * There is more than one because the app fetches more than one kind of thing:
     * book files, cover images, and API responses. Reusing the widest list for all
     * three would let an API or image redirect land on a host that is only trusted
     * for book downloads.
     */
    private enum class RedirectPolicy {
        /** Book downloads and catalogue reads: the download + image allow-lists. */
        TRUSTED_HOSTS,

        /** P2-1: API clients (OpenAI/Gemini/...) never reuse the book allow-list. */
        SAME_HOST_ONLY,

        /**
         * Cover images. Mirrors the image allow-list
         * [com.lexiread.core.util.CoverUrls] applies to the entry URL, including
         * hosts registered at runtime, so a redirect cannot land somewhere the
         * entry check would have refused.
         */
        IMAGE_HOSTS
    }

    /**
     * Follows redirects manually so every hop can be re-validated.
     *
     * OkHttp follows redirects on its own, which means [com.lexiread.core.util.UrlValidator]
     * would only ever see the first URL. An open redirect on an allow-listed host
     * would then make the app fetch from an arbitrary host and store the response
     * as a book file. Automatic redirects are therefore disabled on every client
     * and this interceptor re-checks each `Location` before following it.
     */
    private class RedirectGuard(private val policy: RedirectPolicy) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            var response = chain.proceed(request)
            var hops = 0
            while (response.isRedirect) {
                if (hops >= MAX_REDIRECTS) {
                    response.close()
                    throw java.io.IOException("Too many redirects for ${request.url}")
                }
                val location = response.header("Location")
                val target = location?.let { request.url.resolve(it) }
                if (target == null) {
                    return response
                }
                when (policy) {
                    RedirectPolicy.SAME_HOST_ONLY ->
                        com.lexiread.core.util.UrlValidator.requireSameHostRedirect(
                            request.url.toString(),
                            target.toString()
                        )

                    RedirectPolicy.IMAGE_HOSTS -> {
                        val fromHost = request.url.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() }
                        val extra = if (fromHost != null) setOf(fromHost) else emptySet()
                        com.lexiread.core.util.UrlValidator.requireTrustedImageRedirect(
                            request.url.toString(),
                            target.toString(),
                            extra
                        )
                    }

                    RedirectPolicy.TRUSTED_HOSTS ->
                        com.lexiread.core.util.UrlValidator.requireTrustedRedirect(
                            request.url.toString(),
                            target.toString()
                        )
                }
                response.close()
                request = request.newBuilder().url(target).build()
                response = chain.proceed(request)
                hops++
            }
            return response
        }
    }

    private fun OkHttpClient.Builder.applyCache(): OkHttpClient.Builder = apply {
        httpCache?.let { cache(it) }
    }

    /** Disables automatic redirects and installs the guard that re-validates them. */
    private fun OkHttpClient.Builder.applyRedirectGuard(
        policy: RedirectPolicy = RedirectPolicy.TRUSTED_HOSTS
    ): OkHttpClient.Builder = apply {
        followRedirects(false)
        followSslRedirects(false)
        addInterceptor(RedirectGuard(policy))
    }

    /** P2-1: API traffic follows same-host redirects only, never the book allow-list. */
    private fun OkHttpClient.Builder.applyApiRedirectGuard(): OkHttpClient.Builder =
        applyRedirectGuard(RedirectPolicy.SAME_HOST_ONLY)

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
            .applyRedirectGuard()
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
            .applyApiRedirectGuard()
            .build()
    }

    /**
     * Cover images, fetched by Coil.
     *
     * [com.lexiread.core.util.CoverUrls] allow-lists the URL before it reaches
     * Coil, but that only covers the URL the caller supplied: the request itself
     * can be answered with a redirect, and Coil's default client would follow it
     * wherever it points. This client carries the same per-hop guard the
     * catalogue clients use, so every `Location` is re-checked against the image
     * allow-list before it is followed.
     *
     * The shared HTTP cache is deliberately not attached. Image hosts do send
     * cache headers, so caching covers here would fill the 10 MB budget the
     * catalogue JSON needs; Coil keeps its own disk cache.
     */
    val imageOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .applyRedirectGuard(RedirectPolicy.IMAGE_HOSTS)
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

