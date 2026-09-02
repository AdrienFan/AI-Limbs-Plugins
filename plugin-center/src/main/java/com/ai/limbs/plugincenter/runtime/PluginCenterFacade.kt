package com.ai.limbs.plugincenter.runtime

import com.ai.assistance.operit.plugins.system.SystemJsonServiceV1
import com.ai.assistance.operit.plugins.system.SystemPluginHostV1
import com.ai.limbs.plugincenter.model.*
import org.json.JSONArray
import org.json.JSONObject

internal data class PluginInstallOptions(
    val allowUntrustedForDevelopment: Boolean = false,
    val enableAfterInstall: Boolean = false,
    val approvedScopes: Set<String> = emptySet()
)

internal class PluginControlPlaneFacade(
    private val host: SystemPluginHostV1
) {
    private val service get() = host.pluginAdmin
    @Volatile private var cachedSurfaces: List<HostSurfaceSnapshot> = emptyList()
    @Volatile private var cachedInactivity = PluginInactivityPolicySnapshot(false, InactivityThresholdMode.DAYS, 30, 10, 0L)
    @Volatile private var cachedBackupPolicy = PluginBackupPolicySnapshot(false)

    fun developerModeEnabled(): Boolean = host.pluginPlatform.developerModeEnabled()

    fun hostPrimitiveSnapshots(): List<HostPrimitiveSnapshot> =
        host.pluginPlatform.hostPrimitiveSnapshots().map { item ->
            HostPrimitiveSnapshot(
                HostPrimitiveDefinition(
                    item.number, item.id, item.title,
                    PrimitiveValue(item.maturity), PrimitiveValue(item.exposure),
                    item.requestableScope
                ),
                item.policyAllowed,
                item.callable
            )
        }
    suspend fun setDeveloperMode(enabled: Boolean) =
        host.pluginPlatform.setDeveloperMode(enabled)

    suspend fun setHostPrimitiveAllowed(id: String, allowed: Boolean) {
        host.pluginPlatform.setHostPrimitiveAllowed(id, allowed)
        cachedSurfaces = fetchHostSurfaceSnapshots()
    }

    suspend fun snapshots(): List<PluginControlSnapshot> =
        service.call("snapshots").optJSONArray("plugins").jsonObjects().map(::parseControlSnapshot)

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
        service.call("list_surfaces").optJSONArray("surfaces").jsonObjects().map { item ->
            DynamicSurfaceSnapshot(
                surfaceId = item.getString("surface_id"),
                title = item.getString("title"),
                iconKey = item.optString("icon_key", "extension"),
                bindingCount = item.optInt("binding_count", 0),
                empty = item.optBoolean("empty", true)
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
        return DynamicSurfaceSnapshot(
            item.getString("surface_id"), item.getString("title"),
            item.optString("icon_key", "extension"), item.optInt("binding_count", 0), item.optBoolean("empty", true)
        )
    }

    suspend fun rename(surfaceId: String, title: String) {
        service.call("rename_surface", JSONObject().put("surface_id", surfaceId).put("title", title))
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
            usage = PluginUsageStats(usage.optLong("use_count", 0), usage.optNullableLong("last_used_at")),
            backup = json.optJSONObject("backup")?.let(::parseBackup),
            mountedVersion = json.optNullableString("mounted_version")
        ),
        health = PluginHealthState.valueOf(json.optString("health", "OK"))
    )
}
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
