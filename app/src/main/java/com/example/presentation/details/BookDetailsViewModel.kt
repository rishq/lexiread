package com.example.presentation.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.Book
import com.example.domain.repository.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BookDetailsUiState(
    val book: Book? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class BookDetailsViewModel(
    private val bookId: String,
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookDetailsUiState(isLoading = true))
    val uiState: StateFlow<BookDetailsUiState> = _uiState.asStateFlow()

    init {
        loadBookDetails()
    }

    private fun loadBookDetails() {
        viewModelScope.launch {
            _uiState.value = BookDetailsUiState(isLoading = true)
            val book = bookRepository.getBookById(bookId)
            if (book != null) {
                _uiState.value = BookDetailsUiState(book = book, isLoading = false)
            } else {
                _uiState.value = BookDetailsUiState(
                    isLoading = false,
                    errorMessage = "Book not found in database."
                )
            }
        }
    }

    fun toggleFavorite() {
        val current = _uiState.value.book ?: return
        viewModelScope.launch {
            val newFav = !current.isFavorite
            bookRepository.toggleFavorite(current.id, newFav)
            _uiState.value = _uiState.value.copy(book = current.copy(isFavorite = newFav))
        }
    }

    fun addToLibrary() {
        val current = _uiState.value.book ?: return
        viewModelScope.launch {
            bookRepository.addBookToLibrary(current)
            _uiState.value = _uiState.value.copy(book = current.copy(isSaved = true))
        }
    }

    class Factory(
        private val bookId: String,
        private val bookRepository: BookRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BookDetailsViewModel(bookId, bookRepository) as T
        }
    }
}
