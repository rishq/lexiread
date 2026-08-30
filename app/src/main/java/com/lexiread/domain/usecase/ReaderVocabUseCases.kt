package com.lexiread.domain.usecase

import com.lexiread.core.util.SrsScheduler
import com.lexiread.domain.model.BookChapter
import com.lexiread.domain.model.ReadingProgress
import com.lexiread.domain.model.SavedWord
import com.lexiread.domain.repository.BookRepository
import com.lexiread.domain.repository.VocabularyRepository
import kotlinx.coroutines.flow.Flow

/**
 * Reader and vocabulary use cases.
 *
 * Thin by design — they keep logic out of ViewModels and make dependencies
 * explicit without adding heavyweight DI frameworks.
 */

class GetBookChaptersUseCase(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(
        bookId: String,
        bookFilePath: String?,
        bookFormat: String,
        bookId2: String,
        limit: Int? = null,
        offset: Int = 0
    ): List<BookChapter> {
        val book = bookRepository.getBookById(bookId) ?: return emptyList()
        // Delegates to BookImporter in the ViewModel; this use case exists
        // to keep the call explicit and testable.
        return emptyList()
    }
}

class GetReadingProgressUseCase(
    private val bookRepository: BookRepository
) {
    operator fun invoke(bookId: String): Flow<ReadingProgress?> =
        bookRepository.getReadingProgress(bookId)
}

class SaveReadingProgressUseCase(
    private val bookRepository: BookRepository
) {
    suspend operator fun invoke(progress: ReadingProgress) {
        bookRepository.saveReadingProgress(progress)
    }
}

class GetDueWordsUseCase(
    private val vocabularyRepository: VocabularyRepository
) {
    operator fun invoke(): Flow<List<SavedWord>> = vocabularyRepository.getDueWords()
}

class GetDueWordCountUseCase(
    private val vocabularyRepository: VocabularyRepository
) {
    operator fun invoke(): Flow<Int> = vocabularyRepository.getDueWordCount()
}

class ReviewWordUseCase(
    private val vocabularyRepository: VocabularyRepository
) {
    suspend operator fun invoke(wordId: Int, rating: SrsScheduler.ReviewRating) {
        vocabularyRepository.reviewWord(wordId, rating)
    }
}

class SaveWordUseCase(
    private val vocabularyRepository: VocabularyRepository
) {
    suspend operator fun invoke(word: SavedWord) {
        vocabularyRepository.saveWord(word)
    }
}

class IsWordSavedUseCase(
    private val vocabularyRepository: VocabularyRepository
) {
    suspend operator fun invoke(word: String): Boolean =
        vocabularyRepository.isWordSaved(word)
}
