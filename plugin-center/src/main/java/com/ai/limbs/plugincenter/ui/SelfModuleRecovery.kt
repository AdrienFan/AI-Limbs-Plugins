package com.ai.limbs.plugincenter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Explicit host recovery surface, independent of package-supplied HTML and JS. */
@Composable
internal fun SelfModuleRecovery(controlPlane: PluginControlPlaneFacade, status: JSONObject, onReload: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("模块界面恢复：申请修复当前空白自我模块。") }
    var selectedVersion by remember { mutableStateOf("") }
    var evidence by remember { mutableStateOf("") }
    var pickerCommand by remember { mutableStateOf("upgrade") }
    var versionMenu by remember { mutableStateOf(false) }
    val module = status.optJSONObject("module")
    val grants = status.optJSONObject("authorizations")?.optJSONArray("effective_grants") ?: JSONArray()
    fun authorized(operation: String) = (0 until grants.length()).any { grants.getJSONObject(it).getString("operation") == operation }
    fun perform(operation: String, args: JSONObject = JSONObject()) {
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { controlPlane.selfCall(operation, args) }
                message = if (operation in setOf("self_submit", "self_request")) "已提交，等待 AI 审批。" else "操作已完成；申请类操作请查看审批结果。"
                onReload()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: error.javaClass.simpleName }
            finally { busy = false }
        }
    }
    fun operate(operation: String, parameters: JSONObject) {
        val continuation = operation == "migrate" && (if (parameters.has("migration_id")) status.optJSONArray("outgoing_migrations")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) }.firstOrNull { it.getString("migration_id") == parameters.getString("migration_id") } else module)?.optBoolean("human_migration_authorized") == true &&
            parameters.optString("phase") in setOf("commit", "activate", "cancel", "discard")
        if (authorized(operation) || continuation) perform("self_execute", JSONObject().put("operation", operation).put("parameters", parameters))
        else perform("self_submit", JSONObject().put("mode", "ONE_TIME").put("reason", reason.trim())
            .put("items", JSONArray().put(JSONObject().put("operation", operation).put("parameters", parameters))))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (pickerCommand == "install") perform("self_install_uri", JSONObject().put("uri", uri.toString()))
            else {
                val operation = if (pickerCommand == "prepare") "migrate" else "upgrade"
                val parameters = if (pickerCommand == "prepare") JSONObject().put("phase", "prepare") else JSONObject()
                val args = JSONObject().put("uri", uri.toString())
                if (authorized(operation)) args.put("command", "execute").put("operation", operation).put("parameters", parameters)
                else args.put("command", "submit").put("mode", "ONE_TIME").put("reason", reason.trim())
                    .put("items", JSONArray().put(JSONObject().put("operation", operation).put("parameters", parameters)))
                perform("self_request_uri", args)
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) perform("self_export_uri", JSONObject().put("uri", uri.toString()))
    }
    fun copy(value: JSONObject) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("自我模块交接凭据", value.toString()))
        message = "已复制交接凭据。"
    }
    val versions = status.optJSONArray("versions") ?: JSONArray()
    fun parts(version: String) = version.split('.').map { it.toLong() }
    val older = (0 until versions.length()).map { versions.getString(it) }.filter { version ->
        module != null && parts(version).zip(parts(module.getString("module_version"))).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 }?.let { it < 0 } == true
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("宿主安装与恢复", style = MaterialTheme.typography.titleLarge)
        Text("此入口独立于模块界面，仍执行 AI 授权和技术校验。")
        SelectionContainer { Text("本机交接标识：${status.optString("device_id")}\n" + (module?.let { "身份：${it.getString("identity_id")}\n版本：${it.getString("module_version")} · ${it.getString("lifecycle_state")}" } ?: "尚未安装")) }
        if (message.isNotEmpty()) Text(message)
        OutlinedTextField(value = reason, onValueChange = { reason = it.take(2000) }, label = { Text("无持续授权时的申请理由") }, modifier = Modifier.fillMaxWidth())
        val transfers = status.optJSONArray("outgoing_migrations") ?: JSONArray()
        for (index in 0 until transfers.length()) {
            val transfer = transfers.getJSONObject(index)
            val migrationId = transfer.getString("migration_id")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已卸载源端模块的迁移交接", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text("身份：${transfer.getString("identity_id")}\n迁移编号：$migrationId\n安装槽位已释放，迁移包和恢复资料仍保留。") }
                    if (transfer.optBoolean("transfer_committed")) {
                        OutlinedButton(enabled = !busy, onClick = { copy(transfer.getJSONObject("release")) }) { Text("复制运行权交接凭据") }
                    } else {
                        OutlinedTextField(value = evidence, onValueChange = { evidence = it }, label = { Text("目标设备接收凭据") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && evidence.isNotBlank(), onClick = {
                            runCatching { JSONObject(evidence) }.onSuccess { operate("migrate", JSONObject().put("phase", "commit").put("migration_id", migrationId).put("receipt", it)) }
                                .onFailure { message = "凭据格式无效" }
                        }) { Text("完成运行权交接") }
                        OutlinedButton(enabled = !busy && module == null, onClick = { operate("migrate", JSONObject().put("phase", "cancel").put("migration_id", migrationId)) }) { Text("取消未交接迁移并恢复源端模块") }
                    }
                }
            }
        }
        if (module == null) {
            Button(enabled = !busy, onClick = { pickerCommand = "install"; picker.launch(arrayOf("*/*")) }) { Text("选择 .ails 并首次安装") }
            OutlinedButton(enabled = !busy && reason.isNotBlank(), onClick = { pickerCommand = "prepare"; picker.launch(arrayOf("*/*")) }) { Text("选择迁移包并申请迁入") }
        } else if (module.getString("lifecycle_state") == "ACTIVE") {
            Button(enabled = !busy && (authorized("upgrade") || reason.isNotBlank()), onClick = { pickerCommand = "upgrade"; picker.launch(arrayOf("*/*")) }) { Text(if (authorized("upgrade")) "升级修复模块" else "申请一次性升级修复") }
            Box {
                OutlinedButton(enabled = !busy && older.isNotEmpty(), onClick = { versionMenu = true }) { Text(if (selectedVersion.isEmpty()) "选择旧程序版本" else selectedVersion) }
                DropdownMenu(expanded = versionMenu, onDismissRequest = { versionMenu = false }) {
                    older.forEach { version -> DropdownMenuItem(text = { Text(version) }, onClick = { selectedVersion = version; versionMenu = false }) }
                }
            }
            if (older.isEmpty()) Text("无可用历史版本，无法回滚。")
            Button(enabled = !busy && selectedVersion in older && (authorized("rollback") || reason.isNotBlank()), onClick = { operate("rollback", JSONObject().put("target_version", selectedVersion)) }) { Text(if (authorized("rollback")) "回滚旧程序" else "申请一次性回滚") }
            module.optJSONObject("abort")?.let { receipt -> OutlinedButton(onClick = { copy(receipt) }) { Text("复制取消迁移凭据") } }
        } else if (module.getString("lifecycle_state") == "SEALED") {
            if (module.optString("direction") == "outbound") {
                OutlinedButton(enabled = !busy, onClick = { export.launch("self-migration.ails") }) { Text("保存迁移包") }
                module.optJSONObject("release")?.let { receipt -> OutlinedButton(onClick = { copy(receipt) }) { Text("复制运行权交接凭据") } }
                if (!module.optBoolean("transfer_committed")) {
                    OutlinedTextField(value = evidence, onValueChange = { evidence = it }, label = { Text("目标设备接收凭据") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && evidence.isNotBlank(), onClick = { runCatching { JSONObject(evidence) }.onSuccess { operate("migrate", JSONObject().put("phase", "commit").put("receipt", it)) }.onFailure { message = "凭据格式无效" } }) { Text("完成运行权交接") }
                    OutlinedButton(enabled = !busy, onClick = { operate("migrate", JSONObject().put("phase", "cancel")) }) { Text("取消未交接的迁移并恢复") }
                }
            } else if (module.optString("direction") == "inbound") {
                module.optJSONObject("prepared_receipt")?.let { receipt -> OutlinedButton(onClick = { copy(receipt) }) { Text("复制接收凭据") } }
                OutlinedTextField(value = evidence, onValueChange = { evidence = it }, label = { Text("源端交接或取消凭据") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && evidence.isNotBlank(), onClick = { runCatching { JSONObject(evidence) }.onSuccess { operate("migrate", JSONObject().put("phase", "activate").put("release", it)) }.onFailure { message = "凭据格式无效" } }) { Text("验证交接并激活") }
                OutlinedButton(enabled = !busy && evidence.isNotBlank(), onClick = { runCatching { JSONObject(evidence) }.onSuccess { operate("migrate", JSONObject().put("phase", "discard").put("abort", it)) }.onFailure { message = "凭据格式无效" } }) { Text("源端取消后释放迁入槽位") }
            }
        } else Text("当前状态不允许版本切换，请保留数据并检查恢复记录。")
        TextButton(enabled = !busy, onClick = onReload) { Text("重新读取模块界面") }
    }
}
