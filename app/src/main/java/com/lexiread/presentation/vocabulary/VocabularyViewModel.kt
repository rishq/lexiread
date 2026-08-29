package com.lexiread.presentation.vocabulary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.core.util.TTSHelper
import com.lexiread.domain.model.LearningStatus
import com.lexiread.domain.model.SavedWord
import com.lexiread.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VocabularyFilter {
    ALL,
    NEW,
    LEARNING,
    KNOWN
}

data class VocabularyUiState(
    val filter: VocabularyFilter = VocabularyFilter.ALL,
    val words: List<SavedWord> = emptyList(),
    /**
     * Frozen card queue captured when review started. It is resolved by word id,
     * so changing a status mid-session (which re-filters [words]) can never shift
     * a card out from under the current index.
     */
    val reviewWords: List<SavedWord> = emptyList(),
    val isReviewMode: Boolean = false,
    val currentReviewIndex: Int = 0,
    val isCardFlipped: Boolean = false,
    val isLoading: Boolean = false
)

class VocabularyViewModel(
    private val vocabularyRepository: VocabularyRepository,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    /**
     * All flashcard state lives in one flow: keeping it in separate flows made
     * partial updates observable (e.g. a new index with a stale queue) and
     * exceeded the arity Kotlin's `combine` supports.
     */
    private data class ReviewState(
        val isActive: Boolean = false,
        val queueIds: List<Int> = emptyList(),
        val index: Int = 0,
        val isFlipped: Boolean = false
    )

    private val _filter = MutableStateFlow(VocabularyFilter.ALL)
    private val _review = MutableStateFlow(ReviewState())

    val uiState: StateFlow<VocabularyUiState> = combine(
        _filter,
        vocabularyRepository.getSavedWords(),
        _review
    ) { filter, allWords, review ->
        val filtered = when (filter) {
            VocabularyFilter.ALL -> allWords
            VocabularyFilter.NEW -> allWords.filter { it.learningStatus == LearningStatus.NEW }
            VocabularyFilter.LEARNING -> allWords.filter { it.learningStatus == LearningStatus.LEARNING }
            VocabularyFilter.KNOWN -> allWords.filter { it.learningStatus == LearningStatus.KNOWN }
        }

        // Resolve the frozen queue by id so the review order stays stable even
        // when a status change re-filters the live list.
        val byId = allWords.associateBy { it.id }
        val reviewQueue = review.queueIds.mapNotNull { byId[it] }

        VocabularyUiState(
            filter = filter,
            words = filtered,
            reviewWords = reviewQueue,
            isReviewMode = review.isActive,
            currentReviewIndex = review.index.coerceIn(0, (reviewQueue.size - 1).coerceAtLeast(0)),
            isCardFlipped = review.isFlipped,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VocabularyUiState(isLoading = true)
    )

    fun selectFilter(filter: VocabularyFilter) {
        _filter.value = filter
    }

    fun speak(text: String) {
        ttsHelper.speak(text)
    }

    fun updateStatus(wordId: Int, newStatus: LearningStatus) {
        viewModelScope.launch {
            vocabularyRepository.updateStatus(wordId, newStatus)
        }
    }

    fun deleteWord(wordId: Int) {
        viewModelScope.launch {
            vocabularyRepository.deleteWord(wordId)
        }
    }

    fun startReviewMode() {
        // Snapshot the queue so later status changes cannot reorder it.
        val queue = uiState.value.words.map { it.id }
        if (queue.isEmpty()) return // Nothing to review; never enter a dead-end state.
        _review.value = ReviewState(isActive = true, queueIds = queue, index = 0, isFlipped = false)
    }

    fun exitReviewMode() {
        _review.value = ReviewState()
    }

    fun flipCard() {
        _review.update { it.copy(isFlipped = !it.isFlipped) }
    }

    fun nextReviewCard(markAsKnown: Boolean = false) {
        // Iterate the frozen queue, not the live filtered list: marking a card
        // KNOWN removes it from the filtered list and would otherwise skip the
        // next card.
        val review = _review.value
        val queue = uiState.value.reviewWords

        if (review.index in queue.indices) {
            val currentWord = queue[review.index]
            updateStatus(
                currentWord.id,
                if (markAsKnown) LearningStatus.KNOWN else LearningStatus.LEARNING
            )
        }

        if (review.index + 1 < queue.size) {
            _review.update { it.copy(index = it.index + 1, isFlipped = false) }
        } else {
            // Finished review
            exitReviewMode()
        }
    }

    class Factory(
        private val vocabularyRepository: VocabularyRepository,
        private val ttsHelper: TTSHelper
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VocabularyViewModel(vocabularyRepository, ttsHelper) as T
        }
    }
}
