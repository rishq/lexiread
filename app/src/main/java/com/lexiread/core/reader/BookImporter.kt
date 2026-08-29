package com.lexiread.core.reader

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lexiread.core.reader.parsers.EpubParser
import com.lexiread.core.reader.parsers.Fb2Parser
import com.lexiread.core.reader.parsers.HtmlParser
import com.lexiread.core.reader.parsers.PdfParser
import com.lexiread.core.reader.parsers.TxtParser
import com.lexiread.domain.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class BookImporter(private val context: Context) {

    companion object {
        private const val TAG = "BookImporter"
    }

    private val parsers: List<BookParser> = listOf(
        EpubParser(),
        HtmlParser(),
        PdfParser(),
        Fb2Parser(),
        TxtParser()
    )

    suspend fun importBookFromUri(uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: ""
            var fileName = getFileNameFromUri(uri) ?: "imported_book_${System.currentTimeMillis()}"
            val extension = getExtension(fileName, mimeType)

            val importedDir = File(context.filesDir, "imported_books")
            if (!importedDir.exists()) {
                importedDir.mkdirs()
            }

            val bookId = "import_${UUID.randomUUID().toString().take(12)}"
            val destinationFile = File(importedDir, "$bookId.$extension")

            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext Result.failure(Exception("Could not open file stream."))

            val parser = parsers.firstOrNull { it.canParse(extension, destinationFile) }
                ?: TxtParser()

            val metadata = parser.extractMetadata(destinationFile)
            val chapters = parser.parseChapters(destinationFile)

            val fullTextPreview = chapters.take(3).joinToString("\n\n") { it.content }.take(2000)

            val book = Book(
                id = bookId,
                title = metadata.title ?: fileName,
                author = metadata.author ?: "Unknown Author",
                coverUrl = metadata.coverPath,
                description = metadata.description ?: "Imported personal book.",
                fullText = fullTextPreview,
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

    suspend fun getChaptersForBook(book: Book): List<com.lexiread.domain.model.BookChapter> = withContext(Dispatchers.IO) {
        val path = book.filePath
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                val ext = book.format.lowercase()
                val parser = parsers.firstOrNull { it.canParse(ext, file) } ?: TxtParser()
                return@withContext parser.parseChapters(file)
            }
        }

        // Fallback for preloaded/downloaded text books
        val rawText = book.fullText ?: book.description ?: ""
        ChapterParser.splitIntoChapters(rawText)
    }
}
