package com.tropicalstream.tapvibe.music

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Plays an uploaded track OR an internet-radio stream through the glasses'
 * speakers. Exposes the MediaPlayer's audio session id so the visualizer can
 * attach a Visualizer to OUR OWN audio (permitted and clean — unlike the global
 * session-0 mix), which means radio streams drive the same neon scenes as music.
 */
class MusicPlayer {

    private var mp: MediaPlayer? = null
    var currentFile: File? = null
        private set
    /** For streams (no File): the station title to show as "now playing". */
    var streamTitle: String? = null
        private set
    /** True from a stream's prepare/buffer start until playback resumes. */
    var buffering: Boolean = false
        private set

    /** Called when a new session id is live (playback started). */
    var onSession: ((Int) -> Unit)? = null
    /** Called when the current track finishes (files only; streams never end). */
    var onCompletion: (() -> Unit)? = null
    /** Called when buffering/playback state changes, so the UI can redraw. */
    var onStateChange: (() -> Unit)? = null

    fun play(file: File) {
        val p = begin() ?: return
        try {
            p.setDataSource(file.absolutePath)
            p.setOnPreparedListener {
                it.start()
                buffering = false; onStateChange?.invoke()
                onSession?.invoke(it.audioSessionId)
            }
            p.setOnCompletionListener { onCompletion?.invoke() }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "player error $what/$extra on ${file.name}"); true
            }
            p.prepareAsync()
            mp = p
            currentFile = file; streamTitle = null
        } catch (t: Throwable) {
            Log.w(TAG, "play failed: ${t.message}")
            runCatching { p.release() }
        }
    }

    /** Stream an internet-radio URL. [title] is shown as "now playing". */
    fun playUrl(url: String, title: String) {
        val p = begin() ?: return
        buffering = true
        try {
            p.setDataSource(url)
            p.setOnPreparedListener {
                it.start()
                buffering = false; onStateChange?.invoke()
                onSession?.invoke(it.audioSessionId)
            }
            // Streams don't complete; surface buffer stalls to the UI.
            p.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> { buffering = true; onStateChange?.invoke() }
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> { buffering = false; onStateChange?.invoke() }
                }
                false
            }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "stream error $what/$extra on $url")
                buffering = false; onStateChange?.invoke(); true
            }
            p.prepareAsync()
            mp = p
            currentFile = null; streamTitle = title
        } catch (t: Throwable) {
            Log.w(TAG, "stream failed: ${t.message}")
            buffering = false
            runCatching { p.release() }
        }
    }

    private fun begin(): MediaPlayer? {
        stop()
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer create failed: ${t.message}"); null
        }
    }

    fun toggle() {
        mp?.let { if (it.isPlaying) it.pause() else it.start() }
    }

    val isPlaying: Boolean get() = runCatching { mp?.isPlaying == true }.getOrDefault(false)
    val sessionId: Int get() = runCatching { mp?.audioSessionId ?: 0 }.getOrDefault(0)

    fun progress(): Float {
        val p = mp ?: return 0f
        return runCatching {
            if (p.duration > 0) (p.currentPosition.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
        }.getOrDefault(0f)
    }

    fun stop() {
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        mp = null
        currentFile = null
        streamTitle = null
        buffering = false
    }

    companion object {
        private const val TAG = "MusicPlayer"
    }
}
