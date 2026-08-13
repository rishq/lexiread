package com.example.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.preferences.UserPreferencesManager
import com.example.core.util.TTSHelper
import com.example.domain.model.AiExplanation
import com.example.domain.model.Book
import com.example.domain.model.Bookmark
import com.example.domain.model.DictionaryEntry
import com.example.domain.model.ReaderSettings
import com.example.domain.model.ReaderThemeOption
import com.example.domain.model.ReadingProgress
import com.example.domain.model.SavedWord
import com.example.domain.model.TranslationResult
import com.example.domain.repository.AiRepository
import com.example.domain.repository.BookRepository
import com.example.domain.repository.DictionaryRepository
import com.example.domain.repository.TranslationRepository
import com.example.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class SelectedWordState(
    val word: String,
    val contextSentence: String,
    val dictionaryEntry: DictionaryEntry? = null,
    val translation: TranslationResult? = null,
    val aiExplanation: AiExplanation? = null,
    val isLoadingDict: Boolean = false,
    val isLoadingTrans: Boolean = false,
    val isLoadingAi: Boolean = false,
    val isWordSaved: Boolean = false
)

data class ReaderUiState(
    val book: Book? = null,
    val readerSettings: ReaderSettings = ReaderSettings(),
    val bookmarks: List<Bookmark> = emptyList(),
    val initialScrollOffset: Int = 0,
    val selectedWordState: SelectedWordState? = null,
    val showSettingsDialog: Boolean = false,
    val showBookmarksDialog: Boolean = false,
    val showAiExplanationDialog: Boolean = false,
    val isLoadingBook: Boolean = false,
    val errorMessage: String? = null
)

