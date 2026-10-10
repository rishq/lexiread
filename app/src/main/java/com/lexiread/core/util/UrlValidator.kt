package com.lexiread.core.util

/**
 * Single place that decides whether a URL may be fetched.
 *
 * This check used to be copy-pasted into every book source, and each copy had
 * drifted slightly. Keeping one implementation means an SSRF guard cannot be
 * silently lost when a new source is added.
 */
object UrlValidator {

    /**
     * Anna's Archive itself. Declared here rather than in
     * `AnnasArchiveConfig` so the redirect guard, the source's own entry check
     * and the UI's cover check all read one list.
     */
    val ANNAS_ARCHIVE_HOSTS: Set<String> = setOf(
        "annas-archive.se",
        "annas-archive.org",
        "annas-archive.li"
    )

    /**
     * The partner servers a `/slow_download/…` page may hand out.
     *
     * The download link Anna's Archive serves is a redirect to one of these, so
     * they have to be trusted for redirects as well as for the initial fetch —
     * otherwise the guard rejects the very hop the site exists to make. The list
     * is deliberately explicit rather than "whatever the page says": a scraped
     * page is attacker-controlled, and `UrlValidator` is the thing that stops it
     * from pointing the downloader anywhere it likes.
     *
     * Extend this when Anna's Archive adds a partner; a host that is missing
     * fails closed with a clear message instead of silently downloading.
     */
    val ANNAS_ARCHIVE_MIRROR_HOSTS: Set<String> = setOf(
        // Library Genesis mirrors used as Anna's Archive download partners.
        "libgen.li",
        "libgen.gs",
        "libgen.la",
        "libgen.is",
        "libgen.rs",
        "libgen.st",
        // IPFS gateways.
        "ipfs.io",
        "cloudflare-ipfs.com",
        "gateway.pinata.cloud",
        "dweb.link",
        "w3s.link"
    )

    /** Hosts we are willing to download book files from. */
    val TRUSTED_DOWNLOAD_HOSTS: Set<String> = setOf(
        "gutenberg.org",
        "gutendex.com",
        "gutenberg.net.au",
        "archive.org",
        "standardebooks.org"
    ) + ANNAS_ARCHIVE_HOSTS + ANNAS_ARCHIVE_MIRROR_HOSTS

    /** Hosts we are willing to load images from. */
    val TRUSTED_IMAGE_HOSTS: Set<String> = setOf(
        "gutenberg.org",
        "covers.openlibrary.org",
        "openlibrary.org",
        "archive.org",
        "standardebooks.org",
        "books.google.com",
        "books.googleusercontent.com"
    ) + ANNAS_ARCHIVE_HOSTS

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

    /**
     * Validates a redirect target before it is followed.
     *
     * OkHttp follows redirects automatically, so validating only the URL a
     * caller passes in is not enough: an open redirect on an allow-listed host
     * would let the app fetch from anywhere and store the response as a book.
     * Every hop has to be re-validated.
     *
     * A redirect that stays on the host the request already went to is always
     * allowed — the caller already trusted that host.
     */
    fun requireTrustedRedirect(fromUrl: String, toUrl: String, extraHosts: Set<String> = emptySet()): String {
        val allowed = TRUSTED_DOWNLOAD_HOSTS + TRUSTED_IMAGE_HOSTS + extraHosts
        val (scheme, host) = parse(toUrl)
        if (scheme != "https") throw SecurityException("Redirect rejected: only HTTPS is allowed ($toUrl).")
        val fromHost = parse(fromUrl).second
        if (host != null && host == fromHost) return toUrl
        if (!isTrustedHost(host, allowed)) {
            throw SecurityException("Redirect rejected: host '$host' is not in the allowed list.")
        }
        return toUrl
    }

    /**
     * Redirect policy for cover images, used by the OkHttp client Coil fetches
     * through. Same allow-list as [requireTrustedImageUrl]; pass the source's
     * image hosts via [extraHosts] so trust stays scoped to the request.
     */
    fun requireTrustedImageRedirect(fromUrl: String, toUrl: String, extraHosts: Set<String> = emptySet()): String {
        val allowed = TRUSTED_IMAGE_HOSTS + extraHosts
        val (scheme, host) = parse(toUrl)
        if (scheme != "https") throw SecurityException("Redirect rejected: only HTTPS is allowed ($toUrl).")
        val fromHost = parse(fromUrl).second
        if (host != null && host == fromHost) return toUrl
        if (!isTrustedHost(host, allowed)) {
            throw SecurityException("Redirect rejected: host '$host' is not in the allowed list.")
        }
        return toUrl
    }

    /**
     * P2-1: redirect policy for API clients (OpenAI/Gemini/...). Same-host
     * redirects are followed; cross-host redirects are rejected without
     * consulting the book-download allow-list.
     */
    fun requireSameHostRedirect(fromUrl: String, toUrl: String): String {
        val (scheme, host) = parse(toUrl)
        if (scheme != "https") throw SecurityException("Redirect rejected: only HTTPS is allowed ($toUrl).")
        val fromHost = parse(fromUrl).second
        if (host != null && host == fromHost) return toUrl
        throw SecurityException("Redirect rejected: cross-host redirect '$fromHost' -> '$host' is not allowed for API clients.")
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
