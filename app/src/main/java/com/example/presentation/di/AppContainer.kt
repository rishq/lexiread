package com.example.presentation.di

import android.content.Context
import androidx.room.Room
import com.example.core.preferences.UserPreferencesManager
import com.example.core.util.TTSHelper
import com.example.data.local.AppDatabase
import com.example.data.remote.RetrofitClient
import com.example.data.repository.AiRepositoryImpl
import com.example.data.repository.BookRepositoryImpl
import com.example.data.repository.DictionaryRepositoryImpl
import com.example.data.repository.TranslationRepositoryImpl
import com.example.data.repository.VocabularyRepositoryImpl
import com.example.domain.repository.AiRepository
import com.example.domain.repository.BookRepository
import com.example.domain.repository.DictionaryRepository
import com.example.domain.repository.TranslationRepository
import com.example.domain.repository.VocabularyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(private val context: Context) {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "lexiread_db"
        ).fallbackToDestructiveMigration()
            .build()
    }

    val userPreferencesManager: UserPreferencesManager by lazy {
        UserPreferencesManager(context.applicationContext)
    }

    val ttsHelper: TTSHelper by lazy {
        TTSHelper(context.applicationContext)
    }

    val bookImporter: com.example.core.reader.BookImporter by lazy {
        com.example.core.reader.BookImporter(context.applicationContext)
    }

    val paginationEngine: com.example.core.reader.PaginationEngine by lazy {
        com.example.core.reader.PaginationEngine(context.applicationContext)
    }

    val bookRepository: BookRepository by lazy {
        val repo = BookRepositoryImpl(
            bookDao = database.bookDao(),
            readingProgressDao = database.readingProgressDao(),
            bookmarkDao = database.bookmarkDao(),
            gutendexApi = RetrofitClient.downloadGutendexApi,
            openLibraryApi = RetrofitClient.openLibraryApi
        )
        // Pre-initialize preloaded books in background with safe SupervisorJob
        applicationScope.launch {
            repo.initializePreloadedBooks()
        }
        repo
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
            cacheDao = database.cacheDao()
        )
    }
}
