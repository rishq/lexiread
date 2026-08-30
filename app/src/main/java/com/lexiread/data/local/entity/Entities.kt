package com.lexiread.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val description: String?,
    val filePath: String? = null,
    val format: String = "TXT",
    val language: String,
    val subjects: String, // Comma separated
    val isFavorite: Boolean,
    val isSaved: Boolean,
    val isFinished: Boolean,
    val isImported: Boolean = false,
    val addedTimestamp: Long
)

/**
 * Lightweight projection for list queries — excludes the potentially
 * multi-megabyte `fullText` column.
 */
data class BookMeta(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val description: String?,
    val filePath: String?,
    val format: String,
    val language: String,
    val subjects: String,
    val isFavorite: Boolean,
    val isSaved: Boolean,
    val isFinished: Boolean,
    val isImported: Boolean,
    val addedTimestamp: Long
)

/**
 * Stores book chapters separately so the [BookEntity] row stays lightweight.
 * Chapters are loaded on demand by the reader, not all at once.
 */
@Entity(
    tableName = "book_chapters",
    indices = [Index("bookId")]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val title: String,
    val content: String,
    val chapterIndex: Int
)

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val scrollOffset: Int,
    val currentChapter: Int,
    val currentPage: Int = 0,
    val totalPagesInChapter: Int = 1,
    val totalLength: Int,
    val percentCompleted: Float,
    val lastReadTimestamp: Long
)

@Entity(
    tableName = "saved_words",
    indices = [Index("word"), Index("nextReviewEpoch")]
)
data class SavedWordEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val word: String,
    val translation: String,
    val definition: String,
    val phonetics: String,
    val partOfSpeech: String,
    val example: String,
    val sourceBookId: String,
    val sourceBookTitle: String,
    val sourceSentence: String,
    val dateAdded: Long,
    val learningStatus: String = "NEW",
    // SM-2 spaced repetition fields
    val srsReps: Int = 0,
    val srsEase: Double = 2.5,
    val srsIntervalDays: Int = 0,
    val lastReviewEpoch: Long = 0,
    val nextReviewEpoch: Long = 0
)

@Entity(
    tableName = "bookmarks",
    indices = [Index("bookId")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val bookId: String,
    val scrollOffset: Int,
    val snippet: String,
    val note: String?,
    val timestamp: Long
)

@Entity(tableName = "dictionary_cache")
data class DictionaryCacheEntity(
    @PrimaryKey val word: String,
    val jsonResult: String,
    val timestamp: Long
)

@Entity(tableName = "translation_cache")
data class TranslationCacheEntity(
    @PrimaryKey val cacheKey: String,
    val sourceText: String,
    val translatedText: String,
    val targetLang: String,
    val timestamp: Long
)

@Entity(tableName = "ai_explanation_cache")
data class AiExplanationEntity(
    @PrimaryKey val cacheKey: String,
    val sourceText: String,
    val contextSentence: String,
    val simpleEnglish: String,
    val russianTranslation: String,
    val whyUsed: String,
    val grammarExplanation: String,
    val examplesJson: String,
    val timestamp: Long
)
