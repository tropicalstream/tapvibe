package com.tropicalstream.tapvibe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.tropicalstream.tapvibe.audio.AudioAnalyzer
import com.tropicalstream.tapvibe.input.TrackpadGestureEngine
import com.tropicalstream.tapvibe.music.MusicLibrary
import com.tropicalstream.tapvibe.music.MusicPlayer
import com.tropicalstream.tapvibe.net.CompanionServer
import com.tropicalstream.tapvibe.render.StageView
import com.tropicalstream.tapvibe.ui.BinocularSbsLayout

class MainActivity : Activity(), StageView.Host {

    private val analyzer = AudioAnalyzer()
    private val gestures = TrackpadGestureEngine()
    private val player = MusicPlayer()
    private lateinit var library: MusicLibrary
    private lateinit var view: StageView
    private var server: CompanionServer? = null

    private enum class AudioMode { NONE, MIC, MUSIC }
    private var audioMode = AudioMode.NONE
    private var trackIndex = 0

    private var running = false
    private var lastDrawMs = 0L

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersive()
        library = MusicLibrary(this)
        view = StageView(this, analyzer).also { it.host = this }
        setContentView(BinocularSbsLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view)
        })

        player.onSession = { id -> if (audioMode == AudioMode.MUSIC) analyzer.startSession(id) }
        player.onCompletion = { autoNext() }

        gestures.onTap = { view.onConfirm() }
        gestures.onDoubleTap = { view.onBack() }
        gestures.onSwipeVertical = { dir -> view.onSelect(dir) }
        gestures.onSwipeHorizontal = { dir -> view.onCycle(dir) }

        startServer()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    private fun startServer() {
        runCatching {
            CompanionServer(CompanionServer.PORT, library) {}.also {
                it.start(NanoHTTPD_TIMEOUT, false)
                server = it
            }
        }.onFailure { Log.w(TAG, "server start failed: ${it.message}") }
    }

    // ---- StageView.Host ----
    override fun tracks(): List<String> = library.tracks().map { library.displayName(it) }

    override fun companionUrl(): String =
        "http://${CompanionServer.deviceIp() ?: "no-wifi"}:${CompanionServer.PORT}"

    override fun playTrack(index: Int) {
        val files = library.tracks()
        if (index !in files.indices) return
        trackIndex = index
        audioMode = AudioMode.MUSIC
        player.play(files[index])
    }

    private fun autoNext() {
        val files = library.tracks()
        if (files.isEmpty()) return
        trackIndex = (trackIndex + 1) % files.size
        player.play(files[trackIndex])
    }

    override fun togglePlay() = player.toggle()

    override fun startMic() {
        audioMode = AudioMode.MIC
        analyzer.startMic()
    }

    override fun stopAudio() {
        audioMode = AudioMode.NONE
        analyzer.stop()
        player.stop()
    }

    override fun nowPlaying(): String? = player.currentFile?.let { library.displayName(it) }
    override fun isPlaying(): Boolean = player.isPlaying
    override fun progress(): Float = player.progress()

    private fun configureImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.decorView.setBackgroundColor(Color.BLACK)
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            if (now - lastDrawMs >= 32L) {
                lastDrawMs = now
                view.setFrameTime(now)
                view.invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // If a mode is already active, (re)arm capture now that the mic is granted.
        rearmAudio()
    }

    private fun rearmAudio() {
        when (audioMode) {
            AudioMode.MIC -> analyzer.startMic()
            AudioMode.MUSIC -> if (player.sessionId != 0) analyzer.startSession(player.sessionId)
            AudioMode.NONE -> {}
        }
    }

    override fun onResume() {
        super.onResume()
        rearmAudio()
        running = true
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        super.onPause()
        running = false
        analyzer.stop()   // free the mic/visualizer; music player keeps going
    }

    override fun onDestroy() {
        super.onDestroy()
        gestures.release()
        analyzer.stop()
        player.stop()
        runCatching { server?.stop() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gestures.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (gestures.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gestures.onGenericMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    companion object {
        private const val TAG = "TapVibe"
        private const val REQ_MIC = 1001
        private const val NanoHTTPD_TIMEOUT = 5000
    }
}
