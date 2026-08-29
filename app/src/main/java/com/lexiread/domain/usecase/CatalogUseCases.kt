package com.lexiread.domain.usecase

import com.lexiread.domain.model.Book
import com.lexiread.domain.model.BookFormat
import com.lexiread.domain.model.CatalogBook
import com.lexiread.domain.model.CatalogPage
import com.lexiread.domain.model.Category
import com.lexiread.domain.model.SourceKind
import com.lexiread.domain.repository.BooksRepository

/**
 * Catalogue use cases.
 *
 * They are thin on purpose — their job is to keep query normalization, empty
 * input handling and default source selection out of the ViewModel, not to add
 * another layer of indirection around the repository.
 */

class SearchBooksUseCase(private val repository: BooksRepository) {
    suspend operator fun invoke(
        query: String,
        page: Int = FIRST_PAGE,
        sources: Set<SourceKind> = DEFAULT_SOURCES
    ): CatalogPage {
        val normalized = query.trim()
        if (normalized.isBlank()) return CatalogPage.empty(page)
        return repository.search(normalized, page, sources)
    }
}

class GetPopularBooksUseCase(private val repository: BooksRepository) {
    suspend operator fun invoke(
        page: Int = FIRST_PAGE,
        sources: Set<SourceKind> = DEFAULT_SOURCES
    ): CatalogPage = repository.getPopular(page, sources)
}

class GetBooksByCategoryUseCase(private val repository: BooksRepository) {
    suspend operator fun invoke(
        category: Category,
        page: Int = FIRST_PAGE,
        sources: Set<SourceKind> = DEFAULT_SOURCES
    ): CatalogPage = repository.getByCategory(category, page, sources)

    fun categories(): List<Category> = repository.categories()
}

class GetBookDetailsUseCase(private val repository: BooksRepository) {
    suspend operator fun invoke(bookId: String): CatalogBook? = repository.getBookDetails(bookId)
}

class ResolveReadableFormatUseCase(private val repository: BooksRepository) {
    suspend operator fun invoke(book: CatalogBook): BookFormat? =
        repository.resolveReadableFormat(book)
}

/**
 * Downloads the best available free edition and registers it in the library.
 * Fails with a clear message when the book is not public domain.
 */
class OpenBookForReadingUseCase(private val repository: BooksRepository) {
    suspend operator fun invoke(book: CatalogBook): Result<Book> {
        if (!book.canRead && book.identifiers.gutenbergId == null) {
            return Result.failure(
                UnsupportedOperationException(
                    "'${book.title}' has no free edition we are allowed to download."
                )
            )
        }
        return repository.downloadForReading(book)
    }
}

internal const val FIRST_PAGE = 1

/** All three catalogues are on by default; the user can narrow the selection. */
val DEFAULT_SOURCES: Set<SourceKind> = SourceKind.entries.toSet()
