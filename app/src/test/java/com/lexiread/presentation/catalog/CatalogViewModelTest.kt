package com.lexiread.presentation.catalog

import com.lexiread.domain.model.Book
import com.lexiread.domain.model.Bookmark
import com.lexiread.domain.model.CatalogBook
import com.lexiread.domain.model.CatalogPage
import com.lexiread.domain.model.Category
import com.lexiread.domain.model.Highlight
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.model.SourceKind
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.repository.BooksRepository
import com.lexiread.domain.usecase.DEFAULT_SOURCES
import com.lexiread.domain.usecase.GetBooksByCategoryUseCase
import com.lexiread.domain.usecase.GetPopularBooksUseCase
import com.lexiread.domain.usecase.OpenBookForReadingUseCase
import com.lexiread.domain.usecase.SearchBooksUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(repo: FakeBooksRepository) = CatalogViewModel(
        SearchBooksUseCase(repo),
        GetPopularBooksUseCase(repo),
        GetBooksByCategoryUseCase(repo),
        OpenBookForReadingUseCase(repo),
        FakeLocalBookRepository()
    )

    /** Fresh install must query the default sources, not an empty set. */
    @Test
    fun initialLoadUsesDefaultSources() = runTest(dispatcher) {
        val repo = FakeBooksRepository()
        val vm = viewModel(repo)
        runCurrent()

        assertEquals(DEFAULT_SOURCES, vm.uiState.value.sources)
        assertEquals(listOf(DEFAULT_SOURCES), repo.popularSources)
        assertEquals(listOf("popular"), vm.uiState.value.books.map { it.id })
    }

    /**
     * A category request still in flight when the user types must be cancelled;
     * otherwise its response lands after the debounced search and overwrites it.
     */
    @Test
    fun queryChangeCancelsInFlightCategoryLoad() = runTest(dispatcher) {
        val repo = FakeBooksRepository()
        repo.categoryGate = CompletableDeferred()
        val vm = viewModel(repo)
        runCurrent() // init popular load

        vm.selectCategory(repo.category)
        runCurrent() // category load starts and blocks on the gate
        assertEquals(1, repo.categoryCalls)

        vm.onQueryChange("dune")
        runCurrent() // search job scheduled behind the debounce
        advanceTimeBy(500L) // SEARCH_DEBOUNCE_MS
        runCurrent() // search executes
        assertEquals(listOf("search-dune"), vm.uiState.value.books.map { it.id })

        repo.categoryGate!!.complete(Unit)
        runCurrent() // the stale category response must not overwrite search results
        assertEquals(listOf("search-dune"), vm.uiState.value.books.map { it.id })
    }

    private class FakeBooksRepository : BooksRepository {
        private fun book(id: String) = CatalogBook(id = id, title = "Book $id", source = SourceKind.GUTENDEX)
        val category = Category("fiction", "Fiction", "fiction")
        val popularSources = mutableListOf<Set<SourceKind>>()
        val searchSources = mutableListOf<Set<SourceKind>>()
        var categoryGate: CompletableDeferred<Unit>? = null
        var categoryCalls = 0

        override fun categories(): List<Category> = listOf(category)

        override suspend fun search(query: String, page: Int, sources: Set<SourceKind>): CatalogPage {
            searchSources += sources
            return CatalogPage(listOf(book("search-$query")), page, 1, false)
        }

        override suspend fun getPopular(page: Int, sources: Set<SourceKind>): CatalogPage {
            popularSources += sources
            return CatalogPage(listOf(book("popular")), page, 1, false)
        }

        override suspend fun getByCategory(
            category: Category,
            page: Int,
            sources: Set<SourceKind>
        ): CatalogPage {
            categoryCalls++
            categoryGate?.await()
            return CatalogPage(listOf(book("category")), page, 1, false)
        }

        override suspend fun getBookDetails(id: String): CatalogBook? = null
        override suspend fun downloadForReading(book: CatalogBook): Result<Book> =
            Result.success(Book(id = book.id, title = book.title, author = ""))
    }

    private class FakeLocalBookRepository : BookRepository {
        override fun getAllBooks(): Flow<List<Book>> = flowOf(emptyList())
        override fun getFavoriteBooks(): Flow<List<Book>> = flowOf(emptyList())
        override fun getSavedBooks(): Flow<List<Book>> = flowOf(emptyList())
        override fun getFinishedBooks(): Flow<List<Book>> = flowOf(emptyList())
        override suspend fun getBookById(id: String): Book? = null
        override suspend fun fetchAndSaveFullBook(book: Book, forceRefresh: Boolean): Result<Book> =
            Result.success(book)
        override suspend fun addBookToLibrary(book: Book) = Unit
        override suspend fun deleteBook(id: String) = Unit
        override suspend fun toggleFavorite(bookId: String, isFavorite: Boolean) = Unit
        override suspend fun toggleFinished(bookId: String, isFinished: Boolean) = Unit
        override suspend fun saveReadingProgress(progress: ReadingProgress) = Unit
        override fun getReadingProgress(bookId: String): Flow<ReadingProgress?> = flowOf(null)
        override fun getLatestProgress(): Flow<ReadingProgress?> = flowOf(null)
        override fun getBookmarks(bookId: String): Flow<List<Bookmark>> = flowOf(emptyList())
        override suspend fun addBookmark(bookmark: Bookmark) = Unit
        override suspend fun deleteBookmark(id: Int) = Unit
        override fun getHighlights(bookId: String): Flow<List<Highlight>> = flowOf(emptyList())
        override suspend fun addHighlight(highlight: Highlight) = Unit
        override suspend fun deleteHighlight(id: Long) = Unit
        override suspend fun initializePreloadedBooks() = Unit
    }
}
