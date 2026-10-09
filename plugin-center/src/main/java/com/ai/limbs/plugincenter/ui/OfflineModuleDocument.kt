package com.ai.limbs.plugincenter.ui

import android.graphics.Color
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/** One in-memory document, no network origin and no external resource resolver. */
internal object OfflineModuleDocument {
    fun load(view: WebView, html: String, fontScale: Float, onFailure: (String) -> Unit) {
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
        if (head == null) {
            onFailure("模块页面缺少 head 元素")
            return
        }
        val policy = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">"
        val secured = html.replaceRange(head.range, head.value + policy)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply {
            javaScriptEnabled = true
            textZoom = (fontScale * 100).roundToInt()
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            blockNetworkLoads = true
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        // Interception also receives data: URLs, including the top-level
        // memory document. Authorize this exact document, not an entire scheme.
        val encoded = Base64.encodeToString(secured.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val documentUrl = "data:text/html;charset=utf-8;base64,$encoded"
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame && request.method == "GET" && request.url.toString() == documentUrl) return null
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onFailure("模块页面加载失败：" + error.errorCode + " · " + error.description)
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) onFailure("模块页面加载失败：HTTP " + response.statusCode)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onFailure("模块界面进程已停止，请使用宿主恢复入口。")
                return true
            }
        }
        // Explicit charset and exact URL keep Chinese intact and admission deterministic.
        view.loadUrl(documentUrl)
    }
}

/** Public JVM methods remain visible to Android WebView's JS bridge. */
internal class ModuleMessageBridge(private val receive: (String) -> Unit) {
    @JavascriptInterface fun postMessage(message: String) { receive(message) }
}
