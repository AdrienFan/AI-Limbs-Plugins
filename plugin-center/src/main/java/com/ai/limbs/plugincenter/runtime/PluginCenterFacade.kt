package com.ai.limbs.plugincenter.runtime

import com.ai.assistance.operit.plugins.system.SystemJsonServiceV1
import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.ChildExtensionBackupSnapshot
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import com.ai.limbs.plugincenter.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

internal data class PluginInstallOptions(
    val allowUntrustedForDevelopment: Boolean = false,
    val enableAfterInstall: Boolean = false,
    val approvedScopes: Set<String> = emptySet()
)

private const val EXTENSION_HUB_PLUGIN_ID = "plugin.system.extension_hub"
private const val CHILD_ONLINE_UPDATE_ROLE = "online_update"

internal class PluginControlPlaneFacade(
    private val host: SystemPluginHostV2
) {
    private val service get() = host.pluginAdmin
    @Volatile private var cachedSurfaces: List<HostSurfaceSnapshot> = emptyList()
    @Volatile private var cachedInactivity = PluginInactivityPolicySnapshot(false, InactivityThresholdMode.DAYS, 30, 10, 0L)
    @Volatile private var cachedBackupPolicy = PluginBackupPolicySnapshot(false)

    fun developerModeEnabled(): Boolean = host.pluginPlatform.developerModeEnabled()
    fun developerDiscoveryEnabled(): Boolean = host.pluginPlatform.developerDiscoveryEnabled()

    fun hostPrimitiveSnapshots(): List<HostPrimitiveSnapshot> =
        host.hostGateway.listHostPrimitives().map { item ->
            HostPrimitiveSnapshot(
                HostPrimitiveDefinition(
                    item.number, item.id, item.title,
                    item.description, item.boundary,
                    PrimitiveValue(item.maturity), PrimitiveValue(item.exposure),
                    item.requestableScope
                ),
                item.policyAllowed,
                item.callable
            )
        }

    fun hostPrimitiveOperations(id: String): List<String> =
        host.hostGateway.listHostPrimitiveOperations(id)

    fun hostPrimitiveAvailability(id: String, operation: String? = null) =
        host.hostGateway.availabilityHostPrimitive(id, operation)

    suspend fun invokeHostPrimitive(id: String, operation: String, parameters: JSONObject = JSONObject()): JSONObject =
        host.hostGateway.invokeHostPrimitive(id, operation, parameters)

    suspend fun setDeveloperMode(enabled: Boolean) =
        host.pluginPlatform.setDeveloperMode(enabled)

    suspend fun setDeveloperDiscoveryEnabled(enabled: Boolean) =
        host.pluginPlatform.setDeveloperDiscoveryEnabled(enabled)

    suspend fun setHostPrimitiveAllowed(id: String, allowed: Boolean) {
        host.pluginPlatform.setHostPrimitiveAllowed(id, allowed)
        cachedSurfaces = fetchHostSurfaceSnapshots()
    }

    suspend fun snapshots(): List<PluginControlSnapshot> =
        service.call("snapshots").optJSONArray("plugins").jsonObjects().map(::parseControlSnapshot)

    private fun extensionHubOrNull(): ExtensionHubService? {
        val binding = host.providers.resolve(InProcessSystemIds.EXTENSION_HUB_PROVIDER)
        if (binding?.ownerPluginId != "plugin.system.extension_hub") return null
        return binding.payload as? ExtensionHubService
    }

    private fun requireExtensionHub(): ExtensionHubService =
        extensionHubOrNull() ?: error("Plugin Extension Hub 未启用")

    fun childExtensionInventory(): ChildExtensionInventory {
        val hub = extensionHubOrNull() ?: return ChildExtensionInventory(false, emptyList())
        val backups = hub.backupSnapshots().value.associateBy { it.extensionId }
        return ChildExtensionInventory(
            available = true,
            extensions = hub.snapshots().value.map { child ->
                ChildExtensionSummary(
                    extensionId = child.extensionId,
                    version = child.version,
                    displayName = child.displayName,
                    description = child.description,
                    parentPluginId = child.target.parentPluginId,
                    point = child.target.point,
                    apiVersion = child.target.apiVersion,
                    lifecycle = child.lifecycle.name,
                    enabled = child.enabled,
                    roles = child.roles,
                    useCount = child.useCount,
                    lastError = child.lastError,
                    backupVersion = backups[child.extensionId]?.version
                )
            }
        )
    }

    suspend fun setChildExtensionEnabled(extensionId: String, enabled: Boolean) {
        requireExtensionHub().setEnabled(extensionId, enabled)
    }

    suspend fun backupChildExtension(extensionId: String) {
        requireExtensionHub().backup(extensionId)
    }

    fun childBackupSnapshots(): List<ChildExtensionBackupSnapshot> =
        extensionHubOrNull()?.backupSnapshots()?.value.orEmpty()

    suspend fun restoreChildBackup(extensionId: String) {
        requireExtensionHub().restoreBackup(extensionId)
    }

    suspend fun deleteChildBackup(extensionId: String) {
        check(requireExtensionHub().deleteBackup(extensionId)) { "子插件备份不存在：$extensionId" }
    }

    fun canOnlineUpgrade(snapshot: PluginControlSnapshot): Boolean {
        val manifest = snapshot.plugin.activeManifest ?: return false
        val state = snapshot.plugin.persistentState ?: return false
        manifest.provides.capabilities.singleOrNull { it.endsWith(".online_update") } ?: return false
        return state.enabled && state.lastState == PluginLifecycleState.ACTIVE
    }

    suspend fun onlineUpgrade(snapshot: PluginControlSnapshot) {
        check(canOnlineUpgrade(snapshot)) { "插件未声明可用的在线升级能力" }
        val pluginId = snapshot.plugin.pluginId
        val capabilityId = snapshot.plugin.activeManifest?.provides?.capabilities
            ?.singleOrNull { it.endsWith(".online_update") }
            ?: error("在线升级 capability 不唯一或不存在")
        host.delegatedCapabilities.invokeAsActivePlugin(pluginId, capabilityId, JSONObject())
    }

    fun canOnlineUpgradeChild(child: ChildExtensionSummary, parent: PluginControlSnapshot?): Boolean {
        val parentSnapshot = parent ?: return false
        val parentManifest = parentSnapshot.plugin.activeManifest ?: return false
        val parentState = parentSnapshot.plugin.persistentState ?: return false
        parentManifest.provides.capabilities
            .singleOrNull { it.endsWith(".child_online_update") } ?: return false
        return child.enabled && child.lifecycle == "ACTIVE" && CHILD_ONLINE_UPDATE_ROLE in child.roles &&
            parentState.enabled && parentState.lastState == PluginLifecycleState.ACTIVE
    }

    suspend fun onlineUpgradeChild(child: ChildExtensionSummary, parent: PluginControlSnapshot) {
        check(canOnlineUpgradeChild(child, parent)) { "子插件或所属插件未声明可用的在线升级能力" }
        val capabilityId = parent.plugin.activeManifest?.provides?.capabilities
            ?.singleOrNull { it.endsWith(".child_online_update") }
            ?: error("所属插件未声明唯一的 child_online_update capability")
        host.delegatedCapabilities.invokeAsActivePlugin(
            child.parentPluginId,
            capabilityId,
            JSONObject().put("extension_id", child.extensionId)
                .put("current_version", child.version)
                .put("extension_point", child.point)
        )
    }

    suspend fun upgradeChildExtension(packageFile: File, target: ChildExtensionSummary) {
        require(packageFile.isFile && packageFile.name.lowercase().endsWith(".ailx")) {
            "升级需要 .ailx 安装包"
        }
        val manifest = ZipFile(packageFile).use { zip ->
            val entry = zip.getEntry("extension.json") ?: error(".ailx 缺少 extension.json")
            zip.getInputStream(entry).bufferedReader().use { JSONObject(it.readText()) }
        }
        require(manifest.optString("format") == "AIL_EXTENSION_V1") { "不是 AIL_EXTENSION_V1 安装包" }
        require(manifest.optString("extension_id") == target.extensionId) { "升级包 extension_id 与目标子插件不一致" }
        val targetJson = manifest.optJSONObject("target") ?: error("升级包缺少 target")
        require(targetJson.optString("plugin_id") == target.parentPluginId) { "升级包所属插件目标不一致" }
        require(targetJson.optString("extension_point") == target.point) { "升级包扩展点不一致" }
        require(targetJson.optInt("api", -1) == target.apiVersion) { "升级包 API 版本不一致" }
        val installed = requireExtensionHub().install(packageFile, target.parentPluginId, target.point)
        check(installed.extensionId == target.extensionId) { "Hub 返回的子插件身份不一致" }
    }

    suspend fun uninstallChildExtension(extensionId: String) {
        check(requireExtensionHub().uninstall(extensionId)) { "子插件不存在：$extensionId" }
    }

    suspend fun inspectUri(uri: String): PluginImportCandidate {
        val result = service.call("inspect_uri", JSONObject().put("uri", uri))
        return PluginImportCandidate(
            uri = uri,
            sourceName = result.getString("source_name"),
            manifest = parseManifest(result.getJSONObject("manifest"))
        )
    }

    suspend fun installUri(candidate: PluginImportCandidate, options: PluginInstallOptions) {
        service.call("install_uri", JSONObject()
            .put("uri", candidate.uri)
            .put("allow_untrusted_for_development", options.allowUntrustedForDevelopment)
            .put("enable_after_install", options.enableAfterInstall)
            .put("approved_scopes", JSONArray(options.approvedScopes.toList())))
    }
    suspend fun enable(pluginId: String) {
        service.call("enable", JSONObject().put("plugin_id", pluginId))
    }

    suspend fun disable(pluginId: String, adminAuthorized: Boolean = false) {
        service.call("disable", JSONObject()
            .put("plugin_id", pluginId)
            .put("admin_authorized", adminAuthorized))
    }

    suspend fun activateVersion(pluginId: String, version: String) {
        service.call("activate_version", JSONObject()
            .put("plugin_id", pluginId)
            .put("version", version))
    }

    suspend fun rollback(pluginId: String) {
        service.call("rollback", JSONObject().put("plugin_id", pluginId))
    }

    suspend fun uninstall(pluginId: String, removeData: Boolean = false, adminAuthorized: Boolean = false) {
        check(pluginId != EXTENSION_HUB_PLUGIN_ID) { "Plugin Extension Hub 是基础插件，不能卸载" }
        service.call("uninstall", JSONObject()
            .put("plugin_id", pluginId)
            .put("remove_data", removeData)
            .put("admin_authorized", adminAuthorized))
    }
    fun hostSurfaceSnapshots(): List<HostSurfaceSnapshot> = cachedSurfaces

    private suspend fun fetchHostSurfaceSnapshots(): List<HostSurfaceSnapshot> =
        service.call("host_surfaces").optJSONArray("surfaces").jsonObjects().map(::parseHostSurface)

    suspend fun setHostSurfaceAllowed(id: String, allowed: Boolean) {
        service.call("set_host_surface", JSONObject()
            .put("surface_id", id)
            .put("allowed", allowed))
        cachedSurfaces = fetchHostSurfaceSnapshots()
    }

    suspend fun setHostSurfacesAllowed(ids: Collection<String>, allowed: Boolean) {
        service.call("set_host_surfaces", JSONObject()
            .put("surface_ids", JSONArray(ids.toList()))
            .put("allowed", allowed))
        cachedSurfaces = fetchHostSurfaceSnapshots()
    }

    fun inactivityPolicySnapshot(): PluginInactivityPolicySnapshot = cachedInactivity

    private suspend fun fetchInactivityPolicySnapshot(): PluginInactivityPolicySnapshot {
        val json = service.call("inactivity_status")
        return PluginInactivityPolicySnapshot(
            json.getBoolean("enabled"),
            InactivityThresholdMode.valueOf(json.getString("mode")),
            json.getInt("days"),
            json.getInt("test_seconds"),
            json.optLong("enabled_at", 0L)
        )
    }
    suspend fun configureInactivityPolicy(
        enabled: Boolean,
        mode: InactivityThresholdMode,
        days: Int,
        testSeconds: Int
    ) {
        service.call("configure_inactivity", JSONObject()
            .put("enabled", enabled)
            .put("mode", mode.name)
            .put("days", days)
            .put("test_seconds", testSeconds))
        cachedInactivity = fetchInactivityPolicySnapshot()
    }

    suspend fun runInactivityCheck() {
        service.call("run_inactivity_check")
    }

    fun backupPolicySnapshot(): PluginBackupPolicySnapshot = cachedBackupPolicy

    private suspend fun fetchBackupPolicySnapshot(): PluginBackupPolicySnapshot =
        PluginBackupPolicySnapshot(service.call("backup_policy_status").getBoolean("enabled"))

    suspend fun refreshAdminState() {
        cachedSurfaces = fetchHostSurfaceSnapshots()
        cachedInactivity = fetchInactivityPolicySnapshot()
        cachedBackupPolicy = fetchBackupPolicySnapshot()
    }

    suspend fun configureBackupPolicy(enabled: Boolean) {
        service.call("configure_backup_policy", JSONObject().put("enabled", enabled))
        cachedBackupPolicy = fetchBackupPolicySnapshot()
    }
    suspend fun backupSnapshots(): List<PluginBackupSnapshot> =
        service.call("backups").optJSONArray("backups").jsonObjects().map(::parseBackup)

    suspend fun backup(pluginId: String) {
        service.call("backup", JSONObject().put("plugin_id", pluginId))
    }

    suspend fun restoreBackup(pluginId: String) {
        service.call("restore_backup", JSONObject().put("plugin_id", pluginId))
    }

    suspend fun deleteBackup(pluginId: String) {
        service.call("delete_backup", JSONObject().put("plugin_id", pluginId))
    }
}


