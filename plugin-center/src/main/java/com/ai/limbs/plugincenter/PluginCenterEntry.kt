package com.ai.limbs.plugincenter

import com.ai.assistance.operit.plugins.system.SystemPluginEntryV1
import com.ai.assistance.operit.plugins.system.SystemPluginHostV1
import com.ai.assistance.operit.plugins.system.SystemToolboxEntryV1
import com.ai.assistance.operit.plugins.system.SystemUiNavigatorV1
import com.ai.assistance.operit.plugins.system.SystemUiPageV1
import com.ai.limbs.plugincenter.runtime.PluginCenterRuntime
import com.ai.limbs.plugincenter.ui.PluginCenterScreen

class PluginCenterEntry : SystemPluginEntryV1 {
    override fun mount(host: SystemPluginHostV1): AutoCloseable {
        PluginCenterRuntime.attach(host)
        val uiHandle = host.ui.registerToolboxEntry(
            SystemToolboxEntryV1(
                id = "plugin_center.main",
                title = "Plugin Center",
                description = "AI Limbs 插件与系统接口管理中心",
                iconKey = "extension",
                page = PluginCenterPage()
            )
        )
        return AutoCloseable {
            runCatching { uiHandle.close() }
            PluginCenterRuntime.detach()
        }
    }
}
private class PluginCenterPage : SystemUiPageV1 {
    @androidx.compose.runtime.Composable
    override fun Content(navigator: SystemUiNavigatorV1) {
        PluginCenterScreen(
            onBack = { navigator.backToToolbox() },
            navigator = navigator
        )
    }
}
