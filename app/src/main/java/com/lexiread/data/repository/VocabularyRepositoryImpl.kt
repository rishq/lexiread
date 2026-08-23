package com.lexiread.data.repository

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

    override suspend fun isWordSaved(word: String): Boolean {
        return savedWordDao.getWordByText(word.trim().lowercase()) != null
    }

    override suspend fun saveWord(savedWord: SavedWord) {
        savedWordDao.insertWord(savedWord.toEntity())
    }

    override suspend fun updateStatus(id: Int, status: LearningStatus) {
        savedWordDao.updateStatus(id, status.name)
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
    }
)

fun SavedWord.toEntity() = SavedWordEntity(
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
    learningStatus = learningStatus.name
)
