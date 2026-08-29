package com.lexiread.data.source

import com.lexiread.core.util.SafeDownloader
import com.lexiread.core.util.UrlValidator
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Guards the two rules that keep downloads safe:
 *
 * 1. A URL may only be fetched if its host is on the allow-list — otherwise a
 *    malicious catalogue response could turn the app into an SSRF proxy.
 * 2. A response is streamed to disk with a hard size cap instead of being
 *    buffered in memory, and a truncated transfer leaves no partial file behind.
 *
 * Both used to live inside `GutendexBookSource` and were duplicated in two
 * other sources; they now live in [UrlValidator] and [SafeDownloader], which is
 * what this file exercises.
 */
class GutendexBookSourceDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- URL allow-listing ---

    @Test
    fun `accepts trusted https gutenberg domain`() {
        assertEquals(
            "https://www.gutenberg.org/files/1342/1342-0.txt",
            UrlValidator.requireTrustedDownloadUrl("https://www.gutenberg.org/files/1342/1342-0.txt")
        )
    }

    @Test
    fun `accepts subdomain of trusted host`() {
        assertEquals(
            "https://cache.gutendex.com/books/1.txt",
            UrlValidator.requireTrustedDownloadUrl("https://cache.gutendex.com/books/1.txt")
        )
    }

    @Test
    fun `rejects http scheme`() {
        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedDownloadUrl("http://www.gutenberg.org/files/1342/1342-0.txt")
        }
    }

    @Test
    fun `rejects untrusted domain`() {
        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedDownloadUrl("https://evil.example.com/file.txt")
        }
    }

    @Test
    fun `rejects lookalike domain that merely contains keyword`() {
        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedDownloadUrl("https://www.gutenberg.org.evil.example.com/file.txt")
        }
    }

    @Test
    fun `rejects malformed url`() {
        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedDownloadUrl("not a url at all")
        }
    }

    @Test
    fun `covers may come from open library but book files may not`() {
        val cover = "https://covers.openlibrary.org/b/id/8231857-M.jpg"
        assertEquals(cover, UrlValidator.requireTrustedImageUrl(cover))
        assertThrows(SecurityException::class.java) { UrlValidator.requireTrustedDownloadUrl(cover) }
    }

    // --- streaming download ---

    private fun body(content: String) = ResponseBody.create(null, content)

    @Test
    fun `streams response to disk`() {
        val destination = File(tmp.newFolder("books"), "1342.txt")

        SafeDownloader.downloadToFile(body("hello"), destination, 1024)

        assertEquals("hello", destination.readText())
    }

    @Test
    fun `aborts and deletes the partial file when the response exceeds the cap`() {
        val destination = File(tmp.newFolder("books"), "huge.txt")

        assertThrows(IllegalArgumentException::class.java) {
            SafeDownloader.downloadToFile(body("x".repeat(4096)), destination, 1024)
        }
        assertTrue("partial file must not survive a failed download", !destination.exists())
    }
}
