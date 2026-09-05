package com.ai.limbs.plugincenter.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.assistance.operit.plugins.system.SystemPluginUiSurfaceV2
import com.ai.limbs.plugin.runtime.ChildExtensionSnapshot
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import com.ai.limbs.plugincenter.model.ChildExtensionSummary
import com.ai.limbs.plugincenter.model.PluginControlSnapshot
import com.ai.limbs.plugincenter.runtime.PluginCenterRuntime
import com.ai.limbs.plugincenter.runtime.PluginInstallOptions
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val DRAWER_REFRESH_MS = 350L

internal sealed interface PagePluginParticipant {
    val stableId: String
    val displayName: String
    val version: String
    val enabled: Boolean
    val typeLabel: String

    data class Plugin(val snapshot: PluginControlSnapshot) : PagePluginParticipant {
        override val stableId: String = snapshot.plugin.pluginId
        override val displayName: String = snapshot.plugin.activeManifest?.display?.name ?: stableId
        override val version: String = snapshot.plugin.persistentState?.activeVersion ?: "-"
        override val enabled: Boolean = snapshot.plugin.persistentState?.enabled == true
        override val typeLabel: String = "插件"
    }

    data class Child(val snapshot: ChildExtensionSnapshot) : PagePluginParticipant {
        override val stableId: String = snapshot.extensionId
        override val displayName: String = snapshot.displayName
        override val version: String = snapshot.version
        override val enabled: Boolean = snapshot.enabled
        override val typeLabel: String = "子插件"
    }
}

