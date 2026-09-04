package com.ai.limbs.plugincenter.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.plugins.system.SystemUiNavigatorV1
import com.ai.limbs.plugincenter.runtime.PluginCenterRuntime
import com.ai.limbs.plugincenter.runtime.PluginInstallOptions
import com.ai.limbs.plugincenter.runtime.PluginUiContributionSnapshot
import com.ai.limbs.plugincenter.model.ChildExtensionInventory
import com.ai.limbs.plugincenter.model.ChildExtensionSummary
import com.ai.limbs.plugincenter.model.PluginControlSnapshot
import com.ai.limbs.plugincenter.model.PluginHealthState
import com.ai.limbs.plugincenter.model.PluginLifecycleState
import com.ai.limbs.plugincenter.model.PluginManifest
import com.ai.limbs.plugincenter.model.PluginImportCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import org.json.JSONObject

private const val EXTENSION_HUB_PLUGIN_ID = "plugin.system.extension_hub"
private const val CHILD_ONLINE_UPDATE_ROLE = "online_update"

private sealed interface AdminAction {
    data object OpenSettings : AdminAction
    data class DisableSystem(val pluginId: String) : AdminAction
    data class Uninstall(val pluginId: String) : AdminAction
}

private class PluginCenterHomeSessionState {
    var searchInput by mutableStateOf("")
    var appliedQuery by mutableStateOf("")
    var sortMode by mutableStateOf(PluginSortMode.NAME_ASC)
    var systemExpanded by mutableStateOf(false)
    var installedExpanded by mutableStateOf(false)
    var expandedParentIds by mutableStateOf<Set<String>>(emptySet())
}
@Composable
fun PluginCenterScreen(
    onBack: () -> Unit,
    navigator: SystemUiNavigatorV1
) {
    val controlPlane = remember { PluginCenterRuntime.controlPlane }
    val adminSecurity = remember { PluginCenterRuntime.adminSecurity }
    val selfMaintenance = remember { PluginCenterRuntime.selfMaintenance }
    val navigation = remember { PluginCenterRuntime.navigation }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var snapshots by remember { mutableStateOf<List<PluginControlSnapshot>>(emptyList()) }
    var childInventory by remember { mutableStateOf(ChildExtensionInventory(false, emptyList())) }
    var uiContributions by remember { mutableStateOf<List<PluginUiContributionSnapshot>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<PluginImportCandidate>>(emptyList()) }
    var updateTargetId by remember { mutableStateOf<String?>(null) }
    var selectedPluginId by remember { mutableStateOf<String?>(null) }
    var disableSystemTargetId by remember { mutableStateOf<String?>(null) }
    var uninstallTargetId by remember { mutableStateOf<String?>(null) }
    var uninstallChildTarget by remember { mutableStateOf<ChildExtensionSummary?>(null) }
    var childUpgradeTarget by remember { mutableStateOf<ChildExtensionSummary?>(null) }
    var showAdminSettings by remember { mutableStateOf(false) }
    var pendingAdminAction by remember { mutableStateOf<AdminAction?>(null) }
    var showAdminSetup by remember { mutableStateOf(false) }
    var showAdminPassword by remember { mutableStateOf(false) }
    var showAdminRecovery by remember { mutableStateOf(false) }
    var recoveryKeyToShow by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val homeSession = remember { PluginCenterHomeSessionState() }
    val homeListState = rememberLazyListState()
    val context = LocalContext.current

    suspend fun refresh() {
        val refreshed = withContext(Dispatchers.IO) {
            Triple(
                controlPlane.snapshots(),
                controlPlane.childExtensionInventory(),
                navigation.contributions()
            )
        }
        snapshots = refreshed.first
        childInventory = refreshed.second
        uiContributions = refreshed.third
    }

    fun showError(error: Throwable) {
        scope.launch {
            snackbarHostState.showSnackbar(error.message ?: error::class.java.simpleName)
        }
    }

    fun completeAdminAction(action: AdminAction) {
        when (action) {
            AdminAction.OpenSettings -> showAdminSettings = true
            is AdminAction.DisableSystem -> disableSystemTargetId = action.pluginId
            is AdminAction.Uninstall -> uninstallTargetId = action.pluginId
        }
    }

    fun actionRequiresFreshPassword(action: AdminAction): Boolean = when (action) {
        AdminAction.OpenSettings -> true
        is AdminAction.DisableSystem -> true
        is AdminAction.Uninstall -> {
            val target = snapshots.firstOrNull { it.plugin.pluginId == action.pluginId }
            target == null || isSystemPlugin(target)
        }
    }

    fun requestAdmin(action: AdminAction) {
        scope.launch {
            val security = runCatching { adminSecurity.refresh() }
                .getOrElse { error -> showError(error); return@launch }
            if (!security.configured) {
                pendingAdminAction = action
                showAdminSetup = true
            } else if (actionRequiresFreshPassword(action) || security.authorizationRequired) {
                pendingAdminAction = action
                showAdminPassword = true
            } else {
                completeAdminAction(action)
            }
        }
    }

    LaunchedEffect(Unit) {
        adminSecurity.refresh()
        controlPlane.refreshAdminState()
        refresh()
    }

    suspend fun importCandidate(uri: android.net.Uri, targetPluginId: String?): PluginImportCandidate {
        return withContext(Dispatchers.IO) {
            controlPlane.inspectUri(uri.toString()).copy(updateTargetId = targetPluginId)
        }
    }

    fun mergeCandidates(imported: List<PluginImportCandidate>) {
        var updated = candidates
        imported.forEach { candidate ->
            updated = updated.filterNot { item ->
                item.manifest.pluginId == candidate.manifest.pluginId &&
                    item.manifest.version == candidate.manifest.version &&
                    item.updateTargetId == candidate.updateTargetId
            } + candidate
        }
        candidates = updated
    }

    val addFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                val imported = uris.map { uri -> importCandidate(uri, null) }
                mergeCandidates(imported)
            }.onFailure(::showError)
            busy = false
        }
    }

    val updateFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetPluginId = updateTargetId
        if (uri == null || targetPluginId == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                mergeCandidates(listOf(importCandidate(uri, targetPluginId)))
            }.onFailure(::showError)
            busy = false
            updateTargetId = null
        }
    }

    val childUpgradeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = childUpgradeTarget
        childUpgradeTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val temporary = File(context.cacheDir, "child-upgrade-${UUID.randomUUID()}.ailx")
                    try {
                        context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "无法读取选择的子插件升级包" }
                            temporary.outputStream().use(input::copyTo)
                        }
                        controlPlane.upgradeChildExtension(temporary, target)
                    } finally {
                        temporary.delete()
                    }
                }
            }.onSuccess { refresh() }.onFailure(::showError)
            busy = false
        }
    }

    fun choosePlugin(targetPluginId: String? = null) {
        val mimeTypes = arrayOf("application/zip", "application/octet-stream", "*/*")
        if (targetPluginId == null) {
            addFileLauncher.launch(mimeTypes)
        } else {
            updateTargetId = targetPluginId
            updateFileLauncher.launch(mimeTypes)
        }
    }

    fun chooseChildUpgrade(target: ChildExtensionSummary) {
        childUpgradeTarget = target
        childUpgradeLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    fun jumpToPlugin(snapshot: PluginControlSnapshot) {
        val pluginId = snapshot.plugin.pluginId
        scope.launch {
            runCatching {
                val (surfaces, contributions) = withContext(Dispatchers.IO) {
                    navigation.surfaces() to navigation.contributions()
                }
                val owned = contributions.filter { it.ownerPluginId == pluginId && it.screenActive }
                val boundSurfaceIds = owned.flatMapTo(linkedSetOf()) { it.surfaceIds }
                val surface = surfaces.firstOrNull { it.surfaceId in boundSurfaceIds }
                val parameters = JSONObject()
                    .put("focus_kind", "plugin")
                    .put("focus_id", pluginId)
                when {
                    surface != null -> parameters.put("surface_id", surface.surfaceId)
                    owned.isNotEmpty() -> parameters.put("screen_id", owned.first().screenId)
                    else -> error("插件当前没有可跳转的 UI 页面：$pluginId")
                }
                withContext(Dispatchers.IO) {
                    controlPlane.invokeHostPrimitive("host.ui.surface@1", "open", parameters)
                }
            }.onFailure(::showError)
        }
    }

    fun jumpToChild(child: ChildExtensionSummary) {
        scope.launch {
            runCatching {
                val contribution = withContext(Dispatchers.IO) {
                    navigation.contributions().firstOrNull {
                        it.ownerPluginId == child.parentPluginId && it.screenActive
                    }
                } ?: error("所属插件当前没有可跳转的 UI 页面：${child.parentPluginId}")
                val parameters = JSONObject()
                    .put("screen_id", contribution.screenId)
                    .put("focus_kind", "child")
                    .put("focus_id", child.extensionId)
                withContext(Dispatchers.IO) {
                    controlPlane.invokeHostPrimitive("host.ui.surface@1", "open", parameters)
                }
            }.onFailure(::showError)
        }
    }

    fun runMutation(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            val lifecycleRefreshJob = launch {
                while (isActive) {
                    delay(50)
                    runCatching { refresh() }
                }
            }
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            lifecycleRefreshJob.cancelAndJoin()
            runCatching { refresh() }.onFailure(::showError)
            result.onFailure(::showError)
            busy = false
        }
    }

    BackHandler(enabled = showAdminSettings) {
        showAdminSettings = false
        scope.launch { refresh() }
    }
    BackHandler(enabled = !showAdminSettings && selectedPluginId != null) {
        selectedPluginId = null
    }
    BackHandler(enabled = !showAdminSettings && selectedPluginId == null) {
        onBack()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val selected = snapshots.firstOrNull { it.plugin.pluginId == selectedPluginId }
            if (showAdminSettings) {
                PluginAdminSecurityScreen(
                    controlPlane = controlPlane,
                    adminSecurity = adminSecurity,
                    selfMaintenance = selfMaintenance,
                    navigation = navigation,
                    navigator = navigator,
                    onBack = {
                        showAdminSettings = false
                        scope.launch { refresh() }
                    },
                    onError = ::showError,
                    onPolicyChanged = { scope.launch { refresh() } }
                )
            } else if (selected != null) {
                PluginDetail(
                    snapshot = selected,
                    dependencySummary = dependencySummary(selected, snapshots, childInventory),
                    busy = busy,
                    onBack = { selectedPluginId = null },
                    onOnlineUpgrade = { runMutation { controlPlane.onlineUpgrade(selected) } },
                    onEnable = { runMutation { controlPlane.enable(selected.plugin.pluginId) } },
                    onDisable = {
                        if (isSystemPlugin(selected)) {
                            requestAdmin(AdminAction.DisableSystem(selected.plugin.pluginId))
                        } else {
                            runMutation { controlPlane.disable(selected.plugin.pluginId) }
                        }
                    },
                    onUpdate = { choosePlugin(selected.plugin.pluginId) },
                    onUninstall = {
                        if (selected.plugin.pluginId != EXTENSION_HUB_PLUGIN_ID) {
                            requestAdmin(AdminAction.Uninstall(selected.plugin.pluginId))
                        }
                    },
                    onBackup = { runMutation { controlPlane.backup(selected.plugin.pluginId) } },
                    onRollback = { runMutation { controlPlane.rollback(selected.plugin.pluginId) } }
                )
            } else {
                PluginCenterHome(
                    snapshots = snapshots,
                    childInventory = childInventory,
                    uiContributions = uiContributions,
                    jumpHostAvailable = controlPlane.hostPrimitiveAvailability(
                        "host.ui.surface@1",
                        "open"
                    ).available,
                    candidates = candidates,
                    busy = busy,
                    session = homeSession,
                    listState = homeListState,
                    onOpenSettings = { requestAdmin(AdminAction.OpenSettings) },
                    onChoose = { choosePlugin() },
                    onClearCandidates = { candidates = emptyList() },
                    onRemoveCandidate = { target ->
                        candidates = candidates.filterNot { it.uri == target.uri && it.updateTargetId == target.updateTargetId }
                    },
                    onInstall = {
                        val queue = candidates
                        val mismatch = queue.firstOrNull {
                            it.updateTargetId != null && it.updateTargetId != it.manifest.pluginId
                        }
                        if (mismatch != null) {
                            showError(IllegalArgumentException("升级包的 plugin_id 与目标插件不一致"))
                        } else if (queue.isNotEmpty()) {
                            runMutation {
                                queue.forEach { current ->
                                    controlPlane.installUri(
                                        current,
                                        PluginInstallOptions(
                                            allowUntrustedForDevelopment = controlPlane.developerModeEnabled(),
                                            enableAfterInstall = current.updateTargetId == null,
                                            approvedScopes = current.manifest.permissions.requestedScopes
                                        )
                                    )
                                    current.updateTargetId?.let { target ->
                                        controlPlane.activateVersion(target, current.manifest.version)
                                    }
                                }
                                withContext(Dispatchers.Main) { candidates = emptyList() }
                            }
                        }
                    },
                    onOpen = { selectedPluginId = it.plugin.pluginId },
                    onJump = ::jumpToPlugin,
                    onOnlineUpgrade = { snapshot -> runMutation { controlPlane.onlineUpgrade(snapshot) } },
                    onEnable = { snapshot -> runMutation { controlPlane.enable(snapshot.plugin.pluginId) } },
                    onDisable = { snapshot ->
                        if (isSystemPlugin(snapshot)) {
                            requestAdmin(AdminAction.DisableSystem(snapshot.plugin.pluginId))
                        } else {
                            runMutation { controlPlane.disable(snapshot.plugin.pluginId) }
                        }
                    },
                    onUpdate = { snapshot -> choosePlugin(snapshot.plugin.pluginId) },
                    onUninstall = { snapshot ->
                        if (snapshot.plugin.pluginId != EXTENSION_HUB_PLUGIN_ID) {
                            requestAdmin(AdminAction.Uninstall(snapshot.plugin.pluginId))
                        }
                    },
                    onBackup = { snapshot -> runMutation { controlPlane.backup(snapshot.plugin.pluginId) } },
                    onOnlineUpgradeChild = { child ->
                        val parent = snapshots.firstOrNull { it.plugin.pluginId == child.parentPluginId }
                        if (parent == null) {
                            showError(IllegalStateException("找不到子插件所属插件：${child.parentPluginId}"))
                        } else {
                            runMutation { controlPlane.onlineUpgradeChild(child, parent) }
                        }
                    },
                    onUpgradeChild = { child -> chooseChildUpgrade(child) },
                    onEnableChild = { child ->
                        runMutation { controlPlane.setChildExtensionEnabled(child.extensionId, true) }
                    },
                    onDisableChild = { child ->
                        runMutation { controlPlane.setChildExtensionEnabled(child.extensionId, false) }
                    },
                    onBackupChild = { child ->
                        runMutation { controlPlane.backupChildExtension(child.extensionId) }
                    },
                    onJumpChild = ::jumpToChild,
                    onUninstallChild = { child -> uninstallChildTarget = child }
                )
            }
            if (busy) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    disableSystemTargetId?.let { pluginId ->
        AlertDialog(
            onDismissRequest = { disableSystemTargetId = null },
            title = { Text("禁用系统插件") },
            text = { Text("确定禁用 $pluginId？系统插件可能影响 AI Limbs 的基础运行能力。") },
            confirmButton = {
                TextButton(onClick = {
                    disableSystemTargetId = null
                    runMutation { controlPlane.disable(pluginId, adminAuthorized = true) }
                }) { Text("确认禁用") }
            },
            dismissButton = {
                TextButton(onClick = { disableSystemTargetId = null }) { Text("取消") }
            }
        )
    }

    uninstallTargetId?.let { pluginId ->
        AlertDialog(
            onDismissRequest = { uninstallTargetId = null },
            title = { Text("卸载插件") },
            text = { Text("确定卸载 $pluginId？插件长期数据默认保留。") },
            confirmButton = {
                DangerTextButton(onClick = {
                    uninstallTargetId = null
                    if (selectedPluginId == pluginId) selectedPluginId = null
                    val systemPlugin = snapshots.firstOrNull { it.plugin.pluginId == pluginId }
                        ?.let(::isSystemPlugin) == true
                    runMutation {
                        controlPlane.uninstall(
                            pluginId,
                            removeData = false,
                            adminAuthorized = systemPlugin
                        )
                    }
                }) { Text("卸载") }
            },
            dismissButton = { TextButton(onClick = { uninstallTargetId = null }) { Text("取消") } }
        )
    }

    uninstallChildTarget?.let { child ->
        AlertDialog(
            onDismissRequest = { uninstallChildTarget = null },
            title = { Text("卸载子插件") },
            text = { Text("确定卸载 ${child.displayName}（${child.extensionId}）？子插件长期数据默认保留。") },
            confirmButton = {
                DangerTextButton(onClick = {
                    uninstallChildTarget = null
                    runMutation { controlPlane.uninstallChildExtension(child.extensionId) }
                }) { Text("卸载") }
            },
            dismissButton = { TextButton(onClick = { uninstallChildTarget = null }) { Text("取消") } }
        )
    }

    if (showAdminSetup) {
        AdminSetupDialog(
            adminSecurity = adminSecurity,
            onDismiss = {
                showAdminSetup = false
                pendingAdminAction = null
            },
            onConfigured = { recoveryKey ->
                showAdminSetup = false
                recoveryKeyToShow = recoveryKey
            }
        )
    }

    if (showAdminPassword) {
        AdminPasswordDialog(
            title = when (pendingAdminAction) {
                AdminAction.OpenSettings -> "管理员验证"
                is AdminAction.DisableSystem -> "验证后允许禁用系统插件"
                is AdminAction.Uninstall -> "验证后允许卸载插件"
                null -> "管理员验证"
            },
            adminSecurity = adminSecurity,
            onDismiss = {
                showAdminPassword = false
                pendingAdminAction = null
            },
            onVerified = {
                showAdminPassword = false
                pendingAdminAction?.let(::completeAdminAction)
                pendingAdminAction = null
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
                pendingAdminAction = null
            },
            onRecovered = {
                showAdminRecovery = false
                pendingAdminAction?.let(::completeAdminAction)
                pendingAdminAction = null
            }
        )
    }

    recoveryKeyToShow?.let { key ->
        RecoveryKeyDialog(key) {
            recoveryKeyToShow = null
            pendingAdminAction?.let(::completeAdminAction)
            pendingAdminAction = null
        }
    }
}
@Composable
private fun PluginCenterHome(
    snapshots: List<PluginControlSnapshot>,
    childInventory: ChildExtensionInventory,
    uiContributions: List<PluginUiContributionSnapshot>,
    jumpHostAvailable: Boolean,
    candidates: List<PluginImportCandidate>,
    busy: Boolean,
    session: PluginCenterHomeSessionState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenSettings: () -> Unit,
    onChoose: () -> Unit,
    onInstall: () -> Unit,
    onClearCandidates: () -> Unit,
    onRemoveCandidate: (PluginImportCandidate) -> Unit,
    onOpen: (PluginControlSnapshot) -> Unit,
    onJump: (PluginControlSnapshot) -> Unit,
    onOnlineUpgrade: (PluginControlSnapshot) -> Unit,
    onEnable: (PluginControlSnapshot) -> Unit,
    onDisable: (PluginControlSnapshot) -> Unit,
    onUpdate: (PluginControlSnapshot) -> Unit,
    onUninstall: (PluginControlSnapshot) -> Unit,
    onBackup: (PluginControlSnapshot) -> Unit,
    onOnlineUpgradeChild: (ChildExtensionSummary) -> Unit,
    onUpgradeChild: (ChildExtensionSummary) -> Unit,
    onEnableChild: (ChildExtensionSummary) -> Unit,
    onDisableChild: (ChildExtensionSummary) -> Unit,
    onBackupChild: (ChildExtensionSummary) -> Unit,
    onJumpChild: (ChildExtensionSummary) -> Unit,
    onUninstallChild: (ChildExtensionSummary) -> Unit
) {
    val dependencySummaries = remember(snapshots, childInventory) {
        snapshots.associate { it.plugin.pluginId to dependencySummary(it, snapshots, childInventory) }
    }
    val childrenByParent = remember(childInventory) {
        childInventory.extensions.groupBy { it.parentPluginId }
    }
    val jumpablePluginIds = remember(uiContributions) {
        uiContributions.filter { it.screenActive }.mapTo(mutableSetOf()) { it.ownerPluginId }
    }
    val normalizedQuery = session.appliedQuery.trim().lowercase()
    val systemParents = remember(snapshots) { snapshots.filter(::isSystemPlugin) }
    val installedParents = remember(snapshots) { snapshots.filterNot(::isSystemPlugin) }
    val visibleSystemParents = filterParentPluginsWithChildren(systemParents, childrenByParent, normalizedQuery, session.sortMode)
    val visibleInstalledParents = filterParentPluginsWithChildren(installedParents, childrenByParent, normalizedQuery, session.sortMode)
    val searching = normalizedQuery.isNotBlank()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 23.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "plugin-center-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Plugin Center",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "管理员安全与开发设置")
                    }
                }
            }
            item(key = "plugin-import") {
                ImportPanel(
                    candidates = candidates,
                    busy = busy,
                    onChoose = onChoose,
                    onInstall = onInstall,
                    onClear = onClearCandidates,
                    onRemove = onRemoveCandidate
                )
            }
            item(key = "plugin-search-sort") {
                PluginSearchSortControls(
                    input = session.searchInput,
                    appliedQuery = session.appliedQuery,
                    sortMode = session.sortMode,
                    onInputChange = { session.searchInput = it },
                    onApplySearch = { session.appliedQuery = session.searchInput.trim() },
                    onClearSearch = {
                        session.searchInput = ""
                        session.appliedQuery = ""
                    },
                    onSortModeChange = { session.sortMode = it }
                )
            }

            item(key = "system-header") {
                CollapsiblePluginSectionHeader(
                    title = "系统插件",
                    expanded = session.systemExpanded,
                    matchedCount = visibleSystemParents.size,
                    totalCount = systemParents.size,
                    searching = searching,
                    onToggle = { session.systemExpanded = !session.systemExpanded }
                )
            }
            if (session.systemExpanded) {
                if (visibleSystemParents.isEmpty()) {
                    item(key = "system-empty") {
                        Text(
                            if (searching) "系统插件中没有搜索结果" else "当前为空",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(visibleSystemParents, key = { "system:${it.plugin.pluginId}" }) { snapshot ->
                        val parentId = snapshot.plugin.pluginId
                        val allChildren = childrenByParent[parentId].orEmpty()
                            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.extensionId }))
                        val visibleChildren = if (searching) {
                            allChildren.filter { childMatchesQuery(it, normalizedQuery) }
                        } else {
                            allChildren
                        }
                        val forcedExpanded = searching && visibleChildren.isNotEmpty()
                        val childrenExpanded = forcedExpanded || parentId in session.expandedParentIds
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PluginCard(
                                snapshot = snapshot,
                                dependencySummary = dependencySummaries.getValue(parentId),
                                childCount = allChildren.size,
                                childExpanded = childrenExpanded,
                                onToggleChildren = if (allChildren.isEmpty()) null else {
                                    {
                                        session.expandedParentIds = if (parentId in session.expandedParentIds) {
                                            session.expandedParentIds - parentId
                                        } else {
                                            session.expandedParentIds + parentId
                                        }
                                    }
                                },
                                jumpEnabled = jumpHostAvailable && parentId in jumpablePluginIds,
                                onJump = { onJump(snapshot) },
                                onOpen = { onOpen(snapshot) },
                                onOnlineUpgrade = { onOnlineUpgrade(snapshot) },
                                onEnable = { onEnable(snapshot) },
                                onDisable = { onDisable(snapshot) },
                                onUpdate = { onUpdate(snapshot) },
                                onUninstall = { onUninstall(snapshot) },
                                onBackup = { onBackup(snapshot) }
                            )
                            if (childrenExpanded) {
                                visibleChildren.forEach { child ->
                                    ChildExtensionCard(
                                        child = child,
                                        jumpEnabled = jumpHostAvailable && child.parentPluginId in jumpablePluginIds,
                                        onlineUpgradeEnabled = childOnlineUpgradeAvailable(child, snapshot),
                                        onJump = { onJumpChild(child) },
                                        onOnlineUpgrade = { onOnlineUpgradeChild(child) },
                                        onUpgrade = { onUpgradeChild(child) },
                                        onEnable = { onEnableChild(child) },
                                        onDisable = { onDisableChild(child) },
                                        onBackup = { onBackupChild(child) },
                                        onUninstall = { onUninstallChild(child) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "installed-header") {
                CollapsiblePluginSectionHeader(
                    title = "已安装插件",
                    expanded = session.installedExpanded,
                    matchedCount = visibleInstalledParents.size,
                    totalCount = installedParents.size,
                    searching = searching,
                    onToggle = { session.installedExpanded = !session.installedExpanded }
                )
            }
            if (session.installedExpanded) {
                if (visibleInstalledParents.isEmpty()) {
                    item(key = "installed-empty") {
                        Text(
                            if (searching) "已安装插件中没有搜索结果" else "当前为空",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(visibleInstalledParents, key = { "installed:${it.plugin.pluginId}" }) { snapshot ->
                        val parentId = snapshot.plugin.pluginId
                        val allChildren = childrenByParent[parentId].orEmpty()
                            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.extensionId }))
                        val visibleChildren = if (searching) {
                            allChildren.filter { childMatchesQuery(it, normalizedQuery) }
                        } else {
                            allChildren
                        }
                        val forcedExpanded = searching && visibleChildren.isNotEmpty()
                        val childrenExpanded = forcedExpanded || parentId in session.expandedParentIds
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PluginCard(
                                snapshot = snapshot,
                                dependencySummary = dependencySummaries.getValue(parentId),
                                childCount = allChildren.size,
                                childExpanded = childrenExpanded,
                                onToggleChildren = if (allChildren.isEmpty()) null else {
                                    {
                                        session.expandedParentIds = if (parentId in session.expandedParentIds) {
                                            session.expandedParentIds - parentId
                                        } else {
                                            session.expandedParentIds + parentId
                                        }
                                    }
                                },
                                jumpEnabled = jumpHostAvailable && parentId in jumpablePluginIds,
                                onJump = { onJump(snapshot) },
                                onOpen = { onOpen(snapshot) },
                                onOnlineUpgrade = { onOnlineUpgrade(snapshot) },
                                onEnable = { onEnable(snapshot) },
                                onDisable = { onDisable(snapshot) },
                                onUpdate = { onUpdate(snapshot) },
                                onUninstall = { onUninstall(snapshot) },
                                onBackup = { onBackup(snapshot) }
                            )
                            if (childrenExpanded) {
                                visibleChildren.forEach { child ->
                                    ChildExtensionCard(
                                        child = child,
                                        jumpEnabled = jumpHostAvailable && child.parentPluginId in jumpablePluginIds,
                                        onlineUpgradeEnabled = childOnlineUpgradeAvailable(child, snapshot),
                                        onJump = { onJumpChild(child) },
                                        onOnlineUpgrade = { onOnlineUpgradeChild(child) },
                                        onUpgrade = { onUpgradeChild(child) },
                                        onEnable = { onEnableChild(child) },
                                        onDisable = { onDisableChild(child) },
                                        onBackup = { onBackupChild(child) },
                                        onUninstall = { onUninstallChild(child) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (snapshots.isEmpty()) {
                item(key = "all-empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("尚未安装任何插件", fontWeight = FontWeight.Medium)
                        Text(
                            "点击“添加插件”导入 .ailp",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        LazyListScrollIndicator(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun ImportPanel(
    candidates: List<PluginImportCandidate>,
    busy: Boolean,
    onChoose: () -> Unit,
    onInstall: () -> Unit,
    onClear: () -> Unit,
    onRemove: (PluginImportCandidate) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onChoose, enabled = !busy) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" 添加插件")
            }
            if (candidates.isEmpty()) {
                Text(
                    "选择 .ailp 后将在这里形成待安装队列；可以连续添加多个插件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("待安装插件（${candidates.size}）", fontWeight = FontWeight.Bold)
                candidates.forEachIndexed { index, candidate ->
                    if (index > 0) Divider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                candidate.manifest.display.name,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = { onRemove(candidate) }, enabled = !busy) {
                                Text("移除")
                            }
                        }
                        Text(
                            candidate.manifest.display.description ?: "未提供功能说明",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "v${candidate.manifest.version} · ${candidate.manifest.activationMode.wireName} · ${candidate.manifest.runtime.kind}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val scopes = candidate.manifest.permissions.requestedScopes
                        Text(
                            "请求权限：" + if (scopes.isEmpty()) "无" else scopes.joinToString(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    "批准后将按队列顺序安装，并明确批准各插件上方列出的权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onInstall, enabled = !busy) {
                        Text(if (candidates.size == 1) "批准并安装" else "批准并安装全部")
                    }
                    OutlinedButton(onClick = onClear, enabled = !busy) {
                        Text("清除全部")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginCard(
    snapshot: PluginControlSnapshot,
    dependencySummary: PluginDependencySummary,
    jumpEnabled: Boolean,
    onJump: () -> Unit,
    childCount: Int = 0,
    childExpanded: Boolean = false,
    onToggleChildren: (() -> Unit)? = null,
    onOpen: () -> Unit,
    onOnlineUpgrade: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onBackup: () -> Unit
) {
    val manifest = snapshot.plugin.activeManifest
    val state = snapshot.plugin.persistentState
    val currentVersion = state?.activeVersion
    val backupVersion = snapshot.plugin.backup?.version
    val canBackup = currentVersion != null && backupVersion != currentVersion
    val cardModifier = if (onToggleChildren != null) {
        Modifier.fillMaxWidth().clickable(onClick = onToggleChildren)
    } else {
        Modifier.fillMaxWidth()
    }
    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(snapshot)
                Spacer(Modifier.size(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(manifest?.display?.name ?: snapshot.plugin.pluginId, fontWeight = FontWeight.Bold)
                    manifest?.display?.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (childCount > 0) {
                    Text(
                        "${if (childExpanded) "▼" else "▶"} 子插件 $childCount",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Text(
                "v${state?.activeVersion ?: "-"} · ${manifest?.activationMode?.wireName ?: "-"} · ${manifest?.runtime?.kind ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                usageSummary(snapshot),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                dependencySummaryText(dependencySummary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state?.lastState == PluginLifecycleState.BLOCKED && !state.lastError.isNullOrBlank()) {
                Text(
                    state.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TextButton(onClick = onJump, enabled = jumpEnabled) { Text("跳转") }
                TextButton(onClick = onOpen) { Text("详情") }
                if (state?.enabled == true) {
                    TextButton(onClick = onDisable) { Text("禁用") }
                } else {
                    TextButton(onClick = onEnable) { Text("启用") }
                }
                TextButton(onClick = onOnlineUpgrade, enabled = parentOnlineUpgradeAvailable(snapshot)) { Text("在线升级") }
                TextButton(onClick = onUpdate) { Text("升级") }
                if (snapshot.plugin.pluginId != EXTENSION_HUB_PLUGIN_ID) {
                    DangerTextButton(onClick = onUninstall) { Text("卸载") }
                }
                TextButton(onClick = onBackup, enabled = canBackup) { Text("备份") }
            }
        }
    }
}

private fun parentOnlineUpgradeAvailable(snapshot: PluginControlSnapshot): Boolean {
    val manifest = snapshot.plugin.activeManifest ?: return false
    val state = snapshot.plugin.persistentState ?: return false
    manifest.provides.capabilities.singleOrNull { it.endsWith(".online_update") } ?: return false
    return state.enabled && state.lastState == PluginLifecycleState.ACTIVE
}

private fun childOnlineUpgradeAvailable(
    child: ChildExtensionSummary,
    parent: PluginControlSnapshot
): Boolean {
    val manifest = parent.plugin.activeManifest ?: return false
    val state = parent.plugin.persistentState ?: return false
    manifest.provides.capabilities.singleOrNull { it.endsWith(".child_online_update") } ?: return false
    return child.enabled && child.lifecycle == "ACTIVE" && CHILD_ONLINE_UPDATE_ROLE in child.roles &&
        state.enabled && state.lastState == PluginLifecycleState.ACTIVE
}

private fun filterParentPluginsWithChildren(
    parents: List<PluginControlSnapshot>,
    childrenByParent: Map<String, List<ChildExtensionSummary>>,
    normalizedQuery: String,
    sortMode: PluginSortMode
): List<PluginControlSnapshot> {
    val directlyMatched = filterAndSortPlugins(parents, normalizedQuery, sortMode)
    if (normalizedQuery.isBlank()) return directlyMatched
    val directIds = directlyMatched.mapTo(mutableSetOf()) { it.plugin.pluginId }
    val parentIds = parents.mapTo(mutableSetOf()) { it.plugin.pluginId }
    val childMatchedParentIds = childrenByParent
        .filterKeys { it in parentIds }
        .filterValues { children -> children.any { childMatchesQuery(it, normalizedQuery) } }
        .keys
    return filterAndSortPlugins(
        parents.filter { it.plugin.pluginId in directIds || it.plugin.pluginId in childMatchedParentIds },
        "",
        sortMode
    )
}

private fun childMatchesQuery(child: ChildExtensionSummary, normalizedQuery: String): Boolean {
    if (normalizedQuery.isBlank()) return true
    return listOf(
        child.extensionId,
        child.displayName,
        child.description.orEmpty(),
        child.parentPluginId,
        child.point,
        child.lifecycle,
        child.version
    ).any { it.lowercase().contains(normalizedQuery) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChildExtensionCard(
    child: ChildExtensionSummary,
    jumpEnabled: Boolean,
    onlineUpgradeEnabled: Boolean,
    onJump: () -> Unit,
    onOnlineUpgrade: () -> Unit,
    onUpgrade: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onBackup: () -> Unit,
    onUninstall: () -> Unit
) {
    val statusColor = when {
        !child.enabled || child.lifecycle == "DISABLED" -> Color(0xFF757575)
        child.lifecycle == "ACTIVE" -> Color(0xFF00C853)
        child.lifecycle == "FAILED" -> Color(0xFFD32F2F)
        else -> Color(0xFFFFB300)
    }
    val canBackup = child.backupVersion != child.version
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(9.dp).background(statusColor, CircleShape))
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(child.displayName, fontWeight = FontWeight.Bold)
                    Text(
                        "v${child.version} · ${child.lifecycle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(".ailx", style = MaterialTheme.typography.bodySmall)
            }
            child.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "${child.extensionId} · ${child.point}@${child.apiVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "使用 ${child.useCount} 次 · 备份 ${child.backupVersion ?: "无"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            child.lastError?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TextButton(onClick = onJump, enabled = jumpEnabled) { Text("跳转") }
                if (child.enabled) {
                    TextButton(onClick = onDisable) { Text("禁用") }
                } else {
                    TextButton(onClick = onEnable) { Text("启用") }
                }
                TextButton(onClick = onOnlineUpgrade, enabled = onlineUpgradeEnabled) { Text("在线升级") }
                TextButton(onClick = onUpgrade) { Text("升级") }
                DangerTextButton(onClick = onUninstall) { Text("卸载") }
                TextButton(onClick = onBackup, enabled = canBackup) { Text("备份") }
            }
        }
    }
}

@Composable
private fun StatusDot(snapshot: PluginControlSnapshot) {
    val state = snapshot.plugin.persistentState
    val color = when {
        state?.enabled == false || state?.lastState == PluginLifecycleState.DISABLED -> Color(0xFF757575)
        snapshot.health == PluginHealthState.FAILED || state?.lastState == PluginLifecycleState.FAILED -> Color(0xFFD32F2F)
        state?.lastState == PluginLifecycleState.ACTIVE -> Color(0xFF00C853)
        else -> Color(0xFFFFB300)
    }
    Box(modifier = Modifier.size(11.dp).background(color, CircleShape))
}

@Composable
private fun PluginDetail(
    snapshot: PluginControlSnapshot,
    dependencySummary: PluginDependencySummary,
    busy: Boolean,
    onBack: () -> Unit,
    onOnlineUpgrade: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onBackup: () -> Unit,
    onRollback: () -> Unit
) {
    val manifest = snapshot.plugin.activeManifest
    val state = snapshot.plugin.persistentState
    val canBackup = state?.activeVersion != null && snapshot.plugin.backup?.version != state.activeVersion
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("← 返回 Plugin Center") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(snapshot)
            Spacer(Modifier.size(10.dp))
            Text(manifest?.display?.name ?: snapshot.plugin.pluginId, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        manifest?.display?.description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        DetailLine("版本", state?.activeVersion ?: "-")
        DetailLine("状态", state?.lastState?.name ?: "-")
        state?.lastError?.takeIf { it.isNotBlank() }?.let { DetailLine("状态说明", it) }
        DetailLine("运行时", manifest?.runtime?.kind ?: "-")
        DetailLine("激活模式", manifest?.activationMode?.wireName ?: "-")
        DetailLine("安装位置", "AI Limbs Plugin Store")
        DetailLine("插件 ID", snapshot.plugin.pluginId)
        DetailLine("已挂载版本", snapshot.plugin.mountedVersion ?: "未挂载")
        DetailLine("使用次数", snapshot.plugin.usage.useCount.toString())
        DetailLine("最近使用", usageSummary(snapshot).substringAfter("最近使用："))
        DetailLine("被插件依赖", "${dependencySummary.parentPluginCount} 个")
        DetailLine("被子插件依赖", dependencySummary.childPluginCount?.let { "$it 个" } ?: "不可用")
        DetailLine("备份版本", snapshot.plugin.backup?.version ?: "未备份")
        Divider()
        Text("权限", fontWeight = FontWeight.Bold)
        val scopes = manifest?.permissions?.requestedScopes.orEmpty()
        Text(if (scopes.isEmpty()) "未声明权限" else scopes.joinToString("\n"))
        Text("提供的能力", fontWeight = FontWeight.Bold)
        val capabilities = manifest?.provides?.capabilities.orEmpty()
        Text(if (capabilities.isEmpty()) "无" else capabilities.joinToString("\n"))
        Text("提供的界面扩展", fontWeight = FontWeight.Bold)
        val extensions = manifest?.provides?.extensions.orEmpty()
        Text(if (extensions.isEmpty()) "无" else extensions.joinToString("\n") { it.point + " / " + it.id })
        Text("依赖", fontWeight = FontWeight.Bold)
        val pluginDeps = manifest?.dependencies?.plugins.orEmpty()
        val serviceDeps = manifest?.dependencies?.services.orEmpty()
        if (pluginDeps.isEmpty() && serviceDeps.isEmpty()) {
            Text("无")
        } else {
            pluginDeps.forEach { Text("插件：${it.pluginId}${it.minVersion?.let { v -> " >= $v" } ?: ""}") }
            serviceDeps.forEach { Text("服务：${it.serviceId}${it.minApi?.let { api -> " API >= $api" } ?: ""}") }
        }
        Divider()
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state?.enabled == true) {
                Button(onClick = onDisable, enabled = !busy) { Text("禁用") }
            } else {
                Button(onClick = onEnable, enabled = !busy) { Text("启用") }
            }
            OutlinedButton(
                onClick = onOnlineUpgrade,
                enabled = !busy && parentOnlineUpgradeAvailable(snapshot)
            ) { Text("在线升级") }
            OutlinedButton(onClick = onUpdate, enabled = !busy) { Text("升级") }
            if (snapshot.plugin.pluginId != EXTENSION_HUB_PLUGIN_ID) {
                DangerOutlinedButton(onClick = onUninstall, enabled = !busy) { Text("卸载") }
            }
            OutlinedButton(onClick = onBackup, enabled = !busy && canBackup) { Text("备份") }
        }
        Text("版本管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        DetailLine("当前版本", state?.activeVersion ?: "-")
        DetailLine("上一版本", state?.previousVersion ?: "无")
        if (state?.previousVersion != null) {
            OutlinedButton(onClick = onRollback, enabled = !busy) { Text("回滚") }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.65f))
    }
}
