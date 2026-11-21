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
import com.lexiread.domain.model.Highlight
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
import java.util.concurrent.atomic.AtomicInteger
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

    private lateinit var context: Context
    private lateinit var progressFlow: MutableStateFlow<ReadingProgress?>
    private lateinit var viewModel: ReaderViewModel
    private lateinit var fakeRepo: FakeBookRepo
    private val savedProgresses = mutableListOf<ReadingProgress>()
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
        private val progressFlow: Flow<ReadingProgress?>,
        private val onSave: (ReadingProgress) -> Unit = {},
        private val storedBook: Book = book,
        private val fetchResult: Book = book,
        private val fetchGate: CompletableDeferred<Unit>? = null
    ) : BookRepository {
        private val fetchCalls = AtomicInteger(0)
        private val activeFetches = AtomicInteger(0)
        private val peakFetches = AtomicInteger(0)

        val fetchCount: Int get() = fetchCalls.get()
        val maxConcurrentFetches: Int get() = peakFetches.get()

        override fun getAllBooks() = flowOf(listOf(book))
        override fun getFavoriteBooks() = flowOf(emptyList<Book>())
        override fun getSavedBooks() = flowOf(emptyList<Book>())
        override fun getFinishedBooks() = flowOf(emptyList<Book>())
        override suspend fun getBookById(id: String) = storedBook
        override suspend fun fetchAndSaveFullBook(book: Book, forceRefresh: Boolean): Result<Book> {
            fetchCalls.incrementAndGet()
            val active = activeFetches.incrementAndGet()
            peakFetches.updateAndGet { prev -> maxOf(prev, active) }
            try {
                fetchGate?.await()
            } finally {
                activeFetches.decrementAndGet()
            }
            return Result.success(fetchResult)
        }
        override suspend fun addBookToLibrary(book: Book) {}
        override suspend fun deleteBook(id: String) {}
        override suspend fun toggleFavorite(bookId: String, isFavorite: Boolean) {}
        override suspend fun toggleFinished(bookId: String, isFinished: Boolean) {}
        override suspend fun saveReadingProgress(progress: ReadingProgress) = onSave(progress)
        override fun getReadingProgress(bookId: String) = progressFlow
        override fun getLatestProgress() = flowOf<ReadingProgress?>(null)
        override fun getBookmarks(bookId: String) = flowOf(emptyList<Bookmark>())
        override suspend fun addBookmark(bookmark: Bookmark) {}
        override suspend fun deleteBookmark(id: Int) {}
        override fun getHighlights(bookId: String) = flowOf(emptyList<Highlight>())
        override suspend fun addHighlight(highlight: Highlight) {}
        override suspend fun deleteHighlight(id: Long) {}
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
        context = ApplicationProvider.getApplicationContext<Context>()

        progressFlow = MutableStateFlow(ReadingProgress(bookId = "b1", currentChapter = 1, currentPage = 0))
        fakeRepo = FakeBookRepo(book, progressFlow, onSave = { savedProgresses += it })
        viewModel = buildViewModel(fakeRepo)
    }

    private fun buildViewModel(repo: FakeBookRepo): ReaderViewModel = ReaderViewModel(
        bookId = "b1",
        bookRepository = repo,
        dictionaryRepository = FakeDictRepo(),
        translationRepository = FakeTransRepo(),
        vocabularyRepository = FakeVocabRepo(),
        aiRepository = FakeAiRepo(),
        preferencesManager = UserPreferencesManager(context),
        ttsHelper = TTSHelper(context),
        bookImporter = BookImporter(context),
        paginationEngine = paginator
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads book and restores chapter from saved progress`() {
        viewModel.onTextAreaDimensionsChanged(360, 600)
        awaitUntil { viewModel.uiState.value.pagesForCurrentChapter.isNotEmpty() }

        assertEquals("Test Book", viewModel.uiState.value.book?.title)
        assertEquals(1, viewModel.uiState.value.currentChapterIndex)
    }

    @Test
    fun `stale progress re-emission does not reset reading position`() {
        viewModel.onTextAreaDimensionsChanged(360, 600)
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
        viewModel.onTextAreaDimensionsChanged(360, 600)
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
        viewModel.onTextAreaDimensionsChanged(360, 600)
        awaitUntil { viewModel.uiState.value.pagesForCurrentChapter.isNotEmpty() }
        awaitUntil { !viewModel.uiState.value.isPaginating }

        val before = viewModel.uiState.value.pagesForCurrentChapter
        assertEquals(
            "the first pagination must use the reported viewport",
            paginator.pagesFor(CHAPTER_1, 360, 600),
            before
        )

        viewModel.onTextAreaDimensionsChanged(500, 900)
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
        viewModel.onTextAreaDimensionsChanged(360, 600)
        awaitUntil { viewModel.uiState.value.pagesForCurrentChapter.isNotEmpty() }
        // Chapter 2 is prefetched for the original viewport...
        awaitUntil { paginator.callsFor(CHAPTER_2).any { it.heightPx == 600 } }
        val stale = paginator.pagesFor(CHAPTER_2, 360, 600)

        // ...then the viewport changes. Hold the chapter-2 pagination open so the
        // resize lands *during* a prefetch: that is the window in which a stale
        // page list could be written into the cache and served afterwards.
        val release = CompletableDeferred<Unit>()
        paginator.gate = CHAPTER_2 to release

        viewModel.onTextAreaDimensionsChanged(500, 900)
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

    // --- BUG-20: local chapters must not force a download ---

    @Test
    fun `book with local chapters does not force a download`() {
        awaitUntil { !viewModel.uiState.value.isLoadingBook }
        assertEquals(0, fakeRepo.fetchCount)
        assertEquals("Test Book", viewModel.uiState.value.book?.title)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    // --- BUG-22: slider position must be staged for commitProgress ---

    @Test
    fun `slider goToPage stages progress for commitProgress`() {
        viewModel.onTextAreaDimensionsChanged(360, 600)
        awaitUntil { viewModel.uiState.value.pagesForCurrentChapter.size >= 3 }

        viewModel.goToPage(2, saveProgress = false)
        viewModel.commitProgress()

        awaitUntil { savedProgresses.isNotEmpty() }
        assertEquals(2, savedProgresses.last().currentPage)
    }

    // --- BUG-23: final progress must be persisted on clear ---

    @Test
    fun `onCleared flush persists the latest staged progress`() {
        viewModel.onTextAreaDimensionsChanged(360, 600)
        awaitUntil { viewModel.uiState.value.pagesForCurrentChapter.size >= 3 }

        repeat(2) { viewModel.nextPage() } // page 0 -> 2
        savedProgresses.clear()
        viewModel.flushProgressOnCleared()

        assertEquals(2, savedProgresses.last().currentPage)
    }

    // --- BUG-25: final page of final chapter must be 100% ---

    @Test
    fun `final page of final chapter reports 100 percent`() {
        viewModel.onTextAreaDimensionsChanged(500, 900) // 5 pages per chapter
        awaitUntil { viewModel.uiState.value.pagesForCurrentChapter.size == 5 }

        viewModel.goToChapter(2, Int.MAX_VALUE) // last chapter, last page
        awaitUntil {
            !viewModel.uiState.value.isPaginating &&
                viewModel.uiState.value.currentChapterIndex == 2 &&
                viewModel.uiState.value.currentPageIndex == 4
        }

        savedProgresses.clear()
        viewModel.flushProgressOnCleared()
        assertEquals(100f, savedProgresses.last().percentCompleted)
    }

    // --- BUG-28: out-of-range saved chapter must be clamped ---

    @Test
    fun `out-of-range saved chapter is clamped to last chapter`() {
        val repo = FakeBookRepo(
            book,
            MutableStateFlow(ReadingProgress(bookId = "b1", currentChapter = 50, currentPage = 0)),
            onSave = { savedProgresses += it }
        )
        val vm = buildViewModel(repo)
        vm.onTextAreaDimensionsChanged(360, 600)
        awaitUntil { !vm.uiState.value.isLoadingBook }

        assertEquals(2, vm.uiState.value.currentChapterIndex)
        assertNull(vm.uiState.value.errorMessage)
    }

    // --- BUG-29: a new retry must cancel the prior in-flight load ---

    @Test
    fun `repeated retry cancels the prior in-flight load`() {
        val gate = CompletableDeferred<Unit>()
        // No filePath and no local chapters -> this book needs a download.
        val needyBook = book.copy(fullText = null, filePath = null)
        val repo = FakeBookRepo(
            book = book,
            progressFlow = MutableStateFlow<ReadingProgress?>(null),
            storedBook = needyBook,
            fetchResult = book,
            fetchGate = gate
        )
        val vm = buildViewModel(repo)

        // Initial load is blocked on the gate; two retries should supersede it.
        vm.retryLoad()
        vm.retryLoad()
        gate.complete(Unit)

        awaitUntil { !vm.uiState.value.isLoadingBook }
        assertEquals(1, repo.maxConcurrentFetches)
    }
}
