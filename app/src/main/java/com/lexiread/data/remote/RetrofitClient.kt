package com.lexiread.data.remote

import com.lexiread.BuildConfig
import com.lexiread.data.remote.api.ClaudeApi
import com.lexiread.data.remote.api.DictionaryApi
import com.lexiread.data.remote.api.InternetArchiveApi
import com.lexiread.data.remote.api.StandardEbooksApi
import com.lexiread.data.remote.api.GeminiApi
import com.lexiread.data.remote.api.GutendexApi
import com.lexiread.data.remote.api.OpenAiChatApi
import com.lexiread.data.remote.api.TranslationApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

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

    val gutendexApi: GutendexApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://gutendex.com/")
            .client(downloadOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GutendexApi::class.java)
    }

    val standardEbooksApi: StandardEbooksApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://standardebooks.org/")
            .client(downloadOkHttpClient)
            .build()
            .create(StandardEbooksApi::class.java)
    }

    val internetArchiveApi: InternetArchiveApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://archive.org/")
            .client(downloadOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(InternetArchiveApi::class.java)
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

