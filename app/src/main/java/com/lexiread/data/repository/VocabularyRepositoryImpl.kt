package com.lexiread.data.repository

import com.lexiread.core.util.SrsScheduler
import com.lexiread.data.local.dao.SavedWordDao
import com.lexiread.data.local.entity.SavedWordEntity
import com.lexiread.domain.model.LearningStatus
import com.lexiread.domain.model.SavedWord
import com.lexiread.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class VocabularyRepositoryImpl(
    private val savedWordDao: SavedWordDao
) : VocabularyRepository {

    override fun getSavedWords(): Flow<List<SavedWord>> {
        return savedWordDao.getAllSavedWords().map { entities -> entities.map { it.toDomain() } }
    }

    override fun getWordsByStatus(status: LearningStatus): Flow<List<SavedWord>> {
        return savedWordDao.getWordsByStatus(status.name).map { entities -> entities.map { it.toDomain() } }
    }

    /**
     * Due words, re-evaluated on every emission.
     *
     * The clock is read inside [map], not once when the flow is built. Room
     * re-emits whenever the table changes, but a timestamp captured at
     * construction time stays frozen for the life of the flow — words that
     * become due while the screen is open would never appear, and the badge
     * count would be wrong until the process restarted.
     */
    override fun getDueWords(): Flow<List<SavedWord>> =
        savedWordDao.getAllSavedWords().map { entities ->
            val now = System.currentTimeMillis()
            entities.map { it.toDomain() }
                .filter { it.nextReviewEpoch == 0L || it.nextReviewEpoch <= now }
                .sortedWith(compareBy({ it.nextReviewEpoch }, { -it.dateAdded }))
        }

    override fun getDueWordCount(): Flow<Int> =
        savedWordDao.getAllSavedWords().map { entities ->
            val now = System.currentTimeMillis()
            entities.count { it.nextReviewEpoch == 0L || it.nextReviewEpoch <= now }
        }

    override suspend fun isWordSaved(word: String): Boolean {
        return savedWordDao.getWordByText(word.trim().lowercase()) != null
    }

    override suspend fun saveWord(savedWord: SavedWord) {
        val now = System.currentTimeMillis()
        val srs = SrsScheduler.initialState(now)
        savedWordDao.insertWord(savedWord.toEntity(srs))
    }

    override suspend fun updateStatus(id: Int, status: LearningStatus) {
        savedWordDao.updateStatus(id, status.name)
    }

    override suspend fun reviewWord(id: Int, rating: SrsScheduler.ReviewRating) {
        val entity = savedWordDao.getWordById(id) ?: return
        val word = entity.toDomain()
        val now = System.currentTimeMillis()
        val current = SrsScheduler.SrsState(
            reps = word.srsReps,
            ease = word.srsEase,
            intervalDays = word.srsIntervalDays,
            lastReviewEpoch = word.lastReviewEpoch,
            nextReviewEpoch = word.nextReviewEpoch
        )
        val next = SrsScheduler.review(current, rating, now)
        val status = when {
            next.reps == 0 -> LearningStatus.NEW
            next.intervalDays >= 21 -> LearningStatus.KNOWN
            else -> LearningStatus.LEARNING
        }
        savedWordDao.updateSrs(
            id = id,
            reps = next.reps,
            ease = next.ease,
            intervalDays = next.intervalDays,
            lastReview = next.lastReviewEpoch,
            nextReview = next.nextReviewEpoch,
            status = status.name
        )
    }

    suspend fun reviewWord(word: SavedWord, rating: SrsScheduler.ReviewRating) {
        reviewWord(word.id, rating)
    }

    override suspend fun deleteWord(id: Int) {
        savedWordDao.deleteWord(id)
    }
}

// Mappers
fun SavedWordEntity.toDomain() = SavedWord(
    id = id,
    word = word,
    translation = translation,
    definition = definition,
    phonetics = phonetics,
    partOfSpeech = partOfSpeech,
    example = example,
    sourceBookId = sourceBookId,
    sourceBookTitle = sourceBookTitle,
    sourceSentence = sourceSentence,
    dateAdded = dateAdded,
    learningStatus = try {
        LearningStatus.valueOf(learningStatus)
    } catch (e: Exception) {
        LearningStatus.NEW
    },
    srsReps = srsReps,
    srsEase = srsEase,
    srsIntervalDays = srsIntervalDays,
    lastReviewEpoch = lastReviewEpoch,
    nextReviewEpoch = nextReviewEpoch
)

fun SavedWord.toEntity(srs: SrsScheduler.SrsState? = null) = SavedWordEntity(
    id = id,
    word = word,
    translation = translation,
    definition = definition,
    phonetics = phonetics,
    partOfSpeech = partOfSpeech,
    example = example,
    sourceBookId = sourceBookId,
    sourceBookTitle = sourceBookTitle,
    sourceSentence = sourceSentence,
    dateAdded = dateAdded,
    learningStatus = learningStatus.name,
    srsReps = srs?.reps ?: srsReps,
    srsEase = srs?.ease ?: srsEase,
    srsIntervalDays = srs?.intervalDays ?: srsIntervalDays,
    lastReviewEpoch = srs?.lastReviewEpoch ?: lastReviewEpoch,
    nextReviewEpoch = srs?.nextReviewEpoch ?: nextReviewEpoch
)
