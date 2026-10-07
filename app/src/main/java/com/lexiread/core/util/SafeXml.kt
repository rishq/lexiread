package com.lexiread.core.util

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * XML pull parsers that do not expand entities declared by the document.
 *
 * Every XML entry point in the app hands an untrusted document to
 * [Xml.newPullParser]: FB2 chapters and metadata, the EPUB container and OPF, and
 * the OPDS catalogue feed. AOSP's implementation leaves
 * [XmlPullParser.FEATURE_PROCESS_DOCDECL] enabled, so a `<!DOCTYPE>` internal
 * subset is honoured and the general entities it declares are expanded wherever
 * they are referenced. The size caps the app applies ([TextEncoding.readText]
 * and the per-parser limits) bound the *raw* bytes only: expansion happens after
 * those caps, so a document that fits inside the cap can still expand to
 * something far larger.
 *
 * Verified against the Android 15 framework parser
 * (`com.android.org.kxml2.io.KXmlParser`): entity declarations read from the
 * internal subset are recorded only when its `processDocDecl` field is set, and
 * `readInternalSubset()` consumes the subset either way. Clearing the feature
 * therefore skips the declarations without breaking documents that carry a
 * `<!DOCTYPE>`, and a reference to a declared entity then resolves to nothing and
 * raises `unresolved: &name;` instead of expanding.
 *
 * Two independent controls are provided, because the first depends on the
 * platform parser honouring the feature:
 *  1. [newPullParser] clears `FEATURE_PROCESS_DOCDECL`.
 *  2. [requireNoInternalDtdSubset] rejects a document that declares an internal
 *     subset at all. No FB2, EPUB or OPDS document the app reads uses one.
 */
object SafeXml {

    private const val TAG = "SafeXml"

    /**
     * Matches the start of a DOCTYPE declaration. The keyword's case is fixed by
     * the XML spec, but a malformed document need not respect it.
     */
    private val DOCTYPE_START = Regex("<!\\s*DOCTYPE", RegexOption.IGNORE_CASE)

    /**
     * A pull parser with DTD processing cleared.
     *
     * Namespace processing is left alone so the OPDS reader can turn it off, and
     * `setInput` is left to the caller because the readers that decode a document
     * themselves must not have their encoding re-detected.
     *
     * The feature is cleared defensively: a parser implementation that does not
     * recognise it throws from `setFeature`, and [requireNoInternalDtdSubset] is
     * then the only control. Parsing without the flag is still better than not
     * parsing at all, since the per-parser size caps remain in place.
     */
    fun newPullParser(): XmlPullParser {
        val parser = Xml.newPullParser()
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        } catch (e: Exception) {
            Log.w(TAG, "Parser rejected FEATURE_PROCESS_DOCDECL=false", e)
        }
        return parser
    }

    /**
     * Throws when [document] declares an internal DTD subset.
     *
     * An internal subset is the only place a document can declare general
     * entities inline, and it is the `[...]` between the DOCTYPE keyword and the
     * closing `>`. An external-only declaration such as
     * `<!DOCTYPE html PUBLIC "..." "...dtd">` declares nothing the parser can
     * expand — no entity resolver is installed and the framework parser never
     * fetches an external subset — so it is deliberately allowed through.
     *
     * The declaration is scanned with XML quoting in mind: a `>` inside a quoted
     * literal (legal in a `SYSTEM`/`PUBLIC` identifier) does not end the
     * declaration, so a document such as
     * `<!DOCTYPE FictionBook SYSTEM "a>b" [<!ENTITY a "x">]>` cannot hide its
     * internal subset behind a quoted `>`.
     *
     * Linear: each character is examined at most once across the whole scan, so
     * the guard stays O(n) even for a document that repeats `<!DOCTYPE` tokens.
     *
     * @return [document] unchanged, so callers can chain.
     * @throws SecurityException when an internal subset is present.
     */
    fun requireNoInternalDtdSubset(document: String): String {
        var from = 0
        val length = document.length
        while (true) {
            val match = DOCTYPE_START.find(document, from) ?: return document
            var i = match.range.last + 1
            var quote: Char? = null
            var sawBracket = false
            var bracketOffset = -1
            while (i < length) {
                val c = document[i]
                if (quote != null) {
                    if (c == quote) quote = null
                } else {
                    when (c) {
                        '"', '\'' -> quote = c
                        '[' -> {
                            sawBracket = true
                            bracketOffset = i
                            // A subset is fatal regardless of whether a closing
                            // `>` follows; stop scanning this declaration.
                            break
                        }
                        '>' -> break
                    }
                }
                i++
            }
            if (sawBracket) {
                throw SecurityException(
                    "Rejected XML document: internal DTD subset at offset $bracketOffset is not allowed."
                )
            }
            if (i >= length) return document
            from = i + 1
        }
    }
}
