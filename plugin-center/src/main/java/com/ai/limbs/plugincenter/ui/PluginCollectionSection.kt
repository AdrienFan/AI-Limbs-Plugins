package com.ai.limbs.plugincenter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginCollectionSection(
    title: String,
    totalCount: Int,
    matchedCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    searchPlaceholder: String = "搜索插件",
    headerControl: (@Composable () -> Unit)? = null,
    searchActions: (@Composable FlowRowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ConsolePanel {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = if (expanded) "收起" else "展开")
                    Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(totalCount.toString(), style = MaterialTheme.typography.labelLarge)
                }
            }
            headerControl?.invoke()
            OutlinedTextField(
                value = query,
                onValueChange = { value ->
                    onQueryChange(value)
                    if (value.isNotBlank()) onExpandedChange(true)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text(searchPlaceholder) },
                singleLine = true
            )
            if (searchActions != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp), content = searchActions)
            }
            if (query.isNotBlank()) {
                Text("匹配 " + matchedCount + " / " + totalCount,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) content()
        }
    }
}
