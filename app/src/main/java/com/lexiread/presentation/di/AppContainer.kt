package com.lexiread.presentation.di

import android.content.Context
import androidx.room.Room
import com.lexiread.BuildConfig
import com.lexiread.core.preferences.UserPreferencesManager
import com.lexiread.core.reader.BookImporter
import com.lexiread.core.reader.PaginationEngine
import com.lexiread.core.util.TTSHelper
import com.lexiread.data.local.AppDatabase
import com.lexiread.data.remote.RetrofitClient
import com.lexiread.data.repository.BooksRepositoryImpl
import com.lexiread.data.source.GutendexBookSource
import com.lexiread.data.source.InternetArchiveBookSource
import com.lexiread.data.source.OpenLibraryBookSource
import com.lexiread.data.source.StandardEbooksBookSource
import com.lexiread.data.repository.AiRepositoryImpl
import com.lexiread.data.repository.BookRepositoryImpl
import com.lexiread.data.repository.DictionaryRepositoryImpl
import com.lexiread.data.repository.TranslationRepositoryImpl
import com.lexiread.data.repository.VocabularyRepositoryImpl
import com.lexiread.domain.repository.AiRepository
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.repository.BookSource
import com.lexiread.domain.repository.BooksRepository
import com.lexiread.domain.repository.DictionaryRepository
import com.lexiread.domain.repository.TranslationRepository
import com.lexiread.domain.repository.VocabularyRepository
import com.lexiread.domain.usecase.GetBookDetailsUseCase
import com.lexiread.domain.usecase.GetBooksByCategoryUseCase
import com.lexiread.domain.usecase.GetPopularBooksUseCase
import com.lexiread.domain.usecase.OpenBookForReadingUseCase
import com.lexiread.domain.usecase.SearchBooksUseCase
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
        ).addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
    }

    val userPreferencesManager: UserPreferencesManager by lazy {
        UserPreferencesManager(context.applicationContext)
    }

    val ttsHelper: TTSHelper by lazy {
        TTSHelper(context.applicationContext)
    }

    val bookImporter: BookImporter by lazy {
        BookImporter(context.applicationContext, database.chapterDao())
    }

    val paginationEngine: PaginationEngine by lazy {
        PaginationEngine(context.applicationContext)
    }

    /**
     * One list, shared by both repositories: the legacy downloaders behind
     * [BookRepository] and the catalogue behind [BooksRepository]. Keeping a
     * single instance avoids downloading a book through one object while the
     * other holds a stale path.
     */
    private val bookSources: List<BookSource> by lazy {
        listOf(
            GutendexBookSource(RetrofitClient.gutendexApi, context),
            OpenLibraryBookSource(RetrofitClient.openLibraryApi),
            InternetArchiveBookSource(RetrofitClient.internetArchiveApi, context),
            StandardEbooksBookSource(RetrofitClient.standardEbooksApi, context)
        )
    }

    val bookRepository: BookRepository by lazy {
        BookRepositoryImpl(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            readingProgressDao = database.readingProgressDao(),
            bookmarkDao = database.bookmarkDao(),
            sources = bookSources
        )
    }

    val booksRepository: BooksRepository by lazy {
        BooksRepositoryImpl(
            gutendexApi = RetrofitClient.gutendexApi,
            openLibraryApi = RetrofitClient.openLibraryApi,
            googleBooksApi = RetrofitClient.googleBooksApi,
            internetArchiveApi = RetrofitClient.internetArchiveApi,
            standardEbooksApi = RetrofitClient.standardEbooksApi,
            catalogCacheDao = database.catalogCacheDao(),
            bookRepository = bookRepository,
            sources = bookSources,
            // Anonymous Google Books access is allowed; a key only raises the quota.
            googleBooksApiKey = BuildConfig.GOOGLE_BOOKS_API_KEY.takeIf { it.isNotBlank() }
        )
    }

    val searchBooksUseCase: SearchBooksUseCase by lazy { SearchBooksUseCase(booksRepository) }
    val getPopularBooksUseCase: GetPopularBooksUseCase by lazy { GetPopularBooksUseCase(booksRepository) }
    val getBooksByCategoryUseCase: GetBooksByCategoryUseCase by lazy { GetBooksByCategoryUseCase(booksRepository) }
    val getBookDetailsUseCase: GetBookDetailsUseCase by lazy { GetBookDetailsUseCase(booksRepository) }
    val openBookForReadingUseCase: OpenBookForReadingUseCase by lazy { OpenBookForReadingUseCase(booksRepository) }

    // Reader & vocabulary use cases
    val getReadingProgressUseCase: com.lexiread.domain.usecase.GetReadingProgressUseCase by lazy {
        com.lexiread.domain.usecase.GetReadingProgressUseCase(bookRepository)
    }
    val saveReadingProgressUseCase: com.lexiread.domain.usecase.SaveReadingProgressUseCase by lazy {
        com.lexiread.domain.usecase.SaveReadingProgressUseCase(bookRepository)
    }
    val getDueWordsUseCase: com.lexiread.domain.usecase.GetDueWordsUseCase by lazy {
        com.lexiread.domain.usecase.GetDueWordsUseCase(vocabularyRepository)
    }
    val getDueWordCountUseCase: com.lexiread.domain.usecase.GetDueWordCountUseCase by lazy {
        com.lexiread.domain.usecase.GetDueWordCountUseCase(vocabularyRepository)
    }
    val reviewWordUseCase: com.lexiread.domain.usecase.ReviewWordUseCase by lazy {
        com.lexiread.domain.usecase.ReviewWordUseCase(vocabularyRepository)
    }
    val saveWordUseCase: com.lexiread.domain.usecase.SaveWordUseCase by lazy {
        com.lexiread.domain.usecase.SaveWordUseCase(vocabularyRepository)
    }
    val isWordSavedUseCase: com.lexiread.domain.usecase.IsWordSavedUseCase by lazy {
        com.lexiread.domain.usecase.IsWordSavedUseCase(vocabularyRepository)
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
            cacheDao = database.cacheDao(),
            geminiApi = RetrofitClient.geminiApi,
            openAiApi = RetrofitClient.openAiApi,
            claudeApi = RetrofitClient.claudeApi,
            deepSeekApi = RetrofitClient.deepSeekApi,
            providerConfigProvider = aiProviderConfig
        )
    }

    val vocabularyRepository: VocabularyRepository by lazy {
        VocabularyRepositoryImpl(
            savedWordDao = database.savedWordDao()
        )
    }

    private val aiProviderConfig: suspend () -> Pair<String, String> = {
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

    val aiRepository: AiRepository by lazy {
        AiRepositoryImpl(
            geminiApi = RetrofitClient.geminiApi,
            openAiApi = RetrofitClient.openAiApi,
            claudeApi = RetrofitClient.claudeApi,
            deepSeekApi = RetrofitClient.deepSeekApi,
            cacheDao = database.cacheDao(),
            providerConfigProvider = aiProviderConfig
        )
    }

    fun initialize() {
        applicationScope.launch {
            // Purge expired cache entries once per app start.
            runCatching { database.cacheDao().deleteExpiredDictionaryCache(System.currentTimeMillis() - CACHE_TTL_MS) }
            runCatching { database.cacheDao().deleteExpiredTranslationCache(System.currentTimeMillis() - CACHE_TTL_MS) }
            runCatching { database.cacheDao().deleteExpiredAiExplanationCache(System.currentTimeMillis() - AI_CACHE_TTL_MS) }
            // Stale catalogue pages still serve as offline fallback, but they
            // must not accumulate forever.
            runCatching { database.catalogCacheDao().deleteExpired(System.currentTimeMillis() - CACHE_TTL_MS) }
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
