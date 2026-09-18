package com.lexiread.core.util

import android.util.Xml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Covers the XML hardening added for the 2026-09-18 audit lead "FB2 XML parsing
 * applies no local entity-expansion or nesting-depth bound".
 *
 * The behavioural test carries its own control: the same fixture is parsed with
 * DTD processing explicitly enabled and must expand. Without that control the
 * fixture could be inert and the assertion would pass for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
class SafeXmlTest {

    private class ParseOutcome(val text: String, val error: Throwable?)

    private fun collectText(parser: XmlPullParser, document: String): ParseOutcome {
        parser.setInput(StringReader(document))
        val collected = StringBuilder()
        var error: Throwable? = null
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.TEXT) collected.append(parser.text)
                event = parser.next()
            }
        } catch (t: Throwable) {
            error = t
        }
        return ParseOutcome(collected.toString(), error)
    }

    /**
     * A three-level entity chain whose bottom level is `AB`, so `&c;` expands to
     * fifty characters built from ten-character [MARKER] repeats.
     *
     * The marker is composed from smaller pieces on purpose: it appears nowhere in
     * the declarations themselves, so the only way it can reach the collected text
     * is by expansion. A fixture that spelled the marker out in `<!ENTITY a "…">`
     * would let the test pass, or fail, on the declaration text alone.
     */
    private fun entityChainDocument() = """
        <?xml version="1.0"?>
        <!DOCTYPE book [
          <!ENTITY a "AB">
          <!ENTITY b "&a;&a;&a;&a;&a;">
          <!ENTITY c "&b;&b;&b;&b;&b;">
        ]>
        <book><t>&c;</t></book>
    """.trimIndent()

    @Test
    fun `rejects an internal DTD subset`() {
        assertThrows(SecurityException::class.java) {
            SafeXml.requireNoInternalDtdSubset(entityChainDocument())
        }
    }

    /**
     * A DOCTYPE that only names an external DTD cannot declare anything inline,
     * and real FB2 files carry exactly this form — rejecting it would break
     * legitimate imports, so it must pass.
     */
    @Test
    fun `allows a DOCTYPE with an external identifier only`() {
        val document = """<!DOCTYPE FictionBook SYSTEM "FictionBook2.dtd"><FictionBook/>"""

        assertEquals(document, SafeXml.requireNoInternalDtdSubset(document))
    }

    @Test
    fun `allows a document with no DOCTYPE`() {
        val document = """<?xml version="1.0" encoding="utf-8"?><FictionBook><body/></FictionBook>"""

        assertEquals(document, SafeXml.requireNoInternalDtdSubset(document))
    }

    @Test
    fun `a declared entity expands with DTD processing on and never with SafeXml`() {
        val document = entityChainDocument()

        val control = collectText(
            Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, true)
            },
            document
        )
        assertTrue(
            "fixture must expand when DTD processing is enabled, otherwise it proves nothing",
            control.text.contains(MARKER)
        )

        val hardened = collectText(SafeXml.newPullParser(), document)
        assertFalse("a declared entity must never be expanded", hardened.text.contains(MARKER))
        val error = hardened.error
        assertTrue(
            "the only acceptable failure is the unresolved reference, not a parse error: $error",
            error == null || error.message.orEmpty().contains("unresolved")
        )
    }

    private companion object {
        const val MARKER = "ABABABABAB"
    }
}
