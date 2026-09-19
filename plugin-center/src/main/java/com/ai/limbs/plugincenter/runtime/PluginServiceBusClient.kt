package com.ai.limbs.plugincenter.runtime

import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import org.json.JSONObject

internal object PluginServiceBusClient {
    private const val PRIMITIVE_ID = "host.plugin.service@1"

    suspend fun describe(host: SystemPluginHostV2, serviceId: String): JSONObject? =
        runCatching {
            host.hostGateway.invokeHostPrimitive(
                PRIMITIVE_ID,
                "describe",
                JSONObject().put("service_id", serviceId)
            ).optJSONObject("service")
        }.getOrNull()

    suspend fun available(
        host: SystemPluginHostV2,
        serviceId: String,
        minApi: Int = 1,
        expectedOwnerPluginId: String? = null
    ): Boolean {
        val service = describe(host, serviceId) ?: return false
        if (!service.optBoolean("callable", false)) return false
        if (service.optInt("api_version", 0) < minApi) return false
        if (expectedOwnerPluginId != null && service.optString("owner_plugin_id") != expectedOwnerPluginId) {
            return false
        }
        return true
    }

    suspend fun call(
        host: SystemPluginHostV2,
        serviceId: String,
        operation: String,
        parameters: JSONObject = JSONObject()
    ): JSONObject =
        host.hostGateway.invokeHostPrimitive(
            PRIMITIVE_ID,
            "call",
            JSONObject()
                .put("service_id", serviceId)
                .put("operation", operation)
                .put("parameters", JSONObject(parameters.toString()))
        )
}
