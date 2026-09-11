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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class InferenceClient(private val context: Context) {
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
        withTimeout(8 * 60_000L) {
            var connection: ServiceConnection? = null
            var bound = false
            try {
                suspendCancellableCoroutine { continuation ->
                    fun fail(message: String) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                    }
                    val reply = Messenger(Handler(Looper.getMainLooper()) { response ->
                        if (continuation.isActive) {
                            val error = response.data.getString("error")
                            if (error == null) continuation.resume(response.data) else fail(error)
                        }
                        true
                    })
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (!continuation.isActive) return
                            runCatching {
                                Messenger(binder).send(Message.obtain(null, operation).apply {
                                    data = input
                                    replyTo = reply
                                })
                            }.onFailure { fail("本地模型连接失败，原音仍保留") }
                        }
                        override fun onServiceDisconnected(name: ComponentName) = fail("模型进程已退出，录音不受影响，请重试")
                        override fun onBindingDied(name: ComponentName) = fail("模型连接中断，请重试")
                        override fun onNullBinding(name: ComponentName) = fail("无法启动本地模型")
                    }
                    bound = context.bindService(Intent(context, InferenceService::class.java), requireNotNull(connection), Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND)
                    if (!bound) fail("无法连接本地模型")
                }
            } finally {
                if (bound) connection?.let { runCatching { context.unbindService(it) } }
            }
        }
    }
}
