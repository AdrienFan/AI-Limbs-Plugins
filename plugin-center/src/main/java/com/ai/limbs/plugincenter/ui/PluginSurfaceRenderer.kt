package com.ai.limbs.plugincenter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.assistance.operit.plugins.system.SystemPluginUiRendererV2
import com.ai.assistance.operit.plugins.system.SystemPluginUiSurfaceV2
import com.ai.limbs.plugin.runtime.ChildExtensionLifecycle
import com.ai.limbs.plugin.runtime.ChildUiContributionSnapshot
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
private const val UI_SCHEMA_ID = "ai_limbs.plugin_center.ui.v1"
private const val UI_SCHEMA_VERSION = 1
private val childListExpandRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)

/**
 * Plugin Center-owned renderer for every ordinary .ailp screen.
 *
 * Stable Kernel passes an opaque document here and stops. Component names, composition rules,
 * Android pickers and provider-backed interaction all live in this class/module, so new complex
 * controls can ship as a Plugin Center update instead of an AI Limbs base release.
 */
class PluginCenterPluginUiRenderer(
    private val host: SystemPluginHostV2
) : SystemPluginUiRendererV2 {
    private val components = PluginUiComponentRegistry(host)

    @Composable
    override fun Render(surface: SystemPluginUiSurfaceV2) {
        if (surface.schemaId != UI_SCHEMA_ID) {
            UnsupportedDocument("不支持的 UI Schema：${surface.schemaId}")
            return
        }
        val document = remember(surface.documentJson) {
            runCatching { JSONObject(surface.documentJson) }.getOrNull()
        }
        if (document == null) {
            UnsupportedDocument("插件 UI 文档不是有效 JSON")
            return
        }
        val schemaVersion = document.optInt("schema", UI_SCHEMA_VERSION)
        if (schemaVersion != UI_SCHEMA_VERSION) {
            UnsupportedDocument("不支持的 UI Schema 版本：$schemaVersion")
            return
        }
        val blocks = remember(surface.documentJson) {
            document.optJSONArray("blocks").toObjectList()
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    surface.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                surface.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(blocks) { block -> components.Render(surface, block) }
        }
    }
}

/**
 * Component registry owned entirely by Plugin Center.
 *
 * Ordinary plugins are consumers only: their screen documents may reference component ids that this
 * registry already defines, but they cannot register, override, or remove component semantics. This
 * registry is intentionally private and is never published through system.plugin_center.* services.
 * Add future component ids here (or from another internal Plugin Center module); never add their
 * semantic renderer to Stable Kernel. The Host only knows the opaque screen envelope.
 */
private class PluginUiComponentRegistry(private val host: SystemPluginHostV2) {
    private val renderers = linkedMapOf<String, @Composable (SystemPluginUiSurfaceV2, JSONObject) -> Unit>(
        "text" to { _, block -> TextBlock(block) },
        "capability_button" to { surface, block -> CapabilityButtonBlock(host, surface, block) },
        "child_extension_installer" to { surface, block -> ChildExtensionInstallerBlock(host, surface, block) },
        "child_extension_selector" to { surface, block -> ChildExtensionSelectorBlock(host, surface, block) },
        "child_extension_list" to { surface, block -> ChildExtensionListBlock(host, surface, block) },
        "dynamic_panel" to { surface, block -> DynamicPanelBlock(host, surface, block) },
        "component_slot" to { surface, block -> ComponentSlotBlock(surface, block) }
    )

    @Composable
    fun Render(surface: SystemPluginUiSurfaceV2, block: JSONObject) {
        val type = block.optString("type").trim().lowercase()
        val renderer = renderers[type]
        if (renderer == null) {
            Text("未知 Plugin Center UI 组件：${type.ifBlank { "<empty>" }}")
            return
        }
        renderer(surface, block)
    }

