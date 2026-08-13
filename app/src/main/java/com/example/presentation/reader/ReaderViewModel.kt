package com.example.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.preferences.UserPreferencesManager
import com.example.core.reader.BookImporter
import com.example.core.reader.PaginationEngine
import com.example.core.util.TTSHelper
import com.example.domain.model.AiExplanation
import com.example.domain.model.Book
import com.example.domain.model.BookChapter
import com.example.domain.model.Bookmark
import com.example.domain.model.DictionaryEntry
import com.example.domain.model.ReaderPage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val chapters: List<BookChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentPageIndex: Int = 0,
    val pagesForCurrentChapter: List<ReaderPage> = emptyList(),
    val readerSettings: ReaderSettings = ReaderSettings(),
    val bookmarks: List<Bookmark> = emptyList(),
    val selectedWordState: SelectedWordState? = null,
    val showControlsOverlay: Boolean = false,
    val showSettingsDialog: Boolean = false,
    val showBookmarksDialog: Boolean = false,
    val showTocDialog: Boolean = false,
    val showAiExplanationDialog: Boolean = false,
    val isLoadingBook: Boolean = false,
    val isPaginating: Boolean = false,
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
    private val ttsHelper: TTSHelper,
    private val bookImporter: BookImporter,
    private val paginationEngine: PaginationEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState(isLoadingBook = true))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var availableWidthPx: Int = 0
    private var availableHeightPx: Int = 0

    init {
        loadBookAndChapters()
        observePreferencesAndBookmarks()
    }

    private fun loadBookAndChapters() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingBook = true)
            var book = bookRepository.getBookById(bookId)
            if (book == null || (book.fullText.isNullOrBlank() && book.filePath.isNullOrBlank())) {
                val fetchResult = bookRepository.fetchAndSaveFullBook(
                    book ?: Book(id = bookId, title = "Classic Book", author = "Unknown")
                )
                book = fetchResult.getOrNull() ?: book
            }

            if (book != null) {
                val chapters = bookImporter.getChaptersForBook(book)
                val progress = bookRepository.getReadingProgress(bookId)

                _uiState.value = _uiState.value.copy(
                    book = book,
                    chapters = chapters,
                    isLoadingBook = false
                )

                // Trigger initial pagination if container size is ready
                repaginateCurrentChapter()
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
                val prevSettings = _uiState.value.readerSettings
                _uiState.value = _uiState.value.copy(
                    readerSettings = settings,
                    bookmarks = bookmarks
                )

                // Restore saved progress if chapter/page not initialized
                if (progress != null && _uiState.value.pagesForCurrentChapter.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        currentChapterIndex = progress.currentChapter.coerceIn(0, (_uiState.value.chapters.size - 1).coerceAtLeast(0)),
                        currentPageIndex = progress.currentPage
                    )
                }

                if (prevSettings != settings) {
                    repaginateCurrentChapter()
                }
            }.collect {}
        }
    }

    fun onContainerDimensionsChanged(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (this.availableWidthPx != widthPx || this.availableHeightPx != heightPx) {
            this.availableWidthPx = widthPx
            this.availableHeightPx = heightPx
            repaginateCurrentChapter()
        }
    }

    fun repaginateCurrentChapter() {
        val currentState = _uiState.value
        val chapters = currentState.chapters
        if (chapters.isEmpty() || availableWidthPx <= 0 || availableHeightPx <= 0) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPaginating = true)
            val chapterIdx = currentState.currentChapterIndex.coerceIn(0, chapters.size - 1)
            val chapter = chapters[chapterIdx]

            val pages = paginationEngine.paginateChapter(
                chapter = chapter,
                settings = currentState.readerSettings,
                availableWidthPx = availableWidthPx,
                availableHeightPx = availableHeightPx
            )

            val newPageIndex = currentState.currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

            _uiState.value = _uiState.value.copy(
                pagesForCurrentChapter = pages,
                currentPageIndex = newPageIndex,
                isPaginating = false
            )

            saveCurrentProgress()
        }
    }

    fun nextPage() {
        val state = _uiState.value
        if (state.currentPageIndex < state.pagesForCurrentChapter.size - 1) {
            _uiState.value = state.copy(currentPageIndex = state.currentPageIndex + 1)
            saveCurrentProgress()
        } else if (state.currentChapterIndex < state.chapters.size - 1) {
            // Next chapter
            goToChapter(state.currentChapterIndex + 1, targetPageIndex = 0)
        }
    }

    fun previousPage() {
        val state = _uiState.value
        if (state.currentPageIndex > 0) {
            _uiState.value = state.copy(currentPageIndex = state.currentPageIndex - 1)
            saveCurrentProgress()
        } else if (state.currentChapterIndex > 0) {
            // Previous chapter, last page
            goToChapter(state.currentChapterIndex - 1, targetPageIndex = Int.MAX_VALUE)
        }
    }

    fun goToPage(pageIndex: Int) {
        val maxPage = (_uiState.value.pagesForCurrentChapter.size - 1).coerceAtLeast(0)
        _uiState.value = _uiState.value.copy(currentPageIndex = pageIndex.coerceIn(0, maxPage))
        saveCurrentProgress()
    }

    fun goToChapter(chapterIndex: Int, targetPageIndex: Int = 0) {
        val chapters = _uiState.value.chapters
        if (chapters.isEmpty()) return
        val validChapterIdx = chapterIndex.coerceIn(0, chapters.size - 1)

        _uiState.value = _uiState.value.copy(
            currentChapterIndex = validChapterIdx,
            currentPageIndex = 0,
            showTocDialog = false
        )

        viewModelScope.launch {
            val chapter = chapters[validChapterIdx]
            val pages = paginationEngine.paginateChapter(
                chapter = chapter,
                settings = _uiState.value.readerSettings,
                availableWidthPx = availableWidthPx,
                availableHeightPx = availableHeightPx
            )

            val pageIdx = if (targetPageIndex == Int.MAX_VALUE) {
                (pages.size - 1).coerceAtLeast(0)
            } else {
                targetPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            }

            _uiState.value = _uiState.value.copy(
                pagesForCurrentChapter = pages,
                currentPageIndex = pageIdx
            )
            saveCurrentProgress()
        }
    }

    private fun saveCurrentProgress() {
        val state = _uiState.value
        val currentBook = state.book ?: return
        val totalChapters = state.chapters.size.coerceAtLeast(1)
        val percent = ((state.currentChapterIndex.toFloat() + (state.currentPageIndex.toFloat() / state.pagesForCurrentChapter.size.coerceAtLeast(1))) / totalChapters * 100f).coerceIn(0f, 100f)

        viewModelScope.launch {
            bookRepository.saveReadingProgress(
                ReadingProgress(
                    bookId = currentBook.id,
                    currentChapter = state.currentChapterIndex,
                    currentPage = state.currentPageIndex,
                    totalPagesInChapter = state.pagesForCurrentChapter.size,
                    percentCompleted = percent,
                    lastReadTimestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun toggleControlsOverlay() {
        _uiState.value = _uiState.value.copy(
            showControlsOverlay = !_uiState.value.showControlsOverlay
        )
    }

    fun onVolumeKeyEvent(keyCode: Int): Boolean {
        val settings = _uiState.value.readerSettings
        if (!settings.volumeKeysPageTurn) return false

        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                nextPage()
                true
            }
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                previousPage()
                true
            }
            else -> false
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

    fun addBookmarkSnippet(snippet: String) {
        val currentBook = _uiState.value.book ?: return
        viewModelScope.launch {
            bookRepository.addBookmark(
                Bookmark(
                    bookId = currentBook.id,
                    scrollOffset = _uiState.value.currentPageIndex,
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

    fun toggleTocDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showTocDialog = show)
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

    fun updateFontFamily(family: String) {
        viewModelScope.launch { preferencesManager.updateFontFamily(family) }
    }

    fun updateMarginDp(marginDp: Int) {
        viewModelScope.launch { preferencesManager.updateMarginDp(marginDp) }
    }

    fun updateIsPaginated(isPaginated: Boolean) {
        viewModelScope.launch { preferencesManager.updateIsPaginated(isPaginated) }
    }

    fun updateVolumeKeysPageTurn(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.updateVolumeKeysPageTurn(enabled) }
    }

    class Factory(
        private val bookId: String,
        private val bookRepository: BookRepository,
        private val dictionaryRepository: DictionaryRepository,
        private val translationRepository: TranslationRepository,
        private val vocabularyRepository: VocabularyRepository,
        private val aiRepository: AiRepository,
        private val preferencesManager: UserPreferencesManager,
        private val ttsHelper: TTSHelper,
        private val bookImporter: BookImporter,
        private val paginationEngine: PaginationEngine
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
                ttsHelper,
                bookImporter,
                paginationEngine
            ) as T
        }
    }
}
