package com.lexiread.data.mapper

import com.lexiread.core.util.BookFormatSelector
import com.lexiread.core.util.forceHttps
import com.lexiread.data.remote.googlebooks.GoogleBooksVolumeDto
import com.lexiread.data.remote.gutendex.GutendexBookDto
import com.lexiread.data.remote.openlibrary.OpenLibraryDocDto
import com.lexiread.data.remote.openlibrary.OpenLibraryWorkDto
import com.lexiread.domain.model.Author
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.BookIdentifiers
import com.lexiread.domain.model.CatalogBook
import com.lexiread.domain.model.SourceKind

/**
 * DTO -> domain mapping for all three catalogues.
 *
 * Keeping the mapping out of the repository means the merging/dedupe logic in
 * BooksRepositoryImpl operates purely on domain types and can be unit tested
 * without any Retrofit or Moshi involvement.
 */

// --- Gutendex (Project Gutenberg) ---

fun GutendexBookDto.toCatalogBook(): CatalogBook = CatalogBook(
    id = GUTENDEX_PREFIX + id,
    title = title.trim(),
    authors = authors.orEmpty().mapNotNull { person ->
        person.name?.trim()?.takeIf { it.isNotBlank() }
            ?.let { Author(displayAuthorName(it), person.birth_year, person.death_year) }
    },
    coverUrl = BookFormatSelector.coverUrl(formats),
    description = (summaries?.firstOrNull() ?: subjects?.take(DESCRIPTION_SUBJECT_LIMIT)?.joinToString(" • "))
        ?.let(::stripHtml)
        ?.takeIf { it.isNotBlank() },
    language = languages?.firstOrNull(),
    subjects = subjects.orEmpty(),
    source = SourceKind.GUTENDEX,
    formats = BookFormatSelector.fromGutendexFormats(formats),
    identifiers = BookIdentifiers(gutenbergId = id),
    // `copyright` is null for many older records; only an explicit true blocks us.
    isPublicDomain = copyright != true,
    downloadCount = download_count
)

// --- Open Library ---

/** Returns null for records without a usable work key or title. */
fun OpenLibraryDocDto.toCatalogBook(): CatalogBook? {
    val workKey = key.orEmpty().substringAfterLast('/').trim().takeIf { it.isNotBlank() } ?: return null
    val bookTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val isbns = isbn.orEmpty().map { it.replace("-", "").replace(" ", "").trim() }

    return CatalogBook(
        id = OPEN_LIBRARY_PREFIX + workKey,
        title = bookTitle,
        authors = author_name.orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { Author(it) },
        coverUrl = openLibraryCoverUrl(cover_i),
        description = null,
        language = language?.firstOrNull(),
        subjects = subject.orEmpty().take(SUBJECT_LIMIT),
        source = SourceKind.OPEN_LIBRARY,
        // Open Library exposes no public-domain full text we may stream.
        formats = emptyList(),
        identifiers = BookIdentifiers(
            openLibraryWorkId = workKey,
            gutenbergId = id_gutenberg?.firstOrNull()?.trim()?.toIntOrNull(),
            isbn13 = isbns.firstOrNull { it.length == 13 },
            isbn10 = isbns.firstOrNull { it.length == 10 }
        ),
        isPublicDomain = false,
        publishedYear = first_publish_year
    )
}

fun openLibraryCoverUrl(coverId: Int?): String? =
    coverId?.takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" }

/**
 * Detail view of a single work. Author names are not resolved here: Open
 * Library returns only `/authors/OL…` references, and fetching each one would
 * cost N extra requests for a screen that already has the names from search.
 */
fun OpenLibraryWorkDto.toCatalogBook(workId: String): CatalogBook = CatalogBook(
    id = OPEN_LIBRARY_PREFIX + workId,
    title = title?.trim()?.takeIf { it.isNotBlank() } ?: workId,
    authors = emptyList(),
    coverUrl = openLibraryCoverUrl(covers?.firstOrNull()),
    description = descriptionText(),
    subjects = subjects.orEmpty().take(SUBJECT_LIMIT),
    source = SourceKind.OPEN_LIBRARY,
    formats = emptyList(),
    identifiers = BookIdentifiers(openLibraryWorkId = workId),
    isPublicDomain = false
)