@Composable
internal fun PagePluginDrawer(
    host: SystemPluginHostV2,
    surface: SystemPluginUiSurfaceV2,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controlPlane = remember { PluginCenterRuntime.controlPlane }
    val adminSecurity = remember { PluginCenterRuntime.adminSecurity }
    val referencedProviderIds = remember(surface.documentJson) {
        referencedProviderIds(surface.documentJson)
    }
    val renderedChildScopes = remember(surface.ownerPluginId, surface.documentJson) {
        renderedChildScopes(surface.ownerPluginId, surface.documentJson)
    }

    var expanded by remember(surface.ownerPluginId, surface.screenId) { mutableStateOf(false) }
    var pluginSnapshots by remember(surface.ownerPluginId) { mutableStateOf<List<PluginControlSnapshot>>(emptyList()) }
    var childSnapshots by remember(surface.ownerPluginId) { mutableStateOf<List<ChildExtensionSnapshot>>(emptyList()) }
    var providerPluginIds by remember(surface.ownerPluginId, surface.screenId) { mutableStateOf<Set<String>>(emptySet()) }
    var feedback by remember(surface.ownerPluginId, surface.screenId) { mutableStateOf<String?>(null) }
    var busyIds by remember(surface.ownerPluginId, surface.screenId) { mutableStateOf<Set<String>>(emptySet()) }
    var updateTarget by remember(surface.ownerPluginId, surface.screenId) { mutableStateOf<PagePluginParticipant?>(null) }
    var pendingDisable by remember(surface.ownerPluginId, surface.screenId) { mutableStateOf<PagePluginParticipant?>(null) }
    var showAdminSetup by remember(surface.ownerPluginId, surface.screenId) { mutableStateOf(false) }
    var showAdminPassword by remember(surface.ownerPluginId, surface.screenId) { mutableStateOf(false) }
    var showAdminRecovery by remember(surface.ownerPluginId, surface.screenId) { mutableStateOf(false) }
    var recoveryKeyToShow by remember(surface.ownerPluginId, surface.screenId) { mutableStateOf<String?>(null) }

    val initialHubBinding = remember { host.providers.resolve(InProcessSystemIds.EXTENSION_HUB_PROVIDER) }
    val hubBinding by host.providers.observe(InProcessSystemIds.EXTENSION_HUB_PROVIDER)
        .collectAsState(initial = initialHubBinding)
    val hub = hubBinding
        ?.takeIf { it.ownerPluginId == "plugin.system.extension_hub" }
        ?.payload as? ExtensionHubService

    LaunchedEffect(hub, surface.ownerPluginId) {
        if (hub == null) {
            childSnapshots = emptyList()
            return@LaunchedEffect
        }
        hub.snapshots().collect { latest -> childSnapshots = latest }
    }

    LaunchedEffect(controlPlane, surface.ownerPluginId, referencedProviderIds) {
        while (isActive) {
            runCatching { withContext(Dispatchers.IO) { controlPlane.snapshots() } }
                .onSuccess { latest -> if (latest != pluginSnapshots) pluginSnapshots = latest }
            val latestProviderOwners = referencedProviderIds.mapNotNullTo(linkedSetOf()) { providerId ->
                host.providers.resolve(providerId)?.ownerPluginId
            } - surface.ownerPluginId
            if (latestProviderOwners != providerPluginIds) providerPluginIds = latestProviderOwners
            delay(DRAWER_REFRESH_MS)
        }
    }

    val participants = resolvePagePluginParticipants(
        pluginSnapshots = pluginSnapshots,
        childSnapshots = childSnapshots,
        renderedPluginIds = emptyList(),
        providerPluginIds = providerPluginIds,
        childScopes = renderedChildScopes
    )

    fun runMutation(target: PagePluginParticipant, operation: suspend () -> Unit) {
        scope.launch {
            busyIds = busyIds + target.stableId
            runCatching { withContext(Dispatchers.IO) { operation() } }
                .onFailure { feedback = it.message ?: it::class.java.simpleName }
            busyIds = busyIds - target.stableId
        }
    }

    fun performDisable(target: PagePluginParticipant) {
        runMutation(target) {
            when (target) {
                is PagePluginParticipant.Plugin -> controlPlane.disable(
                    target.stableId,
                    adminAuthorized = isSystemPlugin(target.snapshot)
                )
                is PagePluginParticipant.Child -> controlPlane.setChildExtensionEnabled(target.stableId, false)
            }
        }
    }

    fun requestDisable(target: PagePluginParticipant) {
        scope.launch {
            val security = runCatching { adminSecurity.refresh() }
                .getOrElse { error ->
                    feedback = error.message ?: error::class.java.simpleName
                    return@launch
                }
            val alwaysVerify = target is PagePluginParticipant.Plugin && isSystemPlugin(target.snapshot)
            when {
                !security.configured -> {
                    pendingDisable = target
                    showAdminSetup = true
                }
                alwaysVerify || security.authorizationRequired -> {
                    pendingDisable = target
                    showAdminPassword = true
                }
                else -> performDisable(target)
            }
        }
    }

    fun performEnable(target: PagePluginParticipant) {
        runMutation(target) {
            when (target) {
                is PagePluginParticipant.Plugin -> controlPlane.enable(target.stableId)
                is PagePluginParticipant.Child -> controlPlane.setChildExtensionEnabled(target.stableId, true)
            }
        }
    }

    val updateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = updateTarget
        updateTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runMutation(target) {
            when (target) {
                is PagePluginParticipant.Plugin -> {
                    val candidate = controlPlane.inspectUri(uri.toString())
                    require(candidate.manifest.pluginId == target.stableId) { "更新包 plugin_id 与目标插件不一致" }
                    controlPlane.installUri(
                        candidate.copy(updateTargetId = target.stableId),
                        PluginInstallOptions(
                            allowUntrustedForDevelopment = controlPlane.developerModeEnabled(),
                            enableAfterInstall = false,
                            approvedScopes = candidate.manifest.permissions.requestedScopes
                        )
                    )
                    controlPlane.activateVersion(target.stableId, candidate.manifest.version)
                }
                is PagePluginParticipant.Child -> {
                    val temporary = File(context.cacheDir, "drawer-child-upgrade-${UUID.randomUUID()}.ailx")
                    try {
                        context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "无法读取子插件更新包" }
                            temporary.outputStream().use(input::copyTo)
                        }
                        controlPlane.upgradeChildExtension(temporary, target.snapshot.toSummary())
                    } finally {
                        temporary.delete()
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxHeight()) {
        if (expanded) {
            DrawerPanel(
                participants = participants,
                busyIds = busyIds,
                feedback = feedback,
                onToggle = { target -> if (target.enabled) requestDisable(target) else performEnable(target) },
                onUpdate = { target ->
                    updateTarget = target
                    updateLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 34.dp)
            )
        }
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).width(34.dp).fillMaxHeight(),
            tonalElevation = 3.dp,
            shadowElevation = 3.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowLeft,
                        contentDescription = if (expanded) "关闭页面插件抽屉" else "打开页面插件抽屉"
                    )
                }
            }
        }
    }

    if (showAdminSetup) {
        AdminSetupDialog(
            adminSecurity = adminSecurity,
            onDismiss = {
                showAdminSetup = false
                pendingDisable = null
            },
            onConfigured = { recoveryKey ->
                showAdminSetup = false
                recoveryKeyToShow = recoveryKey
            }
        )
    }
    if (showAdminPassword) {
        AdminPasswordDialog(
            title = "验证后允许禁用插件",
            adminSecurity = adminSecurity,
            onDismiss = {
                showAdminPassword = false
                pendingDisable = null
            },
            onVerified = {
                showAdminPassword = false
                pendingDisable?.let(::performDisable)
                pendingDisable = null
            },
            onForgotPassword = {
                showAdminPassword = false
                showAdminRecovery = true
            }
        )
    }
    if (showAdminRecovery) {
        AdminRecoveryDialog(
            adminSecurity = adminSecurity,
            onDismiss = {
                showAdminRecovery = false
                pendingDisable = null
            },
            onRecovered = {
                showAdminRecovery = false
                pendingDisable?.let(::performDisable)
                pendingDisable = null
            }
        )
    }
    recoveryKeyToShow?.let { key ->
        RecoveryKeyDialog(key) {
            recoveryKeyToShow = null
            pendingDisable?.let(::performDisable)
            pendingDisable = null
        }
    }
}

