package com.ai.assistance.operit.plugins.system

import androidx.compose.runtime.Composable
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

interface SystemPluginEntryV1 {
    fun mount(host: SystemPluginHostV1): AutoCloseable
}
