package com.ai.limbs.plugincenter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugincenter.runtime.PluginControlPlaneFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class SelfModulePresentationState {
    var status by mutableStateOf(JSONObject())
    var document by mutableStateOf<JSONObject?>(null)
    var error by mutableStateOf<String?>(null)
    var renderError by mutableStateOf<String?>(null)
    var loadedKey: String? = null
    fun retry() { loadedKey = null; renderError = null }
}

@Composable
internal fun rememberSelfModulePresentation(controlPlane: PluginControlPlaneFacade): SelfModulePresentationState {
    val state = remember(controlPlane) { SelfModulePresentationState() }
    LaunchedEffect(controlPlane) {
        while (true) {
            try {
                val status = withContext(Dispatchers.IO) { controlPlane.selfCall("self_status") }
                state.status = status
                val module = status.optJSONObject("module")
                val key = module?.let { listOf("identity_id", "data_id", "module_version", "generation").joinToString(":") { field -> it.getString(field) } } ?: "EMPTY"
                state.error = null
                if (state.loadedKey != key) {
                    state.document = null
                    state.renderError = null
                    state.loadedKey = key
                    if (module != null) {
                        try {
                            val resource = withContext(Dispatchers.IO) {
                                controlPlane.selfCall("self_resources", JSONObject().put("paths", JSONArray(listOf("resources/presentation.json"))))
                            }
                            if (resource.optBoolean("available")) {
                                fun decode(value: JSONObject, path: String): String {
                                    val bytes = Base64.decode(value.getJSONObject("resources").getString(path), Base64.DEFAULT)
                                    return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                                }
                                val descriptor = JSONObject(decode(resource, "resources/presentation.json"))
                                check(descriptor.getInt("api_version") == 1 && descriptor.getString("runtime") == "html") { "不支持的模块界面协议" }
                                val binding = resource.getJSONObject("binding")
                                suspend fun load(field: String): String {
                                    val path = descriptor.getString(field)
                                    val content = withContext(Dispatchers.IO) {
                                        controlPlane.selfCall("self_resources", JSONObject().put("paths", JSONArray(listOf(path))).put("program_binding", binding))
                                    }
                                    return decode(content, path)
                                }
                                state.document = JSONObject().put("binding", binding).put("html", load("entry")).put("summary_html", load("summary_entry"))
                            } else state.renderError = "当前模块未提供界面资源。可使用宿主恢复入口升级到含界面的版本。"
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { state.renderError = "无法加载模块界面：${error.message}" }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { state.error = "读取自我模块状态失败：${error.message}" }
            delay(3000)
        }
    }
    return state
}

/** Placement belongs to Plugin Center; the tile's content/style comes from the active .ails. */
@Composable
internal fun SelfModuleSummary(state: SelfModulePresentationState, enabled: Boolean, onOpen: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    Card(Modifier.width(128.dp * fontScale).height(104.dp * fontScale)) {
        Box(Modifier.fillMaxSize()) {
            val document = state.document
            if (document != null && state.renderError == null && state.error == null) {
                key(document.getJSONObject("binding").toString()) {
                    ModuleHtmlView(document.getString("summary_html"), Modifier.fillMaxSize(),
                        onRequest = { request ->
                            check(request.getString("method") == "status") { "SUMMARY_READ_ONLY" }
                            state.status
                        }, onFailure = { state.renderError = it })
                }
            } else Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("自我模块", style = MaterialTheme.typography.titleSmall)
                val module = state.status.optJSONObject("module")
                Text(when {
                    state.error != null -> "状态读取失败"
                    !state.status.optBoolean("success") -> "正在读取…"
                    module == null -> "尚未安装"
                    state.renderError != null -> "界面不可用"
                    else -> "正在加载…"
                }, style = MaterialTheme.typography.bodySmall)
                Text("管理 ›", color = MaterialTheme.colorScheme.primary)
            }
            // Native hit target opens management even when the supplied summary cannot render.
            Box(Modifier.matchParentSize().clickable(enabled = enabled, onClick = onOpen))
        }
    }
}

private class ModuleConsent(val description: String, val decision: CompletableDeferred<Boolean>)
private class ModuleDocumentPick(val result: CompletableDeferred<Uri?>)

/** Only a container, native human-operation transport, and an independent recovery entry. */
@Composable
internal fun SelfModuleScreen(controlPlane: PluginControlPlaneFacade, onBack: () -> Unit) {
    val state = rememberSelfModulePresentation(controlPlane)
    val context = LocalContext.current
    var consent by remember { mutableStateOf<ModuleConsent?>(null) }
    var packagePick by remember { mutableStateOf<ModuleDocumentPick?>(null) }
    var exportPick by remember { mutableStateOf<ModuleDocumentPick?>(null) }
    val packages = remember { mutableMapOf<String, Uri>() }
    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        packagePick?.result?.complete(uri); packagePick = null
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        exportPick?.result?.complete(uri); exportPick = null
    }
    DisposableEffect(Unit) {
        onDispose {
            consent?.decision?.cancel(); packagePick?.result?.cancel(); exportPick?.result?.cancel()
            packages.clear()
        }
    }
    consent?.let { pending ->
        AlertDialog(onDismissRequest = { pending.decision.complete(false); consent = null },
            title = { Text("确认自我模块操作") },
            text = { Text(pending.description, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { pending.decision.complete(true); consent = null }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { pending.decision.complete(false); consent = null }) { Text("取消") } })
    }
    suspend fun confirm(method: String, parameters: JSONObject): Boolean {
        check(consent == null) { "已有操作等待确认" }
        val decision = CompletableDeferred<Boolean>()
        val label = when (method) { "submit", "request" -> "提交权限或操作申请"; "execute" -> "执行已获批准的操作"; "cancel_request" -> "取消待审批申请"; "copy" -> "复制模块内容到剪贴板"; else -> error("MODULE_METHOD_FORBIDDEN") }
        consent = ModuleConsent("$label\n${moduleOperationDescription(method, parameters)}", decision)
        try { return decision.await() } finally { if (consent?.decision === decision) consent = null }
    }
    suspend fun checkCurrent(binding: JSONObject) {
        val current = withContext(Dispatchers.IO) { controlPlane.selfCall("self_status") }.getJSONObject("module")
        for (field in listOf("identity_id", "data_id", "module_version", "generation"))
            check(current.getString(field) == binding.getString(field)) { "SELF_PROGRAM_BINDING_STALE，请重新打开管理界面" }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("返回总控台") }
        }
        state.error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        val document = state.document
        if (document == null || state.renderError != null) {
            state.renderError?.let { Text(it, Modifier.padding(12.dp)) }
            if (state.status.optJSONObject("module") != null && document == null && state.renderError == null)
                Text("正在加载模块界面…", Modifier.padding(12.dp))
            if (state.status.optBoolean("success")) SelfModuleRecovery(controlPlane, state.status, onReload = { state.retry() })
            else TextButton(onClick = { state.retry() }) { Text("正在读取状态，可点击重试") }
        } else key(document.getJSONObject("binding").toString()) {
            val binding = document.getJSONObject("binding")
            ModuleHtmlView(document.getString("html"), Modifier.fillMaxWidth().weight(1f), onFailure = { state.renderError = it }, onRequest = { envelope ->
                val method = envelope.getString("method")
                val parameters = envelope.optJSONObject("parameters") ?: JSONObject()
                rejectModulePrivatePaths(parameters)
                when (method) {
                    "status" -> withContext(Dispatchers.IO) { controlPlane.selfCall("self_status") }
                    "back" -> { onBack(); JSONObject().put("success", true) }
                    "choose_package" -> {
                        checkCurrent(binding)
                        check(packagePick == null) { "已有文件选择正在进行" }
                        val result = CompletableDeferred<Uri?>()
                        packagePick = ModuleDocumentPick(result)
                        packagePicker.launch(arrayOf("*/*"))
                        try {
                            val uri = result.await()
                            if (uri == null) JSONObject().put("success", true).put("cancelled", true)
                            else {
                                checkCurrent(binding)
                                val token = UUID.randomUUID().toString()
                                packages.clear(); packages[token] = uri
                                JSONObject().put("success", true).put("package_token", token)
                            }
                        } finally { if (packagePick?.result === result) packagePick = null }
                    }
                    "save_migration" -> {
                        checkCurrent(binding)
                        check(exportPick == null) { "已有文件保存正在进行" }
                        val result = CompletableDeferred<Uri?>()
                        exportPick = ModuleDocumentPick(result)
                        exportPicker.launch("self-migration.ails")
                        try {
                            val uri = result.await()
                            if (uri == null) JSONObject().put("success", true).put("cancelled", true)
                            else { checkCurrent(binding); withContext(Dispatchers.IO) { controlPlane.selfCall("self_export_uri", JSONObject().put("uri", uri.toString())) } }
                        } finally { if (exportPick?.result === result) exportPick = null }
                    }
                    "copy" -> {
                        checkCurrent(binding)
                        if (!confirm(method, parameters)) JSONObject().put("success", true).put("cancelled", true)
                        else {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("自我模块", parameters.getString("text")))
                            JSONObject().put("success", true)
                        }
                    }
                    "submit", "request", "execute", "cancel_request" -> {
                        checkCurrent(binding)
                        if (!confirm(method, parameters)) JSONObject().put("success", true).put("cancelled", true)
                        else {
                            val args = JSONObject(parameters.toString())
                            val token = args.optString("package_token")
                            args.remove("package_token")
                            args.put("program_binding", binding)
                            val operation = if (token.isNotEmpty()) {
                                check(method in setOf("submit", "request", "execute")) { "MODULE_METHOD_FORBIDDEN" }
                                val uri = packages[token] ?: error("请重新选择 .ails 包")
                                args.put("uri", uri.toString()).put("command", method)
                                "self_request_uri"
                            } else "self_$method"
                            withContext(Dispatchers.IO) { controlPlane.selfCall(operation, args) }
                        }
                    }
                    else -> error("MODULE_METHOD_FORBIDDEN")
                }
            })
        }
    }
}