internal data class DynamicSurfaceSnapshot(
    val surfaceId: String,
    val title: String,
    val iconKey: String,
    val pluginCount: Int,
    val bindingCount: Int,
    val empty: Boolean
)

internal data class PluginUiContributionSnapshot(
    val ownerPluginId: String,
    val tileId: String,
    val title: String,
    val description: String?,
    val screenId: String,
    val surfaceIds: Set<String>
)

internal class DynamicNavigationFacade(
    private val service: SystemJsonServiceV1
) {
    suspend fun surfaces(): List<DynamicSurfaceSnapshot> =
        service.call("list_surfaces").optJSONArray("surfaces").jsonObjects().map(::parseSurface)

    private fun parseSurface(item: JSONObject): DynamicSurfaceSnapshot {
        val bindings = item.optJSONArray("bindings").jsonObjects()
        val pluginCount = bindings.mapNotNull { binding ->
            binding.optString("owner_plugin_id").trim().takeIf { it.isNotEmpty() }
        }.toSet().size
        return DynamicSurfaceSnapshot(
            surfaceId = item.getString("surface_id"),
            title = item.getString("title"),
            iconKey = item.optString("icon_key", "extension"),
            pluginCount = pluginCount,
            bindingCount = item.optInt("binding_count", bindings.size),
            empty = item.optBoolean("empty", bindings.isEmpty())
        )
    }

    suspend fun contributions(): List<PluginUiContributionSnapshot> =
        service.call("list_contributions").optJSONArray("contributions").jsonObjects().map { item ->
            PluginUiContributionSnapshot(
                ownerPluginId = item.getString("owner_plugin_id"),
                tileId = item.getString("tile_id"),
                title = item.getString("title"),
                description = item.optNullableString("description"),
                screenId = item.getString("screen_id"),
                surfaceIds = item.optJSONArray("surface_ids").strings().toSet()
            )
        }

    suspend fun create(title: String? = null): DynamicSurfaceSnapshot {
        val params = JSONObject()
        title?.takeIf { it.isNotBlank() }?.let { params.put("title", it) }
        val item = service.call("create_surface", params).getJSONObject("surface")
        return parseSurface(item)
    }

    suspend fun rename(surfaceId: String, title: String, iconKey: String? = null) {
        val params = JSONObject()
            .put("surface_id", surfaceId)
            .put("title", title)
        iconKey?.let { params.put("icon_key", it) }
        service.call("rename_surface", params)
    }

    suspend fun delete(surfaceId: String, adminPassword: String) {
        service.call("delete_surface", JSONObject()
            .put("surface_id", surfaceId)
            .put("admin_password", adminPassword))
    }

    suspend fun bind(surfaceId: String, tileId: String) {
        service.call("bind_contribution", JSONObject().put("surface_id", surfaceId).put("tile_id", tileId))
    }

    suspend fun unbind(surfaceId: String, tileId: String) {
        service.call("unbind_contribution", JSONObject().put("surface_id", surfaceId).put("tile_id", tileId))
    }
}

