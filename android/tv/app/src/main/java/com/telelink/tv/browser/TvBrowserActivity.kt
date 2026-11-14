package com.telelink.tv.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.telelink.tv.ui.TvButton
import com.telelink.tv.ui.TvButtonStyle
import com.telelink.tv.ui.TvDesignSpace
import com.telelink.tv.ui.theme.TeleLinkTVTheme

class TvBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUrl = intent.getStringExtra(EXTRA_URL) ?: HOME_URL
        setContent {
            TeleLinkTVTheme {
                TvDesignSpace {
                    TvBrowserScreen(
                        initialUrl = initialUrl,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "com.telelink.tv.browser.URL"
        const val HOME_URL = "https://www.google.com"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TvBrowserScreen(
    initialUrl: String,
    onBack: () -> Unit
) {
    val activity = findActivity(LocalContext.current)
    var address by remember { mutableStateOf(initialUrl) }
    var pageStatus by remember { mutableStateOf("Loading") }
    var mainFrameError by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    fun setSystemBarsVisible(visible: Boolean) {
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Full-screen video: the page hands us its video view and we show it above everything.
    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        (activity?.window?.decorView as? FrameLayout)?.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setSystemBarsVisible(false)
    }

    fun exitFullscreen() {
        val view = customView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        setSystemBarsVisible(true)
    }

    // Back leaves full screen first, then goes to the previous page, then closes the browser.
    BackHandler {
        when {
            customView != null -> exitFullscreen()
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onBack()
        }
    }

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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 40.dp, vertical = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Search Google or type a web address") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TvButton(
                text = "Go",
                onClick = { webView?.loadUrl(normalizeWebUrl(address)) },
                style = TvButtonStyle.Primary,
                compact = true
            )
            TvButton(text = "Back", onClick = { webView?.goBack() }, compact = true)
            TvButton(text = "Reload", onClick = { webView?.reload() }, compact = true)
            TvButton(
                text = "Home",
                onClick = { webView?.loadUrl(TvBrowserActivity.HOME_URL) },
                compact = true
            )
            TvButton(text = "−", onClick = { webView?.zoomOut() }, compact = true)
            TvButton(text = "+", onClick = { webView?.zoomIn() }, compact = true)
            TvButton(
                text = "Close",
                onClick = onBack,
                style = TvButtonStyle.Danger,
                compact = true
            )
        }
        Text(
            pageStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
                    // A big-screen (desktop) page layout reads far better on a TV than a phone layout.
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.userAgentString = desktopUserAgent(settings.userAgentString)
                    // Zoom buttons in the toolbar use the built-in zoom without the on-screen widget.
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // Needed for logins and many video players.
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.setSupportMultipleWindows(true)
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
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            if (newProgress < 100) pageStatus = "Loading $newProgress%"
                        }

                        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                            if (view != null && callback != null) enterFullscreen(view, callback)
                        }

                        override fun onHideCustomView() {
                            exitFullscreen()
                        }

                        // Links that open a new tab or window load in this same browser view.
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            if (view == null || resultMsg == null || !isUserGesture) return false
                            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                            val popup = WebView(view.context)
                            popup.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    popupView: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val target = request?.url
                                    if (target != null && (target.scheme == "https" || target.scheme == "http")) {
                                        view.loadUrl(target.toString())
                                    }
                                    popupView?.destroy()
                                    return true
                                }
                            }
                            transport.webView = popup
                            resultMsg.sendToTarget()
                            return true
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

private fun findActivity(context: Context): Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/** Same WebView engine version, but identifying as desktop Chrome so sites serve the big-screen layout. */
private fun desktopUserAgent(default: String): String {
    val chromeVersion = Regex("Chrome/([\\d.]+)").find(default)?.groupValues?.get(1) ?: "124.0.0.0"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/$chromeVersion Safari/537.36"
}

/** Web addresses open directly; anything else is searched on Google. */
private fun normalizeWebUrl(value: String): String {
    val input = value.trim()
    if (input.isEmpty()) return TvBrowserActivity.HOME_URL
    if (input.startsWith("http://", true) || input.startsWith("https://", true)) return input
    val looksLikeAddress = !input.contains(' ') &&
        (input.contains('.') || input.startsWith("localhost", true))
    return if (looksLikeAddress) {
        "https://$input"
    } else {
        "https://www.google.com/search?q=${Uri.encode(input)}"
    }
}
