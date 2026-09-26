package com.ai.limbs.plugincenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Counts are parent plugins, matching the two main list sections. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConsoleOverview(total: Int, running: Int, attention: Int, failed: Int, disabled: Int) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConsoleStat("插件", total)
        ConsoleStat("运行", running)
        ConsoleStat("未就绪", attention)
        ConsoleStat("故障", failed, error = failed > 0)
        ConsoleStat("停用", disabled)
    }
}

@Composable
private fun ConsoleStat(label: String, count: Int, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Text(
        "$label  $count",
        style = MaterialTheme.typography.labelLarge,
        color = if (error) colors.onErrorContainer else colors.onSurfaceVariant,
        modifier = Modifier
            .background(if (error) colors.errorContainer else colors.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/** Presentation only: all permission, update and confirmation callbacks stay with the owner. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConsoleCardActions(
    jumpEnabled: Boolean, onJump: () -> Unit, onOpen: () -> Unit,
    enabled: Boolean, onEnable: () -> Unit, onDisable: () -> Unit,
    canBackup: Boolean, onBackup: () -> Unit, onUpdate: () -> Unit,
    onlineUpgradeEnabled: Boolean, onOnlineUpgrade: () -> Unit,
    onUninstall: (() -> Unit)?
) {
    var moreExpanded by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = onJump, enabled = jumpEnabled) { Text("打开") }
        TextButton(onClick = onOpen) { Text("详情") }
        TextButton(onClick = if (enabled) onDisable else onEnable) {
            Text(if (enabled) "禁用" else "启用")
        }
        Box {
            TextButton(onClick = { moreExpanded = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                    Text("更多")
                }
            }
            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                DropdownMenuItem(text = { Text("在线更新") }, enabled = onlineUpgradeEnabled,
                    onClick = { moreExpanded = false; onOnlineUpgrade() })
                DropdownMenuItem(text = { Text("本地更新") },
                    onClick = { moreExpanded = false; onUpdate() })
                DropdownMenuItem(text = { Text("备份当前版本") }, enabled = canBackup,
                    onClick = { moreExpanded = false; onBackup() })
                if (onUninstall != null) {
                    Divider()
                    DropdownMenuItem(text = { Text("卸载插件", color = MaterialTheme.colorScheme.error) },
                        onClick = { moreExpanded = false; onUninstall() })
                }
            }
        }
    }
}
