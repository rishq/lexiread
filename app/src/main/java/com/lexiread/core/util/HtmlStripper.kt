package com.lexiread.core.util

/**
 * Linear HTML tag stripper for untrusted markup.
 *
 * The regexes this replaces (`<[^>]*>` and
 * `<(script|style|head)[^>]*>.*?</\1>`) backtrack: on input such as a run of
 * `<` with no `>`, each start position rescans the whole remainder, so the cost
 * is quadratic in the input length. The callers feed up to tens of megabytes of
 * attacker-controlled text (scraped catalogue pages, Google Books/Gutendex
 * descriptions), which turns a hostile body into seconds of blocked CPU.
 *
 * This scanner walks the string once. Every character is examined a constant
 * number of times, so the cost is O(n) regardless of how the markup is shaped.
 *
 * Behaviour:
 * - `<script>`, `<style>` and `<head>` blocks (with their content) are dropped.
 * - `<br>` emits a newline; `</p>` emits a blank line, so callers that keep
 *   line breaks still see paragraph structure.
 * - every other tag is replaced by [tagReplacement].
 * - an unterminated `<` ends the scan (the tail is treated as markup).
 */
object HtmlStripper {

    private val DROP_BLOCKS = setOf("script", "style", "head")

    fun stripTags(html: String, tagReplacement: String = ""): String {
        val length = html.length
        val out = StringBuilder(length)
        var i = 0
        while (i < length) {
            val c = html[i]
            if (c != '<') {
                out.append(c)
                i++
                continue
            }

            // Locate the end of this tag. `i` jumps past it afterwards, so this
            // inner scan never revisits characters.
            var j = i + 1
            while (j < length && html[j] != '>') j++
            if (j >= length) break

            val raw = html.substring(i + 1, j)
            val closing = raw.startsWith("/")
            val name = tagName(raw)

            if (!closing && name in DROP_BLOCKS) {
                val close = indexOfIgnoreCase(html, "</$name", j + 1)
                if (close < 0) {
                    // No closing tag: drop the rest of the document.
                    i = length
                    break
                }
                var k = close + 1
                while (k < length && html[k] != '>') k++
                i = if (k < length) k + 1 else length
                out.append('\n')
                continue
            }

            when {
                name == "br" -> out.append('\n')
                closing && name == "p" -> out.append("\n\n")
                else -> if (tagReplacement.isNotEmpty()) out.append(tagReplacement)
            }
            i = j + 1
        }
        return out.toString()
    }

    /** Extracts the lowercased element name from a tag body (no `<`/`>`). */
    private fun tagName(raw: String): String {
        var start = 0
        while (start < raw.length && raw[start] == '/') start++
        var end = start
        while (end < raw.length && !raw[end].isWhitespace() && raw[end] != '/') end++
        return raw.substring(start, end).lowercase()
    }

    private fun indexOfIgnoreCase(haystack: String, needle: String, from: Int): Int {
        val limit = haystack.length - needle.length
        var i = from
        while (i <= limit) {
            if (haystack.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }
}
