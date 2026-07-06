package com.tropicalstream.tapvibe.music

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Plays an uploaded track through the glasses' speakers. Exposes the MediaPlayer's
 * audio session id so the visualizer can attach a Visualizer to OUR OWN audio
 * (which is permitted and works — unlike the global session-0 mix).
 */
class MusicPlayer {

    private var mp: MediaPlayer? = null
    var currentFile: File? = null
        private set

    /** Called when a new session id is live (playback started). */
    var onSession: ((Int) -> Unit)? = null
    /** Called when the current track finishes. */
    var onCompletion: (() -> Unit)? = null

    fun play(file: File) {
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(file.absolutePath)
            p.setOnPreparedListener {
                it.start()
                onSession?.invoke(it.audioSessionId)
            }
            p.setOnCompletionListener { onCompletion?.invoke() }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "player error $what/$extra on ${file.name}")
                true
            }
            p.prepareAsync()
            mp = p
            currentFile = file
        } catch (t: Throwable) {
            Log.w(TAG, "play failed: ${t.message}")
            runCatching { p.release() }
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
    }

    companion object {
        private const val TAG = "MusicPlayer"
    }
}
