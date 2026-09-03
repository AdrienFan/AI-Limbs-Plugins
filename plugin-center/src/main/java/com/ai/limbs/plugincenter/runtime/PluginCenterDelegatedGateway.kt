package com.ai.limbs.plugincenter.runtime

import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.assistance.operit.plugins.system.SystemPluginServiceCallerV2
import com.ai.assistance.operit.plugins.system.SystemPluginServiceEndpointV2
import org.json.JSONObject

internal class PluginCenterDelegatedGateway(
    private val host: SystemPluginHostV2
) {
    fun publish(): AutoCloseable = host.services.publish(
        id = SERVICE_ID,
        apiVersion = API_VERSION,
        endpoint = SystemPluginServiceEndpointV2(::invoke),
        metadata = mapOf(
            "authority" to "plugin_center",
            "trust_purpose" to TRUST_PURPOSE_CHILD_EXTENSION
        )
    )

    private suspend fun invoke(
        caller: SystemPluginServiceCallerV2,
        operation: String,
        parameters: JSONObject
    ): JSONObject {
        requireHub(caller)
        return when (operation.trim().lowercase()) {
            "verify_child_publisher" -> verifyChildPublisher(parameters)
            "invoke_child_capability" -> invokeChildCapability(caller, parameters)
            else -> error("Unsupported Plugin Center delegated gateway operation: $operation")
        }
    }

    private suspend fun verifyChildPublisher(parameters: JSONObject): JSONObject {
        val signerId = parameters.requiredText("signer_id")
        val payloadBase64 = parameters.requiredText("payload_base64")
        val signatureBase64 = parameters.requiredText("signature_base64")
        val trustResult = host.hostGateway.invokeHostPrimitive(
            TRUST_PRIMITIVE,
            "verify_detached",
            JSONObject()
                .put("signer_id", signerId)
                .put("purpose", TRUST_PURPOSE_CHILD_EXTENSION)
                .put("payload_base64", payloadBase64)
                .put("signature_base64", signatureBase64)
        )
        return JSONObject()
            .put("trusted", trustResult.optBoolean("trusted", false))
            .put("signer_id", signerId)
            .put("purpose", TRUST_PURPOSE_CHILD_EXTENSION)
    }

    private suspend fun invokeChildCapability(
        caller: SystemPluginServiceCallerV2,
        parameters: JSONObject
    ): JSONObject {
        val parentPluginId = parameters.requiredText("parent_plugin_id")
        require(parentPluginId == caller.pluginId) {
            "Delegated parent identity does not match the authenticated caller"
        }
        parameters.requiredText("extension_id")
        val capabilityId = parameters.requiredText("capability_id")
        require(!capabilityId.startsWith("kernel.plugin.trust")) {
            "Child extensions cannot access the raw Kernel trust gateway"
        }
        val capabilityParameters = parameters.optJSONObject("parameters") ?: JSONObject()
        return host.delegatedCapabilities.invokeAsActivePlugin(
            pluginId = caller.pluginId,
            capabilityId = capabilityId,
            parameters = JSONObject(capabilityParameters.toString())
        )
    }

    private fun requireHub(caller: SystemPluginServiceCallerV2) {
        require(caller.pluginId == HUB_PLUGIN_ID && HUB_ROLE in caller.roles) {
            "Plugin Center delegated gateway is reserved for Plugin Extension Hub"
        }
    }

    private fun JSONObject.requiredText(name: String): String =
        optString(name).trim().also { require(it.isNotEmpty()) { "$name is required" } }

    companion object {
        private const val SERVICE_ID = "system.plugin_center.delegated_gateway"
        private const val API_VERSION = 1
        private const val HUB_PLUGIN_ID = "plugin.system.extension_hub"
        private const val HUB_ROLE = "system_extension_hub"
        private const val TRUST_PRIMITIVE = "kernel.plugin.trust@1"
        private const val TRUST_PURPOSE_CHILD_EXTENSION = "child_extension"
    }
}
