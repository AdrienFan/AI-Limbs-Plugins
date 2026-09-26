package com.ai.limbs.plugincenter.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** app_process workers cannot resolve SAF providers; the registered UI process stages opted-in input. */
internal object PluginCenterStagedSources {
    private const val MAX_FILES = 200
    private const val MAX_FILE_BYTES = 512L * 1024L * 1024L
    private val ownerId = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")

    suspend fun files(context: Context, owner: String, uris: List<Uri>): List<String> =
        withContext(Dispatchers.IO) {
            require(uris.size in 1..MAX_FILES) { "一次最多选择 $MAX_FILES 个文件" }
            uris.map { stage(context, owner, it) }
        }

    /** Private keys cross the local transport in memory; never persist their plaintext. */
    suspend fun privateKey(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "私钥必须来自系统文件选择器" }
        val bytes = ByteArray(64 * 1024 + 1)
        try {
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取私钥" }
            input.use { source ->
                var size = 0
                while (true) {
                    val count = source.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                    require(size <= 64 * 1024) { "私钥超过 64 KiB 上限" }
                }
                require(size > 0) { "私钥文件为空" }
                Base64.encodeToString(bytes, 0, size, Base64.NO_WRAP)
            }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun apkTree(context: Context, owner: String, tree: Uri): List<String> =
        withContext(Dispatchers.IO) {
            require(tree.scheme == "content") { "目录必须来自系统文件选择器" }
            val results = mutableListOf<Pair<Uri, Long>>()
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            var entries = 0
            fun walk(parentId: String, depth: Int) {
                if (depth > 5 || results.size >= MAX_FILES) return
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                val cursor = requireNotNull(context.contentResolver.query(children, projection, null, null, null)) {
                    "无法读取选择的目录"
                }
                cursor.use {
                    val id = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val name = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mime = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val date = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (it.moveToNext() && results.size < MAX_FILES) {
                        require(++entries <= 5_000) { "目录项目过多，请选择更小的文件夹" }
                        val childId = it.getString(id)
                        if (it.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(childId, depth + 1)
                        } else if (it.getString(name).endsWith(".apk", ignoreCase = true)) {
                            val modified = if (date >= 0 && !it.isNull(date)) it.getLong(date) else 0L
                            results += DocumentsContract.buildDocumentUriUsingTree(tree, childId) to modified
                        }
                    }
                }
            }
            walk(DocumentsContract.getTreeDocumentId(tree), 0)
            results.sortedByDescending { it.second }.map { stage(context, owner, it.first) }
        }

    private fun stage(context: Context, owner: String, uri: Uri): String {
        require(ownerId.matches(owner) && uri.scheme == "content") { "非法暂存来源" }
        val cursor = requireNotNull(
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ) { "无法读取选中文件信息" }
        val original = cursor.use {
            val index = it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            require(it.moveToFirst()) { "选中文件不存在" }
            it.getString(index)
        }
        val cleaned = original.trim().map { c ->
            if (c.isISOControl() || c in "/\\:*?\"<>|") '_' else c
        }.joinToString("").trim(' ', '.')
        val suffix = cleaned.substringAfterLast('.', "").take(12)
        val name = when {
            cleaned.length <= 96 -> cleaned
            suffix.isBlank() -> cleaned.take(96)
            else -> cleaned.take(95 - suffix.length) + "." + suffix
        }
        require(name.isNotBlank()) { "文件名无效" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val directory = File(context.cacheDir, "plugin-center-imports/$owner/$digest")
        check(directory.mkdirs() || directory.isDirectory) { "无法建立暂存目录" }
        val target = File(directory, name)
        if (target.exists()) {
            check(target.isFile && target.length() > 0) { "暂存文件不可用" }
            return target.absolutePath
        }
        val partial = File(directory, ".$name.partial")
        try {
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取选中文件" }
            input.use { source ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var size = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        size += read
                        require(size <= MAX_FILE_BYTES) { "文件超过 512 MiB 上限" }
                        output.write(buffer, 0, read)
                    }
                    require(size > 0) { "不能暂存空文件" }
                }
            }
            check(partial.renameTo(target)) { "无法完成暂存" }
            return target.absolutePath
        } finally {
            partial.delete()
            if (directory.listFiles().isNullOrEmpty()) directory.delete()
        }
    }
}
