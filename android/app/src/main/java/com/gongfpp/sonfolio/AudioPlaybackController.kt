package com.gongfpp.sonfolio

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class AudioPlaybackController {
    var timeline = PlaybackTimeline(emptyList())
    var playing by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null
    private var index = 0
    private var resumeAfterSeek = false

    fun seek(position: Long, autoPlay: Boolean = playing) {
        val target = timeline.locate(position) ?: return
        releasePlayer()
        error = null
        this.position = target.position
        index = target.index
        resumeAfterSeek = autoPlay
        preparing = true
        val media = MediaPlayer()
        player = media
        runCatching {
            media.setDataSource(timeline.slices[index].path)
            media.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                it.seekTo(target.fileOffset, MediaPlayer.SEEK_CLOSEST)
            }
            media.setOnSeekCompleteListener {
                if (player !== it) return@setOnSeekCompleteListener
                preparing = false
                if (resumeAfterSeek) { it.start(); playing = true }
            }
            media.setOnCompletionListener { if (player === it) advance() }
            media.setOnErrorListener { failedPlayer, _, _ -> if (player === failedPlayer) fail(); true }
            media.prepareAsync()
        }.onFailure { fail() }
    }

    fun toggle() {
        if (playing || preparing) {
            pause()
        } else if (player != null) {
            runCatching { player?.start(); playing = true }.onFailure { fail() }
        } else {
            seek(if (position >= timeline.duration) 0 else position, autoPlay = true)
        }
    }

    fun pause() {
        resumeAfterSeek = false
        if (playing) runCatching { player?.pause() }
        playing = false
    }

    fun tick() {
        if (!playing || preparing) return
        val slice = timeline.slices.getOrNull(index) ?: return
        runCatching {
            val offset = player?.currentPosition?.toLong() ?: return
            position = timeline.position(index, offset)
            if (slice.chunkStart + offset >= slice.end) advance()
        }.onFailure { fail() }
    }

    private fun advance() {
        val next = timeline.slices.getOrNull(index + 1)
        if (next == null) { releasePlayer(); position = timeline.duration }
        else seek(next.start - timeline.start, autoPlay = true)
    }

    private fun fail() {
        releasePlayer()
        error = "原始录音暂时无法播放，请在原始录音中检查文件"
    }

    private fun releasePlayer() {
        val old = player
        player = null
        runCatching { old?.release() }
        playing = false
        preparing = false
    }

    fun release() = releasePlayer()
}
