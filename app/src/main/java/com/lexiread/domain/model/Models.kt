package com.lexiread.domain.model

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
    val marginDp: Int = 20,
    val volumeKeysPageTurn: Boolean = false
) {
    companion object {
        /**
         * M22: one range for every font-size slider. The reader sheet allowed up
         * to 30sp while the settings screen capped at 28sp, so a size picked
         * while reading silently snapped down the first time the settings
         * slider was touched.
         */
        val FONT_SIZE_RANGE = 14f..30f
    }
}

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
    val chapterTitle: String,
    /**
     * Offset of [text] inside the chapter content. Page text is trimmed, so
     * this already accounts for the trimmed leading whitespace — adding a
     * page-local offset yields a stable chapter offset for highlights.
     */
    val startOffsetInChapter: Int = 0
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
    val learningStatus: LearningStatus = LearningStatus.NEW,
    // SM-2 spaced repetition
    val srsReps: Int = 0,
    val srsEase: Double = 2.5,
    val srsIntervalDays: Int = 0,
    val lastReviewEpoch: Long = 0,
    val nextReviewEpoch: Long = 0
) {
    val isDue: Boolean
        get() = nextReviewEpoch == 0L || System.currentTimeMillis() >= nextReviewEpoch
}

data class Bookmark(
    val id: Int = 0,
    val bookId: String,
    val scrollOffset: Int,
    val snippet: String,
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/** Highlight color keys. The key is persisted; the actual color lives in UI. */
object HighlightColorKeys {
    const val YELLOW = "yellow"
    const val GREEN = "green"
    const val BLUE = "blue"
    const val PINK = "pink"
    const val DEFAULT = YELLOW
}

/**
 * A persisted user text highlight. Offsets are chapter-content offsets
 * (see [HighlightEntity]), [colorKey] is one of [HighlightColorKeys].
 */
data class Highlight(
    val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val colorKey: String = HighlightColorKeys.DEFAULT,
    val createdAt: Long = System.currentTimeMillis()
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

