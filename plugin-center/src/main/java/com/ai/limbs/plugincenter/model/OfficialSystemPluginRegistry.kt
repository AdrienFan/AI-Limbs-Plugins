package com.ai.limbs.plugincenter.model

/**
 * Presentation classification supplied by the Stable Kernel's trusted identity registry.
 * Plugin Center does not maintain a second plugin-ID allowlist.
 */
internal object OfficialSystemPluginRegistry {
    fun isSystemPlugin(snapshot: PluginControlSnapshot): Boolean =
        snapshot.officialIdentityTrusted
}
