package com.ai.limbs.plugincenter.runtime

import com.ai.assistance.operit.plugins.system.SystemPageSlotIdsV1
import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.assistance.operit.plugins.system.SystemPluginProviderBindingV2
import com.ai.assistance.operit.plugins.system.SystemPluginServiceCallerV2
import com.ai.assistance.operit.plugins.system.SystemPluginServiceEndpointV2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class PageSlotActionRegistration(
    val ownerPluginId: String,
    val actionId: String,
    val targetPageId: String,
    val slotId: String,
    val providerId: String,
    val screenId: String,
    val iconKey: String,
    val contentDescription: String,
    val priority: Int
)

internal object PluginCenterPageSlotActions {
    private val lock = Any()
    private val mutableActions = MutableStateFlow<List<PageSlotActionRegistration>>(emptyList())
    val actions: StateFlow<List<PageSlotActionRegistration>> = mutableActions.asStateFlow()

    fun upsert(action: PageSlotActionRegistration) = synchronized(lock) {
        mutableActions.value = (mutableActions.value.filterNot {
            it.ownerPluginId == action.ownerPluginId && it.actionId == action.actionId
        } + action).sortedWith(
            compareByDescending<PageSlotActionRegistration> { it.priority }
                .thenBy { it.ownerPluginId }
                .thenBy { it.actionId }
        )
    }

    fun remove(ownerPluginId: String, actionId: String) = synchronized(lock) {
        mutableActions.value = mutableActions.value.filterNot {
            it.ownerPluginId == ownerPluginId && it.actionId == actionId
        }
    }

    fun clear() = synchronized(lock) {
        mutableActions.value = emptyList()
    }
}

internal class PluginCenterUiAccessoryService(
    private val host: SystemPluginHostV2
) {
    fun publish(): AutoCloseable {
        val handle = host.services.publish(
            id = SERVICE_ID,
            apiVersion = API_VERSION,
            endpoint = SystemPluginServiceEndpointV2(::invoke),
            metadata = mapOf("authority" to "plugin_center", "kind" to "page_slot_registry")
        )
        restoreDeclaredPageSlotActions()
        return AutoCloseable {
            runCatching { handle.close() }
            PluginCenterPageSlotActions.clear()
        }
    }

    private suspend fun invoke(
        caller: SystemPluginServiceCallerV2,
        operation: String,
        parameters: JSONObject
    ): JSONObject = when (operation.trim().lowercase()) {
        "register_page_slot_action" -> register(caller, parameters)
        "unregister_page_slot_action" -> unregister(caller, parameters)
        else -> error("Unsupported Plugin Center page slot operation: $operation")
    }

    private fun register(
        caller: SystemPluginServiceCallerV2,
        parameters: JSONObject
    ): JSONObject {
        val providerId = parameters.requiredText("provider_id")
        val binding = host.providers.resolve(providerId)
            ?: error("Page slot provider is unavailable: $providerId")
        require(binding.ownerPluginId == caller.pluginId) {
            "Page slot provider $providerId is not owned by ${caller.pluginId}"
        }
        val action = registrationFor(binding, parameters)
        PluginCenterPageSlotActions.upsert(action)
        return registrationResult(action)
    }

    private fun unregister(
        caller: SystemPluginServiceCallerV2,
        parameters: JSONObject
    ): JSONObject {
        val actionId = parameters.requiredText("action_id")
        PluginCenterPageSlotActions.remove(caller.pluginId, actionId)
        return JSONObject()
            .put("registered", false)
            .put("action_id", actionId)
    }

    private fun restoreDeclaredPageSlotActions() {
        host.providers.snapshot().forEach { binding ->
            if (binding.metadata["kind"] != "plugin_page") return@forEach
            val raw = binding.metadata[PAGE_SLOT_ACTIONS_METADATA].orEmpty().trim()
            if (raw.isEmpty()) return@forEach
            val declarations = runCatching { JSONArray(raw) }.getOrNull() ?: return@forEach
            for (index in 0 until declarations.length()) {
                val declaration = declarations.optJSONObject(index) ?: continue
                runCatching { registrationFor(binding, declaration) }
                    .onSuccess(PluginCenterPageSlotActions::upsert)
            }
        }
    }

    private fun registrationFor(
        binding: SystemPluginProviderBindingV2,
        parameters: JSONObject
    ): PageSlotActionRegistration {
        require(binding.metadata["kind"] == "plugin_page") {
            "Page slot action provider must be a plugin_page provider"
        }
        val screenId = binding.metadata["screen_id"].orEmpty().trim()
        require(screenId.isNotEmpty()) { "Page slot provider has no screen_id" }
        val actionId = parameters.requiredText("action_id")
        val targetPageId = parameters.requiredText("target_page_id")
        require(TARGET_PAGE_PREFIXES.any { prefix -> targetPageId.startsWith(prefix) }) {
            "Unsupported target_page_id: $targetPageId"
        }
        val slotId = parameters.requiredText("slot_id").lowercase()
        require(slotId in ACTION_SLOT_IDS) { "Unsupported action slot: $slotId" }
        val iconKey = parameters.optString("icon_key", "extension").trim().lowercase()
        val description = parameters.optString("content_description", actionId).trim()
            .ifEmpty { actionId }
        val priority = parameters.optInt("priority", 0).coerceIn(-1000, 1000)
        return PageSlotActionRegistration(
            ownerPluginId = binding.ownerPluginId,
            actionId = actionId,
            targetPageId = targetPageId,
            slotId = slotId,
            providerId = binding.id,
            screenId = screenId,
            iconKey = iconKey,
            contentDescription = description,
            priority = priority
        )
    }

    private fun registrationResult(action: PageSlotActionRegistration): JSONObject =
        JSONObject()
            .put("registered", true)
            .put("action_id", action.actionId)
            .put("target_page_id", action.targetPageId)
            .put("slot_id", action.slotId)
            .put("screen_id", action.screenId)

    private fun JSONObject.requiredText(name: String): String =
        optString(name).trim().also { require(it.isNotEmpty()) { "$name is required" } }

    companion object {
        const val SERVICE_ID = "system.plugin_center.ui_accessories"
        const val API_VERSION = 1
        const val PAGE_SLOT_ACTIONS_METADATA = "ai_limbs.page_slot_actions.v1"

        private val ACTION_SLOT_IDS = setOf(
            SystemPageSlotIdsV1.TOP_BAR_START,
            SystemPageSlotIdsV1.TOP_BAR_END
        )
        private val TARGET_PAGE_PREFIXES = listOf("host:", "plugin:", "dynamic:", "system:")
    }
}
