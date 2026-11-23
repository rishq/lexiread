package com.lexiread.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.repository.BookRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val continueReadingBook: Book? = null,
    val latestProgress: ReadingProgress? = null,
    val recentBooks: List<Book> = emptyList(),
    val recommendedBooks: List<Book> = emptyList()
)

class HomeViewModel(
    private val bookRepository: BookRepository
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        bookRepository.getAllBooks(),
        bookRepository.getLatestProgress()
    ) { allBooks, progress ->
        val continueBook = allBooks.find { it.id == progress?.bookId } ?: allBooks.firstOrNull()
        // Only reuse the progress if it actually belongs to the highlighted book,
        // otherwise the card would show another book's percentage.
        val matchedProgress = progress?.takeIf { it.bookId == continueBook?.id }
        val recs = allBooks.filter { it.id != continueBook?.id }

        HomeUiState(
            continueReadingBook = continueBook,
            latestProgress = matchedProgress,
            recentBooks = allBooks.take(5),
            recommendedBooks = recs
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun toggleFavorite(bookId: String, isFav: Boolean) {
        viewModelScope.launch {
            bookRepository.toggleFavorite(bookId, isFav)
        }
    }

    class Factory(
        private val bookRepository: BookRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(bookRepository) as T
        }
    }
}
