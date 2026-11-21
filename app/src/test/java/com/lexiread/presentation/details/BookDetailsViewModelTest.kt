package com.lexiread.presentation.details

import com.lexiread.domain.model.Book
import com.lexiread.domain.model.Bookmark
import com.lexiread.domain.model.Highlight
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.repository.BookRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Two taps before the first write completes must end back at false.
     * The buggy version snapshots isFavorite before launch, so both taps
     * write true and the star stays on.
     */
    @Test
    fun twoRapidTapsToggleTwice() = runTest(dispatcher) {
        val repo = FakeBookRepository(Book(id = "b1", title = "T", author = "A", isFavorite = false))
        val vm = BookDetailsViewModel("b1", repo)
        runCurrent() // initial load

        vm.toggleFavorite()
        runCurrent() // #1 acquires the lock and blocks in the repository
        assertEquals(1, repo.favoriteCalls.size)
        assertEquals("b1" to true, repo.favoriteCalls[0])
        assertFalse(vm.uiState.value.book!!.isFavorite)

        vm.toggleFavorite() // #2 queued behind #1
        runCurrent()
        assertEquals(1, repo.favoriteCalls.size)

        repo.gates[0].complete(Unit)
        runCurrent() // #1 commits, then #2 reads the fresh state and writes false
        assertEquals(2, repo.favoriteCalls.size)
        assertEquals("b1" to false, repo.favoriteCalls[1])
        assertTrue(vm.uiState.value.book!!.isFavorite)

        repo.gates[1].complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.book!!.isFavorite)
    }

    private class FakeBookRepository(private val book: Book) : BookRepository {
        val favoriteCalls = mutableListOf<Pair<String, Boolean>>()
        val gates = mutableListOf<CompletableDeferred<Unit>>()

        override fun getAllBooks(): Flow<List<Book>> = flowOf(listOf(book))
        override fun getFavoriteBooks(): Flow<List<Book>> = flowOf(emptyList())
        override fun getSavedBooks(): Flow<List<Book>> = flowOf(emptyList())
        override fun getFinishedBooks(): Flow<List<Book>> = flowOf(emptyList())
        override suspend fun getBookById(id: String): Book = book
        override suspend fun fetchAndSaveFullBook(book: Book, forceRefresh: Boolean): Result<Book> =
            Result.success(book)
        override suspend fun addBookToLibrary(book: Book) = Unit
        override suspend fun deleteBook(id: String) = Unit

        override suspend fun toggleFavorite(bookId: String, isFavorite: Boolean) {
            favoriteCalls += bookId to isFavorite
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
        }

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
