package com.lexiread.presentation.common

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Passes a mirror's browser challenge (Cloudflare 503) in a real WebView.
 *
 * Plain HTTP can never clear the check, so the file download throws
 * [com.lexiread.data.source.ChallengeRequiredException] and the caller shows
 * this dialog instead of an error. Once the page title no longer looks like a
 * challenge (or `cf_clearance` lands in the cookies), [onPassed] fires once
 * and the caller syncs cookies into OkHttp and retries the same download.
 */
@Composable
fun ChallengeWebViewDialog(
    url: String,
    onPassed: () -> Unit,
    onDismiss: () -> Unit
) {
    var status by remember { mutableStateOf("Passing the browser check…") }
    var fired by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("One browser check") },
        text = {
            Column {
                Text(
                    "The mirror asks for one quick check. It passes by itself — " +
                        "the download then retries on its own.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, finishedUrl: String) {
                                    if (fired) return
                                    val cookies = CookieManager.getInstance()
                                        .getCookie(finishedUrl).orEmpty()
                                    view.evaluateJavascript("document.title") { title ->
                                        if (fired) return@evaluateJavascript
                                        val clean = title?.trim('"').orEmpty()
                                        val challenged = clean.contains("Checking", ignoreCase = true) ||
                                            clean.contains("Just a moment", ignoreCase = true) ||
                                            clean.contains("Attention Required", ignoreCase = true)
                                        if (!challenged || "cf_clearance" in cookies) {
                                            fired = true
                                            status = "Check passed. Retrying the download…"
                                            onPassed()
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
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