internal class AdminSecurityFacade(
    private val service: SystemJsonServiceV1
) {
    @Volatile private var cached = AdminSecuritySnapshot(false, false, AdminAuthFrequency.EVERY_ACTION, true)

    fun snapshot(): AdminSecuritySnapshot = cached
    fun authFrequency(): AdminAuthFrequency = cached.authFrequency
    fun authorizationRequired(): Boolean = cached.authorizationRequired

    suspend fun refresh(): AdminSecuritySnapshot {
        cached = parseAdminStatus(service.call("status"))
        return cached
    }

    suspend fun setup(password: String): AdminSetupResult {
        val result = AdminSetupResult(service.call("setup", JSONObject().put("password", password)).getString("recovery_key"))
        refresh()
        return result
    }

    suspend fun verifyPassword(password: String): Boolean {
        val valid = service.call("verify", JSONObject().put("password", password)).getBoolean("valid")
        if (valid) refresh()
        return valid
    }
    suspend fun changePassword(current: String, next: String): Boolean {
        val changed = service.call("change_password", JSONObject()
            .put("current_password", current)
            .put("new_password", next)).getBoolean("changed")
        if (changed) refresh()
        return changed
    }

    suspend fun recoverPassword(key: String, next: String): Boolean {
        val recovered = service.call("recover_password", JSONObject()
            .put("recovery_key", key)
            .put("new_password", next)).getBoolean("recovered")
        if (recovered) refresh()
        return recovered
    }

    suspend fun regenerateRecoveryKey(password: String): String? {
        val json = service.call("regenerate_recovery_key", JSONObject().put("password", password))
        val key = json.optString("recovery_key").takeIf { json.optBoolean("changed") && it.isNotBlank() }
        if (key != null) refresh()
        return key
    }

    suspend fun changeAuthFrequency(password: String, frequency: AdminAuthFrequency): Boolean {
        val changed = service.call("change_auth_frequency", JSONObject()
            .put("password", password)
            .put("frequency", frequency.name)).getBoolean("changed")
        if (changed) refresh()
        return changed
    }
}

