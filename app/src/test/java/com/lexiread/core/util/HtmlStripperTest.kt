package com.lexiread.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the linear tag stripper that replaced the quadratic
 * `Regex("<[^>]*>")` / block-removal regexes on the catalogue and PGA HTML paths.
 */
class HtmlStripperTest {

    @Test
    fun `drops tags and script blocks`() {
        val html = "<html><body><p>Hello <b>world</b></p><script>bad()</script></body></html>"

        val text = HtmlStripper.stripTags(html)

        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("world"))
        assertFalse("script content must be dropped", text.contains("bad()"))
        assertFalse(text.contains("<"))
    }

    @Test
    fun `br becomes a newline and a closing p a blank line`() {
        val text = HtmlStripper.stripTags("a<br>b</p>c")

        assertEquals("a\nb\n\nc", text)
    }

    @Test
    fun `tagReplacement keeps words separated`() {
        val text = HtmlStripper.stripTags("<b>foo</b><i>bar</i>", tagReplacement = " ")

        assertEquals("foo bar", text.replace(Regex("\\s+"), " ").trim())
    }

    /**
     * Regression: a run of `<` with no `>` used to make the regex rescan the
     * remainder from every position (Θ(n²)). The scanner must stay linear, so
     * this large adversarial input has to finish well inside the timeout.
     */
    @Test(timeout = 5_000)
    fun `handles an unterminated tag run without quadratic cost`() {
        val html = "<".repeat(400_000) + "tail"

        val text = HtmlStripper.stripTags(html)

        // An unterminated `<` ends the scan: nothing after it is emitted.
        assertEquals("", text)
    }
}