/**
 * One child-extension surface that is actually present on the current page layer.
 * The pair is exact so identically named extension points on different plugins never bleed together.
 */
internal data class PageChildScope(val parentPluginId: String, val point: String)

/**
 * Resolves management members for the current visible page layer only.
 *
 * A plugin owning the route/surface is NOT a member merely because it owns the page. Host-owned pages
 * such as Toolbox may pass [renderedPluginIds] from the plugin entries they actually render. Plugin
 * surfaces contribute only external provider owners and child-extension scopes that their rendered
 * components actually expose. Manifest dependencies are intentionally irrelevant.
 */
internal fun resolvePagePluginParticipants(
    pluginSnapshots: List<PluginControlSnapshot>,
    childSnapshots: List<ChildExtensionSnapshot>,
    renderedPluginIds: List<String>,
    providerPluginIds: Set<String>,
    childScopes: Set<PageChildScope>
): List<PagePluginParticipant> {
    val renderedOrder = renderedPluginIds.withIndex().associate { it.value to it.index }
    val pluginIds = buildSet {
        addAll(renderedPluginIds)
        addAll(providerPluginIds)
    }
    val pluginParticipants: List<PagePluginParticipant> = pluginSnapshots
        .filter { it.plugin.pluginId in pluginIds }
        .map(PagePluginParticipant::Plugin)
    val childParticipants: List<PagePluginParticipant> = childSnapshots
        .filter { child ->
            PageChildScope(child.target.parentPluginId, child.target.point) in childScopes
        }
        .map(PagePluginParticipant::Child)

    return (pluginParticipants + childParticipants)
        .distinctBy { "${it.typeLabel}:${it.stableId}" }
        .sortedWith(
            compareBy<PagePluginParticipant>(
                { participant ->
                    when {
                        participant is PagePluginParticipant.Plugin && participant.stableId in renderedOrder ->
                            renderedOrder.getValue(participant.stableId)
                        participant is PagePluginParticipant.Plugin -> 10_000
                        else -> 20_000
                    }
                },
                { it.displayName.lowercase() },
                { it.stableId }
            )
        )
}

