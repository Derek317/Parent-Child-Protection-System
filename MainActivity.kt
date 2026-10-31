package com.example.igguard

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    // Replace with your secret ntfy.sh topic URL (install the free ntfy app on the parent's phone and subscribe to this topic)
    private val parentWebhookUrl = "https://ntfy.sh/replace-with-parent-private-topic"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        setContentView(webView)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
        }

        // Native bridge called by ig_guard.js -> bypasses Instagram's Content-Security-Policy completely
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onAlert(jsonPayload: String) {
                thread {
                    try {
                        val obj = JSONObject(jsonPayload)
                        val reason = obj.optString("reason", "alert")
                        val sender = obj.optString("sender", "unknown")
                        val text = obj.optString("text", "")
                        val child = obj.optString("child", "teen")

                        val conn = (URL(parentWebhookUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 5000
                            readTimeout = 5000
                            doOutput = true
                            setRequestProperty("Title", "IG Alert ($reason): @$sender")
                            setRequestProperty(
                                "Priority",
                                if (reason == "sexual_or_grooming_content") "urgent" else "high"
                            )
                        }
                        conn.outputStream.use { out ->
                            out.write("[$child] @$sender: $text".toByteArray(Charsets.UTF_8))
                        }
                        conn.responseCode
                        conn.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
        }, "AndroidParentBridge")

        // Put ig_guard.js inside app/src/main/assets/ig_guard.js
        val guardScript = assets.open("ig_guard.js").bufferedReader().use { it.readText() }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host?.lowercase() ?: return true
                // Keep navigation strictly inside instagram.com
                return !host.endsWith("instagram.com")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(guardScript, null)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView.loadUrl("https://www.instagram.com/direct/inbox/")
    }
}