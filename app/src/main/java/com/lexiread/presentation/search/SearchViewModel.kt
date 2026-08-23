package com.lexiread.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.domain.model.Book
import com.lexiread.domain.repository.BookRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val searchResults: List<Book> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class SearchViewModel(
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Initial default search
        performSearch("Sherlock")
    }

    fun onQueryChange(newQuery: String) {
        _uiState.value = _uiState.value.copy(query = newQuery)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500) // Debounce
            if (newQuery.isNotBlank()) {
                performSearch(newQuery)
            }
        }
    }

    fun performSearch(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = bookRepository.searchBooksOnline(query)
            result.onSuccess { books ->
                _uiState.value = _uiState.value.copy(
                    searchResults = books,
                    isLoading = false
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "Failed to search books online"
                )
            }
        }
    }

    fun addToLibrary(book: Book) {
        viewModelScope.launch {
            bookRepository.addBookToLibrary(book)
        }
    }

    class Factory(private val bookRepository: BookRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(bookRepository) as T
        }
    }
}
