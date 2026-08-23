package com.lexiread.core.reader

import com.lexiread.domain.model.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterParserTest {

    @Test
    fun `cleanParagraphs joins wrapped lines and keeps blank-line paragraphs`() {
        val raw = "Line one\nwrapped here\n\nSecond paragraph\ncontinues"
        val result = ChapterParser.cleanParagraphs(raw)
        assertEquals("Line one wrapped here\n\nSecond paragraph continues", result)
    }

    @Test
    fun `splitIntoChapters detects explicit chapter markers`() {
        val raw = "Chapter 1: Start\n\nSome text.\n\nChapter 2: End\n\nMore text."
        val chapters = ChapterParser.splitIntoChapters(raw)
        val titles = chapters.map { it.title }

        assertTrue("Expected >=2 chapters, got $titles", chapters.size >= 2)
        assertTrue("First title was $titles", chapters[0].title.startsWith("Chapter 1"))
        assertTrue("Second title was $titles", chapters[1].title.startsWith("Chapter 2"))
        assertEquals(0, chapters[0].index)
        assertEquals(1, chapters[1].index)
    }

    @Test
    fun `splitIntoChapters chunks long text without markers by size`() {
        val paragraph = "word ".repeat(100).trim()
        val raw = (1..40).joinToString("\n\n") { "$paragraph $it" }
        val chapters = ChapterParser.splitIntoChapters(raw)

        assertTrue("Expected multiple chunked chapters, got ${chapters.size}", chapters.size > 1)
        assertEquals(chapters.indices.toList(), chapters.map { it.index })
    }

    @Test
    fun `splitIntoChapters returns single chapter for short text`() {
        val chapters = ChapterParser.splitIntoChapters("Just a short story.")
        assertEquals(1, chapters.size)
        assertEquals("Chapter 1", chapters[0].title)
    }

    @Test
    fun `splitIntoChapters handles blank input`() {
        val chapters: List<BookChapter> = ChapterParser.splitIntoChapters("   ")
        assertEquals(1, chapters.size)
    }
}
