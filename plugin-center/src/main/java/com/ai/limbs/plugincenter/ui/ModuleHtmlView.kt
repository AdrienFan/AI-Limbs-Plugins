package com.ai.limbs.plugincenter.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
        view.addJavascriptInterface(bridge, "AilsTransport")
        OfflineModuleDocument.load(view, html, context.resources.configuration.fontScale) {
            if (alive) failure.value(it)
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
