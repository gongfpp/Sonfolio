package com.gongfpp.sonfolio.processing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义 ASR 模型按 [LocalAsrEngine] 的布局解析出的文件角色。目录由用户导入，可能与内置
 * 文件名不完全一致，因此这里记录相对路径，推理进程按 kind 取用。
 */
data class CustomAsrLayout(
    val model: String? = null,
    val tokens: String? = null,
    val convFrontend: String? = null,
    val encoder: String? = null,
    val decoder: String? = null,
    val tokenizerDir: String? = null,
)

data class CustomAsrModel(
    val id: String,
    val label: String,
    val kind: LocalAsrEngine,
    val layout: CustomAsrLayout,
)

/**
 * 用户导入的本地识别模型，存放在 files/asr-models/<id>/，与内置模型完全解耦：不参与内置
 * SHA-256 校验，删除时只回收自己的目录。作为识别引擎列表里的额外选项。
 */
class CustomAsrStore(private val context: Context, name: String = "custom-asr") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun rootDir(): File = File(context.filesDir, "asr-models")
    fun dir(id: String): File = File(rootDir(), id)

    @Synchronized fun list(): List<CustomAsrModel> {
        val raw = prefs.getString("models", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!dir(id).isDirectory) return@mapNotNull null
                val kind = runCatching { LocalAsrEngine.valueOf(item.optString("kind")) }.getOrNull() ?: return@mapNotNull null
                val layoutObject = item.optJSONObject("layout") ?: JSONObject()
                CustomAsrModel(
                    id = id,
                    label = item.optString("label").ifBlank { kind.displayName },
                    kind = kind,
                    layout = CustomAsrLayout(
                        model = layoutObject.stringOrNull("model"),
                        tokens = layoutObject.stringOrNull("tokens"),
                        convFrontend = layoutObject.stringOrNull("convFrontend"),
                        encoder = layoutObject.stringOrNull("encoder"),
                        decoder = layoutObject.stringOrNull("decoder"),
                        tokenizerDir = layoutObject.stringOrNull("tokenizerDir"),
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun byId(id: String?): CustomAsrModel? = id?.let { target -> list().firstOrNull { it.id == target } }

    @Synchronized fun add(label: String, kind: LocalAsrEngine, layout: CustomAsrLayout, id: String = UUID.randomUUID().toString()): CustomAsrModel {
        val clean = label.trim()
        require(clean.isNotEmpty() && clean.length <= 40 && clean.none { it.isISOControl() }) { "名称需为 1–40 个可见字符" }
        require(dir(id).isDirectory) { "模型目录不存在" }
        val model = CustomAsrModel(id, clean, kind, layout)
        val models = list() + model
        check(prefs.edit().putString("models", encode(models)).commit()) { "保存自定义模型失败" }
        return model
    }

    @Synchronized fun remove(id: String) {
        val models = list().filterNot { it.id == id }
        check(prefs.edit().putString("models", encode(models)).commit()) { "删除自定义模型失败" }
        runCatching { dir(id).deleteRecursively() }
    }

    /** 只检查文件是否齐全；用户自带模型无官方哈希，不做校验。 */
    fun isReady(model: CustomAsrModel): Boolean {
        val paths = resolve(model)
        return when (model.kind) {
            LocalAsrEngine.SENSE_VOICE -> paths.model != null
            LocalAsrEngine.FIRE_RED_ASR_CTC -> paths.model != null && paths.tokens != null
            LocalAsrEngine.QWEN3_ASR ->
                paths.convFrontend != null && paths.encoder != null && paths.decoder != null && paths.tokenizer != null
        }
    }

    /** 解析为绝对路径；缺失或损坏的文件返回 null，由推理进程报错。 */
    fun resolve(model: CustomAsrModel): AsrModelPaths {
        val base = dir(model.id)
        fun fileOrNull(relative: String?): File? = relative?.let { File(base, it) }?.takeIf { it.isFile }
        return AsrModelPaths(
            model = fileOrNull(model.layout.model),
            tokens = fileOrNull(model.layout.tokens),
            convFrontend = fileOrNull(model.layout.convFrontend),
            encoder = fileOrNull(model.layout.encoder),
            decoder = fileOrNull(model.layout.decoder),
            tokenizer = model.layout.tokenizerDir?.let { File(base, it) }?.takeIf { it.isDirectory },
        )
    }

    private fun encode(models: List<CustomAsrModel>): String = JSONArray().apply {
        models.forEach { model ->
            put(JSONObject().apply {
                put("id", model.id); put("label", model.label); put("kind", model.kind.name)
                put("layout", JSONObject().apply {
                    put("model", model.layout.model); put("tokens", model.layout.tokens)
                    put("convFrontend", model.layout.convFrontend); put("encoder", model.layout.encoder)
                    put("decoder", model.layout.decoder); put("tokenizerDir", model.layout.tokenizerDir)
                })
            })
        }
    }.toString()
}

private fun JSONObject.stringOrNull(key: String): String? = optString(key).takeIf { it.isNotBlank() }

/** 从 zip 或系统目录树导入用户自带的 sherpa-onnx 模型；只接受与所选类型匹配的文件布局。 */
object CustomAsrImporter {
    private const val MAX_TOTAL_BYTES = 4_500_000_000L
    private const val MAX_ENTRIES = 600
    private val RELEVANT_EXTENSIONS = listOf(".onnx", ".txt", ".json")

    suspend fun importZip(context: Context, uri: Uri, kind: LocalAsrEngine, label: String, store: CustomAsrStore): CustomAsrModel =
        withContext(Dispatchers.IO) {
            val staging = File(store.rootDir(), "staging-${UUID.randomUUID()}")
            staging.mkdirs()
            try {
                var total = 0L
                var entries = 0
                val raw = context.contentResolver.openInputStream(uri) ?: error("无法读取所选压缩包")
                raw.use { stream ->
                    ZipInputStream(BufferedInputStream(stream)).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            require(++entries <= MAX_ENTRIES) { "压缩包条目过多，可能不是模型文件" }
                            val name = entry.name.replace('\\', '/')
                            require(!name.startsWith("/") && name.split('/').none { it == ".." || it.isEmpty() }) { "压缩包路径不合法" }
                            val target = File(staging, name)
                            require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "压缩包路径不合法" }
                            target.parentFile?.mkdirs()
                            target.outputStream().use { output ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    require(total <= MAX_TOTAL_BYTES) { "模型解压后过大" }
                                    output.write(buffer, 0, read)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                finish(store, staging, kind, label)
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }

    suspend fun importTree(context: Context, uri: Uri, kind: LocalAsrEngine, label: String, store: CustomAsrStore): CustomAsrModel =
        withContext(Dispatchers.IO) {
            val staging = File(store.rootDir(), "staging-${UUID.randomUUID()}")
            staging.mkdirs()
            try {
                var total = 0L
                copyTree(context, uri, staging) { bytes ->
                    total += bytes
                    require(total <= MAX_TOTAL_BYTES) { "模型过大" }
                }
                finish(store, staging, kind, label)
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }

    private fun finish(store: CustomAsrStore, staging: File, kind: LocalAsrEngine, label: String): CustomAsrModel {
        val layout = detectAsrLayout(staging, kind)
            ?: error("未找到「${kind.displayName}」所需的模型文件，请确认所选内容与类型一致")
        val id = UUID.randomUUID().toString()
        val target = store.dir(id)
        target.parentFile?.mkdirs()
        require(staging.renameTo(target)) { "移动模型文件失败，请重试" }
        return store.add(label, kind, layout, id)
    }

    /** 递归拷贝目录树中与模型相关的文件，保留相对路径。 */
    private fun copyTree(context: Context, treeUri: Uri, staging: File, onBytes: (Long) -> Unit) {
        val resolver = context.contentResolver
        fun walk(documentId: String, relative: String, depth: Int) {
            require(depth <= 6) { "目录层级过深" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    val childRelative = if (relative.isEmpty()) name else "$relative/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, childRelative, depth + 1)
                    } else if (RELEVANT_EXTENSIONS.any { name.lowercase().endsWith(it) }) {
                        val target = File(staging, childRelative)
                        require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "路径不合法" }
                        target.parentFile?.mkdirs()
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        resolver.openInputStream(documentUri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("无法读取文件：$name")
                        onBytes(target.length())
                    }
                }
            } ?: error("无法读取所选目录")
        }
        walk(DocumentsContract.getTreeDocumentId(treeUri), "", 0)
    }
}

/** 按所选类型识别导入目录里的必需文件；缺少必需项返回 null。用户自带模型文件名可能与内置不同，
 * 这里用不区分大小写的文件名匹配，并把解析出的相对路径记录下来。 */
internal fun detectAsrLayout(root: File, kind: LocalAsrEngine): CustomAsrLayout? {
    val entries = root.walkTopDown().toList()
    val files = entries.filter { it.isFile }
    fun find(name: String): File? = files.firstOrNull { it.name.equals(name, ignoreCase = true) }
    fun rel(file: File?): String? = file?.relativeTo(root)?.path
    return when (kind) {
        LocalAsrEngine.SENSE_VOICE ->
            rel(find("model.int8.onnx") ?: find("model.onnx"))?.let { CustomAsrLayout(model = it) }
        LocalAsrEngine.FIRE_RED_ASR_CTC -> {
            val model = find("model.int8.onnx") ?: find("model.onnx") ?: return null
            val tokens = find("tokens.txt") ?: return null
            CustomAsrLayout(model = rel(model), tokens = rel(tokens))
        }
        LocalAsrEngine.QWEN3_ASR -> {
            val conv = find("conv_frontend.onnx") ?: return null
            val encoder = find("encoder.int8.onnx") ?: find("encoder.onnx") ?: return null
            val decoder = find("decoder.int8.onnx") ?: find("decoder.onnx") ?: return null
            val tokenizer = entries.firstOrNull { it.isDirectory && it.name.equals("tokenizer", ignoreCase = true) } ?: return null
            if (!File(tokenizer, "merges.txt").isFile || !File(tokenizer, "vocab.json").isFile ||
                !File(tokenizer, "tokenizer_config.json").isFile
            ) return null
            CustomAsrLayout(
                convFrontend = rel(conv), encoder = rel(encoder), decoder = rel(decoder),
                tokenizerDir = tokenizer.relativeTo(root).path,
            )
        }
    }
}
