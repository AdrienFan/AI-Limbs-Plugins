package com.ai.limbs.plugincenter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugincenter.runtime.PluginControlPlaneFacade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Lifecycle UI creates applications only; it never approves its own application. */
@Composable
internal fun SelfModuleScreen(controlPlane: PluginControlPlaneFacade, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(JSONObject()) }
    var message by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var evidence by remember { mutableStateOf("") }
    var selectedAction by remember { mutableStateOf("install") }
    var busy by remember { mutableStateOf(false) }
    suspend fun refresh() { status = withContext(Dispatchers.IO) { controlPlane.selfCall("self_status") } }
    fun perform(operation: String, args: JSONObject = JSONObject()) {
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { controlPlane.selfCall(operation, args) }
                message = if (operation.startsWith("self_request")) "申请已提交，等待 AI 明确批准或拒绝。" else "操作已完成。"
                if (result.has("request_id")) message += "\n申请编号：${result.getString("request_id")}"
                refresh()
            } catch (error: Exception) { message = error.message ?: error.javaClass.simpleName }
            finally { busy = false }
        }
    }
    fun request(operation: String, args: JSONObject) = perform("self_request", JSONObject().put("operation", operation).put("parameters", args))
    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (selectedAction == "install") perform("self_install_uri", JSONObject().put("uri", uri.toString()))
            else perform("self_request_uri", JSONObject().put("uri", uri.toString())
                .put("operation", if (selectedAction == "upgrade") "upgrade" else "migrate")
                .put("parameters", if (selectedAction == "upgrade") JSONObject() else JSONObject().put("phase", "prepare")))
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) perform("self_export_uri", JSONObject().put("uri", uri.toString()))
    }
    fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("自我模块交接凭据", text))
        message = "已复制交接凭据。"
    }
    LaunchedEffect(Unit) {
        while (true) {
            try { refresh() } catch (error: Exception) { message = error.message ?: error.javaClass.simpleName }
            delay(3000)
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("返回总控台") }
        Text("空白自我模块", style = MaterialTheme.typography.headlineSmall)
        Text("首次安装可直接执行。升级、迁移和回滚由 AI 审批；未连接或未决定时保持等待。")
        val module = status.optJSONObject("module")
        SelectionContainer {
            Text("本机交接标识：${status.optString("device_id")}\n" + if (module == null) "尚未安装自我模块" else
                "身份：${module.optString("identity_id")}\n版本：${module.optString("module_version")}\n状态：${module.optString("lifecycle_state")}")
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)
        if (module == null) {
            Button(enabled = !busy, onClick = { selectedAction = "install"; packagePicker.launch(arrayOf("*/*")) }) { Text("选择 .ails 并首次安装") }
            OutlinedButton(enabled = !busy, onClick = { selectedAction = "prepare"; packagePicker.launch(arrayOf("*/*")) }) { Text("选择迁移包并申请接收") }
        } else {
            if (module.optString("lifecycle_state") == "ACTIVE") {
                Button(enabled = !busy, onClick = { selectedAction = "upgrade"; packagePicker.launch(arrayOf("*/*")) }) { Text("选择 .ails 并申请升级") }
                OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("回滚目标版本") }, supportingText = { Text("历史版本：${status.optJSONArray("versions") ?: "[]"}") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(enabled = !busy && version.isNotBlank(), onClick = { request("rollback", JSONObject().put("target_version", version.trim())) }) { Text("申请回滚") }
                OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("目标设备的交接标识") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(enabled = !busy && target.isNotBlank(), onClick = { request("migrate", JSONObject().put("phase", "export").put("target_device_id", target.trim())) }) { Text("申请封存并迁出") }
            } else if (module.optString("direction") == "outbound") {
                Text("源模块已封存，数据保留。目标验证后，复制其接收凭据到这里完成运行权交接。")
                OutlinedButton(enabled = !busy, onClick = { exportPicker.launch("self-migration-${module.optString("migration_id")}.ails") }) { Text("保存迁移包到选定位置") }
                if (!module.optBoolean("transfer_committed")) {
                    OutlinedTextField(value = evidence, onValueChange = { evidence = it }, label = { Text("目标设备接收凭据") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    Button(enabled = !busy && evidence.isNotBlank(), onClick = {
                        try { request("migrate", JSONObject().put("phase", "commit").put("receipt", JSONObject(evidence))) }
                        catch (error: Exception) { message = "交接凭据格式无效：${error.message}" }
                    }) { Text("申请交出运行权") }
                    OutlinedButton(enabled = !busy, onClick = { request("migrate", JSONObject().put("phase", "cancel")) }) { Text("申请取消迁出并恢复") }
                }
                module.optJSONObject("release")?.let { release -> Button(onClick = { copy(release.toString()) }) { Text("复制运行权交接凭据") } }
            } else if (module.optString("direction") == "inbound") {
                Text("目标模块已验证，等待源端交出运行权，当前不能运行。")
                module.optJSONObject("prepared_receipt")?.let { receipt -> Button(onClick = { copy(receipt.toString()) }) { Text("复制接收凭据给源设备") } }
                OutlinedTextField(value = evidence, onValueChange = { evidence = it }, label = { Text("源设备的交接或取消凭据") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(enabled = !busy && evidence.isNotBlank(), onClick = {
                    try { request("migrate", JSONObject().put("phase", "activate").put("release", JSONObject(evidence))) }
                    catch (error: Exception) { message = "交接凭据格式无效：${error.message}" }
                }) { Text("申请激活迁入模块") }
                OutlinedButton(enabled = !busy && evidence.isNotBlank(), onClick = {
                    try { request("migrate", JSONObject().put("phase", "discard").put("abort", JSONObject(evidence))) }
                    catch (error: Exception) { message = "取消凭据格式无效：${error.message}" }
                }) { Text("源端取消后申请释放迁入槽位") }
            }
        }
        module?.optJSONObject("abort")?.let { abort -> TextButton(onClick = { copy(abort.toString()) }) { Text("复制源端取消凭据") } }
        Text("操作申请", style = MaterialTheme.typography.titleMedium)
        val requests = status.optJSONArray("requests")
        if (requests != null) for (index in requests.length() - 1 downTo 0) {
            val item = requests.getJSONObject(index)
            Text("${item.optString("operation")} · ${item.optString("status")}\n${item.optString("request_id")}")
            if (item.optString("status") == "PENDING") TextButton(enabled = !busy, onClick = { perform("self_cancel_request", JSONObject().put("request_id", item.getString("request_id"))) }) { Text("取消等待中的申请") }
            item.optJSONObject("result")?.optJSONObject("abort")?.let { abort -> TextButton(onClick = { copy(abort.toString()) }) { Text("复制源端取消凭据") } }
        }
    }
}
