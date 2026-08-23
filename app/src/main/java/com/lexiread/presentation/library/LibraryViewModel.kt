package com.lexiread.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.domain.model.Book
import com.lexiread.domain.repository.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab {
    CURRENTLY_READING,
    FAVORITES,
    SAVED,
    FINISHED
}

data class LibraryUiState(
    val selectedTab: LibraryTab = LibraryTab.CURRENTLY_READING,
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = false,
    val importMessage: String? = null
)

class LibraryViewModel(
    private val bookRepository: BookRepository,
    private val bookImporter: com.lexiread.core.reader.BookImporter
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(LibraryTab.CURRENTLY_READING)
    private val _importMessage = MutableStateFlow<String?>(null)

    private val booksFlow = combine(
        bookRepository.getAllBooks(),
        bookRepository.getFavoriteBooks(),
        bookRepository.getSavedBooks(),
        bookRepository.getFinishedBooks()
    ) { all, favs, saved, finished ->
        mapOf(
            LibraryTab.CURRENTLY_READING to all.filter { !it.isFinished },
            LibraryTab.FAVORITES to favs,
            LibraryTab.SAVED to saved,
            LibraryTab.FINISHED to finished
        )
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        _selectedTab,
        _importMessage,
        booksFlow
    ) { tab, msg, booksMap ->
        LibraryUiState(
            selectedTab = tab,
            books = booksMap[tab] ?: emptyList(),
            isLoading = false,
            importMessage = msg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState(isLoading = true)
    )

    fun selectTab(tab: LibraryTab) {
        _selectedTab.value = tab
    }

    fun importBookFromUri(uri: android.net.Uri) {
        viewModelScope.launch {
            _importMessage.value = "Importing book..."
            val result = bookImporter.importBookFromUri(uri)
            result.onSuccess { book ->
                bookRepository.addBookToLibrary(book)
                _importMessage.value = "Successfully imported '${book.title}'"
            }.onFailure { err ->
                _importMessage.value = "Import failed: ${err.localizedMessage}"
            }
        }
    }

    fun clearImportMessage() {
        _importMessage.value = null
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            bookRepository.deleteBook(bookId)
        }
    }

    fun toggleFavorite(bookId: String, isFav: Boolean) {
        viewModelScope.launch {
            bookRepository.toggleFavorite(bookId, isFav)
        }
    }

    fun toggleFinished(bookId: String, isFinished: Boolean) {
        viewModelScope.launch {
            bookRepository.toggleFinished(bookId, isFinished)
        }
    }

    class Factory(
        private val bookRepository: BookRepository,
        private val bookImporter: com.lexiread.core.reader.BookImporter
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(bookRepository, bookImporter) as T
        }
    }
}
