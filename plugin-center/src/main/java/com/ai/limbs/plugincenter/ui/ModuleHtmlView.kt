package com.ai.limbs.plugincenter.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Opaque, offline module presentation. It owns no domain buttons or lifecycle decisions. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ModuleHtmlView(
    html: String,
    modifier: Modifier,
    onRequest: suspend (JSONObject) -> JSONObject,
    onFailure: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val request = rememberUpdatedState(onRequest)
    val failure = rememberUpdatedState(onFailure)
    val view = remember(html, context) { WebView(context) }
    DisposableEffect(view) {
        var alive = true
        var ready = false
        val pending = mutableSetOf<String>()
        fun deliver(id: String, response: JSONObject) {
            if (!alive) return
            val message = JSONObject().put("id", id).put("response", response).toString()
            view.evaluateJavascript("window.ailsReceive && window.ailsReceive(${JSONObject.quote(message)});", null)
        }
        val bridge = ModuleMessageBridge { message ->
            // JavascriptInterface runs off the UI thread. Ownership and all UI work return to Main.
            view.post {
                if (!alive || message.length > 65536) return@post
                val envelope = try { JSONObject(message) } catch (_: Exception) { return@post }
                val id = envelope.optString("id")
                if (!Regex("^[A-Za-z0-9_-]{1,80}$").matches(id) || pending.size >= 16 || !pending.add(id)) return@post
                if (envelope.optString("method") == "__ready") {
                    ready = true
                    pending.remove(id)
                    deliver(id, JSONObject().put("success", true))
                    return@post
                }
                scope.launch {
                    try { deliver(id, request.value(envelope)) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { deliver(id, JSONObject().put("success", false).put("error", error.message ?: error.javaClass.simpleName)) }
                    finally { pending.remove(id) }
                }
            }
        }
        val watchdog = scope.launch {
            delay(10000)
            if (alive && !ready) failure.value("模块界面未完成启动，请使用宿主恢复入口。")
        }
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply {
            javaScriptEnabled = true
            textZoom = (context.resources.configuration.fontScale * 100).roundToInt()
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
        view.addJavascriptInterface(bridge, "AilsTransport")
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (alive && request.isForMainFrame) failure.value("模块页面加载失败：${error.description}")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (alive) failure.value("模块界面进程已停止，请使用宿主恢复入口。")
                return true
            }
        }
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
        if (!head.containsMatchIn(html)) failure.value("模块页面缺少 head 元素")
        else {
            // The host policy is prepended; module policy cannot relax this additional CSP.
            val policy = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">"
            val match = requireNotNull(head.find(html))
            val secured = html.replaceRange(match.range, match.value + policy)
            view.loadDataWithBaseURL("https://module.invalid/", secured, "text/html", "utf-8", null)
        }
        onDispose {
            alive = false
            watchdog.cancel()
            view.removeJavascriptInterface("AilsTransport")
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

private class ModuleMessageBridge(private val receive: (String) -> Unit) {
    @JavascriptInterface fun postMessage(message: String) { receive(message) }
}
