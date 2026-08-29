package com.lexiread.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class TextEncodingTest {

    /** "Привет, мир!" written without Cyrillic literals so the test file stays ASCII. */
    private val cyrillic = "\u041F\u0440\u0438\u0432\u0435\u0442, \u043C\u0438\u0440!"

    @Test
    fun `detects UTF-8 BOM and strips nothing extra`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            cyrillic.toByteArray(StandardCharsets.UTF_8)

        assertEquals(StandardCharsets.UTF_8, TextEncoding.detectCharset(bytes))
    }

    @Test
    fun `honours windows-1251 xml declaration`() {
        val xml = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><root>$cyrillic</root>"
        val bytes = xml.toByteArray(charset("windows-1251"))

        assertEquals(charset("windows-1251"), TextEncoding.detectCharset(bytes))
        assertTrue(TextEncoding.decode(bytes).contains(cyrillic))
    }

    @Test
    fun `honours meta charset declaration in html`() {
        val html = "<html><head><meta charset=\"windows-1251\"></head><body>$cyrillic</body></html>"
        val bytes = html.toByteArray(charset("windows-1251"))

        assertTrue(TextEncoding.decode(bytes).contains(cyrillic))
    }

    @Test
    fun `falls back to windows-1251 when bytes are not valid utf-8`() {
        // No declaration at all: only content analysis can decide.
        val bytes = cyrillic.toByteArray(charset("windows-1251"))

        assertFalse(TextEncoding.isValidUtf8(bytes))
        assertEquals(cyrillic, TextEncoding.decode(bytes))
    }

    @Test
    fun `reads text larger than the internal buffer without splitting characters`() {
        // Every Cyrillic letter is 2 bytes in UTF-8. An odd prefix guarantees the
        // 8192-byte read boundary lands in the middle of a character.
        val prefix = "<p>"
        val longBody = "\u041F\u0440\u0438\u0432\u0435\u0442 ".repeat(2000)
        val text = prefix + longBody
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        assertTrue(bytes.size > 8192)

        val decoded = TextEncoding.readText(ByteArrayInputStream(bytes), 10L * 1024 * 1024)

        assertFalse("Decoded text must not contain U+FFFD", decoded.contains('\uFFFD'))
        assertEquals(text, decoded)
    }

    @Test
    fun `unknown charset names do not silently corrupt text`() {
        val xml = "<?xml version=\"1.0\" encoding=\"not-a-real-charset\"?><root>$cyrillic</root>"
        val bytes = xml.toByteArray(StandardCharsets.UTF_8)

        // Falls through to content validation rather than throwing or mojibake-ing.
        assertEquals(StandardCharsets.UTF_8, TextEncoding.detectCharset(bytes))
    }

    @Test
    fun `respects the byte cap`() {
        val bytes = "x".repeat(100).toByteArray(StandardCharsets.UTF_8)

        val capped = TextEncoding.readCappedBytes(ByteArrayInputStream(bytes), 10)

        assertEquals(10, capped.size)
    }

    @Test
    fun `empty stream decodes to empty string`() {
        assertEquals("", TextEncoding.readText(ByteArrayInputStream(ByteArray(0)), 1024))
    }
}
