package com.gongfpp.sonfolio.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gongfpp.sonfolio.MainActivity
import com.gongfpp.sonfolio.R
import com.gongfpp.sonfolio.SonfolioApplication
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel

class RecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy {
        (application as SonfolioApplication).recordingRepository
    }
    private var recordingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationStartedAtMillis = 0L
    private val gapEvents = Channel<GapEvent>(Channel.UNLIMITED)
    private var gapJob: Job? = null
    private var lastCapturedAtMillis: Long? = null
    private var lastMeterAtMillis = 0L
    private var resumedInput = false
    private var inputSilenced = false
    private var inputDeviceId: Int? = null
    private var automaticRestart = false
    private data class GapEvent(val kind: String, val at: Long, val reason: String? = null)
    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        isRunningInProcess = true
        createNotificationChannel()
        gapJob = serviceScope.launch {
            for (event in gapEvents) {
                val app = application as SonfolioApplication
                runCatching {
                    val affectedStart = minOf(event.at, app.database.recordingDao().getOpenGap(event.kind)?.startedAtMillis ?: event.at)
                    if (event.reason != null) app.database.recordingDao().openGap(event.at, event.reason, event.kind)
                    else app.database.recordingDao().closeOpenGaps(event.kind, event.at, automaticRestart)
                    app.conversationRepository.rebuildFromTranscripts(affectedStart, event.at)
                }.onFailure { Log.e(TAG, "无法更新录音缺口", it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: RecordingController.ACTION_START) {
            RecordingController.ACTION_STOP -> requestStop()
            RecordingController.ACTION_MARK -> markCurrentMoment(intent)
            RecordingController.ACTION_START -> { automaticRestart = intent == null; startRecordingIfNeeded() }
        }
        // A foreground service may be recreated after the process is reclaimed. The unfinished
        // WAV is repaired on the next start, so returning START_STICKY preserves the recording
        // boundary instead of silently losing the active chunk.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recordingJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        isRunningInProcess = false
        RecordingController.updateHealth { it.copy(serviceActive = false, levels = List(12) { 0f }) }
        super.onDestroy()
    }

    private fun startRecordingIfNeeded() {
        if (recordingJob?.isActive == true) return

        stopRequested = false
        resumedInput = false
        inputSilenced = false
        notificationManager.cancel(FAILURE_NOTIFICATION_ID)
        RecordingController.publishHealth(CaptureHealth(serviceActive = true))
        val serviceStartedAt = System.currentTimeMillis()
        val app = application as SonfolioApplication
        app.preferences.beginRecordingSession(
            app.preferences.recordingSessionStartedAtMillis ?: serviceStartedAt,
        )
        notificationStartedAtMillis = app.preferences.recordingSessionStartedAtMillis ?: serviceStartedAt
        try {
            promoteToForeground(notificationStartedAtMillis)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to promote microphone service", error)
            app.preferences.clearRecordingSession()
            serviceScope.launch {
                app.database.recordingDao().openGap(serviceStartedAt, "无法启动麦克风服务，等待重新开始")
                RecordingController.updateHealth { it.copy(failure = "录音未启动 · 请检查麦克风权限") }
                shutdownService()
            }
            return
        }
        recordingJob = serviceScope.launch {
            try {
                repository.recoverDanglingChunks(serviceStartedAt)
                recordContinuously()
            } catch (_: CancellationException) {
                if (!stopRequested) withContext(NonCancellable) {
                    app.database.recordingDao().openGap(lastCapturedAtMillis ?: serviceStartedAt, "录音服务被终止，等待恢复采集")
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Continuous recording stopped unexpectedly", error)
                val reason = error.message ?: "录音设备异常"
                RecordingController.updateHealth { it.copy(failure = "录音中断 · $reason") }
                RecordingController.publishFeedback(RecordingFeedback.Failed("录音已中断：$reason"))
                app.database.recordingDao().openGap(lastCapturedAtMillis ?: serviceStartedAt, "$reason，等待恢复采集")
            } finally {
                recordingJob = null
                releaseWakeLock()
                if (!stopRequested) withContext(NonCancellable) { shutdownService() }
            }
        }
    }

    private suspend fun recordContinuously() {
        checkRecordAudioPermission()
        val minimumBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBufferSize > 0) { "设备无法提供可用的录音缓冲区" }
        val bufferSize = maxOf(minimumBufferSize * 2, MIN_BUFFER_BYTES)

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord 初始化失败"
        }
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == recorder.audioSessionId }?.let(::updateInputConfiguration)
            }
        }
        try {
            runCatching { recorder.registerAudioRecordingCallback(mainExecutor, callback) }
                .onFailure { Log.w(TAG, "系统音频状态监测不可用，继续保存原音", it) }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "麦克风未进入录音状态"
            }
            runCatching { recorder.activeRecordingConfiguration?.let(::updateInputConfiguration) }
                .onFailure { Log.w(TAG, "暂未获取系统音频状态", it) }
            val buffer = ByteArray(bufferSize)
            while (currentCoroutineContext().isActive) {
                writeChunk(recorder, buffer)
            }
        } finally {
            runCatching { recorder.unregisterAudioRecordingCallback(callback) }
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private suspend fun writeChunk(recorder: AudioRecord, buffer: ByteArray) {
        requireRecordingSpace(filesDir.usableSpace)
        refreshWakeLock()
        val chunkId = UUID.randomUUID().toString()
        val startedAtMillis = System.currentTimeMillis()
        val startedAtElapsed = SystemClock.elapsedRealtime()
        val file = createChunkFile(startedAtMillis)
        val startedZone = java.time.ZoneId.systemDefault()
        val fact = CapturedChunk(chunkId, startedAtMillis, file.absolutePath, SAMPLE_RATE_HZ, CHANNEL_COUNT,
            zoneId = startedZone.id,
            offsetSeconds = startedZone.rules.getOffset(java.time.Instant.ofEpochMilli(startedAtMillis)).totalSeconds,
            localStartDate = java.time.Instant.ofEpochMilli(startedAtMillis).atZone(startedZone).toLocalDate().toString())
        repository.saveCaptureFact(fact)
        val writer = WavChunkWriter(
            file = file,
            sampleRateHz = SAMPLE_RATE_HZ,
            channelCount = CHANNEL_COUNT,
        )
        var state = "RECORDED"
        var errorMessage: String? = null
        var lastCheckpoint = startedAtElapsed
        try {
            while (
                currentCoroutineContext().isActive &&
                SystemClock.elapsedRealtime() - startedAtElapsed < CHUNK_DURATION_MILLIS
            ) {
                when (val bytesRead = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)) {
                    AudioRecord.ERROR_DEAD_OBJECT -> error("录音设备连接已中断")
                    AudioRecord.ERROR_INVALID_OPERATION -> error("AudioRecord 当前状态不可读取")
                    AudioRecord.ERROR_BAD_VALUE -> error("录音缓冲区参数无效")
                    AudioRecord.ERROR -> error("录音设备返回未知错误")
                    else -> if (bytesRead > 0) {
                        writer.write(buffer, bytesRead)
                        val now = System.currentTimeMillis()
                        lastCapturedAtMillis = now
                        val frameStart = now - bytesRead * 1_000L / (SAMPLE_RATE_HZ * CHANNEL_COUNT * 2)
                        val health = RecordingController.health.value
                        val level = pcmLevel(buffer, bytesRead)
                        val silenced = health.clientSilenced == true
                        if (silenced && !inputSilenced) gapEvents.trySend(GapEvent("SYSTEM_SILENCED", frameStart, "系统将音频输入静音，原文件中的这段声音可能缺失"))
                        if (!silenced && inputSilenced) gapEvents.trySend(GapEvent("SYSTEM_SILENCED", frameStart))
                        inputSilenced = silenced
                        if (!resumedInput && !silenced && (health.clientSilenced == false || level > 0)) {
                            resumedInput = true
                            gapEvents.trySend(GapEvent("INTERRUPTION", frameStart))
                            gapEvents.trySend(GapEvent("SYSTEM_SILENCED", frameStart))
                        }
                        if (now - lastMeterAtMillis >= 100) {
                            RecordingController.updateHealth { it.copy(lastBufferAtMillis = now, levels = it.levels.drop(1) + if (silenced) 0f else level) }
                            lastMeterAtMillis = now
                        }
                        if (SystemClock.elapsedRealtime() - lastCheckpoint >= 5_000L) {
                            writer.checkpoint()
                            repository.checkpointCaptureMetadata()
                            lastCheckpoint = SystemClock.elapsedRealtime()
                            val available = filesDir.usableSpace
                            RecordingController.updateHealth { it.copy(storageLow = available < RECORDING_SPACE_WARNING) }
                            requireRecordingSpace(available)
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            state = "FAILED"
            errorMessage = error.message ?: error.javaClass.simpleName
            throw error
        } finally {
            val byteSize = runCatching { writer.finish() }.getOrElse {
                state = "FAILED"
                errorMessage = it.message ?: it.javaClass.simpleName
                file.length()
            }
            repository.saveCaptureFact(fact.copy(
                endedAt = startedAtMillis + WavChunkWriter.durationMillis(byteSize, SAMPLE_RATE_HZ, CHANNEL_COUNT),
                bytes = byteSize, state = state, error = errorMessage,
            ))
        }
    }

    private fun updateInputConfiguration(config: AudioRecordingConfiguration) {
        runCatching {
        val device = config.audioDevice
        val changed = inputDeviceId != null && inputDeviceId != device?.id
        inputDeviceId = device?.id
        val label = when (device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙麦克风"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机麦克风"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 麦克风"
            else -> "系统音频输入"
        }
        RecordingController.updateHealth { it.copy(clientSilenced = config.isClientSilenced, inputDevice = label, deviceChanged = it.deviceChanged || changed) }
        runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification(notificationStartedAtMillis,
            if (config.isClientSilenced) "系统已将输入静音，正在记录缺口" else "$label · 按实际输入显示音量")) }
        }.onFailure {
            RecordingController.updateHealth { value -> value.copy(clientSilenced = null) }
            Log.w(TAG, "系统音频状态读取失败，不能确认静音状态", it)
        }
    }

    private fun markCurrentMoment(intent: Intent?) {
        if (recordingJob?.isActive != true) {
            RecordingController.publishFeedback(
                RecordingFeedback.Failed("当前没有正在进行的录音"),
            )
            return
        }
        serviceScope.launch {
            try {
                val markedAtMillis = System.currentTimeMillis()
                val defaultWindow = (application as SonfolioApplication).preferences.markerWindows.first()
                val windowMinutes = intent
                    ?.getIntExtra(EXTRA_MARK_WINDOW_MINUTES, defaultWindow)
                    ?.coerceIn(1, RecordingRepository.MAX_MARK_MINUTES)
                    ?: defaultWindow
                repository.markNow(markedAtMillis, windowMinutes)
                RecordingController.publishFeedback(
                    RecordingFeedback.Marked(markedAtMillis, windowMinutes),
                )
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(notificationStartedAtMillis, "已标记前 ${windowMinutes} 分钟涉及的对话"),
                )
                (application as SonfolioApplication).conversationRepository.rebuildFromTranscripts(markedAtMillis - windowMinutes * 60_000L, markedAtMillis)
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to save recording marker", error)
                RecordingController.publishFeedback(
                    RecordingFeedback.Failed(error.message ?: "标记保存失败"),
                )
            }
        }
    }

    private fun requestStop() {
        stopRequested = true
        val job = recordingJob
        if (job == null) {
            serviceScope.launch { shutdownService() }
            return
        }
        job.cancel()
        serviceScope.launch {
            job.join()
            shutdownService()
        }
    }

    private suspend fun shutdownService() {
        if (stopRequested && inputSilenced) gapEvents.trySend(GapEvent("SYSTEM_SILENCED", lastCapturedAtMillis ?: System.currentTimeMillis()))
        gapEvents.close()
        gapJob?.join()
        RecordingController.updateHealth { it.copy(serviceActive = false, levels = List(12) { 0f }) }
        (application as SonfolioApplication).preferences.clearRecordingSession()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        RecordingController.health.value.failure?.let { message ->
            notificationManager.notify(FAILURE_NOTIFICATION_ID, NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_wave).setContentTitle("声迹录音已中断")
                .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }
        stopSelf()
    }

    private fun promoteToForeground(startedAtMillis: Long) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(startedAtMillis, "原始音频仅保存在本机"),
            serviceType,
        )
    }

    private fun buildNotification(startedAtMillis: Long, message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val mark = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(RecordingController.ACTION_MARK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingService::class.java).setAction(RecordingController.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_wave)
            .setContentTitle(if (RecordingController.health.value.clientSilenced == true) "声迹 · 输入被静音" else "声迹正在记录")
            .setContentText(message)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(startedAtMillis)
            .setUsesChronometer(true)
            .addAction(R.drawable.ic_notification_wave, "★ 标记刚才", mark)
            .addAction(android.R.drawable.ic_media_pause, "停止", stop)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "持续录音",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示录音状态，并提供标记和停止操作"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun checkRecordAudioPermission() {
        check(
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "没有麦克风权限" }
    }

    private fun createChunkFile(startedAtMillis: Long): File {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startedAtMillis))
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
            .format(Date(startedAtMillis))
        return File(filesDir, "recordings/$date/chunk-$timestamp.wav")
    }

    private fun refreshWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Sonfolio:ContinuousRecording",
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_CHANNEL_ID = "continuous_recording"
        private const val NOTIFICATION_ID = 1001
        private const val FAILURE_NOTIFICATION_ID = 1002
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_COUNT = 1
        private const val MIN_BUFFER_BYTES = 4_096
        // Five-minute storage units let VAD/ASR start while the user is still recording. The
        // conversation merger can still join adjacent units into one readable conversation.
        private const val CHUNK_DURATION_MILLIS = 5 * 60 * 1_000L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 35 * 60 * 1_000L

        const val EXTRA_MARK_WINDOW_MINUTES = "mark_window_minutes"

        @Volatile
        var isRunningInProcess: Boolean = false
            private set
    }
}
