package com.ai.assistance.operit.plugins.system

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

data class SystemHostPrimitiveDescriptor(
    val number: Int,
    val id: String,
    val title: String,
    val description: String,
    val boundary: String,
    val maturity: String,
    val exposure: String,
    val requestableScope: Boolean,
    val policyAllowed: Boolean?,
    val callable: Boolean
)

data class SystemHostPrimitiveAvailability(
    val id: String,
    val operation: String?,
    val known: Boolean,
    val callable: Boolean,
    val available: Boolean,
    val reasonCode: String? = null,
    val reason: String? = null
)

interface SystemHostGatewayV1 {
    fun listHostPrimitives(): List<SystemHostPrimitiveDescriptor>
    fun describeHostPrimitive(id: String): SystemHostPrimitiveDescriptor?
    fun listHostPrimitiveOperations(id: String): List<String>
    fun availabilityHostPrimitive(id: String, operation: String? = null): SystemHostPrimitiveAvailability
    suspend fun invokeHostPrimitive(id: String, parameters: JSONObject = JSONObject()): JSONObject
    suspend fun invokeHostPrimitive(id: String, operation: String, parameters: JSONObject = JSONObject()): JSONObject
}

interface PluginPlatformControlV1 {
    fun developerModeEnabled(): Boolean
    fun developerDiscoveryEnabled(): Boolean
    fun hostPrimitiveSnapshots(): List<SystemHostPrimitiveDescriptor>
    suspend fun setDeveloperMode(enabled: Boolean)
    suspend fun setDeveloperDiscoveryEnabled(enabled: Boolean)
    suspend fun setHostPrimitiveAllowed(primitiveId: String, allowed: Boolean)
}

interface SystemJsonServiceV1 {
    suspend fun call(operation: String, parameters: JSONObject = JSONObject()): JSONObject
}

interface SystemUiNavigatorV1 {
    fun backToToolbox(message: String? = null)
}

interface SystemUiPageV1 {
    @Composable
    fun Content(navigator: SystemUiNavigatorV1)
}

data class SystemToolboxEntryV1(
    val id: String,
    val title: String,
    val description: String?,
    val iconKey: String = "extension",
    val page: SystemUiPageV1
)

interface SystemUiHostV1 {
    fun registerToolboxEntry(entry: SystemToolboxEntryV1): AutoCloseable
}

/**
 * Opaque ordinary-plugin screen envelope.  Plugin Center owns [schemaId] and [documentJson]
 * semantics; Host owns only identity, routing and lifecycle.
 */
fun interface SystemPluginUiActionsV2 {
    /** Host-bound capability invocation for the owner of the current UI surface. */
    suspend fun invokeCapability(capabilityId: String, parameters: JSONObject = JSONObject()): JSONObject
}

data class SystemPluginUiSurfaceV2(
    val ownerPluginId: String,
    val screenId: String,
    val title: String,
    val description: String?,
    val schemaId: String,
    val documentJson: String,
    val actions: SystemPluginUiActionsV2
)

/**
 * Single renderer contract used to move ordinary-plugin component semantics out of Stable Kernel.
 */
interface SystemPluginUiRendererV2 {
    @Composable
    fun Render(surface: SystemPluginUiSurfaceV2)
}

interface SystemUiHostV2 : SystemUiHostV1 {
    fun registerPluginSurfaceRenderer(renderer: SystemPluginUiRendererV2): AutoCloseable
}

data class SystemPluginProviderBindingV2(
    val ownerPluginId: String,
    val id: String,
    val metadata: Map<String, String>,
    val payload: Any?
)

/** Read-only provider discovery for Plugin Center UI components. */
interface SystemPluginProviderDirectoryV2 {
    fun resolve(id: String): SystemPluginProviderBindingV2?
    fun snapshot(): List<SystemPluginProviderBindingV2>
    fun observe(id: String): Flow<SystemPluginProviderBindingV2?>
}

interface SystemPluginHostV1 {
    val hostAbi: Int
    val hostGateway: SystemHostGatewayV1
    val pluginPlatform: PluginPlatformControlV1
    val pluginAdmin: SystemJsonServiceV1
    val adminSecurity: SystemJsonServiceV1
    val selfMaintenance: SystemJsonServiceV1
    val navigation: SystemJsonServiceV1
    val ui: SystemUiHostV1
}

data class SystemPluginServiceCallerV2(
    val pluginId: String,
    val roles: Set<String>,
    val grantedScopes: Set<String>
)

fun interface SystemPluginServiceEndpointV2 {
    suspend fun invoke(
        caller: SystemPluginServiceCallerV2,
        operation: String,
        parameters: JSONObject
    ): JSONObject
}

interface SystemPluginServicePublisherV2 {
    fun publish(
        id: String,
        apiVersion: Int,
        endpoint: SystemPluginServiceEndpointV2,
        metadata: Map<String, String> = emptyMap()
    ): AutoCloseable
}

interface SystemPluginDelegatedCapabilityInvokerV2 {
    suspend fun invokeAsActivePlugin(
        pluginId: String,
        capabilityId: String,
        parameters: JSONObject = JSONObject()
    ): JSONObject
}

interface SystemPluginHostV2 : SystemPluginHostV1 {
    override val ui: SystemUiHostV2
    val services: SystemPluginServicePublisherV2
    val delegatedCapabilities: SystemPluginDelegatedCapabilityInvokerV2
    val providers: SystemPluginProviderDirectoryV2
}

interface SystemPluginEntryV1 {
    fun mount(host: SystemPluginHostV1): AutoCloseable
}
