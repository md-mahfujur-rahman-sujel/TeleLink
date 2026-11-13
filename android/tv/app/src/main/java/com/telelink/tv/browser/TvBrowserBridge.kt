package com.telelink.tv.browser

import android.webkit.WebView
import java.lang.ref.WeakReference

object TvBrowserBridge {
    @Volatile
    private var activeWebView = WeakReference<WebView>(null)

    fun attach(webView: WebView) {
        activeWebView = WeakReference(webView)
    }

    fun detach(webView: WebView) {
        if (activeWebView.get() === webView) activeWebView.clear()
    }

    fun control(
        action: String,
        positionMs: Long? = null,
        onResult: (Boolean) -> Unit
    ): Boolean {
        val webView = activeWebView.get() ?: return false
        val script = when (action.uppercase()) {
            "PLAY" -> "(()=>{const v=document.querySelector('video');if(!v)return false;v.play();return true})()"
            "PAUSE" -> "(()=>{const v=document.querySelector('video');if(!v)return false;v.pause();return true})()"
            "STOP" -> "(()=>{const v=document.querySelector('video');if(!v)return false;v.pause();v.currentTime=0;return true})()"
            "SEEK" -> {
                val seconds = (positionMs ?: return false).coerceAtLeast(0L) / 1000.0
                "(()=>{const v=document.querySelector('video');if(!v)return false;v.currentTime=$seconds;return true})()"
            }
            "SEEK_FORWARD" -> "(()=>{const v=document.querySelector('video');if(!v)return false;v.currentTime=Math.min(Number.isFinite(v.duration)?v.duration:v.currentTime+10,v.currentTime+10);return true})()"
            "SEEK_BACK" -> "(()=>{const v=document.querySelector('video');if(!v)return false;v.currentTime=Math.max(0,v.currentTime-10);return true})()"
            "VOLUME_UP" -> "(()=>{const v=document.querySelector('video');if(!v)return false;v.volume=Math.min(1,v.volume+0.1);return true})()"
            "VOLUME_DOWN" -> "(()=>{const v=document.querySelector('video');if(!v)return false;v.volume=Math.max(0,v.volume-0.1);return true})()"
            else -> return false
        }
        val posted = webView.post {
            try {
                webView.evaluateJavascript(script) { result -> onResult(result == "true") }
            } catch (_: Exception) {
                onResult(false)
            }
        }
        if (!posted) {
            onResult(false)
            return false
        }
        return true
    }
}
