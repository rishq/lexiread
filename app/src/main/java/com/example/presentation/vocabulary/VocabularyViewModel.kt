package com.example.presentation.vocabulary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.util.TTSHelper
import com.example.domain.model.LearningStatus
import com.example.domain.model.SavedWord
import com.example.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    val isReviewMode: Boolean = false,
    val currentReviewIndex: Int = 0,
    val isCardFlipped: Boolean = false,
    val isLoading: Boolean = false
)

class VocabularyViewModel(
    private val vocabularyRepository: VocabularyRepository,
    private val ttsHelper: TTSHelper
) : ViewModel() {

    private val _filter = MutableStateFlow(VocabularyFilter.ALL)
    private val _isReviewMode = MutableStateFlow(false)
    private val _reviewIndex = MutableStateFlow(0)
    private val _isCardFlipped = MutableStateFlow(false)

    val uiState: StateFlow<VocabularyUiState> = combine(
        _filter,
        vocabularyRepository.getSavedWords(),
        _isReviewMode,
        _reviewIndex,
        _isCardFlipped
    ) { filter, allWords, isReview, reviewIdx, isFlipped ->
        val filtered = when (filter) {
            VocabularyFilter.ALL -> allWords
            VocabularyFilter.NEW -> allWords.filter { it.learningStatus == LearningStatus.NEW }
            VocabularyFilter.LEARNING -> allWords.filter { it.learningStatus == LearningStatus.LEARNING }
            VocabularyFilter.KNOWN -> allWords.filter { it.learningStatus == LearningStatus.KNOWN }
        }

        VocabularyUiState(
            filter = filter,
            words = filtered,
            isReviewMode = isReview,
            currentReviewIndex = reviewIdx.coerceIn(0, (filtered.size - 1).coerceAtLeast(0)),
            isCardFlipped = isFlipped,
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
        _isReviewMode.value = true
        _reviewIndex.value = 0
        _isCardFlipped.value = false
    }

    fun exitReviewMode() {
        _isReviewMode.value = false
    }

    fun flipCard() {
        _isCardFlipped.value = !_isCardFlipped.value
    }

    fun nextReviewCard(markAsKnown: Boolean = false) {
        val currentWords = uiState.value.words
        if (currentWords.isNotEmpty() && _reviewIndex.value < currentWords.size) {
            val currentWord = currentWords[_reviewIndex.value]
            if (markAsKnown) {
                updateStatus(currentWord.id, LearningStatus.KNOWN)
            } else {
                updateStatus(currentWord.id, LearningStatus.LEARNING)
            }
        }

        if (_reviewIndex.value + 1 < currentWords.size) {
            _reviewIndex.value += 1
            _isCardFlipped.value = false
        } else {
            // Finished review
            _isReviewMode.value = false
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
