package com.ai.limbs.plugincenter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ConsolePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        content = content
    )
}

@Composable
internal fun ConsoleSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ConsolePanel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun ConsoleSectionHeading(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ConsoleDetailPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 920.dp).fillMaxSize().verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextButton(onClick = onBack) { Text("← 总控台") }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            content()
            Spacer(Modifier.height(12.dp))
        }
        ScrollStateScrollIndicator(scrollState, Modifier.align(Alignment.CenterEnd))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConsoleAdaptiveGroup(content: @Composable FlowRowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = if (maxWidth >= 720.dp) 2 else 1,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
internal fun UninstallConfirmationDialog(
    title: String,
    displayName: String,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit
) {
    var removeData by remember(displayName) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("确定卸载 $displayName？")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeData, onCheckedChange = { removeData = it })
                    Text("同时清除托管数据", style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    if (removeData) "将删除该插件独立数据目录中的内容，无法撤销。"
                    else "保留独立数据目录，重装后可继续使用原有数据。",
                    color = if (removeData) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "备份、导出文件及旧插件写入宿主共享配置的数据不在清理范围内。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            DangerTextButton(onClick = { onConfirm(removeData) }) {
                Text(if (removeData) "卸载并清除" else "卸载并保留数据")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
