package com.ai.limbs.plugincenter.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
            (action.actionKind == "open_page" && current.metadata["screen_id"] != action.screenId) ||
            (action.actionKind == "overlay" && current.metadata["overlay_enabled"] != "true")
        ) {
            return
        }
        val scope = rememberCoroutineScope()
        var badgeCount by remember(action.actionId) { mutableIntStateOf(0) }
        LaunchedEffect(action.badgeCapabilityId) {
            if (action.badgeCapabilityId.isNotEmpty()) {
                while (isActive) {
                    try {
                        val result = host.hostGateway.invokeHostPrimitive(
                            "host.capability@1",
                            "invoke",
                            JSONObject().put("capability_id", action.badgeCapabilityId)
                        )
                        badgeCount = result.getInt("unread_count")
                    } catch (error: Exception) {
                        android.util.Log.w("PluginCenterPageSlot", "Badge read failed", error)
                    }
                    delay(1_500L)
                }
            }
        }
        IconButton(
            enabled = context.enabled,
            onClick = {
                scope.launch {
                    runCatching {
                        if (action.actionKind == "overlay") {
                            host.hostGateway.invokeHostPrimitive(
                                "host.window.overlay@1",
                                "create",
                                JSONObject()
                                    .put("overlay_id", action.overlayId)
                                    .put("provider_id", action.providerId)
                            )
                        } else {
                            host.hostGateway.invokeHostPrimitive(
                                "host.ui.surface@1",
                                "open",
                                JSONObject().put("screen_id", action.screenId)
                            )
                        }
                    }
                }
            }
        ) {
            Box {
                Icon(
                    imageVector = when (action.iconKey) {
                        "terminal" -> Icons.Default.Terminal
                        "chat" -> Icons.Default.ChatBubble
                        else -> Icons.Default.Extension
                    },
                    contentDescription = action.contentDescription,
                    tint = Color(context.contentColorArgb ?: DEFAULT_CONTENT_COLOR)
                )
                if (badgeCount > 0) {
                    Text(
                        text = badgeCount.coerceAtMost(99).toString(),
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp)
                            .background(Color.Red, CircleShape),
                        color = Color.White
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_VISIBLE_ACTIONS = 3
        const val DEFAULT_CONTENT_COLOR = -1
    }
}
