package com.lexiread.core.reader.parsers

import com.lexiread.core.reader.BookParser
import com.lexiread.core.reader.ChapterParser
import com.lexiread.core.reader.ParsedBookMetadata
import com.lexiread.core.util.TextEncoding
import com.lexiread.domain.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TxtParser : BookParser {

    override fun canParse(format: String, file: File): Boolean {
        return format.lowercase() == "txt" || file.extension.lowercase() == "txt"
    }

    override suspend fun parseChapters(file: File): List<BookChapter> = withContext(Dispatchers.IO) {
        val text = readWithEncodingDetection(file)
        ChapterParser.splitIntoChapters(text)
    }

    override suspend fun extractMetadata(file: File): ParsedBookMetadata = withContext(Dispatchers.IO) {
        val nameWithoutExt = file.nameWithoutExtension
        val title = nameWithoutExt.replace("_", " ").replace("-", " ")
        ParsedBookMetadata(
            title = title,
            author = "Unknown Author",
            description = "Plain text document imported from local storage."
        )
    }

    /**
     * Many TXT books (especially Russian ones) are saved in Windows-1251 while
     * being read as UTF-8 produces replacement chars. [TextEncoding] resolves the
     * charset from the BOM / declared encoding and only falls back to Windows-1251
     * when the bytes are not valid UTF-8.
     */
    private fun readWithEncodingDetection(file: File): String {
        return file.inputStream().use { stream ->
            TextEncoding.readText(stream, MAX_FILE_SIZE_BYTES)
        }
    }

    private companion object {
        const val MAX_FILE_SIZE_BYTES = 40L * 1024 * 1024
    }
}