internal class SelfMaintenanceFacade(
    private val service: SystemJsonServiceV1
) {
    suspend fun status(): SelfMaintenanceStatus {
        val json = service.call("status")
        return SelfMaintenanceStatus(
            installedVersion = json.optNullableString("active_version"),
            currentBackupVersion = json.optNullableString("current_backup_version"),
            previousBackupVersion = json.optNullableString("previous_backup_version"),
            canRepair = json.optBoolean("can_repair"),
            canRollback = json.optBoolean("can_rollback")
        )
    }
    suspend fun onlineUpdateStatus(): OnlineUpdateStatus = runCatching {
        val json = service.call("online_update_status")
        OnlineUpdateStatus(
            available = json.optBoolean("available"),
            enabled = json.optBoolean("enabled"),
            reason = json.optNullableString("reason")
        )
    }.getOrElse { OnlineUpdateStatus(reason = "ONLINE_UPDATE_STATUS_UNAVAILABLE") }

    suspend fun onlineUpdate() {
        service.call("online_update")
    }

    suspend fun stageUpgrade(uri: String, sourceName: String? = null) {
        val params = JSONObject().put("uri", uri)
        sourceName?.takeIf { it.isNotBlank() }?.let { params.put("name", it) }
        service.call("stage_upgrade", params)
    }

    suspend fun repair() {
        service.call("repair")
    }

    suspend fun rollback() {
        service.call("rollback")
    }
}

