package com.lexiread.data.source

import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GutendexBookSourceDownloadTest {

    private companion object {
        val trustedHosts = setOf("gutenberg.org", "gutendex.com", "openlibrary.org", "archive.org")
    }

    private fun body(content: String) = ResponseBody.create(null, content)

    private fun download(url: String, content: String) =
        GutendexBookSource.downloadTextSafely(url, body(content), trustedHosts)

    @Test
    fun `accepts trusted https gutenberg domain`() {
        assertEquals("hello", download("https://www.gutenberg.org/files/1/1-0.txt", "hello"))
    }

    @Test
    fun `accepts subdomain of trusted host`() {
        assertEquals("ok", download("https://cache.gutendex.com/books/1.txt", "ok"))
    }

    @Test
    fun `rejects http scheme`() {
        assertThrows(SecurityException::class.java) {
            download("http://www.gutenberg.org/files/1/1-0.txt", "x")
        }
    }

    @Test
    fun `rejects untrusted domain`() {
        assertThrows(SecurityException::class.java) {
            download("https://evil.example.com/file.txt", "x")
        }
    }

    @Test
    fun `rejects lookalike domain that merely contains keyword`() {
        assertThrows(SecurityException::class.java) {
            download("https://www.gutenberg.org.evil.example.com/file.txt", "x")
        }
    }
}
