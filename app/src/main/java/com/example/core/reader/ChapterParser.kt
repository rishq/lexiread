package com.example.core.reader

import android.text.Html
import com.example.domain.model.BookChapter

object ChapterParser {

    fun cleanHtmlText(htmlContent: String): String {
        if (htmlContent.isBlank()) return ""
        val spanned = try {
            Html.fromHtml(htmlContent, Html.FROM_HTML_MODE_LEGACY)
        } catch (e: Exception) {
            null
        }
        val text = spanned?.toString() ?: stripTags(htmlContent)
        return cleanParagraphs(text)
    }

    private fun stripTags(raw: String): String {
        return raw.replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
    }

    fun cleanParagraphs(rawText: String): String {
        val lines = rawText.split("\n")
            .map { it.trim() }
        
        val result = StringBuilder()
        var currentParagraph = StringBuilder()

        for (line in lines) {
            if (line.isEmpty()) {
                if (currentParagraph.isNotEmpty()) {
                    result.append(currentParagraph.toString().trim()).append("\n\n")
                    currentParagraph = StringBuilder()
                }
            } else {
                if (currentParagraph.isNotEmpty()) {
                    currentParagraph.append(" ")
                }
                currentParagraph.append(line)
            }
        }
        if (currentParagraph.isNotEmpty()) {
            result.append(currentParagraph.toString().trim())
        }

        return result.toString().trim()
    }

    fun splitIntoChapters(rawText: String): List<BookChapter> {
        val cleaned = cleanParagraphs(rawText)
        if (cleaned.isBlank()) return listOf(BookChapter("Chapter 1", "No content available.", 0))

        // Detect chapter markers like "Chapter 1", "CHAPTER I", "Глава 1", "BOOK 1"
        val chapterRegex = Regex("(?i)\n\n\\s*(chapter|глава|book|part|часть)\\s+([0-9a-ivxlcdm]+|[一二三四五六七八九十]+).*\n\n")
        val matches = chapterRegex.findAll("\n\n$cleaned\n\n").toList()

        if (matches.size > 1) {
            val chapters = mutableListOf<BookChapter>()
            for (i in matches.indices) {
                val match = matches[i]
                val title = match.value.trim()
                val start = match.range.last + 1
                val end = if (i < matches.size - 1) matches[i + 1].range.first else cleaned.length
                val content = if (start < end && start < cleaned.length) {
                    cleaned.substring(start.coerceAtMost(cleaned.length), end.coerceAtMost(cleaned.length)).trim()
                } else ""

                if (content.isNotBlank()) {
                    chapters.add(BookChapter(title = title, content = content, index = chapters.size))
                }
            }
            if (chapters.isNotEmpty()) return chapters
        }

        // Fallback: chunk by 15,000 characters (~3,000 words per chapter) on paragraph boundaries
        val paragraphs = cleaned.split("\n\n").filter { it.isNotBlank() }
        val chapters = mutableListOf<BookChapter>()
        var currentChapterText = StringBuilder()
        var chapterIndex = 0

        for (p in paragraphs) {
            if (currentChapterText.length + p.length > 15000 && currentChapterText.isNotEmpty()) {
                chapters.add(
                    BookChapter(
                        title = "Chapter ${chapterIndex + 1}",
                        content = currentChapterText.toString().trim(),
                        index = chapterIndex
                    )
                )
                chapterIndex++
                currentChapterText = StringBuilder()
            }
            if (currentChapterText.isNotEmpty()) {
                currentChapterText.append("\n\n")
            }
            currentChapterText.append(p)
        }

        if (currentChapterText.isNotEmpty()) {
            chapters.add(
                BookChapter(
                    title = "Chapter ${chapterIndex + 1}",
                    content = currentChapterText.toString().trim(),
                    index = chapterIndex
                )
            )
        }

        return if (chapters.isEmpty()) {
            listOf(BookChapter("Chapter 1", cleaned, 0))
        } else {
            chapters
        }
    }
}