private fun parseAdminStatus(json: JSONObject) = AdminSecuritySnapshot(
    configured = json.getBoolean("configured"),
    recoveryConfigured = json.getBoolean("recovery_configured"),
    authFrequency = AdminAuthFrequency.valueOf(json.getString("auth_frequency")),
    authorizationRequired = json.getBoolean("authorization_required")
)

private fun parseControlSnapshot(json: JSONObject): PluginControlSnapshot {
    val state = json.optJSONObject("state")?.let(::parsePersistentState)
    val manifest = json.optJSONObject("manifest")?.let(::parseManifest)
    val usage = json.optJSONObject("usage") ?: JSONObject()
    return PluginControlSnapshot(
        plugin = PluginSnapshot(
            pluginId = json.getString("plugin_id"),
            versions = json.optJSONArray("versions").strings(),
            persistentState = state,
            activeManifest = manifest,
            installIdentity = json.optJSONObject("install_identity")?.let(::parseInstallIdentity),
            usage = PluginUsageStats(usage.optLong("use_count", 0), usage.optNullableLong("last_used_at")),
            backup = json.optJSONObject("backup")?.let(::parseBackup),
            mountedVersion = json.optNullableString("mounted_version")
        ),
        health = PluginHealthState.valueOf(json.optString("health", "OK"))
    )
}

