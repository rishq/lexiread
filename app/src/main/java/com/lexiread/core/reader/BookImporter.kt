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

            // Persist chapters to DB if a DAO was provided.
            if (chapterDao != null) {
                val chapterEntities = chapters.take(MAX_CHAPTERS).mapIndexed { index, ch ->
                    ChapterEntity(
                        bookId = bookId,
                        title = ch.title,
                        content = ch.content.take(MAX_CHAPTER_BYTES.toInt()),
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
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex != -1) it.getLong(sizeIndex) else 0L
            } else 0L
        } ?: 0L
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
        val rawText = book.fullText ?: book.description ?: ""
        ChapterParser.splitIntoChapters(rawText)
    }

    /**
     * Returns the total chapter count for a book without loading all content.
     */
    suspend fun getChapterCount(bookId: String): Int = withContext(Dispatchers.IO) {
        chapterDao?.getChapterCount(bookId) ?: 0
    }
}
