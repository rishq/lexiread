package com.lexiread.domain.model

/**
 * Unified domain model for the online catalogue. Every remote provider
 * (Gutendex, Open Library, Google Books) is mapped into these types, so the
 * presentation layer never sees a provider-specific DTO.
 */

/** Which online catalogue a [CatalogBook] was found in. */
enum class SourceKind {
    GUTENDEX,
    OPEN_LIBRARY,
    GOOGLE_BOOKS,
    INTERNET_ARCHIVE,
    STANDARD_EBOOKS
}

/** Physical container of a readable file. */
enum class FormatKind {
    EPUB,
    HTML,
    TXT,
    UNKNOWN
}

/**
 * A single downloadable representation of a book.
 *
 * [mimeType] is kept exactly as the API reported it: format choice is driven by
 * the response, never by a hardcoded URL template.
 */
data class BookFormat(
    val kind: FormatKind,
    val mimeType: String,
    val url: String
)

data class Author(
    val name: String,
    val birthYear: Int? = null,
    val deathYear: Int? = null
)

/**
 * Cross-source identity of a book. Used to merge the same edition returned by
 * several catalogues into one card.
 */
data class BookIdentifiers(
    val gutenbergId: Int? = null,
    val openLibraryWorkId: String? = null,
    val googleBooksId: String? = null,
    val isbn13: String? = null,
    val isbn10: String? = null
)

data class CatalogBook(
    val id: String,
    val title: String,
    val authors: List<Author> = emptyList(),
    val coverUrl: String? = null,
    val description: String? = null,
    val language: String? = null,
    val subjects: List<String> = emptyList(),
    val source: SourceKind,
    val formats: List<BookFormat> = emptyList(),
    val identifiers: BookIdentifiers = BookIdentifiers(),
    /** True when the source declares the work free of copyright restrictions. */
    val isPublicDomain: Boolean = false,
    val downloadCount: Int? = null,
    val publishedYear: Int? = null
) {
    val authorLine: String
        get() = authors.joinToString(", ") { it.name }.ifBlank { UNKNOWN_AUTHOR }

    /**
     * Readable means: we are allowed to fetch the text and we actually know a
     * URL for it. Google Books records therefore never report true, because
     * their protected content is deliberately never downloaded.
     */
    val canRead: Boolean
        get() = isPublicDomain && formats.isNotEmpty()

    companion object {
        const val UNKNOWN_AUTHOR = "Unknown Author"
    }
}

data class CatalogPage(
    val books: List<CatalogBook>,
    val page: Int,
    val totalResults: Int?,
    val hasMore: Boolean
) {
    companion object {
        fun empty(page: Int = 1): CatalogPage = CatalogPage(
            books = emptyList(),
            page = page,
            totalResults = 0,
            hasMore = false
        )
    }
}

/** A browseable subject. [query] is the provider-neutral search term. */
data class Category(
    val id: String,
    val title: String,
    val query: String
)
