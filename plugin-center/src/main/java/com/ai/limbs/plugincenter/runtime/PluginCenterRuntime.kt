package com.ai.limbs.plugincenter.runtime

import com.ai.assistance.operit.plugins.system.SystemPluginHostV1

internal object PluginCenterRuntime {
    private var hostRef: SystemPluginHostV1? = null

    lateinit var controlPlane: PluginControlPlaneFacade
        private set
    lateinit var adminSecurity: AdminSecurityFacade
        private set
    lateinit var selfMaintenance: SelfMaintenanceFacade
        private set
    lateinit var navigation: DynamicNavigationFacade
        private set

    fun attach(host: SystemPluginHostV1) {
        check(host.hostAbi == 1) { "Plugin Center requires AI Limbs Host ABI 1" }
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