    /**
     * Non-destructive instance customization wrapper.
     *
     * The wrapped [component] remains a normal Plugin Center component definition. Parent content in
     * slots is rendered only for this instance, while child content is accepted only from extension
     * points explicitly named by the parent in child_slots. Nothing is written back to the registry.
     */
    @Composable
    private fun ComponentSlotBlock(surface: SystemPluginUiSurfaceV2, block: JSONObject) {
        val componentId = block.requiredText("id").lowercase()
        val component = block.optJSONObject("component")
            ?: error("component_slot 缺少 component")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RenderSlot(surface, block, componentId, "before")
            Render(surface, component)
            RenderSlot(surface, block, componentId, "after")
        }
    }

    @Composable
    private fun RenderSlot(
        surface: SystemPluginUiSurfaceV2,
        wrapper: JSONObject,
        componentId: String,
        slotId: String
    ) {
        // Parent instance content is ordinary Plugin Center schema and therefore may use any component
        // already registered here. It still cannot register a new component type.
        wrapper.optJSONObject("slots")?.optJSONArray(slotId).toObjectList().forEach { local ->
            Render(surface, local)
        }

        val policy = wrapper.optJSONObject("child_slots")?.optJSONObject(slotId) ?: return
        val allowedPoints = policy.optJSONArray("points").toStringList().map { it.lowercase() }.toSet()
        if (allowedPoints.isEmpty()) return
        val hub = observedProvider(host, InProcessSystemIds.EXTENSION_HUB_PROVIDER)?.payload as? ExtensionHubService
            ?: return
        val allContributions by hub.uiContributions().collectAsState()
        val screenId = surface.screenId.trim().lowercase()
        allContributions
            .filter { contribution ->
                contribution.target.parentPluginId == surface.ownerPluginId &&
                    contribution.target.point.lowercase() in allowedPoints &&
                    contribution.screenId == screenId &&
                    contribution.componentId == componentId &&
                    contribution.slotId == slotId
            }
            .forEach { contribution -> ChildContributionBlock(surface, contribution) }
    }

    @Composable
    private fun ChildContributionBlock(
        surface: SystemPluginUiSurfaceV2,
        contribution: ChildUiContributionSnapshot
    ) {
        val documentJson by contribution.provider.documentJson.collectAsState()
        val document = remember(documentJson) { documentJson.toJsonObjectOrNull() } ?: return
        if (document.optInt("schema", UI_SCHEMA_VERSION) != UI_SCHEMA_VERSION) {
            Text("子插件 UI Contribution schema 不受支持", style = MaterialTheme.typography.bodySmall)
            return
        }
        document.optJSONArray("blocks").toObjectList().forEach { block ->
            RenderChildBlock(surface, contribution, block)
        }
    }

    @Composable
    private fun RenderChildBlock(
        surface: SystemPluginUiSurfaceV2,
        contribution: ChildUiContributionSnapshot,
        block: JSONObject
    ) {
        // Child overlays use an explicit safe subset. In particular capability_button is forbidden:
        // child UI must never borrow the parent screen's Host-attested capability identity.
        when (val type = block.optString("type").trim().lowercase()) {
            "text" -> TextBlock(block)
            "event_button" -> ChildEventButtonBlock(surface, contribution, block)
            else -> Text("子插件 UI Contribution 不允许组件：${type.ifBlank { "<empty>" }}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UnsupportedDocument(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message)
    }
}

@Composable
private fun TextBlock(block: JSONObject) {
    Text(block.optString("text"))
}
@Composable
private fun CapabilityButtonBlock(
    host: SystemPluginHostV2,
    surface: SystemPluginUiSurfaceV2,
    block: JSONObject
) {
    val scope = rememberCoroutineScope()
    var feedback by remember(surface.screenId, block.toString()) { mutableStateOf<String?>(null) }
    val label = block.requiredText("label")
    val capabilityId = block.requiredText("capability_id")
    val parameters = block.optJSONObject("parameters") ?: JSONObject()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = {
            scope.launch {
                runCatching {
                    // The Host-bound action object already carries the trusted screen owner.
                    // Plugin Center never supplies or chooses a plugin id for UI-triggered calls.
                    surface.actions.invokeCapability(
                        capabilityId,
                        JSONObject(parameters.toString())
                    )
                }.onSuccess { result ->
                    feedback = result.optString("content").ifBlank { result.toString() }
                }.onFailure { error ->
                    feedback = "操作失败：${error.message ?: "未知错误"}"
                }
            }
        }) { Text(label) }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable
private fun ChildEventButtonBlock(
    surface: SystemPluginUiSurfaceV2,
    contribution: ChildUiContributionSnapshot,
    block: JSONObject
) {
    val scope = rememberCoroutineScope()
    val eventId = block.requiredText("event_id")
    val label = block.requiredText("label")
    val payload = block.optJSONObject("payload") ?: JSONObject()
    var busy by remember(surface.screenId, contribution.extensionId, contribution.contributionId, eventId) {
        mutableStateOf(false)
    }
    var feedback by remember(surface.screenId, contribution.extensionId, contribution.contributionId, eventId) {
        mutableStateOf<String?>(null)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            enabled = !busy && block.optBoolean("enabled", true),
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        // Dispatches to the contribution owner, never through surface.actions. This keeps
                        // child UI interaction under child identity instead of borrowing parent authority.
                        val raw = contribution.provider.perform(eventId, JSONObject(payload.toString()).toString())
                        val result = raw.toJsonObjectOrNull()
                        feedback = result?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: raw.takeIf { it.isNotBlank() }
                    } catch (error: Throwable) {
                        feedback = "操作失败：${error.message ?: "未知错误"}"
                    } finally {
                        busy = false
                    }
                }
            }
        ) { Text(if (busy) "处理中…" else label) }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun observedProvider(
    host: SystemPluginHostV2,
    providerId: String
): com.ai.assistance.operit.plugins.system.SystemPluginProviderBindingV2? {
    val initial = remember(providerId) { host.providers.resolve(providerId) }
    val binding by host.providers.observe(providerId).collectAsState(initial = initial)
    return binding
}