private fun parseInstallIdentity(json: JSONObject) = PluginInstallIdentity(
    pluginId = json.getString("plugin_id"),
    version = json.getString("version"),
    packageSha256 = json.optString("package_sha256"),
    trustVerdict = json.optString("trust_verdict"),
    signerId = json.optNullableString("signer_id"),
    installedAtEpochMs = json.optLong("installed_at", 0L)
)

private fun parsePersistentState(json: JSONObject) = PluginPersistentState(
    pluginId = json.getString("plugin_id"),
    activeVersion = json.optNullableString("active_version"),
    previousVersion = json.optNullableString("previous_version"),
    enabled = json.optBoolean("enabled"),
    lastState = PluginLifecycleState.valueOf(json.optString("last_state", "INSTALLED")),
    lastError = json.optNullableString("last_error"),
    quarantinedVersions = json.optJSONArray("quarantined_versions").strings().toSet(),
    updatedAtEpochMs = json.optLong("updated_at", 0L)
)

private fun parseBackup(json: JSONObject) = PluginBackupSnapshot(
    pluginId = json.getString("plugin_id"),
    version = json.getString("version"),
    packageSha256 = json.optString("package_sha256"),
    backedUpAtEpochMs = json.optLong("backed_up_at", 0L),
    wasEnabled = json.optBoolean("was_enabled"),
    installed = json.optBoolean("installed"),
    installedVersion = json.optNullableString("installed_version"),
    manifest = parseManifest(json.getJSONObject("manifest"))
)

internal fun parseManifest(json: JSONObject): PluginManifest {
    val display = json.getJSONObject("display")
    val dependencies = json.optJSONObject("dependencies") ?: JSONObject()
    return PluginManifest(
        pluginId = json.getString("plugin_id"),
        version = json.getString("version"),
        display = PluginDisplaySpec(
            display.getString("name"),
            display.optNullableString("description"),
            display.optNullableString("icon_entry")
        ),
        roles = json.optJSONArray("roles").strings().toSet(),
        activationMode = PluginActivationMode.fromWireName(json.optString("activation_mode", "hot")),
        runtime = json.optJSONObject("runtime")?.let {
            PluginRuntimeSpec(it.optString("kind"), it.optNullableString("entry"))
        } ?: PluginRuntimeSpec("unknown"),
        dependencies = PluginDependencies(
            plugins = dependencies.optJSONArray("plugins").jsonObjects().map {
                PluginDependencySpec(it.getString("plugin_id"), it.optNullableString("min_version"))
            },
            services = dependencies.optJSONArray("services").jsonObjects().map {
                PluginServiceDependencySpec(it.getString("service_id"), it.optNullableInt("min_api"))
            }
        ),
        permissions = PluginPermissionSpec(json.optJSONArray("requested_scopes").strings().toSet()),
        provides = PluginProvidesSpec(
            capabilities = json.optJSONArray("capabilities").strings().toSet(),
            services = json.optJSONArray("services").strings().toSet(),
            providers = json.optJSONArray("providers").strings().toSet(),
            extensions = json.optJSONArray("extensions").jsonObjects().map {
                PluginExtensionSpec(it.getString("point"), it.getString("id"), it.optInt("api_version", 1))
            }
        )
    )
}

private fun parseHostSurface(json: JSONObject) = HostSurfaceSnapshot(
    HostSurfaceDefinition(
        id = json.getString("id"),
        title = json.getString("title"),
        detail = json.optString("detail"),
        kind = HostSurfaceKind.valueOf(json.getString("kind")),
        requiredScope = json.optNullableString("required_scope"),
        publicContracts = json.optJSONArray("public_contracts").strings()
    ),
    json.optBoolean("allowed")
)
private fun JSONArray?.strings(): List<String> = buildList {
    val array = this@strings ?: return@buildList
    for (index in 0 until array.length()) {
        array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
    }
}

private fun JSONArray?.jsonObjects(): List<JSONObject> = buildList {
    val array = this@jsonObjects ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let(::add)
    }
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().ifBlank { null }
}

private fun JSONObject.optNullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}

private fun JSONObject.optNullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key)
}
