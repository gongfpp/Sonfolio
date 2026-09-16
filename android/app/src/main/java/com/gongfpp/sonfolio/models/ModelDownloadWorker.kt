package com.gongfpp.sonfolio.models

import android.content.Context
import androidx.work.*
import com.gongfpp.sonfolio.SonfolioApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = downloadMutex.withLock { withContext(Dispatchers.IO) {
        val model = ModelCatalog.byId(inputData.getString("model") ?: "") ?: return@withContext Result.failure()
        val filesDir = applicationContext.filesDir
        var connection: HttpURLConnection? = null
        try {
            // 已完成文件计入总进度，续传与多文件共用同一进度口径。
            var completed = model.files.filter { ModelCatalog.verify(ModelCatalog.file(filesDir, it), it) }.sumOf { it.bytes }
            for (file in model.files) {
                ensureActive()
                val target = ModelCatalog.file(filesDir, file)
                if (ModelCatalog.verify(target, file)) continue
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法建立模型目录" }
                val partial = File(target.parentFile, target.name + ".part")
                var offset = partial.takeIf { it.isFile }?.length() ?: 0L
                if (offset > file.bytes) { check(partial.delete()); offset = 0 }
                require(target.parentFile!!.usableSpace > model.bytes - completed - offset + RESERVE) { "空间不足，请至少为录音额外保留 512 MB" }
                if (offset < file.bytes) {
                    var url = URL(file.url)
                    var opened: HttpURLConnection? = null
                    for (redirect in 0..5) {
                        require(url.protocol == "https") { "下载地址必须使用 HTTPS" }
                        val request = url.openConnection() as HttpURLConnection
                        connection = request
                        request.instanceFollowRedirects = false; request.connectTimeout = 20_000; request.readTimeout = 30_000
                        request.setRequestProperty("Accept-Encoding", "identity")
                        if (offset > 0) request.setRequestProperty("Range", "bytes=$offset-")
                        val status = request.responseCode
                        if (status in listOf(301, 302, 303, 307, 308)) {
                            val location = request.getHeaderField("Location") ?: error("下载源重定向无效")
                            url = URL(url, location); request.disconnect()
                        } else { opened = request; break }
                    }
                    val response = opened ?: error("下载重定向次数过多")
                    check(response.responseCode in listOf(200, 206)) { "下载源返回 HTTP ${response.responseCode}，请稍后重试" }
                    val resumed = response.responseCode == 206 && offset > 0
                    if (response.responseCode == 206) require(response.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) { "下载续传位置不匹配" }
                    if (!resumed) offset = 0
                    var current = offset; var lastUpdate = 0L
                    response.inputStream.use { input ->
                        FileOutputStream(partial, resumed).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                current += count
                                require(current <= file.bytes) { "下载内容超过预期大小" }
                                require(target.parentFile!!.usableSpace > RESERVE) { "剩余空间不足，下载已暂停，原音不受影响" }
                                output.write(buffer, 0, count)
                                if (System.currentTimeMillis() - lastUpdate > 700) {
                                    setProgress(workDataOf("bytes" to completed + current, "message" to "正在下载 ${target.name}，可取消后续传"))
                                    lastUpdate = System.currentTimeMillis()
                                }
                            }
                            output.fd.sync()
                        }
                    }
                }
                setProgress(workDataOf("bytes" to completed + partial.length(), "message" to "正在校验 ${target.name}"))
                ensureActive()
                if (!ModelCatalog.verify(partial, file)) {
                    if (partial.length() == file.bytes) partial.delete()
                    error("模型尚不完整或校验失败，请重试；未启用该文件")
                }
                ensureActive()
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                completed += file.bytes
                setProgress(workDataOf("bytes" to completed, "message" to "已下载 ${target.name}"))
            }
            val app = applicationContext as SonfolioApplication
            if (model.id == ModelCatalog.summary.id) app.summarySettings.useDownloadedModel(inputData.getString("activate-revision"))
            if (model.kind == ModelKind.SPEECH) app.recordingRepository.enqueuePendingAsr()
            Result.success(workDataOf("message" to "下载与 SHA-256 校验完成"))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(workDataOf("message" to (error.message?.take(160) ?: "下载失败，请检查网络后重试"))) }
        finally { connection?.disconnect() }
    } }

    companion object {
        const val TAG = "sonfolio-model-download"
        private const val RESERVE = 512L * 1024 * 1024
        private val downloadMutex = Mutex()
        fun enqueue(context: Context, id: String, wifiOnly: Boolean, activateRevision: String? = null) {
            require(ModelCatalog.byId(id) != null) { "未知模型" }
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf("model" to id, "activate-revision" to activateRevision)).addTag(TAG).addTag("model:$id").addTag("created:${System.currentTimeMillis()}")
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("download-model:$id", ExistingWorkPolicy.KEEP, request)
        }
    }
}
