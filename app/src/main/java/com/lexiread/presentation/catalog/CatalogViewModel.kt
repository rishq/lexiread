package com.lexiread.presentation.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.data.mapper.toDomainBook
import com.lexiread.domain.model.CatalogBook
import com.lexiread.domain.model.CatalogPage
import com.lexiread.domain.model.Category
import com.lexiread.domain.model.SourceKind
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.usecase.DEFAULT_SOURCES
import com.lexiread.domain.usecase.GetBooksByCategoryUseCase
import com.lexiread.domain.usecase.GetPopularBooksUseCase
import com.lexiread.domain.usecase.OpenBookForReadingUseCase
import com.lexiread.domain.usecase.SearchBooksUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogUiState(
    val query: String = "",
    val books: List<CatalogBook> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val partialFailureMessage: String? = null,
    val sources: Set<SourceKind> = DEFAULT_SOURCES,
    val activeCategory: Category? = null,
    val openingBookId: String? = null
)

/** One-shot events: navigation and transient messages must not survive rotation. */
sealed interface CatalogEffect {
    data class OpenReader(val bookId: String) : CatalogEffect
    data class OpenDetails(val bookId: String) : CatalogEffect
    data class ShowMessage(val message: String) : CatalogEffect
}

class CatalogViewModel(
    private val searchBooks: SearchBooksUseCase,
    private val getPopularBooks: GetPopularBooksUseCase,
    private val getBooksByCategory: GetBooksByCategoryUseCase,
    private val openBookForReading: OpenBookForReadingUseCase,
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<CatalogEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<CatalogEffect> = _effects.asSharedFlow()

    val categories: List<Category> = getBooksByCategory.categories()

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        loadPopular()
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value, activeCategory = null) }
        searchJob?.cancel()

        if (value.isBlank()) {
            loadPopular()
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            execute(page = 1, append = false) { state ->
                searchBooks(state.query, 1, state.sources)
            }
        }
    }

    fun selectCategory(category: Category?) {
        searchJob?.cancel()
        _uiState.update { it.copy(activeCategory = category, query = "") }
        loadJob?.cancel()
        if (category == null) {
            loadPopular()
        } else {
            loadJob = viewModelScope.launch {
                execute(page = 1, append = false) { state ->
                    getBooksByCategory(category, 1, state.sources)
                }
            }
        }
    }

    fun toggleSource(kind: SourceKind) {
        val updated = _uiState.value.sources.toMutableSet().apply {
            if (!add(kind)) remove(kind)
        }
        if (updated.isEmpty()) {
            _effects.tryEmit(CatalogEffect.ShowMessage("Keep at least one source selected."))
            return
        }
        _uiState.update { it.copy(sources = updated) }
        retry()
    }

    /** Appends the next page. Guarded against overlapping requests. */
    fun loadNextPage() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoading || state.isLoadingMore) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            execute(page = state.page + 1, append = true) { current ->
                val category = current.activeCategory
                when {
                    category != null -> getBooksByCategory(category, current.page + 1, current.sources)
                    current.query.isNotBlank() -> searchBooks(current.query, current.page + 1, current.sources)
                    else -> getPopularBooks(current.page + 1, current.sources)
                }
            }
        }
    }

    fun retry() {
        loadJob?.cancel()
        val state = _uiState.value
        val category = state.activeCategory
        loadJob = viewModelScope.launch {
            execute(page = 1, append = false) { current ->
                when {
                    category != null -> getBooksByCategory(category, 1, current.sources)
                    current.query.isNotBlank() -> searchBooks(current.query, 1, current.sources)
                    else -> getPopularBooks(1, current.sources)
                }
            }
        }
    }

    private fun loadPopular() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            execute(page = 1, append = false) { state -> getPopularBooks(1, state.sources) }
        }
    }

    private suspend fun execute(page: Int, append: Boolean, request: suspend (CatalogUiState) -> CatalogPage) {
        _uiState.update {
            it.copy(
                isLoading = !append,
                isLoadingMore = append,
                errorMessage = null,
                partialFailureMessage = null
            )
        }

        val result: CatalogPage = try {
            request(_uiState.value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = error.message ?: "Could not reach any book catalogue."
                )
            }
            return
        }

        _uiState.update { state ->
            val merged = if (append) (state.books + result.books).distinctBy { it.id } else result.books
            state.copy(
                books = merged,
                page = result.page,
                hasMore = result.hasMore,
                isLoading = false,
                isLoadingMore = false,
                partialFailureMessage = if (result.failedSources.isNotEmpty()) {
                    "Some sources failed: ${result.failedSources.joinToString(", ")}"
                } else null
            )
        }
    }

    fun openBook(book: CatalogBook) {
        viewModelScope.launch {
            _uiState.update { it.copy(openingBookId = book.id) }
            val result = openBookForReading(book)
            _uiState.update { it.copy(openingBookId = null) }
            result
                .onSuccess { saved -> _effects.tryEmit(CatalogEffect.OpenReader(saved.id)) }
                .onFailure { error ->
                    _effects.tryEmit(
                        CatalogEffect.ShowMessage(
                            error.message ?: "This book could not be opened for reading."
                        )
                    )
                }
        }
    }

    /** Saves metadata only, without waiting for a download. */
    fun addToLibrary(book: CatalogBook) {
        viewModelScope.launch {
            runCatching { bookRepository.addBookToLibrary(book.toDomainBook()) }
                .onSuccess { _effects.tryEmit(CatalogEffect.ShowMessage("Saved to your library.")) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _effects.tryEmit(CatalogEffect.ShowMessage("Could not save '${book.title}'."))
                }
        }
    }

    /** Opens details for a book already in the library. */
    fun openDetails(book: CatalogBook) {
        viewModelScope.launch {
            runCatching { bookRepository.addBookToLibrary(book.toDomainBook()) }
                .onSuccess { _effects.tryEmit(CatalogEffect.OpenDetails(book.id)) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _effects.tryEmit(CatalogEffect.OpenDetails(book.id))
                }
        }
    }

    class Factory(
        private val searchBooks: SearchBooksUseCase,
        private val getPopularBooks: GetPopularBooksUseCase,
        private val getBooksByCategory: GetBooksByCategoryUseCase,
        private val openBookForReading: OpenBookForReadingUseCase,
        private val bookRepository: BookRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CatalogViewModel(
                searchBooks,
                getPopularBooks,
                getBooksByCategory,
                openBookForReading,
                bookRepository
            ) as T
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 500L
    }
}
