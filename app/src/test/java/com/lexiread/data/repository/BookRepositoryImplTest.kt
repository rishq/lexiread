package com.lexiread.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lexiread.data.local.AppDatabase
import com.lexiread.data.local.dao.CacheDao
import com.lexiread.data.local.dao.HighlightDao
import com.lexiread.data.local.entity.AiExplanationEntity
import com.lexiread.data.local.entity.BookEntity
import com.lexiread.data.local.entity.BookmarkEntity
import com.lexiread.data.local.entity.ChapterEntity
import com.lexiread.data.local.entity.DictionaryCacheEntity
import com.lexiread.data.local.entity.HighlightEntity
import com.lexiread.data.local.entity.ReadingProgressEntity
import com.lexiread.data.local.entity.TranslationCacheEntity
import com.lexiread.data.remote.api.ClaudeApi
import com.lexiread.data.remote.api.DictionaryApi
import com.lexiread.data.remote.api.GeminiApi
import com.lexiread.data.remote.api.OpenAiChatApi
import com.lexiread.data.remote.api.TranslationApi
import com.lexiread.data.remote.dto.ClaudeMessagesRequestDto
import com.lexiread.data.remote.dto.ClaudeMessagesResponseDto
import com.lexiread.data.remote.dto.DefinitionDto
import com.lexiread.data.remote.dto.DictionaryDto
import com.lexiread.data.remote.dto.GeminiCandidateDto
import com.lexiread.data.remote.dto.GeminiContentDto
import com.lexiread.data.remote.dto.GeminiPartDto
import com.lexiread.data.remote.dto.GeminiRequestDto
import com.lexiread.data.remote.dto.GeminiResponseDto
import com.lexiread.data.remote.dto.MeaningDto
import com.lexiread.data.remote.dto.MyMemoryData
import com.lexiread.data.remote.dto.MyMemoryResponse
import com.lexiread.data.remote.dto.OpenAiChatRequestDto
import com.lexiread.data.remote.dto.OpenAiChatResponseDto
import com.lexiread.data.remote.dto.PhoneticDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookRepositoryImplTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repository(highlightDao: HighlightDao = db.highlightDao()) = BookRepositoryImpl(
        db, db.bookDao(), db.chapterDao(), db.readingProgressDao(),
        db.bookmarkDao(), highlightDao, emptyList()
    )

    private fun testBook(id: String, filePath: String? = null, coverUrl: String? = null) = BookEntity(
        id = id,
        title = "Title $id",
        author = "Author",
        coverUrl = coverUrl,
        description = null,
        filePath = filePath,
        format = "TXT",
        language = "en",
        subjects = "",
        isFavorite = false,
        isSaved = true,
        isFinished = false,
        isImported = false,
        addedTimestamp = 1L
    )

    // --- BUG-11: deleteBook transaction --------------------------------------

    @Test
    fun `deleteBook removes dependents then the book and finally the file`() = runBlocking {
        val file = java.io.File.createTempFile("lexiread-book", ".txt")
        file.writeText("content")
        val id = "local_book"
        db.bookDao().insertBook(testBook(id, filePath = file.absolutePath))
        db.chapterDao().insertChapters(listOf(ChapterEntity(bookId = id, title = "c", content = "x", chapterIndex = 0)))
        db.readingProgressDao().saveProgress(
            ReadingProgressEntity(bookId = id, scrollOffset = 0, currentChapter = 0, totalLength = 1, percentCompleted = 0f, lastReadTimestamp = 1L)
        )
        db.bookmarkDao().insertBookmark(BookmarkEntity(bookId = id, scrollOffset = 0, snippet = "s", note = null, timestamp = 1L))
        db.highlightDao().insertHighlight(HighlightEntity(bookId = id, chapterIndex = 0, startOffset = 0, endOffset = 1, colorKey = "yellow", createdAt = 1L))

        repository().deleteBook(id)

        assertEquals(null, db.bookDao().getBookMetaById(id))
        assertEquals(0, db.chapterDao().getChapterCount(id))
        assertEquals(null, db.readingProgressDao().getProgressByBookId(id).first())
        assertFalse(file.exists())
    }

    @Test(expected = IllegalStateException::class)
    fun `deleteBook propagates dependent delete failure and keeps book and file`() = runBlocking {
        val file = java.io.File.createTempFile("lexiread-book", ".txt")
        file.writeText("content")
        val id = "local_book_fail"
        db.bookDao().insertBook(testBook(id, filePath = file.absolutePath))
        db.chapterDao().insertChapters(listOf(ChapterEntity(bookId = id, title = "c", content = "x", chapterIndex = 0)))

        val failingHighlightDao = object : HighlightDao by db.highlightDao() {
            override suspend fun deleteHighlightsForBook(bookId: String) {
                throw IllegalStateException("transient DB error")
            }
        }

        try {
            repository(failingHighlightDao).deleteBook(id)
        } finally {
            // Transaction must roll back the book delete, and file deletion
            // runs only after a successful DB delete.
            assertTrue(db.bookDao().getBookMetaById(id) != null)
            assertEquals(1, db.chapterDao().getChapterCount(id))
            assertTrue("file must survive a failed DB delete", file.exists())
            file.delete()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class CacheFailureRepositoryTest {

    @Test
    fun `AI translation is returned when the cache insert fails`() = runBlocking {
        val repo = TranslationRepositoryImpl(
            FakeTranslationApi(), ThrowingCacheDao(), FakeGeminiApi("Привет"),
            FakeOpenAiChatApi(), FakeClaudeApi(), FakeOpenAiChatApi()
        ) { "gemini" to "key" }

        val result = repo.translateText("hello", "ru")

        assertTrue(result.isSuccess)
        assertEquals("Привет", result.getOrThrow().translatedText)
    }

    @Test
    fun `fetched definition survives a cache insert failure`() = runBlocking {
        val repo = DictionaryRepositoryImpl(FakeDictionaryApi(), ThrowingCacheDao())

        val result = repo.lookupWord("word")

        assertTrue(result.isSuccess)
        val definition = result.getOrThrow().meanings.first().definitions.first()
        assertEquals("a unit of language", definition)
        assertFalse(definition.contains("unavailable"))
    }

    @Test
    fun `AI explanation is returned when the cache insert fails`() = runBlocking {
        val json = """{"simpleEnglish":"a simple meaning","russianTranslation":"per","whyUsed":"why","grammarExplanation":"grammar","exampleSentences":["ex1"]}"""
        val repo = AiRepositoryImpl(
            FakeGeminiApi(json), FakeOpenAiChatApi(), FakeClaudeApi(), FakeOpenAiChatApi(),
            ThrowingCacheDao()
        ) { "gemini" to "key" }

        val result = repo.explainWordOrSentence("serendipity", "It was pure serendipity.")

        assertTrue(result.isSuccess)
        assertEquals("a simple meaning", result.getOrThrow().simpleEnglish)
    }
}

// --- fakes --------------------------------------------------------------------

private class ThrowingCacheDao : CacheDao {
    override suspend fun getDictionaryCache(word: String): DictionaryCacheEntity? = null
    override suspend fun insertDictionaryCache(cache: DictionaryCacheEntity) = throw IllegalStateException("disk full")
    override suspend fun getTranslationCache(key: String): TranslationCacheEntity? = null
    override suspend fun insertTranslationCache(cache: TranslationCacheEntity) = throw IllegalStateException("disk full")
    override suspend fun getAiExplanationCache(key: String): AiExplanationEntity? = null
    override suspend fun insertAiExplanationCache(cache: AiExplanationEntity) = throw IllegalStateException("disk full")
    override suspend fun deleteExpiredAiExplanationCache(expireBefore: Long) = Unit
    override suspend fun deleteExpiredDictionaryCache(expireBefore: Long) = Unit
    override suspend fun deleteExpiredTranslationCache(expireBefore: Long) = Unit
}

private class FakeGeminiApi(private val text: String) : GeminiApi {
    override suspend fun generateContent(apiKey: String, request: GeminiRequestDto): GeminiResponseDto =
        GeminiResponseDto(
            listOf(GeminiCandidateDto(GeminiContentDto(listOf(GeminiPartDto(text)))))
        )
}

private class FakeOpenAiChatApi : OpenAiChatApi {
    override suspend fun chatCompletions(authHeader: String, request: OpenAiChatRequestDto): OpenAiChatResponseDto =
        OpenAiChatResponseDto(null)
}

private class FakeClaudeApi : ClaudeApi {
    override suspend fun createMessage(
        apiKey: String,
        version: String,
        request: ClaudeMessagesRequestDto
    ): ClaudeMessagesResponseDto = ClaudeMessagesResponseDto(null)
}

private class FakeTranslationApi : TranslationApi {
    override suspend fun translate(text: String, langpair: String): MyMemoryResponse =
        MyMemoryResponse(MyMemoryData("fallback", 1.0))
}

private class FakeDictionaryApi : DictionaryApi {
    override suspend fun getDefinition(word: String): List<DictionaryDto> = listOf(
        DictionaryDto(
            word = word,
            phonetic = "/wɜːd/",
            phonetics = listOf(PhoneticDto("/wɜːd/", null)),
            meanings = listOf(
                MeaningDto(
                    partOfSpeech = "noun",
                    definitions = listOf(DefinitionDto("a unit of language", null, null)),
                    synonyms = emptyList()
                )
            )
        )
    )
}
