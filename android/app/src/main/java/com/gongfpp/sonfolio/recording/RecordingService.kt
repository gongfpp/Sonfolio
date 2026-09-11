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

class RecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy {
        (application as SonfolioApplication).recordingRepository
    }
    private var recordingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationStartedAtMillis = 0L
    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        isRunningInProcess = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: RecordingController.ACTION_START) {
            RecordingController.ACTION_STOP -> requestStop()
            RecordingController.ACTION_MARK -> markCurrentMoment(intent)
            RecordingController.ACTION_START -> startRecordingIfNeeded()
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
        super.onDestroy()
    }

    private fun startRecordingIfNeeded() {
        if (recordingJob?.isActive == true) return

        stopRequested = false
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
                repository.recordGap(
                    startedAtMillis = serviceStartedAt,
                    endedAtMillis = System.currentTimeMillis(),
                    reason = error.message ?: error.javaClass.simpleName,
                    recoveredAutomatically = false,
                )
                stopSelf()
            }
            return
        }
        recordingJob = serviceScope.launch {
            try {
                repository.recoverDanglingChunks(serviceStartedAt)
                recordContinuously()
            } catch (_: CancellationException) {
                // Normal stop path; the active chunk is finalized in writeChunk().
            } catch (error: Throwable) {
                Log.e(TAG, "Continuous recording stopped unexpectedly", error)
                val failedAt = System.currentTimeMillis()
                repository.recordGap(
                    startedAtMillis = failedAt,
                    endedAtMillis = failedAt + 1_000,
                    reason = error.message ?: error.javaClass.simpleName,
                    recoveredAutomatically = false,
                )
            } finally {
                recordingJob = null
                releaseWakeLock()
                if (!stopRequested) shutdownService()
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

        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "麦克风未进入录音状态"
            }
            val buffer = ByteArray(bufferSize)
            while (currentCoroutineContext().isActive) {
                writeChunk(recorder, buffer)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private suspend fun writeChunk(recorder: AudioRecord, buffer: ByteArray) {
        refreshWakeLock()
        val chunkId = UUID.randomUUID().toString()
        val startedAtMillis = System.currentTimeMillis()
        val startedAtElapsed = SystemClock.elapsedRealtime()
        val file = createChunkFile(startedAtMillis)
        val writer = WavChunkWriter(
            file = file,
            sampleRateHz = SAMPLE_RATE_HZ,
            channelCount = CHANNEL_COUNT,
        )
        try {
            repository.beginChunk(
                id = chunkId,
                startedAtMillis = startedAtMillis,
                file = file,
                sampleRateHz = SAMPLE_RATE_HZ,
                channelCount = CHANNEL_COUNT,
            )
        } catch (error: Throwable) {
            runCatching { writer.finish() }
            throw error
        }

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
                        if (SystemClock.elapsedRealtime() - lastCheckpoint >= 5_000L) {
                            val byteSize = writer.checkpoint()
                            serviceScope.launch {
                                runCatching { (application as SonfolioApplication).database.recordingDao().checkpoint(chunkId, byteSize) }
                                    .onFailure { Log.w(TAG, "录音状态更新延迟，音频已经落盘", it) }
                            }
                            lastCheckpoint = SystemClock.elapsedRealtime()
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
            withContext(NonCancellable) {
                repository.finishChunk(
                    id = chunkId,
                    endedAtMillis = startedAtMillis + WavChunkWriter.durationMillis(byteSize, SAMPLE_RATE_HZ, CHANNEL_COUNT),
                    byteSize = byteSize,
                    state = state,
                    errorMessage = errorMessage,
                )
            }
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
                val windowMinutes = intent
                    ?.getIntExtra(EXTRA_MARK_WINDOW_MINUTES, RecordingRepository.DEFAULT_MARK_MINUTES)
                    ?.coerceIn(1, RecordingRepository.MAX_MARK_MINUTES)
                    ?: RecordingRepository.DEFAULT_MARK_MINUTES
                repository.markNow(markedAtMillis, windowMinutes)
                RecordingController.publishFeedback(
                    RecordingFeedback.Marked(markedAtMillis, windowMinutes),
                )
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(notificationStartedAtMillis, "已标记前 ${windowMinutes} 分钟涉及的对话"),
                )
                (application as SonfolioApplication).conversationRepository.rebuildFromTranscripts()
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
            shutdownService()
            return
        }
        job.cancel()
        serviceScope.launch {
            job.join()
            shutdownService()
        }
    }

    private fun shutdownService() {
        (application as SonfolioApplication).preferences.clearRecordingSession()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
            .setContentTitle("声迹正在记录")
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
