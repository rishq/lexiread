package com.lexiread.domain.repository

import com.lexiread.domain.model.Book
import com.lexiread.domain.model.BookFormat
import com.lexiread.domain.model.CatalogBook
import com.lexiread.domain.model.CatalogPage
import com.lexiread.domain.model.Category
import com.lexiread.domain.model.SourceKind

/**
 * Hides the differences between the three catalogues behind one API.
 *
 * This is intentionally separate from [BookRepository]: that one owns the
 * user's local library and the legacy [BookSource] downloaders, while this one
 * owns discovery, metadata merging and pagination.
 */
interface BooksRepository {

    fun categories(): List<Category>

    /** Free-text search across the selected sources, 1-based [page]. */
    suspend fun search(query: String, page: Int, sources: Set<SourceKind>): CatalogPage

    /** Most-downloaded / most-held titles. */
    suspend fun getPopular(page: Int, sources: Set<SourceKind>): CatalogPage

    suspend fun getByCategory(
        category: Category,
        page: Int,
        sources: Set<SourceKind>
    ): CatalogPage

    /**
     * Fetches the richest record available for [id], merging metadata from the
     * other catalogues when the same edition is found there.
     */
    suspend fun getBookDetails(id: String): CatalogBook?

    /** Best readable format for this book, or null when reading is not possible. */
    suspend fun resolveReadableFormat(book: CatalogBook): BookFormat?

    /**
     * Downloads the best format and registers the result in the local library.
     * Returns the stored [Book], ready to be opened by the reader.
     */
    suspend fun downloadForReading(book: CatalogBook): Result<Book>
}
