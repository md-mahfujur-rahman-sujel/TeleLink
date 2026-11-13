package com.telelink.tv.browser

import android.os.Build
import android.os.Bundle
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.telelink.tv.ui.theme.TeleLinkTVTheme

class TvBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUrl = intent.getStringExtra(EXTRA_URL) ?: "https://www.google.com"
        setContent {
            TeleLinkTVTheme {
                TvBrowserScreen(
                    initialUrl = initialUrl,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "com.telelink.tv.browser.URL"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TvBrowserScreen(
    initialUrl: String,
    onBack: () -> Unit
) {
    var address by remember { mutableStateOf(initialUrl) }
    var pageStatus by remember { mutableStateOf("Loading") }
    var mainFrameError by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(webView) {
        webView?.let(TvBrowserBridge::attach)
        onDispose {
            webView?.let { view ->
                TvBrowserBridge.detach(view)
                view.stopLoading()
                view.onPause()
                view.pauseTimers()
                view.removeAllViews()
                view.destroy()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Web address") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { webView?.loadUrl(normalizeWebUrl(address)) },
                modifier = Modifier.height(56.dp)
            ) { Text("Go") }
            OutlinedButton(onClick = { webView?.goBack() }, modifier = Modifier.height(56.dp)) {
                Text("Back")
            }
            OutlinedButton(onClick = { webView?.reload() }, modifier = Modifier.height(56.dp)) {
                Text("Reload")
            }
            TextButton(onClick = onBack, modifier = Modifier.height(56.dp)) { Text("Close") }
        }
        Text(pageStatus, style = MaterialTheme.typography.labelSmall)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    // Modern sites and HTML video need JS. The page has no native bridge; file access is off,
                    // mixed content is blocked, navigation is HTTP(S)-only, and Safe Browsing stays enabled.
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            mainFrameError = false
                            pageStatus = "Loading"
                            if (!url.isNullOrBlank()) address = url
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!mainFrameError) pageStatus = "Ready"
                            if (!url.isNullOrBlank()) address = url
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                mainFrameError = true
                                pageStatus = "Page could not load: ${error?.description ?: "network error"}"
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            if (request?.isForMainFrame == true) {
                                mainFrameError = true
                                pageStatus = "Website returned HTTP ${errorResponse?.statusCode ?: "error"}"
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url ?: return true
                            if (uri.scheme == "https" || uri.scheme == "http") return false
                            pageStatus = "Unsupported link type: ${uri.scheme ?: "unknown"}"
                            return true
                        }

                        @SuppressLint("NewApi") // WebView invokes this callback only on API 27 and newer.
                        @RequiresApi(Build.VERSION_CODES.O_MR1)
                        override fun onSafeBrowsingHit(
                            view: WebView?,
                            request: WebResourceRequest?,
                            threatType: Int,
                            callback: SafeBrowsingResponse?
                        ) {
                            pageStatus = "Blocked by Safe Browsing"
                            callback?.backToSafety(true)
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            if (newProgress < 100) pageStatus = "Loading $newProgress%"
                        }
                    }
                    webView = this
                    TvBrowserBridge.attach(this)
                    loadUrl(normalizeWebUrl(initialUrl))
                }
            },
            update = { view ->
                webView = view
                TvBrowserBridge.attach(view)
            }
        )
    }
}

private fun normalizeWebUrl(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
        trimmed
    } else {
        "https://$trimmed"
    }
}
