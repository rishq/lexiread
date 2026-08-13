package com.example.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.Book
import com.example.domain.repository.BookRepository
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
    val isLoading: Boolean = false
)

class LibraryViewModel(
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(LibraryTab.CURRENTLY_READING)

    val uiState: StateFlow<LibraryUiState> = combine(
        _selectedTab,
        bookRepository.getAllBooks(),
        bookRepository.getFavoriteBooks(),
        bookRepository.getSavedBooks(),
        bookRepository.getFinishedBooks()
    ) { tab, all, favs, saved, finished ->
        val filteredList = when (tab) {
            LibraryTab.CURRENTLY_READING -> all.filter { !it.isFinished }
            LibraryTab.FAVORITES -> favs
            LibraryTab.SAVED -> saved
            LibraryTab.FINISHED -> finished
        }
        LibraryUiState(
            selectedTab = tab,
            books = filteredList,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState(isLoading = true)
    )

    fun selectTab(tab: LibraryTab) {
        _selectedTab.value = tab
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

    class Factory(private val bookRepository: BookRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(bookRepository) as T
        }
    }
}
