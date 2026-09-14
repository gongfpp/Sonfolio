package com.gongfpp.sonfolio.recording

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/**
 * 一次批量导出的单个切片：连同其本地文件路径与转写文本，供打包成可长期保存的备份。
 */
data class ChunkExport(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val localPath: String,
    val texts: List<String>,
)

/**
 * 把若干原始录音切片连同转写文本打包成一个 zip，保存到用户选择的系统位置。
 * wav 放在 audio/，转写与元信息放在 transcript/，便于脱离应用后也能查看。
 */
object RecordingExporter {
    suspend fun exportToZip(context: Context, uri: Uri, items: List<ChunkExport>) = withContext(Dispatchers.IO) {
      com.gongfpp.sonfolio.processing.AudioFileAccess.mutex.withLock {
        require(items.isNotEmpty()) { "没有选择可导出的录音" }
        items.forEach { item ->
            require(item.endedAtMillis != null) { "正在录音的文件不能导出，请停止录音或等待切片完成" }
            require(item.id.matches(Regex("[a-zA-Z0-9_-]+"))) { "文件标识不合法" }
            require(File(item.localPath).isFile) { "选中原音已清理或缺失，请取消选择后重试" }
        }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(out).use { zip ->
                items.forEach { item ->
                    val file = File(item.localPath)
                    if (file.exists()) {
                        zip.putNextEntry(ZipEntry("audio/${item.id}.wav"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    val manifest = buildString {
                        append("id: ${item.id}\n")
                        append("startedAt: ${item.startedAtMillis}\n")
                        append("endedAt: ${item.endedAtMillis ?: ""}\n")
                        append("transcript:\n")
                        if (item.texts.isEmpty()) append("  （暂无转写）\n")
                        else item.texts.forEach { append("  - $it\n") }
                    }
                    zip.putNextEntry(ZipEntry("transcript/${item.id}.txt"))
                    manifest.byteInputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("无法打开导出目标")
      }
    }
}