/** A module page never supplies host paths, SAF URIs, or its own authority binding. */
private fun rejectModulePrivatePaths(value: Any, depth: Int = 0) {
    check(depth <= 24) { "MODULE_PAYLOAD_TOO_DEEP" }
    when (value) {
        is JSONObject -> value.keys().asSequence().forEach { key ->
            check(key !in setOf("package_path", "uri", "program_binding")) { "MODULE_PRIVATE_PATH_FORBIDDEN" }
            rejectModulePrivatePaths(value.get(key), depth + 1)
        }
        is JSONArray -> for (index in 0 until value.length()) rejectModulePrivatePaths(value.get(index), depth + 1)
    }
}

/** Show authoritative operation fields before user-authored text; ignored fields cannot hide them. */
private fun moduleOperationDescription(method: String, args: JSONObject): String {
    fun operation(item: JSONObject): String {
        val name = when (item.getString("operation")) { "upgrade" -> "升级"; "rollback" -> "回滚"; "migrate" -> "迁移"; else -> error("MODULE_OPERATION_FORBIDDEN") }
        val parameters = item.getJSONObject("parameters")
        val fields = listOf("phase", "target_version", "target_device_id").filter { parameters.has(it) }.map { field ->
            val value = parameters.getString(field)
            check(value.length <= 128 && value.none { it.isISOControl() }) { "MODULE_OPERATION_FIELD_INVALID" }
            val label = when (field) { "phase" -> "交接步骤"; "target_version" -> "目标版本"; else -> "目标设备" }
            "$label：$value"
        }
        return (listOf(name) + fields).joinToString("\n")
    }
    return when (method) {
        "submit" -> {
            val items = args.getJSONArray("items")
            check(items.length() in 1..3) { "MODULE_APPLICATION_INVALID" }
            val mode = when (args.getString("mode")) { "ONE_TIME" -> "一次性"; "TIMED" -> "限时"; "LONG" -> "长期至撤销"; else -> error("MODULE_APPLICATION_INVALID") }
            (0 until items.length()).joinToString("\n\n") { operation(items.getJSONObject(it)) } +
                "\n申请性质：$mode" + (if (args.has("duration_seconds")) "\n申请秒数：${args.getLong("duration_seconds")}" else "") +
                "\n申请理由：${args.getString("reason").take(2000)}"
        }
        "request", "execute" -> operation(args)
        "cancel_request" -> "申请编号：${args.getString("request_id").take(80)}"
        "copy" -> args.getString("text").take(4000)
        else -> error("MODULE_METHOD_FORBIDDEN")
    } + if (args.has("package_token")) "\n使用系统文件选择器中选定的 .ails 包。" else ""
}
