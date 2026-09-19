package com.lexiread.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun `download url on trusted host passes`() {
        val url = "https://gutenberg.org/cache/epub/1342/pg1342.txt"
        assertEquals(url, UrlValidator.requireTrustedDownloadUrl(url))
    }

    @Test
    fun `download url on unknown host is rejected`() {
        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedDownloadUrl("https://evil.example.com/book.epub")
        }
    }

    @Test
    fun `plain http download is rejected`() {
        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedDownloadUrl("http://gutenberg.org/book.epub")
        }
    }

    @Test
    fun `redirect to same host is allowed`() {
        val from = "https://gutenberg.org/search"
        val to = "https://gutenberg.org/redirect/abc"
        assertEquals(to, UrlValidator.requireTrustedRedirect(from, to))
    }

    @Test
    fun `redirect to a trusted different host is allowed`() {
        val from = "https://gutenberg.org/x"
        val to = "https://archive.org/download/abc.epub"
        assertEquals(to, UrlValidator.requireTrustedRedirect(from, to))
    }

    @Test
    fun `redirect to an untrusted host is rejected`() {
        val from = "https://gutenberg.org/x"
        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedRedirect(from, "https://evil.example.com/book.epub")
        }
    }

    @Test
    fun `redirect over plain http is rejected`() {
        val from = "https://gutenberg.org/x"
        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedRedirect(from, "http://gutenberg.org/book.epub")
        }
    }

    // P2-1: API redirect policy — same-host only, never the book allow-list.
    @Test
    fun `api same-host redirect is allowed`() {
        val from = "https://api.openai.com/v1/chat"
        val to = "https://api.openai.com/v1/chat/retry"
        assertEquals(to, UrlValidator.requireSameHostRedirect(from, to))
    }

    @Test
    fun `api cross-host redirect is rejected even for trusted book hosts`() {
        val from = "https://api.openai.com/v1/chat"
        assertThrows(SecurityException::class.java) {
            UrlValidator.requireSameHostRedirect(from, "https://archive.org/download/x")
        }
    }

    @Test
    fun `api redirect over plain http is rejected`() {
        val from = "https://api.openai.com/v1/chat"
        assertThrows(SecurityException::class.java) {
            UrlValidator.requireSameHostRedirect(from, "http://api.openai.com/v1/chat")
        }
    }

    // P2-2: global image-host registry can be reset between tests.
    @Test
    fun `extra image hosts can be cleared to avoid cross-test leaks`() {
        try {
            UrlValidator.allowImageHost("ephemeral-test-host.example.com")
            UrlValidator.requireTrustedImageUrl("https://ephemeral-test-host.example.com/c.jpg")
        } finally {
            UrlValidator.clearExtraImageHosts()
        }
        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedImageUrl("https://ephemeral-test-host.example.com/c.jpg")
        }
    }

    // Lead 1 (audit 2026-09-18): the guard on a cover redirect has to accept
    // exactly what the entry check accepted, or a validated cover would fail to
    // load once Coil's client carries the guard.
    @Test
    fun `image redirect to the same host is allowed`() {
        val from = "https://covers.openlibrary.org/b/id/1-M.jpg"
        val to = "https://covers.openlibrary.org/b/id/1-L.jpg"

        assertEquals(to, UrlValidator.requireTrustedImageRedirect(from, to))
    }

    @Test
    fun `image redirect to another allow-listed image host is allowed`() {
        val from = "https://covers.openlibrary.org/b/id/1-M.jpg"
        val to = "https://archive.org/download/cover.jpg"

        assertEquals(to, UrlValidator.requireTrustedImageRedirect(from, to))
    }

    /**
     * The MyLib host is registered at runtime rather than compiled in, so the
     * redirect guard must consult the same registry the entry check does.
     */
    @Test
    fun `image redirect to a host registered at runtime is allowed`() {
        try {
            UrlValidator.allowImageHost("runtime-cover-host.invalid")
            val from = "https://runtime-cover-host.invalid/a.jpg"
            val to = "https://static.runtime-cover-host.invalid/a.jpg"

            assertEquals(to, UrlValidator.requireTrustedImageRedirect(from, to))
        } finally {
            UrlValidator.clearExtraImageHosts()
        }
    }

    @Test
    fun `image redirect with per-call extra host is allowed`() {
        val from = "https://zlib.bz/covers/a.jpg"
        val to = "https://static.zlib.bz/covers/a.jpg"
        assertEquals(to, UrlValidator.requireTrustedImageRedirect(from, to, setOf("zlib.bz")))
    }

    @Test
    fun `image redirect to an untrusted host is rejected`() {
        val from = "https://covers.openlibrary.org/b/id/1-M.jpg"

        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedImageRedirect(from, "https://evil.example.com/cover.jpg")
        }
    }

    /**
     * The image allow-list is not the download allow-list: a cover must not be able
     * to redirect onto a host that is only trusted for book files.
     */
    @Test
    fun `image redirect to a download-only host is rejected`() {
        val from = "https://covers.openlibrary.org/b/id/1-M.jpg"

        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedImageRedirect(from, "https://gutenberg.net.au/cover.jpg")
        }
    }

    @Test
    fun `image redirect over plain http is rejected`() {
        val from = "https://covers.openlibrary.org/b/id/1-M.jpg"

        assertThrows(SecurityException::class.java) {
            UrlValidator.requireTrustedImageRedirect(from, "http://covers.openlibrary.org/b/id/1-M.jpg")
        }
    }
}
