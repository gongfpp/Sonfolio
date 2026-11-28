package com.gongfpp.sonfolio.models

import android.content.Context
import androidx.work.*
import com.gongfpp.sonfolio.SonfolioApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private var activeConnection: HttpURLConnection? = null

    override suspend fun doWork(): Result = downloadMutex.withLock { withContext(Dispatchers.IO) {
        val model = ModelCatalog.byId(inputData.getString("model") ?: "") ?: return@withContext Result.failure()
        val filesDir = applicationContext.filesDir
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
                if (offset < file.bytes) downloadWithFallback(file, target, partial, completed, offset)
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
            // Download/verification never changes the active mode or chosen model.
            if (model.kind == ModelKind.SPEECH) app.recordingRepository.enqueuePendingAsr()
            Result.success(workDataOf("message" to "下载与 SHA-256 校验完成"))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(workDataOf("message" to downloadFailure(error))) }
        finally { activeConnection?.disconnect(); activeConnection = null }
    } }

    /**
     * 把网络异常翻译成用户能据此行动的中文。系统的英文原文（形如
     * "failed to connect to huggingface.co/31.13.64.7 ... from /10.87.100.3 after 20000ms"）
     * 既不可读，也会把运营商内网地址暴露在界面和截图里，不能直接上屏。
     */
    private fun downloadFailure(error: Exception): String {
        // 已经写好的中文业务提示（如“下载源返回 HTTP 404”）原样保留。
        if (error is IllegalStateException && error.message?.any { it.code > 127 } == true) {
            return error.message!!.take(160)
        }
        return when (error) {
            is java.net.UnknownHostException ->
                "无法解析下载服务器地址，请检查网络（可能是 DNS 问题）后重试"
            is java.net.ConnectException ->
                "无法连接下载服务器，当前网络可能受限；已下载部分会保留，换网络后可续传"
            is java.net.SocketTimeoutException ->
                "连接下载服务器超时，网络不通畅；已下载部分会保留，可稍后重试"
            is javax.net.ssl.SSLException ->
                "与下载服务器的加密连接失败，网络可能被拦截"
            is java.io.IOException ->
                "下载中断，已下载部分会保留；请检查网络后重试，支持断点续传"
            else -> error.message?.takeIf { it.any { c -> c.code > 127 } }?.take(160)
                ?: "下载失败，请检查网络后重试"
        }
    }

    /** 依次尝试该文件登记的所有源（大陆镜像优先，上游回退）；每个源都能续传，全部失败才报错。 */
    private suspend fun downloadWithFallback(file: ModelFile, target: File, partial: File, completed: Long, initialOffset: Long) {
        var lastError: Exception? = null
        for ((index, source) in file.urls.withIndex()) {
            currentCoroutineContext().ensureActive()
            var offset = partial.takeIf { it.isFile }?.length() ?: initialOffset
            if (offset > file.bytes) { check(partial.delete()); offset = 0 }
            try {
                downloadFrom(source, target, partial, file, completed, offset, fallback = index > 0)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError?.let {
            IllegalStateException(
                "${file.relativePath.substringAfterLast('/')} 的 ${file.urls.size} 个下载源均不可用：${downloadFailure(it)}"
            )
        } ?: error("没有可用的模型下载源")
    }

    private suspend fun downloadFrom(source: String, target: File, partial: File, file: ModelFile, completed: Long, initialOffset: Long, fallback: Boolean) {
        var offset = initialOffset
        var url = URL(source)
        var opened: HttpURLConnection? = null
        for (redirect in 0..5) {
            require(url.protocol == "https") { "下载地址必须使用 HTTPS" }
            val request = url.openConnection() as HttpURLConnection
            activeConnection = request
            // 连接超时从 20s 收到 10s：国内直连镜像通常 1s 内握手，久等只会拖长失败反馈。
            request.instanceFollowRedirects = false; request.connectTimeout = 10_000; request.readTimeout = 30_000
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
        val sourceLabel = if (fallback) "备用源" else "镜像"
        var current = offset; var lastUpdate = 0L
        response.inputStream.use { input ->
            FileOutputStream(partial, resumed).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    current += count
                    require(current <= file.bytes) { "下载内容超过预期大小" }
                    require(target.parentFile!!.usableSpace > RESERVE) { "剩余空间不足，下载已暂停，录音不受影响" }
                    output.write(buffer, 0, count)
                    if (System.currentTimeMillis() - lastUpdate > 700) {
                        setProgress(workDataOf("bytes" to completed + current, "message" to "正在下载 ${target.name}（$sourceLabel），可取消后续传"))
                        lastUpdate = System.currentTimeMillis()
                    }
                }
                output.fd.sync()
            }
        }
    }

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
