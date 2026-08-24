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
     * being read as UTF-8 produces replacement chars. Decode heuristically:
     * prefer UTF-8 unless it decodes with loss and the file is not valid UTF-8.
     */
    private fun readWithEncodingDetection(file: File): String {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return ""

        // Strip UTF-8 BOM if present
        val bomLess = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else bytes

        // Strict UTF-8 decode: any malformed sequence means the file is not UTF-8
        val isStrictUtf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bomLess))
        }.isSuccess

        return if (isStrictUtf8) String(bomLess, Charsets.UTF_8)
        else String(bomLess, charset("windows-1251"))
    }
}
