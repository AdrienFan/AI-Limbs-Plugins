package com.ai.limbs.plugincenter

import com.ai.assistance.operit.plugins.system.SystemPluginEntryV1
import com.ai.assistance.operit.plugins.system.SystemPluginHostV1
import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.assistance.operit.plugins.system.SystemToolboxEntryV1
import com.ai.assistance.operit.plugins.system.SystemUiNavigatorV1
import com.ai.assistance.operit.plugins.system.SystemUiPageV1
import com.ai.limbs.plugincenter.runtime.PluginCenterDelegatedGateway
import com.ai.limbs.plugincenter.runtime.PluginCenterRuntime
import com.ai.limbs.plugincenter.ui.PluginCenterPluginUiRenderer
import com.ai.limbs.plugincenter.ui.PluginCenterScreen

class PluginCenterEntry : SystemPluginEntryV1 {
    override fun mount(host: SystemPluginHostV1): AutoCloseable {
        val hostV2 = host as? SystemPluginHostV2
            ?: error("Plugin Center requires AI Limbs System Host ABI 2")
        PluginCenterRuntime.attach(hostV2)
        // Plugin Center is the semantic owner of ordinary-plugin UI. Register this before the rest
        // of the control plane so Host health checks never observe a half-mounted UI runtime.
        val rendererHandle = try {
            hostV2.ui.registerPluginSurfaceRenderer(PluginCenterPluginUiRenderer(hostV2))
        } catch (error: Throwable) {
            PluginCenterRuntime.detach()
            throw error
        }
        val serviceHandle = try {
            PluginCenterDelegatedGateway(hostV2).publish()
        } catch (error: Throwable) {
            runCatching { rendererHandle.close() }
            PluginCenterRuntime.detach()
            throw error
        }
        val uiHandle = try {
            hostV2.ui.registerToolboxEntry(
                SystemToolboxEntryV1(
                    id = "plugin_center.main",
                    title = "Plugin Center",
                    description = "AI Limbs 插件与系统接口管理中心",
                    iconKey = "extension",
                    page = PluginCenterPage()
                )
            )
        } catch (error: Throwable) {
            runCatching { serviceHandle.close() }
            runCatching { rendererHandle.close() }
            PluginCenterRuntime.detach()
            throw error
        }
        return AutoCloseable {
            runCatching { uiHandle.close() }
            runCatching { serviceHandle.close() }
            runCatching { rendererHandle.close() }
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
