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
import com.ai.limbs.plugincenter.runtime.PluginControlPlaneFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun DynamicNavigationAdminSection(
    navigation: DynamicNavigationFacade,
    controlPlane: PluginControlPlaneFacade,
    onError: (Throwable) -> Unit
) {
    var surfaces by remember { mutableStateOf<List<DynamicSurfaceSnapshot>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DynamicSurfaceSnapshot?>(null) }
    var renameTarget by remember { mutableStateOf<DynamicSurfaceSnapshot?>(null) }
    var iconTarget by remember { mutableStateOf<DynamicSurfaceSnapshot?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        surfaces = withContext(Dispatchers.IO) { navigation.surfaces() }
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

    fun openSurface(surface: DynamicSurfaceSnapshot) {
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    controlPlane.invokeHostPrimitive(
                        id = "host.ui.surface@1",
                        operation = "open",
                        parameters = JSONObject().put("surface_id", surface.surfaceId)
                    )
                }
            }.onFailure(onError)
            busy = false
        }
    }

    LaunchedEffect(navigation) {
        while (currentCoroutineContext().isActive) {
            try {
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onError(error)
            }
            delay(500)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("新增页管理", fontWeight = FontWeight.Bold)
            Text(
                "页面名称可以留空或随时修改；页面编号按当前页面顺序连续生成，删除后自动补位，仅在开发管理界面用于区分。",
                style = MaterialTheme.typography.bodySmall
            )
            Text("页面 ${surfaces.size} 个", style = MaterialTheme.typography.bodySmall)
            if (surfaces.isEmpty()) {
                Text("还没有动态页面；请用侧边栏底部的 ⊕ 创建。")
            }
            surfaces.forEachIndexed { index, surface ->
                val pageNumber = index + 1
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${pageNumber.toString().padStart(2, '0')}  ${surface.title.ifEmpty { "（无名称）" }}",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "插件 ${surface.pluginCount} 个 · 功能入口 ${surface.bindingCount} 个",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text("图标：${surface.iconKey}", style = MaterialTheme.typography.bodySmall)
                                if (!surface.empty) {
                                    Text(
                                        "页面非空，需先移除全部插件入口后才能删除。",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        val openAvailability = controlPlane.hostPrimitiveAvailability("host.ui.surface@1", "open")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                enabled = !busy && openAvailability.available,
                                onClick = { openSurface(surface) },
                                modifier = Modifier.weight(1f)
                            ) { Text("跳转") }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { renameTarget = surface },
                                modifier = Modifier.weight(1f)
                            ) { Text("重命名") }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { iconTarget = surface },
                                modifier = Modifier.weight(1f)
                            ) { Text("更换图标") }
                            DangerOutlinedButton(
                                enabled = !busy && surface.empty,
                                onClick = { deleteTarget = surface },
                                modifier = Modifier.weight(1f)
                            ) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
    renameTarget?.let { target ->
        DynamicSurfaceRenameDialog(
            currentTitle = target.title,
            onDismiss = { renameTarget = null },
            onSave = { title ->
                renameTarget = null
                mutate { navigation.rename(target.surfaceId, title) }
            }
        )
    }
    iconTarget?.let { target ->
        DynamicSurfaceIconDialog(
            currentIconKey = target.iconKey,
            onDismiss = { iconTarget = null },
            onSelect = { iconKey ->
                iconTarget = null
                mutate { navigation.rename(target.surfaceId, target.title, iconKey) }
            }
        )
    }
    deleteTarget?.let { target ->
        val targetIndex = surfaces.indexOfFirst { it.surfaceId == target.surfaceId }
        val targetNumber = if (targetIndex >= 0) (targetIndex + 1).toString().padStart(2, '0') else "--"
        DynamicSurfaceDeleteDialog(
            pageLabel = "$targetNumber  ${target.title.ifEmpty { "（无名称）" }}",
            onDismiss = { deleteTarget = null },
            onDelete = { password ->
                deleteTarget = null
                mutate { navigation.delete(target.surfaceId, password) }
            }
        )
    }
}

private data class DynamicIconPreset(val key: String, val emoji: String, val label: String)

private val DYNAMIC_ICON_PRESETS = listOf(
    DynamicIconPreset("extension", "🧩", "默认"),
    DynamicIconPreset("games", "🎮", "游戏"),
    DynamicIconPreset("book", "📖", "阅读"),
    DynamicIconPreset("build", "🧰", "工具"),
    DynamicIconPreset("science", "🧪", "实验"),
    DynamicIconPreset("folder", "🗂️", "文件"),
    DynamicIconPreset("code", "💻", "代码"),
    DynamicIconPreset("palette", "🎨", "创作"),
    DynamicIconPreset("music_note", "🎵", "音乐"),
    DynamicIconPreset("android", "🤖", "智能"),
    DynamicIconPreset("public", "🌐", "网络"),
    DynamicIconPreset("photo", "🖼️", "图像")
)

@Composable
private fun DynamicSurfaceRenameDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var title by remember(currentTitle) { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名动态页面") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("页面名称（可留空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { Button(onClick = { onSave(title) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun DynamicSurfaceIconDialog(
    currentIconKey: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择页面图标") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DYNAMIC_ICON_PRESETS.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { preset ->
                            OutlinedButton(
                                onClick = { onSelect(preset.key) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text((if (preset.key == currentIconKey) "✓ " else "") + preset.emoji + " " + preset.label)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun DynamicSurfaceDeleteDialog(
    pageLabel: String,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除动态页面") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("仅空页面可以删除：$pageLabel")
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
            DangerButton(
                enabled = password.isNotBlank(),
                onClick = { onDelete(password) }
            ) { Text("确认删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
