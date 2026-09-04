package com.lexiread.presentation.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.core.util.UserErrorMessages
import com.lexiread.domain.model.Book
import com.lexiread.domain.repository.BookRepository
import kotlinx.coroutines.CancellationException
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
            try {
                val book = bookRepository.getBookById(bookId)
                if (book != null) {
                    _uiState.value = BookDetailsUiState(book = book, isLoading = false)
                } else {
                    _uiState.value = BookDetailsUiState(
                        isLoading = false,
                        errorMessage = "Book not found in database."
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Without this the screen would spin forever on a database error.
                _uiState.value = BookDetailsUiState(
                    isLoading = false,
                    errorMessage = UserErrorMessages.messageFor(error, "Unable to load this book.")
                )
            }
        }
    }

    fun toggleFavorite() {
        val current = _uiState.value.book ?: return
        viewModelScope.launch {
            runCatching { bookRepository.toggleFavorite(current.id, !current.isFavorite) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(book = current.copy(isFavorite = !current.isFavorite))
                }
        }
    }

    fun addToLibrary() {
        val current = _uiState.value.book ?: return
        viewModelScope.launch {
            runCatching { bookRepository.addBookToLibrary(current) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(book = current.copy(isSaved = true))
                }
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
