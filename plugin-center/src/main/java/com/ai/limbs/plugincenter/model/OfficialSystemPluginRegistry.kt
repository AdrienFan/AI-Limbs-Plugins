package com.ai.limbs.plugincenter.model

/**
 * Plugin Center-owned presentation and management classification for official system plugins.
 *
 * Manifest roles only describe requested functions. A package is classified as a system plugin
 * only after Kernel verification persisted a trusted identity for an exact official plugin ID.
 */
internal object OfficialSystemPluginRegistry {
    private const val OFFICIAL_SIGNER_ID = "ai-limbs-parent-plugin-dev-v1"

    private val officialPluginIds = setOf(
        "plugin.system.extension_hub",
        "plugin.system.bridge",
        "plugin.system.developer_guide",
        "plugin.system.packager"
    )

    fun isSystemPlugin(snapshot: PluginControlSnapshot): Boolean {
        val plugin = snapshot.plugin
        val identity = plugin.installIdentity ?: return false
        return plugin.pluginId in officialPluginIds &&
            identity.pluginId == plugin.pluginId &&
            identity.version == plugin.persistentState?.activeVersion &&
            identity.trustVerdict == "TRUSTED" &&
            identity.signerId == OFFICIAL_SIGNER_ID
    }
}