// --- Google Books ---

/** Returns null for volumes without a usable id or title. */
fun GoogleBooksVolumeDto.toCatalogBook(): CatalogBook? {
    val volumeId = id?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val info = volumeInfo ?: return null
    val bookTitle = info.title?.trim()?.takeIf { it.isNotBlank() } ?: return null

    val identifiers = info.industryIdentifiers.orEmpty()
    val cover = (info.imageLinks?.thumbnail ?: info.imageLinks?.smallThumbnail)
        ?.takeIf { it.isNotBlank() }
        ?.forceHttps()

    return CatalogBook(
        id = GOOGLE_BOOKS_PREFIX + volumeId,
        title = bookTitle,
        authors = info.authors.orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { Author(it) },
        coverUrl = cover,
        description = info.description?.let(::stripHtml)?.takeIf { it.isNotBlank() },
        language = info.language,
        subjects = info.categories.orEmpty().take(SUBJECT_LIMIT),
        source = SourceKind.GOOGLE_BOOKS,
        // Intentionally always empty: this source is metadata-only.
        formats = emptyList(),
        identifiers = BookIdentifiers(
            googleBooksId = volumeId,
            isbn13 = identifiers.firstOrNull { it.type == "ISBN_13" }?.identifier?.normalizeIsbn(),
            isbn10 = identifiers.firstOrNull { it.type == "ISBN_10" }?.identifier?.normalizeIsbn()
        ),
        isPublicDomain = accessInfo?.publicDomain == true,
        publishedYear = info.publishedDate?.take(4)?.toIntOrNull()
    )
}

// --- domain bridge ---

/**
 * Projects a catalogue entry onto the library [Book] used by the reader and the
 * Room entity. Content stays null: nothing is downloaded until the user opens
 * the book.
 */
fun CatalogBook.toDomainBook(): Book = Book(
    id = id,
    title = title,
    author = authorLine,
    coverUrl = coverUrl,
    description = description,
    fullText = null,
    filePath = null,
    format = BookFormatSelector.pickBest(formats)?.kind?.name ?: DEFAULT_FORMAT,
    language = language ?: DEFAULT_LANGUAGE,
    subjects = subjects,
    isSaved = false
)

// --- shared helpers ---

internal fun String.normalizeIsbn(): String = replace("-", "").replace(" ", "").trim()

/**
 * Gutendex stores names the way a library catalogue does — "Austen, Jane" —
 * while Open Library and Google Books use "Jane Austen". Flipping the two parts
 * here means cards read the same whichever catalogue supplied them; it also
 * removes a formatting difference the dedupe layer would otherwise have to
 * compensate for.
 *
 * Only the plain "surname, forename" shape is rewritten. Names carrying extra
 * suffixes ("Doyle, Arthur Conan, Sir") or no comma at all are left untouched
 * rather than guesswork-mangled.
 */
internal fun displayAuthorName(raw: String): String {
    val parts = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
    return if (parts.size == 2) "${parts[1]} ${parts[0]}" else raw
}

/**
 * Google Books descriptions contain markup. The catalogue only needs readable
 * prose, so tags are dropped with a regex rather than pulling `Html.fromHtml`
 * into a pure JVM mapping layer.
 */
internal fun stripHtml(raw: String): String = raw
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
    .replace(Regex("<[^>]*>"), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .trim()

const val GUTENDEX_PREFIX = "gutenberg_"
const val OPEN_LIBRARY_PREFIX = "ol_"
const val GOOGLE_BOOKS_PREFIX = "gb_"
const val INTERNET_ARCHIVE_PREFIX = "ia_"
const val STANDARD_EBOOKS_PREFIX = "se_"

private const val SUBJECT_LIMIT = 8
private const val DESCRIPTION_SUBJECT_LIMIT = 6
private const val DEFAULT_LANGUAGE = "en"
private const val DEFAULT_FORMAT = "TXT"
