package com.example.domain.model

enum class LearningStatus {
    NEW,
    LEARNING,
    KNOWN
}

enum class ReaderThemeOption {
    LIGHT,
    DARK,
    SEPIA
}

data class ReaderSettings(
    val theme: ReaderThemeOption = ReaderThemeOption.SEPIA,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.4f,
    val fontFamilyName: String = "Serif",
    val contentWidthPercent: Float = 100f,
    val marginDp: Int = 20,
    val isPaginated: Boolean = true,
    val volumeKeysPageTurn: Boolean = false
)

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null,
    val description: String? = null,
    val fullText: String? = null,
    val filePath: String? = null,
    val format: String = "TXT",
    val language: String = "en",
    val subjects: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val isSaved: Boolean = false,
    val isFinished: Boolean = false,
    val isImported: Boolean = false,
    val addedTimestamp: Long = System.currentTimeMillis()
)

data class BookChapter(
    val title: String,
    val content: String,
    val index: Int
)

data class ReaderPage(
    val chapterIndex: Int,
    val pageIndex: Int,
    val totalPagesInChapter: Int,
    val text: String,
    val chapterTitle: String
)

data class ReadingProgress(
    val bookId: String,
    val scrollOffset: Int = 0,
    val currentChapter: Int = 0,
    val currentPage: Int = 0,
    val totalPagesInChapter: Int = 1,
    val totalLength: Int = 0,
    val percentCompleted: Float = 0f,
    val lastReadTimestamp: Long = System.currentTimeMillis()
)

data class SavedWord(
    val id: Int = 0,
    val word: String,
    val translation: String,
    val definition: String = "",
    val phonetics: String = "",
    val partOfSpeech: String = "",
    val example: String = "",
    val sourceBookId: String = "",
    val sourceBookTitle: String = "",
    val sourceSentence: String = "",
    val dateAdded: Long = System.currentTimeMillis(),
    val learningStatus: LearningStatus = LearningStatus.NEW
)

data class Bookmark(
    val id: Int = 0,
    val bookId: String,
    val scrollOffset: Int,
    val snippet: String,
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class DefinitionMeaning(
    val partOfSpeech: String,
    val definitions: List<String>,
    val example: String? = null,
    val synonyms: List<String> = emptyList()
)

data class DictionaryEntry(
    val word: String,
    val phonetics: String = "",
    val audioUrl: String? = null,
    val meanings: List<DefinitionMeaning> = emptyList()
)

data class TranslationResult(
    val sourceText: String,
    val translatedText: String,
    val sourceLang: String = "en",
    val targetLang: String = "ru"
)

data class AiExplanation(
    val wordOrSentence: String,
    val contextSentence: String,
    val simpleEnglish: String,
    val russianTranslation: String,
    val whyUsed: String,
    val grammarExplanation: String,
    val exampleSentences: List<String>
)

