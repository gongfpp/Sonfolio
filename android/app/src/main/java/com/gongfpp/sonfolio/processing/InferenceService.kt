package com.gongfpp.sonfolio.processing

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.io.File

/** 模型运行在 :inference 进程；原生崩溃不会退出持有麦克风的主进程。 */
class InferenceService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("sonfolio-model", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        messenger = Messenger(Handler(thread.looper) { request ->
            val reply = Message.obtain(null, request.what)
            reply.data = try {
                val input = request.data
                val file = File(requireNotNull(input.getString("path"))).canonicalFile
                require(file.path.startsWith(filesDir.canonicalPath + File.separator)) { "录音不在本地存储中" }
                when (request.what) {
                    DETECT -> {
                        val windows = SileroVadProcessor(this).detect(file)
                        Bundle().apply {
                            putLongArray("starts", windows.map { it.startOffsetMillis }.toLongArray())
                            putLongArray("ends", windows.map { it.endOffsetMillis }.toLongArray())
                        }
                    }
                    TRANSCRIBE -> {
                        val starts = requireNotNull(input.getLongArray("starts"))
                        val ends = requireNotNull(input.getLongArray("ends"))
                        require(starts.size == ends.size)
                        val texts = SenseVoiceAsrProcessor(this, input.getString("language") ?: "zh").use { processor ->
                            starts.indices.map { index -> processor.transcribe(file, starts[index], ends[index]) }
                        }
                        Bundle().apply { putStringArrayList("texts", ArrayList(texts)) }
                    }
                    else -> error("未知处理请求")
                }
            } catch (error: OutOfMemoryError) {
                Bundle().apply { putString("error", "模型内存不足，原音仍保留，可稍后重试") }
            } catch (error: Exception) {
                Bundle().apply { putString("error", error.message ?: "本地模型处理失败") }
            }
            runCatching { request.replyTo?.send(reply) }
            true
        })
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val DETECT = 1
        const val TRANSCRIBE = 2
    }
}
