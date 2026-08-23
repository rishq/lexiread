package com.lexiread.presentation.di

import android.content.Context
import androidx.room.Room
import com.lexiread.core.preferences.UserPreferencesManager
import com.lexiread.core.reader.BookImporter
import com.lexiread.core.reader.PaginationEngine
import com.lexiread.core.util.TTSHelper
import com.lexiread.data.local.AppDatabase
import com.lexiread.data.remote.RetrofitClient
import com.lexiread.data.source.GutendexBookSource
import com.lexiread.data.source.OpenLibraryBookSource
import com.lexiread.data.source.StandardEbooksBookSource
import com.lexiread.data.repository.AiRepositoryImpl
import com.lexiread.data.repository.BookRepositoryImpl
import com.lexiread.data.repository.DictionaryRepositoryImpl
import com.lexiread.data.repository.TranslationRepositoryImpl
import com.lexiread.data.repository.VocabularyRepositoryImpl
import com.lexiread.domain.repository.AiRepository
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.repository.DictionaryRepository
import com.lexiread.domain.repository.TranslationRepository
import com.lexiread.domain.repository.VocabularyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(private val context: Context) {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "lexiread_db"
        ).addMigrations(AppDatabase.MIGRATION_2_3)
            .build()
    }

    val userPreferencesManager: UserPreferencesManager by lazy {
        UserPreferencesManager(context.applicationContext)
    }

    val ttsHelper: TTSHelper by lazy {
        TTSHelper(context.applicationContext)
    }

    val bookImporter: BookImporter by lazy {
        BookImporter(context.applicationContext)
    }

    val paginationEngine: PaginationEngine by lazy {
        PaginationEngine(context.applicationContext)
    }

    val bookRepository: BookRepository by lazy {
        BookRepositoryImpl(
            bookDao = database.bookDao(),
            readingProgressDao = database.readingProgressDao(),
            bookmarkDao = database.bookmarkDao(),
            sources = listOf(
                GutendexBookSource(RetrofitClient.gutendexApi),
                OpenLibraryBookSource(RetrofitClient.openLibraryApi),
                StandardEbooksBookSource(RetrofitClient.standardEbooksApi, context)
            )
        )
    }

    val dictionaryRepository: DictionaryRepository by lazy {
        DictionaryRepositoryImpl(
            dictionaryApi = RetrofitClient.dictionaryApi,
            cacheDao = database.cacheDao()
        )
    }

    val translationRepository: TranslationRepository by lazy {
        TranslationRepositoryImpl(
            translationApi = RetrofitClient.translationApi,
            cacheDao = database.cacheDao()
        )
    }

    val vocabularyRepository: VocabularyRepository by lazy {
        VocabularyRepositoryImpl(
            savedWordDao = database.savedWordDao()
        )
    }

    val aiRepository: AiRepository by lazy {
        AiRepositoryImpl(
            geminiApi = RetrofitClient.geminiApi,
            openAiApi = RetrofitClient.openAiApi,
            claudeApi = RetrofitClient.claudeApi,
            deepSeekApi = RetrofitClient.deepSeekApi,
            cacheDao = database.cacheDao(),
            providerConfigProvider = {
                val prefs = userPreferencesManager
                val provider = prefs.aiProvider.first()
                val key = when (provider) {
                    com.lexiread.data.repository.AiProviders.CHATGPT -> prefs.openAiApiKey.first()
                    com.lexiread.data.repository.AiProviders.CLAUDE -> prefs.claudeApiKey.first()
                    com.lexiread.data.repository.AiProviders.DEEPSEEK -> prefs.deepSeekApiKey.first()
                    else -> prefs.geminiApiKey.first()
                }
                provider to key
            }
        )
    }

    fun initialize() {
        applicationScope.launch {
            // Purge expired cache entries once per app start.
            runCatching { database.cacheDao().deleteExpiredDictionaryCache(System.currentTimeMillis() - CACHE_TTL_MS) }
            runCatching { database.cacheDao().deleteExpiredTranslationCache(System.currentTimeMillis() - CACHE_TTL_MS) }
            runCatching { database.cacheDao().deleteExpiredAiExplanationCache(System.currentTimeMillis() - AI_CACHE_TTL_MS) }
        }
        applicationScope.launch {
            (bookRepository as BookRepositoryImpl).initializePreloadedBooks()
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val AI_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