@Composable
private fun DrawerPanel(
    participants: List<PagePluginParticipant>,
    busyIds: Set<String>,
    feedback: String?,
    onToggle: (PagePluginParticipant) -> Unit,
    onUpdate: (PagePluginParticipant) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = remember(participants) { participants.chunked(2) }
    val listState = rememberLazyListState()
    Surface(
        modifier = modifier.width(320.dp).fillMaxHeight().border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
        ),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = 12.dp)) {
            Text(
                "当前页面插件 / 子插件",
                modifier = Modifier.padding(horizontal = 14.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "仅提供启用/禁用与更新",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            feedback?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                DrawerScrollRail(listState, rows.size)
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("当前页面没有可管理的插件或子插件", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxHeight().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)
                    ) {
                        items(rows, key = { row -> row.joinToString("|") { it.stableId } }) { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { participant ->
                                    ParticipantCard(
                                        participant = participant,
                                        busy = participant.stableId in busyIds,
                                        onToggle = { onToggle(participant) },
                                        onUpdate = { onUpdate(participant) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParticipantCard(
    participant: PagePluginParticipant,
    busy: Boolean,
    onToggle: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = when (participant) {
        is PagePluginParticipant.Plugin -> when {
            !participant.enabled -> Color(0xFF757575)
            participant.snapshot.plugin.persistentState?.lastState?.name == "ACTIVE" -> Color(0xFF00C853)
            participant.snapshot.plugin.persistentState?.lastState?.name == "FAILED" -> Color(0xFFD32F2F)
            else -> Color(0xFFFFB300)
        }
        is PagePluginParticipant.Child -> when {
            !participant.enabled -> Color(0xFF757575)
            participant.snapshot.lifecycle.name == "ACTIVE" -> Color(0xFF00C853)
            participant.snapshot.lifecycle.name == "FAILED" -> Color(0xFFD32F2F)
            else -> Color(0xFFFFB300)
        }
    }
    Card(
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(statusColor, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        participant.displayName,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${participant.typeLabel} · v${participant.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (participant.enabled) "已启用" else "已禁用",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onToggle, enabled = !busy) {
                    Text(if (participant.enabled) "禁用" else "启用")
                }
                TextButton(onClick = onUpdate, enabled = !busy) { Text("更新") }
            }
        }
    }
}

@Composable
private fun DrawerScrollRail(
    state: androidx.compose.foundation.lazy.LazyListState,
    totalItems: Int
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(
        modifier = Modifier.width(5.dp).fillMaxHeight().background(
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
    ) {
        if (totalItems > 0) {
            val visibleItems = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            val visibleFraction = (visibleItems.toFloat() / totalItems.toFloat()).coerceIn(0.12f, 1f)
            val thumbHeight = maxHeight * visibleFraction
            val maxFirstIndex = (totalItems - visibleItems).coerceAtLeast(0)
            val progress = if (maxFirstIndex == 0) 0f else {
                (state.firstVisibleItemIndex.toFloat() / maxFirstIndex.toFloat()).coerceIn(0f, 1f)
            }
            val travel = maxHeight - thumbHeight
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .pointerInput(totalItems, visibleItems, maxFirstIndex) {
                        fun seek(y: Float) {
                            if (maxFirstIndex <= 0 || size.height <= 0) return
                            val target = ((y / size.height.toFloat()).coerceIn(0f, 1f) * maxFirstIndex)
                                .toInt()
                                .coerceIn(0, maxFirstIndex)
                            scope.launch { state.scrollToItem(target) }
                        }
                        detectVerticalDragGestures(
                            onDragStart = { offset -> seek(offset.y) },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                seek(change.position.y)
                            }
                        )
                    }
            ) {
                Box(
                    Modifier
                        .width(5.dp)
                        .height(thumbHeight)
                        .offset(y = travel * progress)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                            RoundedCornerShape(99.dp)
                        )
                )
            }
        }
    }
}

private fun ChildExtensionSnapshot.toSummary(): ChildExtensionSummary = ChildExtensionSummary(
    extensionId = extensionId,
    version = version,
    displayName = displayName,
    description = description,
    parentPluginId = target.parentPluginId,
    point = target.point,
    apiVersion = target.apiVersion,
    lifecycle = lifecycle.name,
    enabled = enabled,
    roles = roles,
    useCount = useCount,
    lastError = lastError,
    backupVersion = null
)

private fun referencedProviderIds(documentJson: String): Set<String> {
    val document = runCatching { JSONObject(documentJson) }.getOrNull() ?: return emptySet()
    val providerIds = linkedSetOf<String>()
    fun visit(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val child = value.opt(key)
                if (key == "provider_id") {
                    value.optString(key).trim().takeIf { it.isNotEmpty() }?.let(providerIds::add)
                }
                visit(child)
            }
            is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
        }
    }
    visit(document)
    return providerIds
}

private fun renderedChildScopes(ownerPluginId: String, documentJson: String): Set<PageChildScope> {
    val document = runCatching { JSONObject(documentJson) }.getOrNull() ?: return emptySet()
    val result = linkedSetOf<PageChildScope>()

    fun addPoint(raw: String) {
        raw.trim().takeIf { it.isNotEmpty() }?.let { point ->
            result.add(PageChildScope(ownerPluginId, point))
        }
    }

    fun addPoints(array: JSONArray?) {
        val source = array ?: return
        for (index in 0 until source.length()) addPoint(source.optString(index))
    }

    fun visitBlock(block: JSONObject) {
        when (block.optString("type").trim().lowercase()) {
            "child_extension_installer",
            "child_extension_selector",
            "child_extension_list" -> addPoint(block.optString("point"))

            "component_slot" -> {
                val childSlots = block.optJSONObject("child_slots")
                childSlots?.keys()?.forEach { slotId ->
                    addPoints(childSlots.optJSONObject(slotId)?.optJSONArray("points"))
                }
                block.optJSONObject("component")?.let(::visitBlock)
                val localSlots = block.optJSONObject("slots")
                localSlots?.keys()?.forEach { slotId ->
                    val items = localSlots.optJSONArray(slotId) ?: return@forEach
                    for (index in 0 until items.length()) items.optJSONObject(index)?.let(::visitBlock)
                }
            }
        }
    }

    val blocks = document.optJSONArray("blocks") ?: return result
    for (index in 0 until blocks.length()) blocks.optJSONObject(index)?.let(::visitBlock)
    return result
}
