package com.example.core.reader.parsers

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.core.reader.BookParser
import com.example.core.reader.ParsedBookMetadata
import com.example.domain.model.BookChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PdfParser : BookParser {

    override fun canParse(format: String, file: File): Boolean {
        return format.lowercase() == "pdf" || file.extension.lowercase() == "pdf"
    }

    override suspend fun parseChapters(file: File): List<BookChapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<BookChapter>()
        var pfd: ParcelFileDescriptor? = null
        var pdfRenderer: PdfRenderer? = null

        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pfd)
            val pageCount = pdfRenderer.pageCount

            val textContent = StringBuilder()
            // Extract text from file or create section pages
            val fileText = try {
                file.readText(Charsets.ISO_8859_1)
            } catch (e: Exception) {
                ""
            }

            val extractedText = extractPdfText(fileText)

            if (extractedText.isNotBlank()) {
                val lines = extractedText.split("\n\n").filter { it.isNotBlank() }
                var currentChunk = StringBuilder()
                var chapterIdx = 0

                for (line in lines) {
                    if (currentChunk.length + line.length > 5000 && currentChunk.isNotEmpty()) {
                        chapters.add(
                            BookChapter(
                                title = "Page Section ${chapterIdx + 1}",
                                content = currentChunk.toString().trim(),
                                index = chapterIdx
                            )
                        )
                        chapterIdx++
                        currentChunk = StringBuilder()
                    }
                    if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                    currentChunk.append(line)
                }

                if (currentChunk.isNotEmpty()) {
                    chapters.add(
                        BookChapter(
                            title = "Page Section ${chapterIdx + 1}",
                            content = currentChunk.toString().trim(),
                            index = chapterIdx
                        )
                    )
                }
            } else {
                chapters.add(
                    BookChapter(
                        title = "PDF Document ($pageCount pages)",
                        content = "PDF document imported with $pageCount pages. Content is formatted for optimized page rendering.",
                        index = 0
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            chapters.add(
                BookChapter(
                    title = "PDF Document",
                    content = "Unable to extract raw text from this PDF document.",
                    index = 0
                )
            )
        } finally {
            pdfRenderer?.close()
            pfd?.close()
        }

        if (chapters.isEmpty()) {
            chapters.add(BookChapter("Document", file.nameWithoutExtension, 0))
        }
        chapters
    }

    override suspend fun extractMetadata(file: File): ParsedBookMetadata = withContext(Dispatchers.IO) {
        var pageCount = 0
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdfRenderer = PdfRenderer(pfd)
            pageCount = pdfRenderer.pageCount
            pdfRenderer.close()
            pfd.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val title = file.nameWithoutExtension.replace("_", " ")
        ParsedBookMetadata(
            title = title,
            author = "Unknown Author",
            description = "PDF Document ($pageCount pages)"
        )
    }

    private fun extractPdfText(rawPdfString: String): String {
        val result = StringBuilder()
        val streamRegex = Regex("stream[\\r\\n]+([\\s\\S]*?)[\\r\\n]+endstream")
        val tjRegex = Regex("\\((.*?)\\)\\s*TJ|\\((.*?)\\)\\s*Tj", RegexOption.IGNORE_CASE)

        for (match in streamRegex.findAll(rawPdfString)) {
            val streamData = match.groupValues[1]
            for (tjMatch in tjRegex.findAll(streamData)) {
                val textPart = tjMatch.groupValues[1].ifEmpty { tjMatch.groupValues[2] }
                if (textPart.isNotBlank() && textPart.length > 2) {
                    result.append(textPart).append(" ")
                }
            }
        }

        return result.toString().trim()
            .replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\n", "\n")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
    }
}
