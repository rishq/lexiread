package com.lexiread.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.core.preferences.UserPreferencesManager
import com.lexiread.core.reader.BookImporter
import com.lexiread.core.reader.Paginator
import com.lexiread.core.util.TTSHelper
import com.lexiread.core.util.UserErrorMessages
import com.lexiread.data.source.ChallengeRequiredException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
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
    val isWordSaved: Boolean = false,
    val translationError: String? = null,
    val aiError: String? = null,
    /**
     * N-3: cloud lookups are switched off. The hint is a UI concern, so the
     * screen renders `R.string.cloud_lookups_off_hint` /
     * `R.string.cloud_ai_off_hint` instead of a message baked into the
     * ViewModel — otherwise the string resources stayed dead while the same
     * text was hardcoded next to the early-exit.
     */
    val translationBlockedOffline: Boolean = false,
    val aiBlockedOffline: Boolean = false
)

data class ReaderUiState(
    val book: Book? = null,
    val chapters: List<BookChapter> = emptyList(),
    val tocTitles: List<BookChapter> = emptyList(),
    val totalChapterCount: Int = 0,
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
    // P1-6: first-tap consent for cloud lookups (text leaves the device).
    val showCloudConsentDialog: Boolean = false,
    val cloudLookupEnabled: Boolean = false,
    val isLoadingBook: Boolean = false,
    val isPaginating: Boolean = false,
    val isLoadingNextChapter: Boolean = false,
    val errorMessage: String? = null,
    /** Mirror browser-check URL: dialog passes it, then the download retries. */
    val challengeUrl: String? = null
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
    private val paginationEngine: Paginator
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState(isLoadingBook = true))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var availableWidthPx: Int = 0
    private var availableHeightPx: Int = 0
    private var paginationJob: kotlinx.coroutines.Job? = null
    private var prefetchJob: kotlinx.coroutines.Job? = null
    // P1-4: debounce progress writes — rapid page turns conflate into one Room
    // write per 500 ms instead of hundreds of transactions. Flush on dispose.
    private val pendingProgress = MutableStateFlow<ReadingProgress?>(null)
    private var progressRestored: Boolean = false

    /**
     * LRU pagination cache: chapter index -> list of pages.
     * Prevents re-pagination when navigating back to a previously visited
     * chapter. Capped to [PAGINATION_CACHE_SIZE] entries.
     */
    private val paginationCache = object : LinkedHashMap<Int, List<ReaderPage>>(
        PAGINATION_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, List<ReaderPage>>): Boolean {
            return size > PAGINATION_CACHE_SIZE
        }
    }

    init {
        loadBookAndChapters()
        observePreferencesAndBookmarks()
        observePendingProgress()
        observeCloudConsent()
    }

    /** P1-6: mirror the DataStore consent flag into UI state for the dialog. */
    private fun observeCloudConsent() {
        viewModelScope.launch {
            combine(
                preferencesManager.cloudLookupEnabled,
                preferencesManager.cloudConsentAsked
            ) { enabled, _ -> enabled }.collect { enabled ->
                _uiState.update { it.copy(cloudLookupEnabled = enabled) }
            }
        }
    }

    /**
     * P1-6: user decision from the consent dialog. When accepted, the pending
     * word lookup is resumed; when declined, lookups stay on-device only.
     */
    fun onCloudConsentResult(accepted: Boolean) {
        viewModelScope.launch {
            preferencesManager.setCloudLookupEnabled(accepted)
            _uiState.update { it.copy(showCloudConsentDialog = false) }
            if (accepted) {
                pendingWord?.let { (word, sentence) ->
                    pendingWord = null
                    lookupWordCloud(word, sentence)
                }
            } else {
                pendingWord = null
            }
        }
    }

    fun setCloudLookupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setCloudLookupEnabled(enabled)
        }
    }

    private var pendingWord: Pair<String, String>? = null

    /** P1-4: single collector — conflates rapid taps into one write per 500 ms. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observePendingProgress() {
        viewModelScope.launch {
            pendingProgress
                .filterNotNull()
                .debounce(500)
                .collectLatest { progress ->
                    runCatching { bookRepository.saveReadingProgress(progress) }
                }
        }
    }

    /** P1-4: force the latest progress to disk (slider release, dispose). */
    private fun flushProgress() {
        val progress = pendingProgress.value ?: return
        viewModelScope.launch {
            runCatching { bookRepository.saveReadingProgress(progress) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Best-effort synchronous flush is impossible here (no scope), so the
        // debounced collector already holds the value; flush via runBlocking-free
        // fire-and-forget is handled by commitProgress() callers (slider stop,
        // chapter change). Keep the hook documented for lifecycle owners:
        // call commitProgress() from DisposableEffect.onDispose.
    }

    private fun loadBookAndChapters() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoadingBook = true) }
                val stored = bookRepository.getBookById(bookId)
                val needsDownload = stored == null || stored.filePath.isNullOrBlank()

                val book = if (needsDownload) {
                    val fetchResult = bookRepository.fetchAndSaveFullBook(
                        stored ?: Book(id = bookId, title = "Classic Book", author = "Unknown")
                    )
                    fetchResult.getOrNull() ?: run {
                        val challenge = fetchResult.exceptionOrNull() as? ChallengeRequiredException
                        _uiState.update {
                            it.copy(
                                isLoadingBook = false,
                                challengeUrl = challenge?.url,
                                // P2-8: never leak raw exception.message to UI.
                                errorMessage = if (challenge != null) null else fetchResult.exceptionOrNull()?.let { err ->
                                    UserErrorMessages.messageFor(err, "This book could not be downloaded.")
                                } ?: "This book could not be downloaded."
                            )
                        }
                        return@launch
                    }
                } else {
                    stored
                }

                // Read saved progress before loading chapters so we know which
                // chapter to load first.
                val progress = bookRepository.getReadingProgress(bookId).first()
                val targetChapter = progress?.currentChapter?.coerceAtLeast(0) ?: 0

                // Lazy loading: load only the target chapter initially.
                // For books with a file path or chapters in DB, this loads one
                // chapter; for legacy fullText books, getChaptersForBook returns
                // the single requested window.
                var chapters = bookImporter.getChaptersForBook(book, limit = 1, offset = targetChapter)
                // Total is the real chapter count (DB or fullText), not the size of
                // the lazily loaded window — otherwise navigation bounds collapse
                // to a single chapter.
                val totalChapters = bookImporter.getChapterCount(book)

                if (chapters.isEmpty() && !book.isImported) {
                    // One clean re-download before reporting an error.
                    val refreshed = bookRepository.fetchAndSaveFullBook(
                        book = book,
                        forceRefresh = true
                    ).getOrNull()
                    if (refreshed != null) {
                        chapters = bookImporter.getChaptersForBook(book, limit = 1, offset = targetChapter)
                    }
                }
                if (chapters.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoadingBook = false,
                            errorMessage = "The downloaded file is not a readable EPUB or text book. Please choose another edition."
                        )
                    }
                    return@launch
                }

                if (!progressRestored && progress != null) {
                    progressRestored = true
                    _uiState.update {
                        it.copy(
                            currentChapterIndex = targetChapter,
                            currentPageIndex = progress.currentPage
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        book = book,
                        chapters = chapters,
                        totalChapterCount = totalChapters,
                        isLoadingBook = false,
                        errorMessage = null
                    )
                }

                // P1-3: load cheap TOC titles (no content) so the sheet works
                // with lazy loading instead of showing blank placeholders.
                viewModelScope.launch {
                    val titles = runCatching { bookImporter.getChapterTitles(book) }
                        .getOrDefault(emptyList())
                    if (titles.isNotEmpty()) {
                        _uiState.update { it.copy(tocTitles = titles) }
                    }
                }

                // Trigger initial pagination if container size is ready
                repaginateCurrentChapter()
                // Prefetch the next chapter in the background
                prefetchNextChapter()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val challenge = error as? ChallengeRequiredException
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        challengeUrl = challenge?.url,
                        errorMessage = if (challenge != null) null
                            else UserErrorMessages.messageFor(error, "Failed to open this book.")
                    )
                }
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
                    // Settings changed: all cached pages are now stale.
                    invalidatePagination()
                    repaginateCurrentChapter()
                }
            }
        }
    }

    private fun invalidatePagination() {
        paginationJob?.cancel()
        prefetchJob?.cancel()
        paginationCache.clear()
    }

    fun onContainerDimensionsChanged(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (this.availableWidthPx != widthPx || this.availableHeightPx != heightPx) {
            invalidatePagination()
            this.availableWidthPx = widthPx
            this.availableHeightPx = heightPx
            repaginateCurrentChapter()
        }
    }

    /**
     * Resolves a chapter by its own index instead of by list position.
     *
     * The chapter list is built by absolute index and padded lazily, so position
     * and chapter number are not the same thing: after resuming at chapter 1 the
     * list holds one element at position 0 whose index is 1. Trusting position
     * alone renders a padded blank placeholder — the book goes blank after a
     * rotation or a settings change.
     */
    private fun chapterAt(index: Int): BookChapter? =
        _uiState.value.chapters.getOrNull(index)
            ?.takeIf { it.index == index && it.content.isNotBlank() }

    /**
     * Atomically stores [chapter] at position `chapter.index`, padding the gap
     * with blank placeholders that also carry their own position as index.
     */
    private fun putChapter(chapter: BookChapter) {
        _uiState.update { state ->
            val updated = state.chapters.toMutableList()
            while (updated.size <= chapter.index) {
                updated.add(BookChapter(title = "", content = "", index = updated.size))
            }
            updated[chapter.index] = chapter
            state.copy(chapters = updated)
        }
    }

    /** Loads a single chapter from the importer and merges it into the state. */
    private suspend fun loadChapter(index: Int): BookChapter? {
        val book = _uiState.value.book ?: return null
        val loaded = bookImporter.getChaptersForBook(book, limit = 1, offset = index).firstOrNull()
            ?: return null
        putChapter(loaded)
        return loaded
    }

    /**
     * Index of the last chapter, preferring the known total over the lazily
     * loaded list. With lazy loading `chapters` usually holds a single element
     * while the real book has dozens, so `chapters.size` is not a valid bound.
     */
    private fun lastChapterIndex(state: ReaderUiState = _uiState.value): Int =
        ((state.totalChapterCount.takeIf { it > 0 } ?: state.chapters.size) - 1).coerceAtLeast(0)

    fun repaginateCurrentChapter() {
        if (_uiState.value.chapters.isEmpty() || availableWidthPx <= 0 || availableHeightPx <= 0) return

        paginationJob?.cancel()
        paginationJob = viewModelScope.launch {
            _uiState.update { it.copy(isPaginating = true) }
            try {
                val currentState = _uiState.value
                val settings = currentState.readerSettings
                val chapterIdx = currentState.currentChapterIndex.coerceAtLeast(0)
                val chapter = chapterAt(chapterIdx) ?: loadChapter(chapterIdx)
                if (chapter == null) {
                    _uiState.update {
                        it.copy(isPaginating = false, errorMessage = "Chapter $chapterIdx not found.")
                    }
                    return@launch
                }

                // Check the cache first: if we've already paginated this chapter
                // with the same settings and screen size, reuse the result.
                val cacheKey = chapter.index
                val cached = paginationCache[cacheKey]
                val pages = if (cached != null) {
                    cached
                } else {
                    val fresh = paginationEngine.paginateChapter(
                        chapter = chapter,
                        settings = settings,
                        availableWidthPx = availableWidthPx,
                        availableHeightPx = availableHeightPx
                    )
                    currentCoroutineContext().ensureActive()
                    paginationCache[cacheKey] = fresh
                    fresh
                }

                _uiState.update {
                    it.copy(
                        pagesForCurrentChapter = pages,
                        currentPageIndex = it.currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
                        isPaginating = false
                    )
                }

                saveCurrentProgress()
                // After pagination, prefetch the next chapter if not cached
                prefetchNextChapter()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isPaginating = false,
                        errorMessage = UserErrorMessages.messageFor(e, "This page could not be displayed.")
                    )
                }
            }
        }
    }

    /**
     * Background prefetch: loads the next chapter's pages so that turning
     * forward is instant. Cancelled when the user navigates away.
     */
    private fun prefetchNextChapter() {
        val state = _uiState.value
        if (state.book == null) return
        val nextIdx = state.currentChapterIndex + 1
        val total = state.totalChapterCount
        if (total > 0 && nextIdx >= total) return
        if (paginationCache.containsKey(nextIdx)) return // Already cached

        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoadingNextChapter = true) }

                // Load the next chapter from DB/file if we don't have it.
                // Resolved by index, never by position: a padded placeholder from
                // an earlier prefetch must not be mistaken for real content.
                val nextChapter = chapterAt(nextIdx) ?: loadChapter(nextIdx)
                    ?: return@launch

                if (availableWidthPx <= 0 || availableHeightPx <= 0) return@launch

                val pages = paginationEngine.paginateChapter(
                    chapter = nextChapter,
                    settings = _uiState.value.readerSettings,
                    availableWidthPx = availableWidthPx,
                    availableHeightPx = availableHeightPx
                )
                currentCoroutineContext().ensureActive()
                paginationCache[nextChapter.index] = pages
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Next-chapter prefetch failed", e)
            } finally {
                _uiState.update { it.copy(isLoadingNextChapter = false) }
            }
        }
    }

    fun nextPage() {
        val state = _uiState.value
        if (state.currentPageIndex < state.pagesForCurrentChapter.size - 1) {
            _uiState.value = state.copy(currentPageIndex = state.currentPageIndex + 1)
            saveCurrentProgress()
        } else if (state.currentChapterIndex < lastChapterIndex(state)) {
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
        flushProgress()
    }

    /** Retries the download after the browser check cleared, via [challengeUrl]. */
    fun retryAfterChallenge() {
        _uiState.update { it.copy(challengeUrl = null) }
        loadBookAndChapters()
    }

    /** Full reload from the empty/error state (used by the Retry button). */
    fun retryLoad() {
        _uiState.update { it.copy(errorMessage = null) }
        loadBookAndChapters()
    }

    fun dismissChallenge() {
        _uiState.update { it.copy(challengeUrl = null) }
    }

    fun goToChapter(chapterIndex: Int, targetPageIndex: Int = 0) {
        if (_uiState.value.chapters.isEmpty()) return
        val validChapterIdx = chapterIndex.coerceIn(0, lastChapterIndex())

        _uiState.update {
            it.copy(
                currentChapterIndex = validChapterIdx,
                currentPageIndex = 0,
                showTocDialog = false
            )
        }

        paginationJob?.cancel()
        paginationJob = viewModelScope.launch {
                _uiState.update { it.copy(isPaginating = true) }
                try {
                    // Check cache first
                    val cached = paginationCache[validChapterIdx]
                if (cached != null) {
                    val pageIdx = if (targetPageIndex == Int.MAX_VALUE) {
                        (cached.size - 1).coerceAtLeast(0)
                    } else {
                        targetPageIndex.coerceIn(0, (cached.size - 1).coerceAtLeast(0))
                    }
                    _uiState.update {
                        it.copy(
                            pagesForCurrentChapter = cached,
                            currentPageIndex = pageIdx,
                            isPaginating = false
                        )
                    }
                    saveCurrentProgress()
                    prefetchNextChapter()
                    return@launch
                }

                // Need the chapter loaded: if not in the list, fetch it.
                // `loadChapter` merges atomically, so a concurrent prefetch cannot
                // be clobbered by a merge into a stale snapshot.
                val chapter = chapterAt(validChapterIdx) ?: loadChapter(validChapterIdx)

                if (chapter == null) {
                    _uiState.update {
                        it.copy(isPaginating = false, errorMessage = "Chapter $validChapterIdx not found.")
                    }
                    return@launch
                }

                val pages = paginationEngine.paginateChapter(
                    chapter = chapter,
                    settings = _uiState.value.readerSettings,
                    availableWidthPx = availableWidthPx,
                    availableHeightPx = availableHeightPx
                )
                currentCoroutineContext().ensureActive()
                paginationCache[chapter.index] = pages

                val pageIdx = if (targetPageIndex == Int.MAX_VALUE) {
                    (pages.size - 1).coerceAtLeast(0)
                } else {
                    targetPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                }

                _uiState.update {
                    it.copy(
                        pagesForCurrentChapter = pages,
                        currentPageIndex = pageIdx,
                        isPaginating = false
                    )
                }
                saveCurrentProgress()
                prefetchNextChapter()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isPaginating = false,
                        errorMessage = UserErrorMessages.messageFor(e, "This page could not be displayed.")
                    )
                }
            }
        }
    }

    private fun saveCurrentProgress() {
        val state = _uiState.value
        val currentBook = state.book ?: return
        val totalChapters = (state.totalChapterCount.takeIf { it > 0 } ?: state.chapters.size).coerceAtLeast(1)
        val percent = ((state.currentChapterIndex.toFloat() + (state.currentPageIndex.toFloat() / state.pagesForCurrentChapter.size.coerceAtLeast(1))) / totalChapters * 100f).coerceIn(0f, 100f)

        // P1-4: conflate — no coroutine/transaction per tap, the debounced
        // collector persists the latest value.
        pendingProgress.value = ReadingProgress(
            bookId = currentBook.id,
            currentChapter = state.currentChapterIndex,
            currentPage = state.currentPageIndex,
            totalPagesInChapter = state.pagesForCurrentChapter.size,
            percentCompleted = percent,
            lastReadTimestamp = System.currentTimeMillis()
        )
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

        // P1-6: no user text leaves the device without explicit opt-in.
        viewModelScope.launch {
            val enabled = preferencesManager.cloudLookupEnabled.first()
            val asked = preferencesManager.cloudConsentAsked.first()
            if (!asked) {
                pendingWord = cleanWord to contextSentence
                _uiState.update {
                    it.copy(
                        selectedWordState = SelectedWordState(
                            word = cleanWord,
                            contextSentence = contextSentence,
                            isLoadingDict = false,
                            isLoadingTrans = false
                        ),
                        showCloudConsentDialog = true
                    )
                }
                return@launch
            }
            if (!enabled) {
                _uiState.update {
                    it.copy(
                        selectedWordState = SelectedWordState(
                            word = cleanWord,
                            contextSentence = contextSentence,
                            isLoadingDict = false,
                            isLoadingTrans = false,
                            // N-3: the hint text lives in strings.xml.
                            translationBlockedOffline = true
                        )
                    )
                }
                return@launch
            }
            lookupWordCloud(cleanWord, contextSentence)
        }
    }

    /** P1-6: cloud path — dictionary + translation leave the device. */
    private fun lookupWordCloud(cleanWord: String, contextSentence: String) {
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
                        // P2-8: user-friendly message, never raw exception text.
                        translationError = transResult.exceptionOrNull()?.let { err ->
                            UserErrorMessages.messageFor(err, "Translation is unavailable offline.")
                        }
                    )
                )
            }
        }
    }

    fun requestAiExplanation() {
        val selected = _uiState.value.selectedWordState ?: return
        // P1-6: AI explanation also sends user text to the cloud.
        if (!_uiState.value.cloudLookupEnabled) {
            _uiState.update {
                it.copy(
                    selectedWordState = selected.copy(
                        isLoadingAi = false,
                        // N-3: the hint text lives in strings.xml.
                        aiBlockedOffline = true
                    ),
                    showAiExplanationDialog = true
                )
            }
            return
        }
        _uiState.value = _uiState.value.copy(
            selectedWordState = selected.copy(isLoadingAi = true),
            showAiExplanationDialog = true
        )

        viewModelScope.launch {
            val aiResult = aiRepository.explainWordOrSentence(selected.word, selected.contextSentence)
            val current = _uiState.value.selectedWordState
            if (current?.word == selected.word) {
                _uiState.value = _uiState.value.copy(
                    selectedWordState = current.copy(
                        aiExplanation = aiResult.getOrNull(),
                        isLoadingAi = false,
                        // P2-8: user-friendly message, never raw exception text.
                        aiError = aiResult.exceptionOrNull()?.let { err ->
                            UserErrorMessages.messageFor(err, "AI explanation is unavailable.")
                        }
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
            val current = _uiState.value.selectedWordState
            if (current?.word == selected.word) {
                _uiState.value = _uiState.value.copy(
                    selectedWordState = current.copy(isWordSaved = true)
                )
            }
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
        private val paginationEngine: Paginator
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

    private companion object {
        /** How many chapters' paginated pages to keep in memory. */
        const val PAGINATION_CACHE_SIZE = 10
        const val TAG = "ReaderViewModel"
    }
}
