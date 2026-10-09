package com.ai.limbs.plugincenter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.ai.limbs.plugincenter.runtime.PluginControlPlaneFacade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private fun selfActionName(operation: String) = when (operation) {
    "upgrade" -> "升级"; "rollback" -> "回滚"; "migrate" -> "迁移"; else -> operation
}
private fun selfModeName(mode: String) = when (mode) {
    "ONE_TIME" -> "一次性"; "TIMED" -> "限时"; "LONG" -> "长期"; else -> mode
}
private fun selfTime(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(ms))

/** The plugin renders requests/results. Only the host-attested AI review route can grant authority. */
@Composable
internal fun SelfModuleScreen(controlPlane: PluginControlPlaneFacade, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(JSONObject()) }
    var message by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var evidence by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var mode by remember { mutableStateOf("ONE_TIME") }
    var hours by remember { mutableStateOf("2") }
    var reason by remember { mutableStateOf("") }
    var permissionMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var formExpanded by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }
    var packageUri by remember { mutableStateOf<Uri?>(null) }
    var pickerAction by remember { mutableStateOf("select") }
    var busy by remember { mutableStateOf(false) }
    suspend fun refresh() { status = withContext(Dispatchers.IO) { controlPlane.selfCall("self_status") } }
    fun perform(operation: String, args: JSONObject = JSONObject()) {
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { controlPlane.selfCall(operation, args) }
                message = if (operation == "self_submit" || (operation == "self_request_uri" && args.optString("command") == "submit") || operation == "self_request")
                    "申请已提交，各项等待 AI 独立决定。" else "操作已完成。"
                result.optJSONObject("request")?.let { message += "\n${it.optString("status")}" }
                refresh()
            } catch (error: Exception) { message = error.message ?: error.javaClass.simpleName }
            finally { busy = false }
        }
    }
    val module = status.optJSONObject("module")
    val grants = status.optJSONObject("authorizations")?.optJSONArray("effective_grants") ?: JSONArray()
    fun authorized(operation: String) = (0 until grants.length()).any { grants.getJSONObject(it).getString("operation") == operation }
    fun operate(operation: String, args: JSONObject) {
        val continuation = operation == "migrate" && module?.optBoolean("human_migration_authorized") == true &&
            args.optString("phase") in setOf("commit", "activate", "cancel", "discard")
        perform(if (authorized(operation) || continuation) "self_execute" else "self_request",
            JSONObject().put("operation", operation).put("parameters", args))
    }
    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) when (pickerAction) {
            "install" -> perform("self_install_uri", JSONObject().put("uri", uri.toString()))
            "execute_upgrade" -> perform("self_request_uri", JSONObject().put("command", "execute").put("uri", uri.toString())
                .put("operation", "upgrade").put("parameters", JSONObject()))
            else -> packageUri = uri
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) perform("self_export_uri", JSONObject().put("uri", uri.toString()))
    }
    fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("自我模块交接凭据", text))
        message = "已复制交接凭据。"
    }
    fun submit() {
        try {
            val items = JSONArray()
            for (operation in listOf("upgrade", "migrate", "rollback").filter { it in selected }) {
                val parameters = JSONObject()
                if (mode == "ONE_TIME") when (operation) {
                    "upgrade" -> check(packageUri != null) { "请选择升级 .ails 包" }
                    "rollback" -> { check(version.isNotBlank()) { "请填写回滚版本" }; parameters.put("target_version", version.trim()) }
                    "migrate" -> if (module == null) {
                        check(packageUri != null) { "请选择迁移 .ails 包" }; parameters.put("phase", "prepare")
                    } else { check(target.isNotBlank()) { "请填写目标设备交接标识" }; parameters.put("phase", "export").put("target_device_id", target.trim()) }
                }
                items.put(JSONObject().put("operation", operation).put("parameters", parameters))
            }
            val args = JSONObject().put("items", items).put("mode", mode).put("reason", reason.trim())
            if (mode == "TIMED") {
                val count = hours.toLongOrNull()
                check(count != null && count in 1..8760) { "申请时长请输入 1～8760 小时；从批准时开始计时" }
                args.put("duration_seconds", count * 3600L)
            }
            val needsFile = mode == "ONE_TIME" && ("upgrade" in selected || ("migrate" in selected && module == null))
            if (needsFile) perform("self_request_uri", args.put("command", "submit").put("uri", requireNotNull(packageUri).toString()))
            else perform("self_submit", args)
        } catch (error: Exception) { message = error.message ?: "申请内容无效" }
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
        SelectionContainer {
            Text("本机交接标识：${status.optString("device_id")}\n" + if (module == null) "尚未安装自我模块" else
                "身份：${module.optString("identity_id")}\n版本：${module.optString("module_version")}\n状态：${module.optString("lifecycle_state")}")
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前批准权限", style = MaterialTheme.typography.titleMedium)
                for (operation in listOf("upgrade", "migrate", "rollback")) {
                    val grant = (0 until grants.length()).map { grants.getJSONObject(it) }.firstOrNull { it.getString("operation") == operation }
                    Text("${selfActionName(operation)}：" + if (grant == null) "无持续授权" else
                        if (grant.getString("mode") == "LONG") "长期有效，至 AI 撤销" else "限时有效至 ${selfTime(grant.getLong("expires_at_ms"))}")
                    if (grant != null && grant.optString("reason").isNotBlank()) Text("批准理由：${grant.getString("reason")}")
                }
                Text("一次性批准随具体操作消费；持续授权不跳过身份、状态兼容和迁移交接检查。")
                if (module?.optBoolean("human_migration_authorized") == true) Text("当前迁移已获授权，可继续交接或安全取消。新设备不继承持续授权。")
            }
        }
        if (module == null) Button(enabled = !busy, onClick = { pickerAction = "install"; packagePicker.launch(arrayOf("*/*")) }) { Text("选择 .ails 并首次安装") }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { formExpanded = !formExpanded }) { Text(if (formExpanded) "收起权限申请" else "申请管理权限") }
                if (formExpanded) {
                    Box {
                        OutlinedButton(onClick = { permissionMenu = true }, enabled = !busy) { Text(if (selected.isEmpty()) "选择权限，可多选" else selected.joinToString("、", transform = ::selfActionName)) }
                        DropdownMenu(expanded = permissionMenu, onDismissRequest = { permissionMenu = false }) {
                            for (operation in listOf("upgrade", "migrate", "rollback")) DropdownMenuItem(
                                text = { Text(selfActionName(operation)) },
                                enabled = module != null || operation == "migrate",
                                leadingIcon = { Checkbox(checked = operation in selected, onCheckedChange = null) },
                                onClick = { selected = if (operation in selected) selected - operation else selected + operation })
                            DropdownMenuItem(text = { Text("完成选择") }, onClick = { permissionMenu = false })
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { modeMenu = true }, enabled = !busy) { Text("申请性质：${selfModeName(mode)}") }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            for (value in listOf("ONE_TIME", "TIMED", "LONG")) DropdownMenuItem(text = { Text(selfModeName(value)) },
                                enabled = value == "ONE_TIME" || module?.optString("lifecycle_state") == "ACTIVE",
                                onClick = { mode = value; modeMenu = false })
                        }
                    }
                    if (mode == "TIMED") OutlinedTextField(value = hours, onValueChange = { hours = it }, label = { Text("申请时长，小时") }, supportingText = { Text("从 AI 实际批准时开始计时，AI 可以缩短时限") }, modifier = Modifier.fillMaxWidth())
                    if (mode == "ONE_TIME") {
                        if ("upgrade" in selected || ("migrate" in selected && module == null)) {
                            OutlinedButton(enabled = !busy, onClick = { pickerAction = "select"; packagePicker.launch(arrayOf("*/*")) }) { Text("选择本次操作的 .ails 包") }
                            packageUri?.let { Text("已选择：${it.lastPathSegment}") }
                        }
                        if ("rollback" in selected) OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("本次回滚目标版本") }, supportingText = { Text("历史版本：${status.optJSONArray("versions") ?: "[]"}") }, modifier = Modifier.fillMaxWidth())
                        if ("migrate" in selected && module != null) OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("本次迁移目标设备标识") }, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(value = reason, onValueChange = { reason = it.take(2000) }, label = { Text("申请理由") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Text("每个权限独立审批；可部分批准、缩短时限或拒绝。一次性批准执行绑定操作；限时和长期批准只开放后续操作。")
                    Button(enabled = !busy && selected.isNotEmpty() && reason.isNotBlank(), onClick = ::submit) { Text("提交申请") }
                }
            }
        }
        if (module?.optString("lifecycle_state") == "ACTIVE") {
            if (authorized("upgrade")) Button(enabled = !busy, onClick = { pickerAction = "execute_upgrade"; packagePicker.launch(arrayOf("*/*")) }) { Text("使用已批准权限升级") }
            if (authorized("rollback")) {
                OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("回滚目标版本") }, supportingText = { Text("历史版本：${status.optJSONArray("versions") ?: "[]"}") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && version.isNotBlank(), onClick = { operate("rollback", JSONObject().put("target_version", version.trim())) }) { Text("使用已批准权限回滚") }
            }
            if (authorized("migrate")) {
                OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("目标设备的交接标识") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && target.isNotBlank(), onClick = { operate("migrate", JSONObject().put("phase", "export").put("target_device_id", target.trim())) }) { Text("使用已批准权限封存并迁出") }
            }
        } else if (module?.optString("direction") == "outbound") {
            Text("源模块已封存，数据保留。目标验证后，用其接收凭据交出运行权。")
            OutlinedButton(enabled = !busy, onClick = { exportPicker.launch("self-migration-${module.optString("migration_id")}.ails") }) { Text("保存迁移包") }
            if (!module.optBoolean("transfer_committed")) {
                OutlinedTextField(value = evidence, onValueChange = { evidence = it }, label = { Text("目标设备接收凭据") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && evidence.isNotBlank(), onClick = {
                    try { operate("migrate", JSONObject().put("phase", "commit").put("receipt", JSONObject(evidence))) }
                    catch (error: Exception) { message = "凭据格式无效：${error.message}" }
                }) { Text("交出运行权") }
                OutlinedButton(enabled = !busy, onClick = { operate("migrate", JSONObject().put("phase", "cancel")) }) { Text("取消迁出并恢复") }
            }
            module.optJSONObject("release")?.let { release -> Button(onClick = { copy(release.toString()) }) { Text("复制运行权交接凭据") } }
        } else if (module?.optString("direction") == "inbound") {
            Text("目标模块已验证，当前封存，等待源端交出运行权。")
            module.optJSONObject("prepared_receipt")?.let { receipt -> Button(onClick = { copy(receipt.toString()) }) { Text("复制接收凭据") } }
            OutlinedTextField(value = evidence, onValueChange = { evidence = it }, label = { Text("源设备的交接或取消凭据") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && evidence.isNotBlank(), onClick = {
                try { operate("migrate", JSONObject().put("phase", "activate").put("release", JSONObject(evidence))) }
                catch (error: Exception) { message = "凭据格式无效：${error.message}" }
            }) { Text("激活迁入模块") }
            OutlinedButton(enabled = !busy && evidence.isNotBlank(), onClick = {
                try { operate("migrate", JSONObject().put("phase", "discard").put("abort", JSONObject(evidence))) }
                catch (error: Exception) { message = "凭据格式无效：${error.message}" }
            }) { Text("源端取消后释放迁入槽位") }
        }
        module?.optJSONObject("abort")?.let { abort -> TextButton(onClick = { copy(abort.toString()) }) { Text("复制源端取消凭据") } }
        TextButton(onClick = { historyExpanded = !historyExpanded }) { Text(if (historyExpanded) "收起审批记录" else "查看申请与批准记录") }
        if (historyExpanded) {
            val requests = status.optJSONArray("requests") ?: JSONArray()
            val ordered = (0 until requests.length()).map { requests.getJSONObject(it) }.sortedByDescending { it.optLong("created_at_ms") }
            for (item in ordered) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${selfActionName(item.optString("operation"))} · ${item.optString("status")}")
                    Text("申请：${selfModeName(item.optString("requested_mode"))}" + if (item.has("requested_duration_seconds")) " ${item.getLong("requested_duration_seconds") / 3600} 小时" else "")
                    if (item.optString("reason").isNotEmpty()) Text("申请理由：${item.getString("reason")}")
                    item.optJSONObject("grant")?.let { grant -> Text("实际批准：${selfModeName(grant.getString("mode"))}" + if (grant.getString("mode") == "TIMED") "，至 ${selfTime(grant.getLong("expires_at_ms"))}" else "，至撤销") }
                    if (item.optString("review_reason").isNotEmpty()) Text("审批理由：${item.getString("review_reason")}")
                    if (item.optString("error").isNotEmpty()) Text("执行结果：${item.getString("error")}")
                    SelectionContainer { Text("编号：${item.optString("request_id")}") }
                    if (item.optString("status") == "PENDING") TextButton(enabled = !busy, onClick = { perform("self_cancel_request", JSONObject().put("request_id", item.getString("request_id"))) }) { Text("取消等待中的申请") }
                }
            }
        }
    }
}


