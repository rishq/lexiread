package com.lexiread.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.model.SavedWord
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val continueReadingBook: Book? = null,
    val latestProgress: ReadingProgress? = null,
    val recentBooks: List<Book> = emptyList(),
    val recommendedBooks: List<Book> = emptyList(),
    val savedWordsCount: Int = 0,
    val knownWordsCount: Int = 0,
    val isLoading: Boolean = false
)

class HomeViewModel(
    private val bookRepository: BookRepository,
    private val vocabularyRepository: VocabularyRepository
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        bookRepository.getAllBooks(),
        bookRepository.getLatestProgress(),
        vocabularyRepository.getSavedWords()
    ) { allBooks, progress, savedWords ->
        val continueBook = allBooks.find { it.id == progress?.bookId } ?: allBooks.firstOrNull()
        val recs = allBooks.filter { it.id != continueBook?.id }
        val known = savedWords.count { it.learningStatus == com.lexiread.domain.model.LearningStatus.KNOWN }

        HomeUiState(
            continueReadingBook = continueBook,
            latestProgress = progress,
            recentBooks = allBooks.take(5),
            recommendedBooks = recs,
            savedWordsCount = savedWords.size,
            knownWordsCount = known,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(isLoading = true)
    )

    fun toggleFavorite(bookId: String, isFav: Boolean) {
        viewModelScope.launch {
            bookRepository.toggleFavorite(bookId, isFav)
        }
    }

    class Factory(
        private val bookRepository: BookRepository,
        private val vocabularyRepository: VocabularyRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(bookRepository, vocabularyRepository) as T
        }
    }
}
