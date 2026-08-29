package com.lexiread.core.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Encoding-aware text decoding helpers.
 *
 * Two failure modes are handled here, both of which silently destroy book text:
 *
 * 1. **Split multi-byte sequences.** Decoding a stream chunk-by-chunk with
 *    `String(bytes, 0, read, UTF_8)` corrupts any character whose bytes straddle
 *    a buffer boundary (every Cyrillic letter is 2 bytes, CJK 3, emoji 4). The
 *    halves decode to U+FFFD. Always decode the complete byte array in one call,
 *    or stream through an [InputStreamReader].
 *
 * 2. **Wrong charset assumption.** Large parts of the Russian e-book corpus are
 *    still distributed as windows-1251 / KOI8-R, and EPUBs declare their encoding
 *    in the XML prolog or a `<meta charset>` tag. Hardcoding UTF-8 turns the whole
 *    book into mojibake. Charset is therefore detected, never assumed.
 */
object TextEncoding {

    /** Fallback used when a file is not valid UTF-8 and declares no encoding. */
    private val DEFAULT_FALLBACK: Charset = charset("windows-1251")

    /** How much of the head of a document is scanned for an encoding declaration. */
    private const val SNIFF_LIMIT_BYTES = 4096

    private val XML_DECLARATION =
        Regex("""<\?xml[^>]*?encoding\s*=\s*["']([A-Za-z0-9._:\-]+)["']""", RegexOption.IGNORE_CASE)

    private val META_CHARSET =
        Regex("""<meta[^>]+charset\s*=\s*["']?\s*([A-Za-z0-9._:\-]+)""", RegexOption.IGNORE_CASE)

    private val CONTENT_TYPE_CHARSET =
        Regex("""content\s*=\s*["'][^"']*charset\s*=\s*([A-Za-z0-9._:\-]+)""", RegexOption.IGNORE_CASE)

    /**
     * Reads at most [maxBytes] from [input] and decodes the result as text.
     *
     * The byte array is decoded in a single operation so multi-byte characters can
     * never be split. Reading is size-capped to keep hostile or truncated archives
     * from exhausting memory.
     */
    fun readText(input: InputStream, maxBytes: Long): String {
        val bytes = readCappedBytes(input, maxBytes)
        if (bytes.isEmpty()) return ""
        return decode(bytes)
    }

    /**
     * Reads at most [maxBytes] bytes. Returns every byte read up to the cap rather
     * than failing, so a truncated entry still yields readable text.
     */
    fun readCappedBytes(input: InputStream, maxBytes: Long): ByteArray {
        if (maxBytes <= 0L) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            val remaining = maxBytes - total
            if (remaining <= 0) break
            val accepted = minOf(read.toLong(), remaining).toInt()
            out.write(buffer, 0, accepted)
            total += accepted
            if (accepted < read) break
        }
        return out.toByteArray()
    }

    /** Decodes a complete byte array using the detected charset. */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return String(bytes, detectCharset(bytes))
    }

    /**
     * Resolves a charset by BOM, then declared encoding, then content validation,
     * and only then falls back to a legacy single-byte encoding.
     */
    fun detectCharset(bytes: ByteArray): Charset {
        detectBomCharset(bytes)?.let { return it }

        val head = String(
            bytes,
            0,
            minOf(bytes.size, SNIFF_LIMIT_BYTES),
            StandardCharsets.ISO_8859_1
        )

        // A declaration inside the document is authoritative — trust it first.
        declaredCharset(head)?.let { return it }

        // No declaration: UTF-8 only if the bytes genuinely are valid UTF-8.
        if (isValidUtf8(bytes)) return StandardCharsets.UTF_8

        return DEFAULT_FALLBACK
    }

    private fun detectBomCharset(bytes: ByteArray): Charset? {
        if (bytes.size < 2) return null
        return when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> StandardCharsets.UTF_8

            bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> StandardCharsets.UTF_16LE
            bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> StandardCharsets.UTF_16BE
            else -> null
        }
    }

    private fun declaredCharset(head: String): Charset? {
        val names = listOf(XML_DECLARATION, META_CHARSET, CONTENT_TYPE_CHARSET)
        for (regex in names) {
            val value = regex.find(head)?.groupValues?.getOrNull(1) ?: continue
            resolveCharsetOrNull(value)?.let { return it }
        }
        return null
    }

    /**
     * Maps a declared charset name to a [Charset], returning null for unknown
     * names so the caller can keep probing instead of silently corrupting text.
     */
    fun resolveCharsetOrNull(name: String?): Charset? {
        val normalized = name?.trim()?.trim('"', '\'') ?: return null
        if (normalized.isBlank()) return null
        return runCatching { Charset.forName(normalized) }.getOrNull()
    }

    /**
     * Strict UTF-8 validation: any malformed or unmappable sequence means the
     * bytes belong to some other encoding.
     */
    fun isValidUtf8(bytes: ByteArray): Boolean {
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        }.isSuccess
    }

    /** Convenience overload for files small enough to hold fully in memory. */
    fun readFileText(bytes: ByteArray): String = decode(bytes)
}