class ReaderViewModel(
    private val bookId: String,
    private val bookRepository: BookRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val translationRepository: TranslationRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val aiRepository: AiRepository,
    private val preferencesManager: UserPreferencesManager,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState(isLoadingBook = true))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        loadBookAndProgress()
        observePreferencesAndBookmarks()
    }

    private fun loadBookAndProgress() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingBook = true)
            var book = bookRepository.getBookById(bookId)
            if (book == null || book.fullText.isNullOrBlank()) {
                // Fetch full text online or fallback
                val fetchResult = bookRepository.fetchAndSaveFullBook(
                    book ?: Book(id = bookId, title = "Classic Book", author = "Unknown")
                )
                book = fetchResult.getOrNull() ?: book
            }

            if (book != null) {
                _uiState.value = _uiState.value.copy(
                    book = book,
                    isLoadingBook = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoadingBook = false,
                    errorMessage = "Failed to load book text."
                )
            }
        }
    }

    private fun observePreferencesAndBookmarks() {
        viewModelScope.launch {
            combine(
                preferencesManager.readerSettings,
                bookRepository.getBookmarks(bookId),
                bookRepository.getReadingProgress(bookId)
            ) { settings, bookmarks, progress ->
                _uiState.value = _uiState.value.copy(
                    readerSettings = settings,
                    bookmarks = bookmarks,
                    initialScrollOffset = progress?.scrollOffset ?: 0
                )
            }.collect {}
        }
    }

    fun onWordSelected(word: String, contextSentence: String) {
        val cleanWord = word.trim().lowercase().removeSurrounding("\"", "\"").removeSurrounding("'", "'")
            .filter { it.isLetter() || it == '-' }

        if (cleanWord.isBlank()) return

        val initialState = SelectedWordState(
            word = cleanWord,
            contextSentence = contextSentence,
            isLoadingDict = true,
            isLoadingTrans = true
        )

        _uiState.value = _uiState.value.copy(selectedWordState = initialState)

        // 1. Fetch Dictionary & Translation in parallel
        viewModelScope.launch {
            val dictResult = dictionaryRepository.lookupWord(cleanWord)
            val current = _uiState.value.selectedWordState
            if (current?.word == cleanWord) {
                _uiState.value = _uiState.value.copy(
                    selectedWordState = current.copy(
                        dictionaryEntry = dictResult.getOrNull(),
                        isLoadingDict = false
                    )
                )
            }
        }

        viewModelScope.launch {
            val transResult = translationRepository.translateText(cleanWord)
            val isSaved = vocabularyRepository.isWordSaved(cleanWord)
            val current = _uiState.value.selectedWordState
            if (current?.word == cleanWord) {
                _uiState.value = _uiState.value.copy(
                    selectedWordState = current.copy(
                        translation = transResult.getOrNull(),
                        isLoadingTrans = false,
                        isWordSaved = isSaved
                    )
                )
            }
        }
    }

    fun requestAiExplanation() {
        val selected = _uiState.value.selectedWordState ?: return
        _uiState.value = _uiState.value.copy(
            selectedWordState = selected.copy(isLoadingAi = true),
            showAiExplanationDialog = true
        )

        viewModelScope.launch {
            val aiResult = aiRepository.explainWordOrSentence(selected.word, selected.contextSentence)
            val current = _uiState.value.selectedWordState
            if (current != null) {
                _uiState.value = _uiState.value.copy(
                    selectedWordState = current.copy(
                        aiExplanation = aiResult.getOrNull(),
                        isLoadingAi = false
                    )
                )
            }
        }
    }

    fun saveWordToVocabulary() {
        val selected = _uiState.value.selectedWordState ?: return
        val currentBook = _uiState.value.book

        val savedWord = SavedWord(
            word = selected.word,
            translation = selected.translation?.translatedText ?: "Translation",
            definition = selected.dictionaryEntry?.meanings?.firstOrNull()?.definitions?.firstOrNull() ?: "",
            phonetics = selected.dictionaryEntry?.phonetics ?: "/${selected.word}/",
            partOfSpeech = selected.dictionaryEntry?.meanings?.firstOrNull()?.partOfSpeech ?: "word",
            example = selected.dictionaryEntry?.meanings?.firstOrNull()?.example ?: "",
            sourceBookId = currentBook?.id ?: "",
            sourceBookTitle = currentBook?.title ?: "",
            sourceSentence = selected.contextSentence,
            dateAdded = System.currentTimeMillis()
        )

        viewModelScope.launch {
            vocabularyRepository.saveWord(savedWord)
            _uiState.value = _uiState.value.copy(
                selectedWordState = selected.copy(isWordSaved = true)
            )
        }
    }

    fun speakWord(text: String) {
        ttsHelper.speak(text)
    }

    fun saveScrollPosition(scrollOffset: Int, totalLength: Int) {
        val currentBook = _uiState.value.book ?: return
        val percent = if (totalLength > 0) (scrollOffset.toFloat() / totalLength * 100f).coerceIn(0f, 100f) else 0f

        viewModelScope.launch {
            bookRepository.saveReadingProgress(
                ReadingProgress(
                    bookId = currentBook.id,
                    scrollOffset = scrollOffset,
                    totalLength = totalLength,
                    percentCompleted = percent,
                    lastReadTimestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun addBookmark(scrollOffset: Int, snippet: String) {
        val currentBook = _uiState.value.book ?: return
        viewModelScope.launch {
            bookRepository.addBookmark(
                Bookmark(
                    bookId = currentBook.id,
                    scrollOffset = scrollOffset,
                    snippet = snippet.take(100)
                )
            )
        }
    }

    fun deleteBookmark(id: Int) {
        viewModelScope.launch {
            bookRepository.deleteBookmark(id)
        }
    }

    fun dismissWordSelection() {
        _uiState.value = _uiState.value.copy(selectedWordState = null)
    }

    fun toggleSettingsDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSettingsDialog = show)
    }

    fun toggleBookmarksDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showBookmarksDialog = show)
    }

    fun toggleAiExplanationDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAiExplanationDialog = show)
    }

    fun updateTheme(theme: ReaderThemeOption) {
        viewModelScope.launch { preferencesManager.updateTheme(theme) }
    }

    fun updateFontSize(sizeSp: Float) {
        viewModelScope.launch { preferencesManager.updateFontSize(sizeSp) }
    }

    fun updateLineHeight(multiplier: Float) {
        viewModelScope.launch { preferencesManager.updateLineHeight(multiplier) }
    }

    class Factory(
        private val bookId: String,
        private val bookRepository: BookRepository,
        private val dictionaryRepository: DictionaryRepository,
        private val translationRepository: TranslationRepository,
        private val vocabularyRepository: VocabularyRepository,
        private val aiRepository: AiRepository,
        private val preferencesManager: UserPreferencesManager,
        private val ttsHelper: TTSHelper
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReaderViewModel(
                bookId,
                bookRepository,
                dictionaryRepository,
                translationRepository,
                vocabularyRepository,
                aiRepository,
                preferencesManager,
                ttsHelper
            ) as T
        }
    }
}
