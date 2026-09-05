package com.ai.limbs.plugincenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.assistance.operit.plugins.system.SystemPluginUiSurfaceV2
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val TerminalBlack = Color(0xFF050505)
private val TerminalChrome = Color(0xFF1D201D)
private val TerminalTab = Color(0xFF30322F)
private val TerminalAccent = Color(0xFF00C853)
private val TerminalDanger = Color(0xFF9E4545)

@Composable
internal fun TerminalWorkbenchBlock(
    host: SystemPluginHostV2,
    surface: SystemPluginUiSurfaceV2,
    block: JSONObject
) {
    val providerId = block.stringRequired("provider_id")
    val initial = remember(providerId) { host.providers.resolve(providerId) }
    val binding by host.providers.observe(providerId).collectAsState(initial = initial)
    val provider = binding
        ?.takeIf { it.ownerPluginId == surface.ownerPluginId }
        ?.payload as? InProcessUiStateProvider
    if (provider == null) {
        TerminalMessage("终端工作台 Provider 不可用")
        return
    }

    val stateJson by provider.stateJson.collectAsState()
    val state = remember(stateJson) {
        stateJson?.let { runCatching { JSONObject(it) }.getOrNull() }
    }
    if (state == null) {
        TerminalMessage("终端工作台状态不可用")
        return
    }

    val events = state.optJSONObject("events")
    if (events == null) {
        TerminalMessage("终端工作台缺少事件契约")
        return
    }
    val tabs = remember(stateJson) { state.optJSONArray("tabs").objects() }
    val activeTabId = state.optString("active_tab_id")
    val consoleContent = state.optString("console_content")
    val consoleEmptyText = state.optString("console_empty_text")
    val ubuntuRunning = state.optBoolean("ubuntu_running")
    val sessionActive = state.optBoolean("session_active")
    val inputEnabled = state.optBoolean("input_enabled")
    val shareOnline = state.optBoolean("share_online")
    val localControlsEnabled = state.optBoolean("local_controls_enabled")
    val scope = rememberCoroutineScope()
    var command by remember(providerId, activeTabId) { mutableStateOf("") }
    var feedback by remember(providerId) { mutableStateOf<String?>(null) }
    var busyEvent by remember(providerId) { mutableStateOf<String?>(null) }
    var configExpanded by remember(providerId) { mutableStateOf(false) }

    fun invoke(eventKey: String, payload: JSONObject = JSONObject()) {
        val eventId = events.optString(eventKey).trim()
        if (eventId.isBlank() || busyEvent != null) return
        scope.launch {
            busyEvent = eventId
            try {
                val raw = provider.perform(eventId, payload.toString())
                val result = runCatching { JSONObject(raw) }.getOrNull()
                feedback = result?.optString("message")?.takeIf { it.isNotBlank() }
                if (result?.optBoolean("clear_input") == true) command = ""
            } catch (error: Throwable) {
                feedback = "操作失败：${error.message ?: "未知错误"}"
            } finally {
                busyEvent = null
            }
        }
    }

    val consoleScroll = rememberScrollState()
    LaunchedEffect(consoleContent) {
        consoleScroll.scrollTo(consoleScroll.maxValue)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TerminalBlack)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalChrome)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEach { tab ->
                    val tabId = tab.stringRequired("id")
                    TerminalTabChip(
                        title = tab.stringRequired("title"),
                        selected = tabId == activeTabId,
                        closable = tab.optBoolean("closable"),
                        shared = tab.optBoolean("shared"),
                        online = tab.optBoolean("online"),
                        onSelect = { invoke("select_tab", JSONObject().put("tab_id", tabId)) },
                        onClose = { invoke("close_tab", JSONObject().put("tab_id", tabId)) }
                    )
                }
            }
            TextButton(
                enabled = busyEvent == null,
                onClick = { invoke("show_shared") }
            ) {
                Text(
                    text = if (shareOnline) "◉" else "◎",
                    color = if (shareOnline) TerminalAccent else Color.LightGray,
                    fontSize = 23.sp
                )
            }
            TextButton(
                enabled = busyEvent == null,
                onClick = { invoke("add_tab") }
            ) {
                Text("+", color = Color.White, fontSize = 28.sp)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            SelectionContainer {
                Text(
                    text = consoleContent.ifBlank { consoleEmptyText },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(consoleScroll)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                )
            }
        }

        (feedback ?: state.optString("status_message")).takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalChrome)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalChrome)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                enabled = localControlsEnabled && sessionActive && busyEvent == null,
                onClick = { invoke("ctrl_c") }
            ) {
                Text("Ctrl+C")
            }
            Button(
                enabled = localControlsEnabled && busyEvent == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (ubuntuRunning) TerminalDanger else Color(0xFF3567B7)
                ),
                onClick = { invoke(if (ubuntuRunning) "stop" else "start") }
            ) {
                Text(if (ubuntuRunning) "停止 Ubuntu" else "启动 Ubuntu")
            }
            Box {
                OutlinedButton(
                    enabled = localControlsEnabled && busyEvent == null,
                    onClick = { configExpanded = true }
                ) {
                    Text("环境配置")
                }
                DropdownMenu(
                    expanded = configExpanded,
                    onDismissRequest = { configExpanded = false }
                ) {
                    listOf(
                        "KEEP_RUNNING" to "保持运行",
                        "MINUTES_10" to "10 分钟",
                        "MINUTES_15" to "15 分钟",
                        "MINUTES_30" to "30 分钟",
                        "MINUTES_60" to "60 分钟"
                    ).forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text("空闲策略：$label") },
                            onClick = {
                                configExpanded = false
                                invoke("set_idle", JSONObject().put("mode", mode))
                            }
                        )
                    }
                }
            }
            Text(
                text = state.optString("idle_label"),
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalBlack)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = Color(0xFF006D16), shape = MaterialTheme.shapes.small) {
                Text(
                    text = state.optString("prompt", "~ $"),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                enabled = inputEnabled && busyEvent == null,
                placeholder = {
                    Text(if (inputEnabled) "输入命令" else "共享标签页只读")
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (command.isNotBlank()) {
                            invoke("execute", JSONObject().put("command", command))
                        }
                    }
                )
            )
            Button(
                enabled = inputEnabled && command.isNotBlank() && busyEvent == null,
                onClick = { invoke("execute", JSONObject().put("command", command)) }
            ) {
                Text("↵")
            }
        }
    }
}

@Composable
private fun TerminalTabChip(
    title: String,
    selected: Boolean,
    closable: Boolean,
    shared: Boolean,
    online: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = if (selected) Color(0xFF535550) else TerminalTab,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = if (closable) 4.dp else 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (shared) {
                Text("●", color = if (online) TerminalAccent else Color.Gray, fontSize = 10.sp)
            }
            Text(title, color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            if (closable) {
                Text(
                    "×",
                    color = Color.LightGray,
                    fontSize = 20.sp,
                    modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun TerminalMessage(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(TerminalBlack),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = Color.White)
    }
}

private fun JSONObject.stringRequired(key: String): String =
    optString(key).trim().ifBlank { error("terminal_workbench 缺少 $key") }

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }
}
