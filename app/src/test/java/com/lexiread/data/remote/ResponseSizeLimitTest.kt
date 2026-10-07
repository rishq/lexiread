package com.lexiread.data.remote

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The image client Coil fetches covers through had no response-size bound: a
 * hostile (but allow-listed) host could stream an arbitrarily large body into
 * heap. These tests pin both halves of the guard — the declared `Content-Length`
 * check and the streaming byte counter that catches a chunked body.
 */
class ResponseSizeLimitTest {

    private fun readFully(url: String) {
        val request = Request.Builder().url(url).build()
        RetrofitClient.imageOkHttpClient.newCall(request).execute().use { response ->
            response.body!!.bytes()
        }
    }

    @Test
    fun `declared oversize cover fails fast`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x".repeat(11 * 1024 * 1024)))
        server.start()
        try {
            val error = assertThrows(IOException::class.java) {
                readFully(server.url("/cover.jpg").toString())
            }
            assertTrue("expected the cover size cap: ${error.message}", error.message.orEmpty().contains("Cover image"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `chunked oversize cover fails while streaming`() {
        val server = MockWebServer()
        // No Content-Length: only the streaming counter can stop this.
        server.enqueue(MockResponse().setChunkedBody("x".repeat(11 * 1024 * 1024), 64 * 1024))
        server.start()
        try {
            val error = assertThrows(IOException::class.java) {
                readFully(server.url("/cover.jpg").toString())
            }
            assertTrue("expected the cover size cap: ${error.message}", error.message.orEmpty().contains("Cover image"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `small cover passes through`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("small-cover"))
        server.start()
        try {
            val request = Request.Builder().url(server.url("/cover.jpg").toString()).build()
            RetrofitClient.imageOkHttpClient.newCall(request).execute().use { response ->
                assertEquals("small-cover", response.body!!.string())
            }
        } finally {
            server.shutdown()
        }
    }
}
