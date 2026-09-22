package com.lexiread.presentation.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lexiread.core.preferences.UserPreferencesManager
import com.lexiread.core.reader.BookImporter
import com.lexiread.core.reader.Paginator
import com.lexiread.core.util.TTSHelper
import com.lexiread.domain.model.AiExplanation
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.BookChapter
import com.lexiread.domain.model.Bookmark
import com.lexiread.domain.model.DictionaryEntry
import com.lexiread.domain.model.LearningStatus
import com.lexiread.domain.model.ReaderPage
import com.lexiread.domain.model.ReaderSettings
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.model.SavedWord
import com.lexiread.domain.model.TranslationResult
import com.lexiread.domain.repository.AiRepository
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.repository.DictionaryRepository
import com.lexiread.domain.repository.TranslationRepository
import com.lexiread.domain.repository.VocabularyRepository
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    private val paginator = FakePaginator()

    /** `book` below starts at chapter index 1 (see the saved progress in setUp). */
    private companion object {
        const val CHAPTER_1 = 1
        const val CHAPTER_2 = 2
    }

    private val chapterText = ("lorem ipsum dolor sit amet consectetur ".repeat(200)).trim()

    /**
     * Deterministic stand-in for [com.lexiread.core.reader.PaginationEngine].
     *
     * The real engine is useless for these assertions under Robolectric: text
     * metrics are faked, so every chapter collapses to a single page and the
     * page list does not change with the viewport. A test that compares page
     * lists across a resize could therefore never fail — which is exactly how
     * the previous version of this suite ended up both red (one test) and
     * vacuous (its neighbour). Here the page count is a pure function of the
     * viewport height, so "was this chapter repaginated at the new size?" is a
     * question the test can actually answer.
     */
    private class FakePaginator : Paginator {

        data class Call(val chapterIndex: Int, val widthPx: Int, val heightPx: Int)

        private val lock = Any()
        private val recorded = mutableListOf<Call>()

        /** Chapter index whose pagination blocks until the deferred completes. */
        @Volatile
        var gate: Pair<Int, CompletableDeferred<Unit>>? = null

        val calls: List<Call> get() = synchronized(lock) { recorded.toList() }

        fun callsFor(chapterIndex: Int): List<Call> =
            calls.filter { it.chapterIndex == chapterIndex }

        /** Pages the engine would produce for this chapter at this viewport. */
        fun pagesFor(chapterIndex: Int, widthPx: Int, heightPx: Int): List<ReaderPage> {
            val count = pageCount(heightPx)
            return (0 until count).map { pageIndex ->
                ReaderPage(
                    chapterIndex = chapterIndex,
                    pageIndex = pageIndex,
                    totalPagesInChapter = count,
                    text = "chapter-$chapterIndex-${widthPx}x$heightPx-page-$pageIndex",
                    chapterTitle = "Chapter ${chapterIndex + 1}"
                )
            }
        }

        override suspend fun paginateChapter(
            chapter: BookChapter,
            settings: ReaderSettings,
            availableWidthPx: Int,
            availableHeightPx: Int
        ): List<ReaderPage> {
            synchronized(lock) {
                recorded += Call(chapter.index, availableWidthPx, availableHeightPx)
            }
            gate?.takeIf { it.first == chapter.index }?.second?.await()
            return pagesFor(chapter.index, availableWidthPx, availableHeightPx)
        }

        private fun pageCount(heightPx: Int) = if (heightPx >= 900) 5 else 3
    }

    // NOTE: chapter markers must be followed by a blank line — cleanParagraphs
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
        override suspend fun fetchAndSaveFullBook(book: Book, forceRefresh: Boolean) = Result.success(book)
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
        override suspend fun initializePreloadedBooks() = Unit
    }

    private class FakeDictRepo : DictionaryRepository {
        override suspend fun lookupWord(word: String): Result<DictionaryEntry> =
            Result.success(DictionaryEntry(word = word))
    }

    private class FakeTransRepo : TranslationRepository {
        override suspend fun translateText(text: String, targetLang: String): Result<TranslationResult> =
            Result.success(TranslationResult(sourceText = text, translatedText = "перевод"))
    }

    private class FakeVocabRepo : VocabularyRepository {
        override fun getSavedWords() = flowOf(emptyList<SavedWord>())
        override suspend fun isWordSaved(word: String) = false
        override suspend fun saveWord(savedWord: SavedWord) {}
        override suspend fun updateStatus(id: Int, status: LearningStatus) {}
        override suspend fun reviewWord(id: Int, rating: com.lexiread.core.util.SrsScheduler.ReviewRating) {}
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
            paginationEngine = paginator
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
        // The old assertion (`book != null || errorMessage != null`) was true in
        // every case, including the failure one it was meant to exclude.
        assertEquals("Test Book", viewModel.uiState.value.book?.title)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `resize invalidates cached pagination and repaginates current chapter`() {
        viewModel.onContainerDimensionsChanged(360, 600)
        awaitUntil { viewModel.uiState.value.pagesForCurrentChapter.isNotEmpty() }
        awaitUntil { !viewModel.uiState.value.isPaginating }

        val before = viewModel.uiState.value.pagesForCurrentChapter
        assertEquals(
            "the first pagination must use the reported viewport",
            paginator.pagesFor(CHAPTER_1, 360, 600),
            before
        )

        viewModel.onContainerDimensionsChanged(500, 900)
        awaitUntil {
            !viewModel.uiState.value.isPaginating &&
                viewModel.uiState.value.pagesForCurrentChapter != before
        }

        val fresh = viewModel.uiState.value.pagesForCurrentChapter
        assertEquals(
            "the chapter must be re-paginated for the new viewport, not reused from the cache",
            paginator.pagesFor(CHAPTER_1, 500, 900),
            fresh
        )
        assertEquals(
            "the cache must be dropped, so the engine is asked again",
            FakePaginator.Call(CHAPTER_1, 500, 900),
            paginator.callsFor(CHAPTER_1).last()
        )
    }

    @Test
    fun `resized chapter is not served from stale prefetch cache`() {
        viewModel.onContainerDimensionsChanged(360, 600)
        awaitUntil { viewModel.uiState.value.pagesForCurrentChapter.isNotEmpty() }
        // Chapter 2 is prefetched for the original viewport...
        awaitUntil { paginator.callsFor(CHAPTER_2).any { it.heightPx == 600 } }
        val stale = paginator.pagesFor(CHAPTER_2, 360, 600)

        // ...then the viewport changes. Hold the chapter-2 pagination open so the
        // resize lands *during* a prefetch: that is the window in which a stale
        // page list could be written into the cache and served afterwards.
        val release = CompletableDeferred<Unit>()
        paginator.gate = CHAPTER_2 to release

        viewModel.onContainerDimensionsChanged(500, 900)
        awaitUntil { paginator.callsFor(CHAPTER_2).any { it.heightPx == 900 } }
        viewModel.goToChapter(CHAPTER_2)
        release.complete(Unit)

        awaitUntil {
            viewModel.uiState.value.currentChapterIndex == CHAPTER_2 &&
                !viewModel.uiState.value.isPaginating
        }

        val fresh = viewModel.uiState.value.pagesForCurrentChapter
        assertNotEquals("stale prefetched pages were served after a resize", stale, fresh)
        assertEquals(paginator.pagesFor(CHAPTER_2, 500, 900), fresh)
    }
}
