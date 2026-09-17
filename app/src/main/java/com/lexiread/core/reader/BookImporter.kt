package com.lexiread.core.reader

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lexiread.core.reader.parsers.EpubParser
import com.lexiread.core.reader.parsers.Fb2Parser
import com.lexiread.core.reader.parsers.HtmlParser
import com.lexiread.core.reader.parsers.PdfParser
import com.lexiread.core.reader.parsers.TxtParser
import com.lexiread.data.local.dao.ChapterDao
import com.lexiread.data.local.entity.ChapterEntity
import com.lexiread.domain.model.Book
import com.lexiread.domain.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class BookImporter(
    private val context: Context,
    private val chapterDao: ChapterDao? = null
) {

    companion object {
        private const val TAG = "BookImporter"
        const val MAX_IMPORT_BYTES = 100L * 1024 * 1024 // 100 MB hard cap
        const val MAX_CHAPTER_BYTES = 5L * 1024 * 1024  // 5 MB per chapter content
        const val MAX_CHAPTERS = 5_000
    }

    private val parsers: List<BookParser> = listOf(
        EpubParser(),
        HtmlParser(),
        PdfParser(),
        Fb2Parser(),
        TxtParser()
    )

    /**
     * Imports a book from a content URI. Validates file size before copying,
     * then delegates to the appropriate parser.
     *
     * Returns a [Result] with a clear error message for:
     * - oversized files
     * - corrupt/unsupported formats
     * - OOM scenarios
     */
    suspend fun importBookFromUri(uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: ""
            var fileName = getFileNameFromUri(uri) ?: "imported_book_${System.currentTimeMillis()}"
            val extension = getExtension(fileName, mimeType)

            // Size check: query the document for its size before copying.
            // P2-4: getFileSize() returns -1 when unknown — skip the pre-check
            // then and rely on the guarded copy loop below.
            val fileSize = getFileSize(uri)
            if (fileSize > MAX_IMPORT_BYTES) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "File is too large to import (${fileSize / (1024 * 1024)} MB). " +
                            "Maximum supported size is ${MAX_IMPORT_BYTES / (1024 * 1024)} MB."
                    )
                )
            }

            val importedDir = File(context.filesDir, "imported_books")
            if (!importedDir.exists()) {
                importedDir.mkdirs()
            }

            val bookId = "import_${UUID.randomUUID().toString().take(12)}"
            val destinationFile = File(importedDir, "$bookId.$extension")

            // Copy with size guard: stop if the stream exceeds the limit.
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var totalCopied = 0L
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read <= 0) break
                        totalCopied += read
                        if (totalCopied > MAX_IMPORT_BYTES) {
                            destinationFile.delete()
                            return@withContext Result.failure(
                                IllegalArgumentException(
                                    "File exceeded the ${MAX_IMPORT_BYTES / (1024 * 1024)} MB import limit during copy."
                                )
                            )
                        }
                        outputStream.write(buffer, 0, read)
                    }
                }
            } ?: return@withContext Result.failure(Exception("Could not open file stream."))

            val parser = parsers.firstOrNull { it.canParse(extension, destinationFile) }
                ?: TxtParser()

            val metadata = try {
                parser.extractMetadata(destinationFile)
            } catch (e: OutOfMemoryError) {
                destinationFile.delete()
                return@withContext Result.failure(
                    IllegalStateException("Out of memory while reading '$fileName'. The file may be too large or corrupted.")
                )
            } catch (e: Exception) {
                Log.w(TAG, "Metadata extraction failed for $fileName, using fallback", e)
                com.lexiread.core.reader.ParsedBookMetadata(
                    title = fileName.substringBeforeLast('.').replace("_", " "),
                    author = "Unknown Author",
                    description = "Imported book."
                )
            }

            val chapters = try {
                parser.parseChapters(destinationFile)
            } catch (e: OutOfMemoryError) {
                destinationFile.delete()
                return@withContext Result.failure(
                    IllegalStateException("Out of memory while parsing '$fileName'. The file may be a zip bomb or corrupted.")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse chapters from $fileName", e)
                emptyList()
            }

            if (chapters.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Could not extract readable text from '$fileName'. " +
                            "The file may be corrupted, encrypted, or in an unsupported format."
                    )
                )
            }

            // P2-3: cap BEFORE persist so the full list becomes GC-eligible
            // immediately — parseChapters() still returns everything, but we
            // never hold two full copies (raw + entities) at once.
            val cappedChapters = chapters.take(MAX_CHAPTERS).map { ch ->
                if (ch.content.length.toLong() > MAX_CHAPTER_BYTES) {
                    ch.copy(content = ch.content.take(MAX_CHAPTER_BYTES.toInt()))
                } else ch
            }

            // Persist chapters to DB if a DAO was provided.
            if (chapterDao != null) {
                val chapterEntities = cappedChapters.mapIndexed { index, ch ->
                    ChapterEntity(
                        bookId = bookId,
                        title = ch.title,
                        content = ch.content,
                        chapterIndex = index
                    )
                }
                chapterDao.insertChapters(chapterEntities)
            }

            val book = Book(
                id = bookId,
                title = metadata.title ?: fileName,
                author = metadata.author ?: "Unknown Author",
                coverUrl = metadata.coverPath,
                description = metadata.description ?: "Imported personal book.",
                fullText = null, // No longer stored inline — chapters are in DB
                filePath = destinationFile.absolutePath,
                format = extension.uppercase(),
                language = "en",
                isFavorite = false,
                isSaved = true,
                isFinished = false,
                isImported = true,
                addedTimestamp = System.currentTimeMillis()
            )

            Result.success(book)
        } catch (e: Exception) {
            Log.e(TAG, "Error importing book from uri: $uri", e)
            Result.failure(e)
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name ?: uri.lastPathSegment
    }

    private fun getExtension(fileName: String, mimeType: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext.length <= 5) return ext

        return when {
            mimeType.contains("epub") -> "epub"
            mimeType.contains("pdf") -> "pdf"
            mimeType.contains("fictionbook") || mimeType.contains("fb2") -> "fb2"
            mimeType.contains("mobi") -> "mobi"
            else -> "txt"
        }
    }

    private fun getFileSize(uri: Uri): Long {
        // P2-4: -1 = unknown (missing SIZE column or empty cursor). Callers must
        // not treat it as "empty file" — the copy loop below enforces the cap
        // regardless, so an unknown size still cannot overflow storage.
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex != -1) it.getLong(sizeIndex) else -1L
            } else -1L
        } ?: -1L
    }

    /**
     * Returns chapters for a book. Tries the DB first (for imported/preloaded
     * books), then falls back to parsing the file on disk.
     *
     * Supports lazy loading: [limit] and [offset] allow fetching a window
     * of chapters instead of the entire book at once.
     */
    suspend fun getChaptersForBook(
        book: Book,
        limit: Int? = null,
        offset: Int = 0
    ): List<BookChapter> = withContext(Dispatchers.IO) {
        // Try DB first (preloaded and imported books store chapters here).
        if (chapterDao != null) {
            val dbChapters = if (limit != null) {
                chapterDao.getChaptersRange(book.id, limit, offset)
            } else {
                chapterDao.getChaptersForBook(book.id)
            }
            if (dbChapters.isNotEmpty()) {
                return@withContext dbChapters.map { ch ->
                    BookChapter(title = ch.title, content = ch.content, index = ch.chapterIndex)
                }
            }
        }

        // Fall back to parsing the file on disk.
        val path = book.filePath
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                val ext = book.format.lowercase()
                val parser = parsers.firstOrNull { it.canParse(ext, file) } ?: TxtParser()
                val allChapters = try {
                    parser.parseChapters(file)
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "OOM while parsing chapters from ${file.name}", e)
                    emptyList()
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing chapters from ${file.name}", e)
                    emptyList()
                }

                // If DB is available, cache the parsed chapters for next time.
                if (chapterDao != null && allChapters.isNotEmpty()) {
                    val entities = allChapters.take(MAX_CHAPTERS).mapIndexed { index, ch ->
                        ChapterEntity(
                            bookId = book.id,
                            title = ch.title,
                            content = ch.content.take(MAX_CHAPTER_BYTES.toInt()),
                            chapterIndex = index
                        )
                    }
                    runCatching { chapterDao.insertChapters(entities) }
                }

                val windowed = if (limit != null) {
                    allChapters.drop(offset).take(limit)
                } else {
                    allChapters
                }
                return@withContext windowed
            }
        }

        // Last resort: try fullText (for legacy books without a file).
        // limit/offset apply here too — the reader asks for one chapter at a
        // time, and splitting the whole book defeats lazy loading.
        val rawText = book.fullText ?: book.description ?: ""
        val allChapters = ChapterParser.splitIntoChapters(rawText)
        if (limit != null) allChapters.drop(offset).take(limit) else allChapters.drop(offset)
    }

    /**
     * Total chapter count without loading all content. For books whose chapters
     * live in the DB this reads the count directly; for legacy full-text books
     * (no DB rows) it counts the full-text chapters.
     *
     * Navigation bounds rely on this being the *real* total, not the lazily
     * loaded window that [getChaptersForBook] may return.
     */
    suspend fun getChapterCount(book: Book): Int = withContext(Dispatchers.IO) {
        val dbCount = chapterDao?.getChapterCount(book.id) ?: 0
        if (dbCount > 0) return@withContext dbCount
        book.fullText?.let { ChapterParser.splitIntoChapters(it).size } ?: 0
    }

    /**
     * P1-3: cheap TOC titles without loading chapter content. DB titles first;
     * falls back to parsing (titles only are kept, content is dropped).
     */
    suspend fun getChapterTitles(book: Book): List<BookChapter> = withContext(Dispatchers.IO) {
        if (chapterDao != null) {
            val rows = chapterDao.getChapterTitles(book.id)
            if (rows.isNotEmpty()) {
                return@withContext rows.map { row ->
                    BookChapter(title = row.title.ifBlank { "Chapter ${row.chapterIndex + 1}" }, content = "", index = row.chapterIndex)
                }
            }
        }
        // Fallback: parse and keep titles only (content dropped to save memory).
        val path = book.filePath
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                val ext = book.format.lowercase()
                val parser = parsers.firstOrNull { it.canParse(ext, file) } ?: TxtParser()
                val parsed = try {
                    parser.parseChapters(file)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing titles from ${file.name}", e)
                    emptyList()
                }
                if (parsed.isNotEmpty()) {
                    return@withContext parsed.map { ch -> ch.copy(content = "") }
                }
            }
        }
        val rawText = book.fullText ?: book.description ?: ""
        if (rawText.isNotBlank()) {
            return@withContext ChapterParser.splitIntoChapters(rawText)
                .map { ch -> ch.copy(content = "") }
        }
        emptyList()
    }

    /**
     * Returns the total chapter count for a book without loading all content.
     */
    suspend fun getChapterCount(bookId: String): Int = withContext(Dispatchers.IO) {
        chapterDao?.getChapterCount(bookId) ?: 0
    }
}