private fun childListKey(ownerPluginId: String, point: String): String = "$ownerPluginId|$point"

@Composable
private fun ChildExtensionInstallerBlock(
    host: SystemPluginHostV2,
    surface: SystemPluginUiSurfaceV2,
    block: JSONObject
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val point = block.requiredText("point")
    val label = block.requiredText("label")
    val hub = observedProvider(host, InProcessSystemIds.EXTENSION_HUB_PROVIDER)?.payload as? ExtensionHubService
    var feedback by remember(surface.ownerPluginId, point) { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && hub != null) {
            scope.launch {
                val temporary = File(context.cacheDir, "extension-import-${UUID.randomUUID()}.ailx")
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "无法读取选择的子插件" }
                            temporary.outputStream().use(input::copyTo)
                        }
                    }
                    val snapshot = withContext(Dispatchers.IO) {
                        hub.install(temporary, surface.ownerPluginId, point)
                    }
                    feedback = "已安装 ${snapshot.displayName} ${snapshot.version} · ${snapshot.lifecycle}"
                    childListExpandRequests.tryEmit(childListKey(surface.ownerPluginId, point))
                } catch (error: Throwable) {
                    feedback = "子插件安装失败：${error.message ?: "未知错误"}"
                } finally {
                    temporary.delete()
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(enabled = hub != null, onClick = {
            // Android ActivityResult ownership moved with the complex control into Plugin Center.
            // Stable Kernel is no longer aware that this component opens a document picker.
            launcher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }) { Text(label) }
        Text(
            if (hub == null) "Plugin Extension Hub 未启用" else "仅接受 AIL_EXTENSION_V1 / .ailx",
            style = MaterialTheme.typography.bodySmall
        )
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ChildExtensionSelectorBlock(
    host: SystemPluginHostV2,
    surface: SystemPluginUiSurfaceV2,
    block: JSONObject
) {
    val point = block.requiredText("point")
    val label = block.requiredText("label")
    val selectCapabilityId = block.requiredText("select_capability_id")
    val selectionProviderId = block.optString("selection_provider_id").trim().ifBlank { null }
    val hub = observedProvider(host, InProcessSystemIds.EXTENSION_HUB_PROVIDER)?.payload as? ExtensionHubService
    if (hub == null) {
        Text("Plugin Extension Hub 未启用")
        return
    }
    val snapshots by hub.snapshotsForPoint(point).collectAsState()
    val active = snapshots.filter {
        it.target.parentPluginId == surface.ownerPluginId && it.lifecycle == ChildExtensionLifecycle.ACTIVE
    }
    val selectionProvider = selectionProviderId?.let { providerId ->
        observedProvider(host, providerId)
            ?.takeIf { it.ownerPluginId == surface.ownerPluginId }
            ?.payload as? InProcessUiStateProvider
    }
    val selectedExtensionId = selectedIdFromState(selectionProvider)
    val selectedSnapshot = active.firstOrNull { it.extensionId == selectedExtensionId }
    val scope = rememberCoroutineScope()
    var expanded by remember(surface.ownerPluginId, point) { mutableStateOf(false) }
    var localSelectedName by remember(surface.ownerPluginId, point) { mutableStateOf<String?>(null) }
    var feedback by remember(surface.ownerPluginId, point) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Box {
            Button(enabled = active.isNotEmpty(), onClick = { expanded = true }) {
                Text(selectedSnapshot?.displayName ?: localSelectedName ?: active.firstOrNull()?.displayName ?: "暂无 Provider")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                active.forEach { snapshot ->
                    DropdownMenuItem(text = { Text(snapshot.displayName) }, onClick = {
                        expanded = false
                        scope.launch {
                            try {
                                val value = surface.actions.invokeCapability(
                                    selectCapabilityId,
                                    JSONObject().put("extension_id", snapshot.extensionId)
                                )
                                localSelectedName = snapshot.displayName
                                feedback = value.optString("content").ifBlank { value.toString() }
                            } catch (error: Throwable) {
                                feedback = "选择失败：${error.message ?: "未知错误"}"
                            }
                        }
                    })
                }
            }
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable
private fun ChildExtensionListBlock(
    host: SystemPluginHostV2,
    surface: SystemPluginUiSurfaceV2,
    block: JSONObject
) {
    val point = block.requiredText("point")
    val hub = observedProvider(host, InProcessSystemIds.EXTENSION_HUB_PROVIDER)?.payload as? ExtensionHubService
    if (hub == null) {
        Text("Plugin Extension Hub 未启用")
        return
    }
    val allSnapshots by hub.snapshotsForPoint(point).collectAsState()
    val allBackups by hub.backupSnapshots().collectAsState()
    val snapshots = remember(allSnapshots, surface.ownerPluginId, point) {
        allSnapshots.filter { it.target.parentPluginId == surface.ownerPluginId && it.target.point == point }
    }
    val backups = remember(allBackups, surface.ownerPluginId, point) {
        allBackups.filter { it.target.parentPluginId == surface.ownerPluginId && it.target.point == point }
    }
    val scope = rememberCoroutineScope()
    var feedback by remember(surface.ownerPluginId, point) { mutableStateOf<String?>(null) }
    var expanded by remember(surface.ownerPluginId, point) { mutableStateOf(false) }
    var query by remember(surface.ownerPluginId, point) { mutableStateOf("") }
    LaunchedEffect(surface.ownerPluginId, point) {
        childListExpandRequests.collect { requestKey ->
            if (requestKey == childListKey(surface.ownerPluginId, point)) {
                query = ""
                expanded = true
            }
        }
    }

    val normalizedQuery = query.trim()
    val visibleSnapshots = remember(snapshots, normalizedQuery) {
        if (normalizedQuery.isBlank()) snapshots else snapshots.filter { snapshot ->
            listOf(
                snapshot.displayName,
                snapshot.extensionId,
                snapshot.version,
                snapshot.description.orEmpty(),
                snapshot.lifecycle.name,
                snapshot.lastError.orEmpty()
            ).any { it.contains(normalizedQuery, ignoreCase = true) }
        }
    }
    val restorable = remember(backups, snapshots) {
        backups.filter { backup -> snapshots.none { it.extensionId == backup.extensionId } }
    }
    val visibleRestorable = remember(restorable, normalizedQuery) {
        if (normalizedQuery.isBlank()) restorable else restorable.filter { backup ->
            listOf(
                backup.displayName,
                backup.extensionId,
                backup.version,
                backup.description.orEmpty(),
                backup.installedVersion.orEmpty(),
                "backup"
            ).any { it.contains(normalizedQuery, ignoreCase = true) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { value ->
                query = value
                if (value.isNotBlank()) expanded = true
            },
            label = { Text("搜索子插件") },
            placeholder = { Text("名称、ID、版本或状态") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Card(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (expanded) "▼ 已安装子插件" else "▶ 已安装子插件",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(snapshots.size.toString(), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (expanded) {
            if (visibleSnapshots.isEmpty() && visibleRestorable.isEmpty()) {
                Text(
                    if (normalizedQuery.isBlank()) "尚未安装子插件" else "没有匹配的子插件",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            visibleSnapshots.forEach { snapshot ->
                val backup = backups.firstOrNull { it.extensionId == snapshot.extensionId }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${snapshot.displayName} · ${snapshot.version}", style = MaterialTheme.typography.titleSmall)
                        Text("${snapshot.extensionId} · ${snapshot.lifecycle}", style = MaterialTheme.typography.bodySmall)
                        Text("使用 ${snapshot.useCount} 次 · 备份：${backup?.version ?: "未备份"}", style = MaterialTheme.typography.bodySmall)
                        snapshot.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    runCatching { hub.setEnabled(snapshot.extensionId, !snapshot.enabled) }
                                        .onSuccess { feedback = "${it.displayName} → ${it.lifecycle}" }
                                        .onFailure { feedback = "操作失败：${it.message}" }
                                }
                            }) { Text(if (snapshot.enabled) "禁用" else "启用") }
                            Button(onClick = {
                                scope.launch {
                                    runCatching { hub.backup(snapshot.extensionId) }
                                        .onSuccess { feedback = "已备份 ${it.displayName} ${it.version}" }
                                        .onFailure { feedback = "备份失败：${it.message}" }
                                }
                            }) { Text("备份") }
                            Button(onClick = {
                                scope.launch {
                                    runCatching { hub.uninstall(snapshot.extensionId) }
                                        .onSuccess { feedback = if (it) "已卸载 ${snapshot.displayName}" else "子插件不存在" }
                                        .onFailure { feedback = "卸载失败：${it.message}" }
                                }
                            }) { Text("卸载") }
                        }
                    }
                }
            }
            if (visibleRestorable.isNotEmpty()) {
                Text("可恢复备份 · ${visibleRestorable.size}", style = MaterialTheme.typography.titleSmall)
            }
            visibleRestorable.forEach { backup ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${backup.displayName} · ${backup.version}", style = MaterialTheme.typography.titleSmall)
                        Text("${backup.extensionId} · backup", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    runCatching { hub.restoreBackup(backup.extensionId) }
                                        .onSuccess { feedback = "已恢复 ${it.displayName} ${it.version}" }
                                        .onFailure { feedback = "恢复失败：${it.message}" }
                                }
                            }) { Text("恢复") }
                            Button(onClick = {
                                scope.launch {
                                    runCatching { hub.deleteBackup(backup.extensionId) }
                                        .onSuccess { feedback = if (it) "已删除备份" else "备份不存在" }
                                        .onFailure { feedback = "删除备份失败：${it.message}" }
                                }
                            }) { Text("删除备份") }
                        }
                    }
                }
            }
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable
private fun DynamicPanelBlock(
    host: SystemPluginHostV2,
    surface: SystemPluginUiSurfaceV2,
    block: JSONObject
) {
    val providerId = block.requiredText("provider_id")
    val binding = observedProvider(host, providerId)
    val provider = binding
        ?.takeIf { it.ownerPluginId == surface.ownerPluginId }
        ?.payload as? InProcessUiStateProvider
    if (provider == null) {
        Text("当前 Provider 控制面板不可用", style = MaterialTheme.typography.bodySmall)
        return
    }

    val stateJson by provider.stateJson.collectAsState()
    val current = remember(stateJson) { stateJson.toJsonObjectOrNull() }
    if (current == null) {
        Text("尚未选择可用的 Provider", style = MaterialTheme.typography.bodySmall)
        return
    }

    // Everything below is Plugin Center schema, not Host ABI. New field/action kinds are added here
    // without changing InProcessUiStateProvider or rebuilding Stable Kernel.
    val fields = remember(stateJson) { current.optJSONArray("fields").toObjectList() }
    val actions = remember(stateJson) { current.optJSONArray("actions").toObjectList() }
    val scope = rememberCoroutineScope()
    val values = remember(surface.ownerPluginId, providerId) { mutableStateMapOf<String, String>() }
    val initialValues = remember(surface.ownerPluginId, providerId) { mutableStateMapOf<String, String>() }
    var feedback by remember(surface.ownerPluginId, providerId) { mutableStateOf<String?>(null) }
    var busyAction by remember(surface.ownerPluginId, providerId) { mutableStateOf<String?>(null) }

    LaunchedEffect(stateJson) {
        val activeIds = fields.mapNotNull { it.optString("id").takeIf(String::isNotBlank) }.toSet()
        values.keys.filter { it !in activeIds }.toList().forEach(values::remove)
        initialValues.keys.filter { it !in activeIds }.toList().forEach(initialValues::remove)
        fields.forEach { field ->
            val id = field.optString("id").trim()
            if (id.isBlank()) return@forEach
            val nextInitial = field.optString("value")
            val previousInitial = initialValues[id]
            if (id !in values || values[id] == previousInitial) values[id] = nextInitial
            initialValues[id] = nextInitial
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(current.optString("title"), style = MaterialTheme.typography.titleMedium)
            current.optString("description").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            current.optJSONArray("status_lines").toStringList().forEach {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            fields.forEach { field ->
                val id = field.requiredText("id")
                val kind = field.optString("kind", "text").trim().lowercase()
                OutlinedTextField(
                    value = values[id] ?: field.optString("value"),
                    onValueChange = { values[id] = it },
                    label = { Text(field.requiredText("label")) },
                    placeholder = { Text(field.optString("placeholder")) },
                    enabled = field.optBoolean("enabled", true),
                    visualTransformation = if (kind == "secret") {
                        PasswordVisualTransformation()
                    } else {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            actions.forEach { action ->
                val actionId = action.requiredText("id")
                val required = action.optJSONArray("required_field_ids").toStringList()
                val requiredReady = required.all { values[it].orEmpty().isNotBlank() }
                Button(
                    enabled = action.optBoolean("enabled", true) && requiredReady && busyAction == null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            busyAction = actionId
                            try {
                                val fieldValues = JSONObject().apply {
                                    values.forEach { (key, value) -> put(key, value) }
                                }
                                val rawResult = provider.perform(
                                    actionId,
                                    JSONObject().put("field_values", fieldValues).toString()
                                )
                                val result = rawResult.toJsonObjectOrNull() ?: JSONObject()
                                result.optJSONObject("field_values")?.let { returned ->
                                    returned.keys().forEach { key -> values[key] = returned.optString(key) }
                                }
                                feedback = result.optString("message").takeIf { it.isNotBlank() }
                            } catch (error: Throwable) {
                                feedback = "操作失败：${error.message ?: "未知错误"}"
                            } finally {
                                busyAction = null
                            }
                        }
                    }
                ) { Text(if (busyAction == actionId) "处理中…" else action.requiredText("label")) }
            }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun selectedIdFromState(provider: InProcessUiStateProvider?): String? {
    if (provider == null) return null
    val stateJson by provider.stateJson.collectAsState()
    return remember(stateJson) {
        stateJson.toJsonObjectOrNull()?.optString("selected_id")?.trim()?.takeIf { it.isNotBlank() }
    }
}

private fun JSONObject.requiredText(name: String): String =
    optString(name).trim().takeIf { it.isNotBlank() }
        ?: error("Plugin Center UI 组件缺少字段：$name")

private fun String?.toJsonObjectOrNull(): JSONObject? =
    this?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun JSONArray?.toObjectList(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }
}
