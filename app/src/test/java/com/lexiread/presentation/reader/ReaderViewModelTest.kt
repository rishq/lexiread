package com.lexiread.presentation.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.core.preferences.UserPreferencesManager
import com.lexiread.core.reader.BookImporter
import com.lexiread.core.reader.PaginationEngine
import com.lexiread.core.util.TTSHelper
import com.lexiread.domain.model.AiExplanation
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.Bookmark
import com.lexiread.domain.model.DictionaryEntry
import com.lexiread.domain.model.LearningStatus
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.model.SavedWord
import com.lexiread.domain.model.TranslationResult
import com.lexiread.domain.repository.AiRepository
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.repository.DictionaryRepository
import com.lexiread.domain.repository.TranslationRepository
import com.lexiread.domain.repository.VocabularyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var progressFlow: MutableStateFlow<ReadingProgress?>
    private lateinit var viewModel: ReaderViewModel

    private val chapterText = ("lorem ipsum dolor sit amet consectetur ".repeat(200)).trim()

    // NOTE: chapter markers must be followed by a blank line вЂ” cleanParagraphs
    // glues the marker line together with the adjacent paragraph otherwise.
    private val book = Book(
        id = "b1",
        title = "Test Book",
        author = "Author",
        fullText = listOf("Chapter 1", "Chapter 2", "Chapter 3")
            .joinToString("\n\n") { "$it\n\n$chapterText" }
    )

    private class FakeBookRepo(
        private val book: Book,
        private val progressFlow: Flow<ReadingProgress?>
    ) : BookRepository {
        override fun getAllBooks() = flowOf(listOf(book))
        override fun getFavoriteBooks() = flowOf(emptyList<Book>())
        override fun getSavedBooks() = flowOf(emptyList<Book>())
        override fun getFinishedBooks() = flowOf(emptyList<Book>())
        override suspend fun getBookById(id: String) = book
        override suspend fun searchBooksOnline(query: String) = Result.success(emptyList<Book>())
        override suspend fun fetchAndSaveFullBook(book: Book) = Result.success(book)
        override suspend fun addBookToLibrary(book: Book) {}
        override suspend fun deleteBook(id: String) {}
        override suspend fun toggleFavorite(bookId: String, isFavorite: Boolean) {}
        override suspend fun toggleFinished(bookId: String, isFinished: Boolean) {}
        override suspend fun saveReadingProgress(progress: ReadingProgress) {}
        override fun getReadingProgress(bookId: String) = progressFlow
        override fun getLatestProgress() = flowOf<ReadingProgress?>(null)
        override fun getBookmarks(bookId: String) = flowOf(emptyList<Bookmark>())
        override suspend fun addBookmark(bookmark: Bookmark) {}
        override suspend fun deleteBookmark(id: Int) {}
    }

    private class FakeDictRepo : DictionaryRepository {
        override suspend fun lookupWord(word: String): Result<DictionaryEntry> =
            Result.success(DictionaryEntry(word = word))
    }

    private class FakeTransRepo : TranslationRepository {
        override suspend fun translateText(text: String, targetLang: String): Result<TranslationResult> =
            Result.success(TranslationResult(sourceText = text, translatedText = "РїРµСЂРµРІРѕРґ"))
    }

    private class FakeVocabRepo : VocabularyRepository {
        override fun getSavedWords() = flowOf(emptyList<SavedWord>())
        override fun getWordsByStatus(status: LearningStatus) = flowOf(emptyList<SavedWord>())
        override suspend fun isWordSaved(word: String) = false
        override suspend fun saveWord(savedWord: SavedWord) {}
        override suspend fun updateStatus(id: Int, status: LearningStatus) {}
        override suspend fun deleteWord(id: Int) {}
    }

    private class FakeAiRepo : AiRepository {
        override suspend fun explainWordOrSentence(
            sourceText: String,
            contextSentence: String
        ): Result<AiExplanation> = Result.failure(IllegalStateException("not configured"))
    }

    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(50)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()

        progressFlow = MutableStateFlow(ReadingProgress(bookId = "b1", currentChapter = 1, currentPage = 0))

        viewModel = ReaderViewModel(
            bookId = "b1",
            bookRepository = FakeBookRepo(book, progressFlow),
            dictionaryRepository = FakeDictRepo(),
            translationRepository = FakeTransRepo(),
            vocabularyRepository = FakeVocabRepo(),
            aiRepository = FakeAiRepo(),
            preferencesManager = UserPreferencesManager(context),
            ttsHelper = TTSHelper(context),
            bookImporter = BookImporter(context),
            paginationEngine = PaginationEngine(context)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads book and restores chapter from saved progress`() {
        viewModel.onContainerDimensionsChanged(360, 600)
        awaitUntil { viewModel.uiState.value.pagesForCurrentChapter.isNotEmpty() }

        assertEquals("Test Book", viewModel.uiState.value.book?.title)
        assertEquals(1, viewModel.uiState.value.currentChapterIndex)
    }

    @Test
    fun `stale progress re-emission does not reset reading position`() {
        viewModel.onContainerDimensionsChanged(360, 600)
        awaitUntil { !viewModel.uiState.value.isLoadingBook }

        val before = viewModel.uiState.value

        repeat(3) { viewModel.nextPage() }
        val moved = viewModel.uiState.value
        assertTrue(
            "Expected reader to advance (chapter or page): before=$before moved=$moved",
            moved.currentChapterIndex > before.currentChapterIndex ||
                moved.currentPageIndex > before.currentPageIndex
        )

        // A late re-emission of stale progress must NOT yank the reader back.
        progressFlow.value = ReadingProgress(bookId = "b1", currentChapter = 1, currentPage = 0)
        Thread.sleep(200)
        assertEquals(moved.currentChapterIndex, viewModel.uiState.value.currentChapterIndex)
        assertEquals(moved.currentPageIndex, viewModel.uiState.value.currentPageIndex)
    }

    @Test
    fun `goToChapter clamps out-of-range index`() {
        viewModel.onContainerDimensionsChanged(360, 600)
        awaitUntil { viewModel.uiState.value.pagesForCurrentChapter.isNotEmpty() }

        viewModel.goToChapter(99)
        awaitUntil { !viewModel.uiState.value.isPaginating }
        // Three-chapter book -> clamped to the last chapter (index 2)
        assertEquals(2, viewModel.uiState.value.currentChapterIndex)
    }

    @Test
    fun `book finishes loading`() {
        awaitUntil { !viewModel.uiState.value.isLoadingBook }
        assertTrue(viewModel.uiState.value.book != null || viewModel.uiState.value.errorMessage != null)
    }
}
