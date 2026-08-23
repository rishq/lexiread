package com.lexiread.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.core.preferences.UserPreferencesManager
import com.lexiread.core.reader.BookImporter
import com.lexiread.core.reader.PaginationEngine
import com.lexiread.core.util.TTSHelper
import com.lexiread.domain.model.AiExplanation
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.BookChapter
import com.lexiread.domain.model.Bookmark
import com.lexiread.domain.model.DictionaryEntry
import com.lexiread.domain.model.ReaderPage
import com.lexiread.domain.model.ReaderSettings
import com.lexiread.domain.model.ReaderThemeOption
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.model.SavedWord
import com.lexiread.domain.model.TranslationResult
import com.lexiread.domain.repository.AiRepository
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.repository.DictionaryRepository
import com.lexiread.domain.repository.TranslationRepository
import com.lexiread.domain.repository.VocabularyRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    val isWordSaved: Boolean = false,
    val translationError: String? = null,
    val aiError: String? = null
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
    private var paginationJob: kotlinx.coroutines.Job? = null
    private var progressRestored: Boolean = false

    init {
        loadBookAndChapters()
        observePreferencesAndBookmarks()
    }

    private fun loadBookAndChapters() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBook = true) }
            var book = bookRepository.getBookById(bookId)
            if (book == null || (book.fullText.isNullOrBlank() && book.filePath.isNullOrBlank())) {
                val fetchResult = bookRepository.fetchAndSaveFullBook(
                    book ?: Book(id = bookId, title = "Classic Book", author = "Unknown")
                )
                book = fetchResult.getOrNull() ?: book
            }

            if (book != null) {
                val chapters = bookImporter.getChaptersForBook(book)

                // Read saved progress ONCE, synchronously before the first pagination,
                // so restoring the position can never race with pagination.
                val progress = bookRepository.getReadingProgress(bookId).first()
                if (!progressRestored && progress != null && chapters.isNotEmpty()) {
                    progressRestored = true
                    _uiState.update {
                        it.copy(
                            currentChapterIndex = progress.currentChapter.coerceIn(0, chapters.size - 1),
                            currentPageIndex = progress.currentPage
                        )
                    }
                }

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
            ) { settings, bookmarks, _ ->
                settings to bookmarks
            }.collect { (settings, bookmarks) ->
                val prevSettings = _uiState.value.readerSettings
                _uiState.update {
                    it.copy(
                        readerSettings = settings,
                        bookmarks = bookmarks
                    )
                }
                if (prevSettings != settings) {
                    repaginateCurrentChapter()
                }
            }
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
        val chapters = _uiState.value.chapters
        if (chapters.isEmpty() || availableWidthPx <= 0 || availableHeightPx <= 0) return

        paginationJob?.cancel()
        paginationJob = viewModelScope.launch {
            _uiState.update { it.copy(isPaginating = true) }
            try {
                // Read state INSIDE the coroutine so we never paginate stale values.
                val currentState = _uiState.value
                val settings = currentState.readerSettings
                val chapterIdx = currentState.currentChapterIndex.coerceIn(0, chapters.size - 1)
                val chapter = chapters[chapterIdx]

                val pages = paginationEngine.paginateChapter(
                    chapter = chapter,
                    settings = settings,
                    availableWidthPx = availableWidthPx,
                    availableHeightPx = availableHeightPx
                )

                // Re-clamp against the CURRENT index: the user may have turned pages
                // while this pagination was running.
                _uiState.update {
                    it.copy(
                        pagesForCurrentChapter = pages,
                        currentPageIndex = it.currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
                        isPaginating = false
                    )
                }

                saveCurrentProgress()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never crash the app on pagination failure; show an error instead.
                _uiState.update {
                    it.copy(
                        isPaginating = false,
                        errorMessage = "Failed to render page: ${e.message}"
                    )
                }
            }
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

    fun goToPage(pageIndex: Int, saveProgress: Boolean = true) {
        val maxPage = (_uiState.value.pagesForCurrentChapter.size - 1).coerceAtLeast(0)
        _uiState.value = _uiState.value.copy(currentPageIndex = pageIndex.coerceIn(0, maxPage))
        if (saveProgress) {
            saveCurrentProgress()
        }
    }

    fun commitProgress() {
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

        paginationJob?.cancel()
        paginationJob = viewModelScope.launch {
            _uiState.update { it.copy(isPaginating = true) }
            try {
                val chapter = chapters[validChapterIdx]
                val pages = paginationEngine.paginateChapter(
                    chapter = chapter,
                    settings = _uiState.value.readerSettings,
                    availableWidthPx = availableWidthPx,
                    availableHeightPx = availableHeightPx
                )

                currentCoroutineContext().ensureActive()

                val pageIdx = if (targetPageIndex == Int.MAX_VALUE) {
                    (pages.size - 1).coerceAtLeast(0)
                } else {
                    targetPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                }

                _uiState.value = _uiState.value.copy(
                    pagesForCurrentChapter = pages,
                    currentPageIndex = pageIdx,
                    isPaginating = false
                )
                saveCurrentProgress()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isPaginating = false,
                        errorMessage = "Failed to render page: ${e.message}"
                    )
                }
            }
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
                        isWordSaved = isSaved,
                        translationError = transResult.exceptionOrNull()?.message
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
                        isLoadingAi = false,
                        aiError = aiResult.exceptionOrNull()?.message
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