@Composable
internal fun SelfModuleSummary(controlPlane: PluginControlPlaneFacade, enabled: Boolean, onOpen: () -> Unit) {
    var summary by remember { mutableStateOf("正在读取自我模块状态…") }
    LaunchedEffect(controlPlane) {
        while (true) {
            try {
                val value = withContext(Dispatchers.IO) { controlPlane.selfCall("self_status") }
                val module = value.optJSONObject("module")
                val grants = value.getJSONObject("authorizations").getJSONArray("effective_grants")
                summary = if (module == null) "尚未安装" else "${module.getString("module_version")} · ${module.getString("lifecycle_state")}" +
                    "\n当前授权：" + if (grants.length() == 0) "无持续授权" else (0 until grants.length()).joinToString("、") {
                        val grant = grants.getJSONObject(it)
                        selfActionName(grant.getString("operation")) + if (grant.getString("mode") == "LONG") "长期" else "至 ${selfTime(grant.getLong("expires_at_ms"))}"
                    }
            } catch (error: Exception) { summary = "读取状态失败：${error.message}" }
            delay(3000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("空白自我模块 · .ails", style = MaterialTheme.typography.titleMedium)
            Text(summary)
            OutlinedButton(onClick = onOpen, enabled = enabled) { Text("权限与申请") }
        }
    }
}
