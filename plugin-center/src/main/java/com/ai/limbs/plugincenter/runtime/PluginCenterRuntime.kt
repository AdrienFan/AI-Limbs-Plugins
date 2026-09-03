package com.ai.limbs.plugincenter.runtime

import com.ai.assistance.operit.plugins.system.SystemPluginHostV2

internal object PluginCenterRuntime {
    private var hostRef: SystemPluginHostV2? = null

    lateinit var controlPlane: PluginControlPlaneFacade
        private set
    lateinit var adminSecurity: AdminSecurityFacade
        private set
    lateinit var selfMaintenance: SelfMaintenanceFacade
        private set
    lateinit var navigation: DynamicNavigationFacade
        private set

    fun attach(host: SystemPluginHostV2) {
        check(host.hostAbi == 2) { "Plugin Center requires AI Limbs Host ABI 2" }
        hostRef = host
        controlPlane = PluginControlPlaneFacade(host)
        adminSecurity = AdminSecurityFacade(host.adminSecurity)
        selfMaintenance = SelfMaintenanceFacade(host.selfMaintenance)
        navigation = DynamicNavigationFacade(host.navigation)
    }

    fun detach() {
        hostRef = null
    }
}
