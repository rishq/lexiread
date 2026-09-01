package com.lexiread.core.util

/**
 * Single place that decides whether a URL may be fetched.
 *
 * This check used to be copy-pasted into every book source, and each copy had
 * drifted slightly. Keeping one implementation means an SSRF guard cannot be
 * silently lost when a new source is added.
 */
object UrlValidator {

    /** Hosts we are willing to download book files from. */
    val TRUSTED_DOWNLOAD_HOSTS: Set<String> = setOf(
        "gutenberg.org",
        "gutendex.com",
        "gutenberg.net.au",
        "archive.org",
        "standardebooks.org"
    )

    /** Hosts we are willing to load images from. */
    val TRUSTED_IMAGE_HOSTS: Set<String> = setOf(
        "gutenberg.org",
        "covers.openlibrary.org",
        "openlibrary.org",
        "archive.org",
        "standardebooks.org",
        "books.google.com",
        "books.googleusercontent.com"
    )

    /**
     * Validates a book-file URL. Returns it unchanged so callers can chain:
     * `val url = UrlValidator.requireTrustedDownload(candidate)`.
     *
     * @throws SecurityException when the scheme is not https or the host is unknown.
     */
    fun requireTrustedDownloadUrl(url: String, extraHosts: Set<String> = emptySet()): String {
        val allowed = TRUSTED_DOWNLOAD_HOSTS + extraHosts
        val (scheme, host) = parse(url)
        if (scheme != "https") throw SecurityException("Download rejected: only HTTPS is allowed ($url).")
        if (!isTrustedHost(host, allowed)) {
            throw SecurityException("Download rejected: host '$host' is not in the allowed list.")
        }
        return url
    }

    /** Validates a cover URL. Images are not executable, so the allow-list is broader. */
    fun requireTrustedImageUrl(url: String, extraHosts: Set<String> = emptySet()): String {
        val allowed = TRUSTED_IMAGE_HOSTS + extraHosts
        val (scheme, host) = parse(url)
        if (scheme != "https") throw SecurityException("Image rejected: only HTTPS is allowed ($url).")
        if (!isTrustedHost(host, allowed)) {
            throw SecurityException("Image rejected: host '$host' is not in the allowed list.")
        }
        return url
    }

    fun isTrustedHost(host: String?, allowed: Set<String>): Boolean {
        val normalized = host?.lowercase()?.trimEnd('.') ?: return false
        if (normalized.isBlank()) return false
        return allowed.any { allowedHost ->
            normalized == allowedHost || normalized.endsWith(".$allowedHost")
        }
    }

    private fun parse(url: String): Pair<String?, String?> {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
            ?: throw SecurityException("Malformed URL: $url")
        return uri.scheme?.lowercase() to uri.host?.lowercase()
    }
}
