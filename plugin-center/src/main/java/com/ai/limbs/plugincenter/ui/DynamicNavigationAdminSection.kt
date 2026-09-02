package com.ai.limbs.plugincenter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.limbs.plugincenter.runtime.DynamicNavigationFacade
import com.ai.limbs.plugincenter.runtime.DynamicSurfaceSnapshot
import com.ai.limbs.plugincenter.runtime.PluginUiContributionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DynamicNavigationAdminSection(
    navigation: DynamicNavigationFacade,
    onError: (Throwable) -> Unit
) {
    var surfaces by remember { mutableStateOf<List<DynamicSurfaceSnapshot>>(emptyList()) }
    var contributions by remember { mutableStateOf<List<PluginUiContributionSnapshot>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DynamicSurfaceSnapshot?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        val loaded = withContext(Dispatchers.IO) {
            Pair(navigation.surfaces(), navigation.contributions())
        }
        surfaces = loaded.first
        contributions = loaded.second
    }

    fun mutate(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) { block() }
                refresh()
            }.onFailure(onError)
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        runCatching { refresh() }.onFailure(onError)
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("动态页面与插件绑定", fontWeight = FontWeight.Bold)
            Text(
                "每个动态页面都有稳定 surfaceId。普通插件继续注册原有 UI contribution，Plugin Center 只负责把 contribution 绑定到页面。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "页面 ${surfaces.size} 个 · 可用插件入口 ${contributions.size} 个",
                style = MaterialTheme.typography.bodySmall
            )
            if (surfaces.isEmpty()) {
                Text("还没有动态页面；请用侧边栏底部的 ⊕ 创建。")
            }
            surfaces.forEach { surface ->
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(surface.title, fontWeight = FontWeight.SemiBold)
                                Text(surface.surfaceId, style = MaterialTheme.typography.bodySmall)
                                Text("已绑定 ${surface.bindingCount} 个入口", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                enabled = !busy && surface.empty,
                                onClick = { deleteTarget = surface }
                            ) { Text("删除") }
                        }
                        if (contributions.isEmpty()) {
                            Text("当前没有已激活插件 UI contribution。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            contributions.forEach { contribution ->
                                val bound = surface.surfaceId in contribution.surfaceIds
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(contribution.title)
                                        Text(
                                            "${contribution.ownerPluginId} · ${contribution.tileId}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (bound) {
                                        OutlinedButton(
                                            enabled = !busy,
                                            onClick = {
                                                mutate {
                                                    navigation.unbind(surface.surfaceId, contribution.tileId)
                                                }
                                            }
                                        ) { Text("移除") }
                                    } else {
                                        Button(
                                            enabled = !busy,
                                            onClick = {
                                                mutate {
                                                    navigation.bind(surface.surfaceId, contribution.tileId)
                                                }
                                            }
                                        ) { Text("添加") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { target ->
        DynamicSurfaceDeleteDialog(
            title = target.title,
            onDismiss = { deleteTarget = null },
            onDelete = { password ->
                deleteTarget = null
                mutate { navigation.delete(target.surfaceId, password) }
            }
        )
    }
}

@Composable
private fun DynamicSurfaceDeleteDialog(
    title: String,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除动态页面") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("仅空页面可以删除：$title")
                Text("此操作始终需要管理员密码，且 Kernel 不提供级联删除。")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("管理员密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = password.isNotBlank(),
                onClick = { onDelete(password) }
            ) { Text("确认删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
