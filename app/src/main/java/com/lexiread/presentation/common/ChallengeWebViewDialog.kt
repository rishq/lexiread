package com.lexiread.presentation.common

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lexiread.data.remote.RetrofitClient

/**
 * Passes a source's browser check in a real WebView.
 *
 * Plain HTTP can never clear the check, so the request throws
 * [com.lexiread.data.source.ChallengeRequiredException] and the caller shows
 * this dialog instead of an error. Auto-pass fires when the clearance cookie
 * lands in cookies; otherwise user logs in manually then taps Continue,
 * and caller syncs cookies into OkHttp and retries the request.
 *
 * Two checks are in play: Cloudflare (`cf_clearance`) and DDoS-Guard
 * (`ddg_last_challenge`), which is what Anna's Archive answers with. Both are
 * recognised — see [isChallengePage].
 *
 * The embedded form defeats some logins (no password-manager fill, hostile
 * captcha), so "Open in browser" hands the same URL to the user's real
 * browser. The session cannot come back automatically (separate cookie
 * store) — after logging in outside, finish the check in the view above so
 * the clearance cookies exist where the app can read them, then Continue.
 */
@Composable
fun ChallengeWebViewDialog(
    url: String,
    onPassed: () -> Unit,
    onDismiss: () -> Unit
) {
    var status by remember { mutableStateOf("Passing the browser check…") }
    var fired by remember { mutableStateOf(false) }
    var ddgCleared by remember { mutableStateOf(false) }
    var webViewRef: WebView? by remember { mutableStateOf(null) }
    val context = LocalContext.current
    val initialHost = remember(url) {
        runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull()
    }

    /**
     * The check is over: hand the clearance back to the HTTP client.
     *
     * The WebView's own User-Agent is copied too. A browser check fingerprints
     * the client that passed it and the clearance belongs to that browser, so
     * a retry that claims to be a different one is refused.
     */
    fun pass() {
        fired = true
        status = "Check passed. Retrying…"
        RetrofitClient.setBrowserUserAgent(
            webViewRef?.settings?.userAgentString.orEmpty()
        )
        onPassed()
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply { removeAllViews(); stopLoading(); destroy() }
            webViewRef = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("One browser check") },
        text = {
            Column {
                Text(
                    "The site asks for one quick check. Log in inside if asked, " +
                        "then tap Continue — the request retries on its own. " +
                        "If the form here won't take your login, open it in your " +
                        "browser instead, then finish the check here.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webViewRef = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.safeBrowsingEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url?.scheme?.lowercase()
                                    // Block non-web schemes only (intent:, javascript:, etc).
                                    // Login / Turnstile often hop hosts — blocking
                                    // cross-host https breaks login and looks like refresh.
                                    // Surface the hop so a phishing redirect is visible.
                                    val targetHost = request.url?.host?.lowercase()
                                    if (scheme == "http" || scheme == "https") {
                                        if (targetHost != null && initialHost != null && targetHost != initialHost) {
                                            status = "On $targetHost — verify address, then Continue."
                                        }
                                        return false
                                    }
                                    return true
                                }
                                override fun onPageFinished(view: WebView, finishedUrl: String) {
                                    if (fired) return
                                    val cookies = CookieManager.getInstance()
                                        .getCookie(finishedUrl).orEmpty()
                                    // Auto-pass only on clearance. Old code fired
                                    // on any non-challenge title — login pages have
                                    // normal titles, so typing login triggered an
                                    // instant retry + reopen loop that looks like
                                    // refresh.
                                    if (CLEARANCE_COOKIE in cookies) {
                                        pass()
                                        return
                                    }
                                    view.evaluateJavascript(READ_PAGE_STATE_JS) { raw ->
                                        if (fired) return@evaluateJavascript
                                        val state = raw.orEmpty().trim('"')
                                        val title = state.substringBefore('|')
                                        val marker = state.substringAfter('|', "")
                                        val challenged = isChallengePage(title, marker)
                                        // DDoS-Guard: its challenge script fingerprints
                                        // the browser, sets a cookie and reloads. A page
                                        // that is no longer the challenge page means the
                                        // check passed.
                                        ddgCleared = !challenged &&
                                            DDG_CHALLENGE_COOKIE in cookies
                                        if (ddgCleared) {
                                            pass()
                                        } else {
                                            status = if (challenged) "Passing the browser check…"
                                            else "Log in if needed, then tap Continue."
                                        }
                                    }
                                }
                            }
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(380.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (fired) return@TextButton
                // Gate Continue on clearance: ungated retry syncs stale cookies.
                val currentUrl = webViewRef?.url ?: url
                val cookies = CookieManager.getInstance().getCookie(currentUrl).orEmpty()
                if (CLEARANCE_COOKIE in cookies || ddgCleared) {
                    pass()
                } else {
                    status = "Check not passed yet — complete it, then Continue."
                }
            }) { Text("Continue") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(webViewRef?.url ?: url)
                            )
                        )
                    }.onFailure {
                        status = "No browser found to open the link."
                    }
                }) { Text("Open in browser") }
            }
        }
    )
}

/**
 * Whether the loaded page is still a browser check.
 *
 * Two different checks are in play. Cloudflare answers with "Just a moment…"
 * and hands out `cf_clearance`; DDoS-Guard — which Anna's Archive sits behind
 * since 2026 — answers with a "DDoS-Guard" page that runs a fingerprint
 * script, sets `ddg_last_challenge` and reloads. Both are recognised, so one
 * dialog serves both.
 *
 * The marker is the id of the challenge page's own heading element: the title
 * is localised, the element is not.
 */
internal fun isChallengePage(title: String, marker: String): Boolean =
    marker == DDG_MARKER ||
        title.contains("Just a moment", ignoreCase = true) ||
        title.contains("Checking your browser", ignoreCase = true) ||
        title.contains("Attention Required", ignoreCase = true) ||
        title.contains("DDoS-Guard", ignoreCase = true)

/** Cloudflare's clearance cookie. */
private const val CLEARANCE_COOKIE = "cf_clearance"

/** The cookie DDoS-Guard's challenge script sets before it reloads the page. */
private const val DDG_CHALLENGE_COOKIE = "ddg_last_challenge"

/** Id of the DDoS-Guard challenge page's heading element. */
private const val DDG_MARKER = "ddg"

/** Reads the page title and the DDoS-Guard marker in one round trip. */
private const val READ_PAGE_STATE_JS =
    "(document.title||'') + '|' + " +
        "(document.getElementById('ddg-l10n-title') ? '$DDG_MARKER' : '')"
