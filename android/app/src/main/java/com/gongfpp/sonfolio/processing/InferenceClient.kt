package com.gongfpp.sonfolio.processing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class InferenceClient(
    private val context: Context,
    private val connectionTimeoutMillis: Long = 30_000L,
    private val processingTimeoutMillis: Long = 8 * 60_000L,
) {
    suspend fun detect(file: File): List<DetectedSpeechWindow> {
        val response = call(InferenceService.DETECT, Bundle().apply { putString("path", file.path) })
        val starts = requireNotNull(response.getLongArray("starts"))
        val ends = requireNotNull(response.getLongArray("ends"))
        require(starts.size == ends.size)
        return starts.indices.map { DetectedSpeechWindow(starts[it], ends[it]) }
    }

    suspend fun transcribe(file: File, windows: List<DetectedSpeechWindow>, language: String): List<String> {
        if (windows.isEmpty()) return emptyList()
        val response = call(InferenceService.TRANSCRIBE, Bundle().apply {
            putString("path", file.path)
            putString("language", language)
            putLongArray("starts", windows.map { it.startOffsetMillis }.toLongArray())
            putLongArray("ends", windows.map { it.endOffsetMillis }.toLongArray())
        })
        return requireNotNull(response.getStringArrayList("texts")).also { require(it.size == windows.size) }
    }

    private suspend fun call(operation: Int, input: Bundle): Bundle = withContext(Dispatchers.Main) {
        val connected = CompletableDeferred<Messenger>()
        val result = CompletableDeferred<Bundle>()
        fun fail(message: String) {
            val error = IllegalStateException(message)
            connected.completeExceptionally(error)
            result.completeExceptionally(error)
        }
        val reply = Messenger(Handler(Looper.getMainLooper()) { response ->
            val error = response.data.getString("error")
            if (error == null) result.complete(response.data) else fail(error)
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(Messenger(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = fail("模型进程已退出，录音不受影响，请重试")
            override fun onBindingDied(name: ComponentName) = fail("模型连接中断，请重试")
            override fun onNullBinding(name: ComponentName) = fail("无法启动本地模型")
        }
        var bound = false
        try {
            bound = context.bindService(Intent(context, InferenceService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND)
            check(bound) { "无法连接本地模型" }
            // 冷启动即退出可能没有断开回调，连接等待必须单独限时。
            val remote = withTimeoutOrNull(connectionTimeoutMillis) { connected.await() }
                ?: error("本地模型启动超时，原音仍保留，请重试")
            remote.send(Message.obtain(null, operation).apply { data = input; replyTo = reply })
            // 自身超时是可重试的处理失败；外部取消仍正常向上传递。
            withTimeoutOrNull(processingTimeoutMillis) { result.await() }
                ?: error("本地模型处理超时，原音仍保留，请重试")
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
            connected.cancel()
            result.cancel()
        }
    }
}
