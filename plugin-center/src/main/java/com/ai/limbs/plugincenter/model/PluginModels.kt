package com.ai.limbs.plugincenter.model

enum class PluginHealthState { OK, ATTENTION, FAILED }
enum class PluginLifecycleState {
    INSTALLED, MOUNTING, ACTIVE, BLOCKED, UNMOUNTING,
    DISABLED, PENDING_RESTART, FAILED, QUARANTINED
}
enum class PluginActivationMode(val wireName: String) {
    HOT("hot"),
    RESTART_REQUIRED("restart_required"),
    COLD_EXTENSION("cold_extension");

    companion object {
        fun fromWireName(value: String): PluginActivationMode =
            entries.firstOrNull { it.wireName == value } ?: HOT
    }
}
enum class AdminAuthFrequency {
    EVERY_ACTION, ONCE_PER_APP_SESSION, NEVER
}
enum class InactivityThresholdMode { DAYS, TEST_SECONDS }
enum class HostSurfaceKind {
    EXTENSION_POINT, HOST_CAPABILITY, HOST_PROVIDER,
    PLUGIN_CAPABILITY_BUS, PLUGIN_SERVICE_BUS, PLUGIN_PROVIDER_BUS
}

data class PluginDisplaySpec(
    val name: String,
    val description: String? = null,
    val iconEntry: String? = null
)
data class PluginRuntimeSpec(val kind: String, val entry: String? = null)
data class PluginPermissionSpec(val requestedScopes: Set<String> = emptySet())
data class PluginExtensionSpec(val point: String, val id: String, val apiVersion: Int)
data class PluginProvidesSpec(
    val capabilities: Set<String> = emptySet(),
    val services: Set<String> = emptySet(),
    val providers: Set<String> = emptySet(),
    val extensions: List<PluginExtensionSpec> = emptyList()
)
data class PluginDependencySpec(val pluginId: String, val minVersion: String? = null)
data class PluginServiceDependencySpec(val serviceId: String, val minApi: Int? = null)
data class PluginDependencies(
    val plugins: List<PluginDependencySpec> = emptyList(),
    val services: List<PluginServiceDependencySpec> = emptyList()
)

data class PluginManifest(
    val pluginId: String,
    val version: String,
    val display: PluginDisplaySpec,
    val roles: Set<String>,
    val activationMode: PluginActivationMode,
    val runtime: PluginRuntimeSpec,
    val dependencies: PluginDependencies,
    val permissions: PluginPermissionSpec,
    val provides: PluginProvidesSpec
)
data class PluginPersistentState(
    val pluginId: String,
    val activeVersion: String?,
    val previousVersion: String?,
    val enabled: Boolean,
    val lastState: PluginLifecycleState,
    val lastError: String?,
    val quarantinedVersions: Set<String>,
    val updatedAtEpochMs: Long
)
data class PluginUsageStats(val useCount: Long, val lastUsedAtEpochMs: Long?)
data class PluginBackupSnapshot(
    val pluginId: String,
    val version: String,
    val packageSha256: String,
    val backedUpAtEpochMs: Long,
    val wasEnabled: Boolean,
    val installed: Boolean,
    val installedVersion: String?,
    val manifest: PluginManifest
)

data class PluginSnapshot(
    val pluginId: String,
    val versions: List<String>,
    val persistentState: PluginPersistentState?,
    val activeManifest: PluginManifest?,
    val usage: PluginUsageStats,
    val backup: PluginBackupSnapshot?,
    val mountedVersion: String?
)
data class PluginControlSnapshot(
    val plugin: PluginSnapshot,
    val health: PluginHealthState,
    val bindings: List<String> = emptyList()
)
data class HostPrimitiveDefinition(
    val number: Int,
    val id: String,
    val title: String,
    val maturity: PrimitiveValue,
    val exposure: PrimitiveValue,
    val requestableScope: Boolean
)
data class PrimitiveValue(val name: String)
data class HostPrimitiveSnapshot(
    val definition: HostPrimitiveDefinition,
    val policyAllowed: Boolean?,
    val callable: Boolean
)

data class HostSurfaceDefinition(
    val id: String,
    val title: String,
    val detail: String,
    val kind: HostSurfaceKind,
    val requiredScope: String?,
    val publicContracts: List<String>
)
data class HostSurfaceSnapshot(val definition: HostSurfaceDefinition, val allowed: Boolean)
data class PluginInactivityPolicySnapshot(
    val enabled: Boolean,
    val mode: InactivityThresholdMode,
    val days: Int,
    val testSeconds: Int,
    val enabledAtEpochMs: Long
)
data class PluginBackupPolicySnapshot(val enabled: Boolean)
data class AdminSecuritySnapshot(
    val configured: Boolean,
    val recoveryConfigured: Boolean,
    val authFrequency: AdminAuthFrequency,
    val authorizationRequired: Boolean
)
data class AdminSetupResult(val recoveryKey: String)
data class PluginImportCandidate(
    val uri: String,
    val sourceName: String,
    val manifest: PluginManifest,
    val updateTargetId: String? = null
)
data class SelfMaintenanceStatus(
    val installedVersion: String?,
    val currentBackupVersion: String?,
    val previousBackupVersion: String?,
    val canRepair: Boolean,
    val canRollback: Boolean
)

object PluginInactivityPolicyStore {
    const val MIN_DAYS = 1
    const val MAX_DAYS = 3650
    const val MIN_TEST_SECONDS = 5
    const val MAX_TEST_SECONDS = 3600
}
object PluginBackupPolicyStore {
    const val HIGH_FREQUENCY_USE_COUNT = 10L
}
