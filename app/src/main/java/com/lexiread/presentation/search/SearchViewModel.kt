package com.lexiread.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.domain.model.Book
import com.lexiread.domain.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = DEFAULT_QUERY,
    val searchResults: List<Book> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

private const val DEFAULT_QUERY = "Sherlock"

class SearchViewModel(
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        performSearch(DEFAULT_QUERY)
    }

    fun onQueryChange(newQuery: String) {
        _uiState.value = _uiState.value.copy(query = newQuery)
        searchJob?.cancel()
        if (newQuery.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), isLoading = false, errorMessage = null)
            return
        }
        searchJob = viewModelScope.launch {
            delay(500) // Debounce
            search(newQuery)
        }
    }

    fun performSearch(query: String) {
        val normalizedQuery = query.trim()
        searchJob?.cancel()
        if (normalizedQuery.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), isLoading = false, errorMessage = null)
            return
        }
        _uiState.value = _uiState.value.copy(query = normalizedQuery)
        searchJob = viewModelScope.launch { search(normalizedQuery) }
    }

    private suspend fun search(query: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        val result = bookRepository.searchBooksOnline(query)
        result.onSuccess { books ->
            // Do not overwrite results for a newer query if a provider finishes late.
            if (_uiState.value.query == query) {
                _uiState.value = _uiState.value.copy(
                    searchResults = books,
                    isLoading = false
                )
            }
        }.onFailure { err ->
            if (_uiState.value.query == query) {
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

    /**
     * Online results are not in Room yet. Persist their metadata before
     * navigating so the details and reader screens receive the actual book,
     * not a placeholder built only from its id.
     */
    fun openBook(book: Book, onReady: (String) -> Unit) {
        viewModelScope.launch {
            try {
                bookRepository.addBookToLibrary(book)
                onReady(book.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = error.message ?: "Unable to save the selected book."
                )
            }
        }
    }

    class Factory(private val bookRepository: BookRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(bookRepository) as T
        }
    }
}
