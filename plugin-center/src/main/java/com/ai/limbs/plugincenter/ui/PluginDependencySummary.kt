package com.ai.limbs.plugincenter.ui

import com.ai.limbs.plugincenter.model.ChildExtensionInventory
import com.ai.limbs.plugincenter.model.OfficialSystemPluginRegistry
import com.ai.limbs.plugincenter.model.PluginControlSnapshot

private const val EXTENSION_HUB_PLUGIN_ID = "plugin.system.extension_hub"

internal data class PluginDependencySummary(
    val parentPluginCount: Int,
    val childPluginCount: Int?
)

internal fun isSystemPlugin(snapshot: PluginControlSnapshot): Boolean =
    OfficialSystemPluginRegistry.isSystemPlugin(snapshot)

internal fun dependencySummary(
    target: PluginControlSnapshot,
    allParents: List<PluginControlSnapshot>,
    childInventory: ChildExtensionInventory
): PluginDependencySummary {
    val targetId = target.plugin.pluginId
    val providedServices = target.plugin.activeManifest?.provides?.services.orEmpty()
    val parentPluginCount = allParents.count { candidate ->
        if (candidate.plugin.pluginId == targetId) {
            false
        } else {
            candidate.plugin.activeManifest?.dependencies?.let { dependencies ->
                dependencies.plugins.any { it.pluginId == targetId } ||
                    dependencies.services.any { it.serviceId in providedServices }
            } == true
        }
    }
    val childPluginCount = if (childInventory.available) {
        childInventory.extensions.count { child ->
            targetId == child.parentPluginId || targetId == EXTENSION_HUB_PLUGIN_ID
        }
    } else {
        null
    }
    return PluginDependencySummary(parentPluginCount, childPluginCount)
}

internal fun dependencySummaryText(summary: PluginDependencySummary): String =
    "被依赖：父级插件 ${summary.parentPluginCount} 个 · 子插件 " +
        (summary.childPluginCount?.let { "$it 个" } ?: "不可用")
