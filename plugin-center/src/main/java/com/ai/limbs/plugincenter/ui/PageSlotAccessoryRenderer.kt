package com.ai.limbs.plugincenter.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import com.ai.assistance.operit.plugins.system.SystemPageSlotContextV1
import com.ai.assistance.operit.plugins.system.SystemPageSlotRendererV1
import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.limbs.plugincenter.runtime.PageSlotActionRegistration
import com.ai.limbs.plugincenter.runtime.PluginCenterPageSlotActions
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class PluginCenterPageSlotRenderer(
    private val host: SystemPluginHostV2
) : SystemPageSlotRendererV1 {
    @Composable
    override fun Render(context: SystemPageSlotContextV1) {
        val actions by PluginCenterPageSlotActions.actions.collectAsState()
        actions
            .filter { it.targetPageId == context.page.pageId && it.slotId == context.slotId }
            .take(MAX_VISIBLE_ACTIONS)
            .forEach { action ->
                ActionButton(action = action, context = context)
            }
    }

    @Composable
    private fun ActionButton(
        action: PageSlotActionRegistration,
        context: SystemPageSlotContextV1
    ) {
        val binding by host.providers.observe(action.providerId)
            .collectAsState(initial = host.providers.resolve(action.providerId))
        val current = binding
        if (current?.ownerPluginId != action.ownerPluginId ||
            current.metadata["screen_id"] != action.screenId
        ) {
            return
        }
        val scope = rememberCoroutineScope()
        IconButton(
            enabled = context.enabled,
            onClick = {
                scope.launch {
                    runCatching {
                        host.hostGateway.invokeHostPrimitive(
                            "host.ui.surface@1",
                            "open",
                            JSONObject().put("screen_id", action.screenId)
                        )
                    }
                }
            }
        ) {
            Icon(
                imageVector = when (action.iconKey) {
                    "terminal" -> Icons.Default.Terminal
                    else -> Icons.Default.Extension
                },
                contentDescription = action.contentDescription,
                tint = Color(context.contentColorArgb ?: DEFAULT_CONTENT_COLOR)
            )
        }
    }

    private companion object {
        const val MAX_VISIBLE_ACTIONS = 3
        const val DEFAULT_CONTENT_COLOR = -1
    }
}
