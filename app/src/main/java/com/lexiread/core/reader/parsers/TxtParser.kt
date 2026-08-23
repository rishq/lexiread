package com.lexiread.core.reader.parsers

import com.lexiread.core.reader.BookParser
import com.lexiread.core.reader.ChapterParser
import com.lexiread.core.reader.ParsedBookMetadata
import com.lexiread.domain.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TxtParser : BookParser {

    override fun canParse(format: String, file: File): Boolean {
        return format.lowercase() == "txt" || file.extension.lowercase() == "txt"
    }

    override suspend fun parseChapters(file: File): List<BookChapter> = withContext(Dispatchers.IO) {
        val text = file.readText(Charsets.UTF_8)
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
}
