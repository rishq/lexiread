package com.lexiread.core.reader

import com.lexiread.domain.model.BookChapter
import java.io.File

interface BookParser {
    fun canParse(format: String, file: File): Boolean
    suspend fun parseChapters(file: File): List<BookChapter>
    suspend fun extractMetadata(file: File): ParsedBookMetadata
}

data class ParsedBookMetadata(
    val title: String?,
    val author: String?,
    val description: String?,
    val coverPath: String? = null
)
