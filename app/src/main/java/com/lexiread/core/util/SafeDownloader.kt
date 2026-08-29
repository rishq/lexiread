package com.lexiread.core.util

import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Streaming download with a hard size cap.
 *
 * Book files used to be read into a String or a ByteArrayOutputStream, which
 * meant a 10 MB book occupied 10 MB of RAM (more, transiently, while the
 * buffer grew). Writing straight to disk through a fixed 8 KiB buffer keeps
 * memory flat regardless of book size.
 *
 * The caps matter for a second reason: every byte is attacker-controlled. A
 * server that answers with an endless stream would otherwise OOM the app.
 */
object SafeDownloader {

    /**
     * Streams [body] into [destination]. Deletes the partial file if anything
     * goes wrong, so a truncated book can never be mistaken for a complete one.
     */
    fun downloadToFile(body: ResponseBody, destination: File, maxBytes: Long): File {
        val declaredLength = body.contentLength()
        require(declaredLength == -1L || declaredLength <= maxBytes) {
            "Book content exceeds the ${maxBytes / (1024 * 1024)}MB limit."
        }

        try {
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        totalBytes += read
                        require(totalBytes <= maxBytes) {
                            "Book content exceeds the ${maxBytes / (1024 * 1024)}MB limit."
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            return destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    /**
     * A downloaded EPUB must contain `META-INF/container.xml`. Without it the
     * parser silently yields zero chapters, and the reader shows an empty book
     * instead of an error.
     */
    fun isValidEpub(file: File): Boolean = runCatching {
        ZipFile(file).use { zip -> zip.getEntry("META-INF/container.xml") != null }
    }.getOrDefault(false)
}
