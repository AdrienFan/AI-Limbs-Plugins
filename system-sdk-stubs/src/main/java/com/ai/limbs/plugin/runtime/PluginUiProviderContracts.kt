package com.ai.limbs.plugin.runtime

import android.content.Context
import android.view.View
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Compile-only mirror of the public ordinary-plugin provider contracts consumed by Plugin Center UI.
 *
 * These classes are NOT packaged into Plugin Center. At runtime the parent AI Limbs class loader
 * supplies the canonical contracts. Keeping this module compile-only prevents a duplicate type
 * universe while still letting Plugin Center render providers without depending on Host internals.
 */
/**
 * Compile-only mirror of the Host's opaque UI state/event channel.
 *
 * Plugin Center, not Stable Kernel, defines the JSON component schema carried through this channel.
 */
interface InProcessUiStateProvider {
    val stateJson: StateFlow<String?>
    suspend fun perform(eventId: String, payloadJson: String = "{}"): String
}

interface InProcessPageProvider {
    fun createView(context: Context, sharedUi: InProcessSharedUiHost): View
}

interface InProcessSharedUiHost {
    fun supports(componentId: String): Boolean
    fun createComponent(componentId: String, parametersJson: String = "{}"): View
}

object InProcessSharedUiComponentIds {
    const val CHILD_EXTENSION_INSTALLER = "plugin_center.shared.child_extension_installer"
    const val CHILD_EXTENSION_SELECTOR = "plugin_center.shared.child_extension_selector"
    const val CHILD_EXTENSION_LIST = "plugin_center.shared.child_extension_list"
    const val PAGE_ACCESSORY_SUPPRESSOR = "plugin_center.shared.page_accessory_suppressor"
}

/**
 * Compile-only mirror of a non-destructive Plugin Center UI contribution.
 * The provider supplies instance content/events only; it has no component-definition authority.
 */
interface InProcessUiContributionProvider {
    val documentJson: StateFlow<String?>
    suspend fun perform(eventId: String, payloadJson: String = "{}"): String
}

enum class ChildExtensionLifecycle {
    INSTALLED,
    ACTIVE,
    BLOCKED,
    DISABLED,
    FAILED
}

data class ChildExtensionTarget(
    val parentPluginId: String,
    val point: String,
    val apiVersion: Int
)

data class ChildExtensionSnapshot(
    val extensionId: String,
    val version: String,
    val displayName: String,
    val description: String?,
    val target: ChildExtensionTarget,
    val lifecycle: ChildExtensionLifecycle,
    val enabled: Boolean,
    val roles: Set<String> = emptySet(),
    val useCount: Long = 0L,
    val lastError: String? = null
)

data class ChildUiContributionSnapshot(
    val extensionId: String,
    val target: ChildExtensionTarget,
    val screenId: String,
    val componentId: String,
    val slotId: String,
    val contributionId: String,
    val provider: InProcessUiContributionProvider
)
data class ChildExtensionBackupSnapshot(
    val extensionId: String,
    val version: String,
    val displayName: String,
    val description: String?,
    val target: ChildExtensionTarget,
    val roles: Set<String>,
    val packageSha256: String,
    val backedUpAtEpochMs: Long,
    val wasEnabled: Boolean,
    val installed: Boolean,
    val installedVersion: String? = null
)

/**
 * Compile-only view of the Extension Hub operations needed by Plugin Center's child-extension UI.
 * The complete runtime interface lives in AI Limbs; this mirror intentionally exposes no extra
 * authority beyond the methods the migrated renderer already used while it lived in Host.
 */
interface ExtensionHubService {
    suspend fun install(
        packageFile: File,
        expectedParentPluginId: String?,
        expectedPoint: String?
    ): ChildExtensionSnapshot
    suspend fun uninstall(extensionId: String): Boolean
    suspend fun setEnabled(extensionId: String, enabled: Boolean): ChildExtensionSnapshot
    suspend fun backup(extensionId: String): ChildExtensionBackupSnapshot
    suspend fun restoreBackup(extensionId: String): ChildExtensionSnapshot
    suspend fun deleteBackup(extensionId: String): Boolean
    fun snapshots(): StateFlow<List<ChildExtensionSnapshot>>
    fun snapshotsForPoint(point: String): StateFlow<List<ChildExtensionSnapshot>>
    fun backupSnapshots(): StateFlow<List<ChildExtensionBackupSnapshot>>
    fun uiContributions(): StateFlow<List<ChildUiContributionSnapshot>>
}

object InProcessSystemIds {
    const val EXTENSION_HUB_PROVIDER = "system.extension.hub"
}
