package com.tropicalstream.tapvibe.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.tropicalstream.tapvibe.audio.AudioAnalyzer
import com.tropicalstream.tapvibe.scenes.Neon
import com.tropicalstream.tapvibe.scenes.OrbScene
import com.tropicalstream.tapvibe.scenes.Scene
import com.tropicalstream.tapvibe.scenes.SwarmScene
import com.tropicalstream.tapvibe.scenes.TerrainScene
import com.tropicalstream.tapvibe.scenes.TunnelScene
import com.tropicalstream.tapvibe.scenes.WaveformScene
import com.tropicalstream.tapvibe.scenes.ZenScene

/** Main menu + scene host. Playback/library side effects go through [Host]. */
class StageView(context: Context, private val analyzer: AudioAnalyzer) : View(context) {

    interface Host {
        fun tracks(): List<String>
        fun companionUrl(): String
        fun playTrack(index: Int)
        fun togglePlay()
        fun startMic()
        fun stopAudio()
        fun nowPlaying(): String?
        fun isPlaying(): Boolean
        fun progress(): Float
    }

    var host: Host? = null

    enum class Mode { MENU, ZEN, LIBRARY, VISUALIZER }
    private enum class Source { MUSIC, MIC }

    var mode = Mode.MENU
        private set
    private var source = Source.MUSIC

    private val menu = listOf("ZEN  BREATHING", "MUSIC  LIBRARY", "LIVE  VISUALIZER (mic)")
    private var menuIndex = 0
    private var libIndex = 0

    private val visualScenes: List<Scene> =
        listOf(TunnelScene(), OrbScene(), TerrainScene(), SwarmScene(), WaveformScene())
    private var sceneIndex = 0
    private val zen = ZenScene()
    private var frameTime = 0L

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val meterPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setFrameTime(t: Long) { frameTime = t }

    // ---- gesture intents ----
    fun onSelect(dir: Int) {
        val step = if (dir >= 0) 1 else -1
        when (mode) {
            Mode.MENU -> menuIndex = (menuIndex + step + menu.size) % menu.size
            Mode.LIBRARY -> {
                val n = host?.tracks()?.size ?: 0
                if (n > 0) libIndex = (libIndex + step + n) % n
            }
            else -> {}
        }
    }

    fun onCycle(dir: Int) {
        if (mode == Mode.VISUALIZER) {
            val n = visualScenes.size
            sceneIndex = (sceneIndex + (if (dir >= 0) 1 else -1) + n) % n
        }
    }

    fun onConfirm() {
        when (mode) {
            Mode.MENU -> when (menuIndex) {
                0 -> mode = Mode.ZEN
                1 -> { libIndex = 0; mode = Mode.LIBRARY }
                else -> { source = Source.MIC; host?.startMic(); mode = Mode.VISUALIZER }
            }
            Mode.LIBRARY -> {
                val n = host?.tracks()?.size ?: 0
                if (n > 0) {
                    source = Source.MUSIC
                    host?.playTrack(libIndex.coerceIn(0, n - 1))
                    mode = Mode.VISUALIZER
                }
            }
            Mode.VISUALIZER -> if (source == Source.MUSIC) host?.togglePlay()
            Mode.ZEN -> {}
        }
    }

    fun onBack(): Boolean {
        return when (mode) {
            Mode.MENU -> false
            Mode.ZEN, Mode.LIBRARY -> { host?.stopAudio(); mode = Mode.MENU; true }
            Mode.VISUALIZER -> {
                host?.stopAudio()
                mode = if (source == Source.MUSIC) Mode.LIBRARY else Mode.MENU
                true
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val a = analyzer.audio
        val w = width; val h = height
        when (mode) {
            Mode.MENU -> drawMenu(canvas, w, h)
            Mode.ZEN -> {
                zen.draw(canvas, a, w, h, frameTime)
                footer(canvas, w, h, "ZEN  ·  double-tap to menu")
            }
            Mode.LIBRARY -> drawLibrary(canvas, w, h)
            Mode.VISUALIZER -> {
                visualScenes[sceneIndex].draw(canvas, a, w, h, frameTime)
                header(canvas, w, h, "${sceneIndex + 1}/${visualScenes.size}  ${visualScenes[sceneIndex].name}")
                if (source == Source.MUSIC) drawNowPlaying(canvas, w, h)
                else {
                    levelMeter(canvas, w, h, a.rms, a.active)
                    if (!a.active) hintCenter(canvas, w, h, "no audio — play music (heard via the glasses)")
                }
                footer(canvas, w, h, "swipe ← →  scene   ·   tap  play/pause   ·   double-tap  back")
            }
        }
    }

    // ---- menu ----
    private fun drawMenu(canvas: Canvas, w: Int, h: Int) {
        text.textAlign = Paint.Align.CENTER
        text.color = Neon.hsv(190f, 0.9f, 1f)
        text.setShadowLayer(h * 0.03f, 0f, 0f, Neon.hsv(190f, 0.9f, 1f))
        text.textSize = h * 0.12f
        canvas.drawText("TAPVIBE", w / 2f, h * 0.22f, text)
        text.clearShadowLayer()
        val hues = floatArrayOf(160f, 285f, 45f)
        val ys = floatArrayOf(0.44f, 0.58f, 0.72f)
        for (i in menu.indices) option(canvas, w, h, i, menu[i], h * ys[i], hues[i])
        text.color = Color.WHITE; text.alpha = 110; text.textSize = h * 0.04f
        canvas.drawText("swipe ↑ ↓ to choose   ·   tap to enter", w / 2f, h * 0.92f, text)
        text.alpha = 255
    }

    private fun option(canvas: Canvas, w: Int, h: Int, index: Int, s: String, y: Float, hue: Float) {
        val sel = menuIndex == index
        val color = Neon.hsv(hue, 0.85f, if (sel) 1f else 0.5f)
        text.color = color
        text.alpha = if (sel) 255 else 150
        text.textSize = h * (if (sel) 0.075f else 0.062f)
        if (sel) text.setShadowLayer(h * 0.022f, 0f, 0f, color) else text.clearShadowLayer()
        canvas.drawText((if (sel) "▸  " else "") + s, w / 2f, y, text)
        text.clearShadowLayer(); text.alpha = 255
    }

    // ---- library ----
    private fun drawLibrary(canvas: Canvas, w: Int, h: Int) {
        text.textAlign = Paint.Align.CENTER
        text.color = Neon.hsv(285f, 0.8f, 1f)
        text.textSize = h * 0.075f
        text.setShadowLayer(h * 0.02f, 0f, 0f, Neon.hsv(285f, 0.8f, 1f))
        canvas.drawText("MUSIC LIBRARY", w / 2f, h * 0.13f, text)
        text.clearShadowLayer()

        text.color = Neon.hsv(190f, 0.7f, 1f); text.alpha = 210; text.textSize = h * 0.045f
        canvas.drawText("add music:  ${host?.companionUrl() ?: "…"}", w / 2f, h * 0.22f, text)
        text.alpha = 255

        val tracks = host?.tracks() ?: emptyList()
        if (tracks.isEmpty()) {
            text.color = Color.WHITE; text.alpha = 140; text.textSize = h * 0.05f
            canvas.drawText("no tracks yet", w / 2f, h * 0.48f, text)
            text.textSize = h * 0.04f; text.alpha = 110
            canvas.drawText("open that address in your phone browser and drag music in", w / 2f, h * 0.56f, text)
            text.alpha = 255
            footer(canvas, w, h, "double-tap to menu")
            return
        }

        if (libIndex >= tracks.size) libIndex = tracks.size - 1
        val rows = 5
        val start = (libIndex - rows / 2).coerceIn(0, maxOf(0, tracks.size - rows))
        text.textAlign = Paint.Align.LEFT
        var y = h * 0.34f
        for (i in start until minOf(start + rows, tracks.size)) {
            val sel = i == libIndex
            text.color = if (sel) Neon.hsv(190f, 0.9f, 1f) else Color.WHITE
            text.alpha = if (sel) 255 else 130
            text.textSize = h * (if (sel) 0.058f else 0.05f)
            if (sel) text.setShadowLayer(h * 0.02f, 0f, 0f, Neon.hsv(190f, 0.9f, 1f)) else text.clearShadowLayer()
            val name = tracks[i]
            canvas.drawText((if (sel) "▸ " else "   ") + clip(name, 34), w * 0.10f, y, text)
            y += h * 0.10f
        }
        text.clearShadowLayer(); text.alpha = 255; text.textAlign = Paint.Align.CENTER
        footer(canvas, w, h, "swipe ↑ ↓  select   ·   tap  play   ·   double-tap  menu")
    }

    // ---- now playing (music visualizer) ----
    private fun drawNowPlaying(canvas: Canvas, w: Int, h: Int) {
        val name = host?.nowPlaying() ?: return
        val playing = host?.isPlaying() ?: false
        text.textAlign = Paint.Align.LEFT
        text.clearShadowLayer()
        text.color = Neon.hsv(285f, 0.7f, 1f); text.alpha = 220; text.textSize = h * 0.045f
        canvas.drawText((if (playing) "▶ " else "❚❚ ") + clip(name, 30), w * 0.04f, h * 0.9f, text)
        text.textAlign = Paint.Align.CENTER; text.alpha = 255
        // progress bar
        val p = host?.progress() ?: 0f
        val bx = w * 0.04f; val bw = w * 0.92f; val by = h * 0.94f; val bh = h * 0.012f
        meterPaint.style = Paint.Style.FILL
        meterPaint.color = Neon.hsv(285f, 0.5f, 0.5f); meterPaint.alpha = 60
        canvas.drawRoundRect(bx, by, bx + bw, by + bh, 3f, 3f, meterPaint)
        meterPaint.color = Neon.hsv(285f, 0.85f, 1f); meterPaint.alpha = 230
        canvas.drawRoundRect(bx, by, bx + bw * p.coerceIn(0f, 1f), by + bh, 3f, 3f, meterPaint)
    }

    private fun levelMeter(canvas: Canvas, w: Int, h: Int, rms: Float, active: Boolean) {
        val barW = w * 0.22f; val barH = h * 0.02f; val x = w * 0.74f; val y = h * 0.06f
        meterPaint.style = Paint.Style.FILL
        meterPaint.color = Neon.hsv(190f, 0.6f, 0.6f); meterPaint.alpha = 45
        canvas.drawRoundRect(x, y, x + barW, y + barH, 3f, 3f, meterPaint)
        meterPaint.color = if (active) Neon.hsv(140f, 0.9f, 1f) else Neon.hsv(0f, 0f, 0.5f)
        meterPaint.alpha = 230
        canvas.drawRoundRect(x, y, x + barW * rms.coerceIn(0f, 1f), y + barH, 3f, 3f, meterPaint)
    }

    private fun header(canvas: Canvas, w: Int, h: Int, s: String) {
        text.textAlign = Paint.Align.LEFT; text.clearShadowLayer()
        text.color = Neon.hsv(190f, 0.7f, 1f); text.alpha = 200; text.textSize = h * 0.045f
        canvas.drawText(s, w * 0.04f, h * 0.09f, text)
        text.textAlign = Paint.Align.CENTER; text.alpha = 255
    }

    private fun footer(canvas: Canvas, w: Int, h: Int, s: String) {
        text.textAlign = Paint.Align.CENTER; text.clearShadowLayer()
        text.color = Color.WHITE; text.alpha = 100; text.textSize = h * 0.036f
        canvas.drawText(s, w / 2f, h * 0.965f, text)
        text.alpha = 255
    }

    private fun hintCenter(canvas: Canvas, w: Int, h: Int, s: String) {
        text.textAlign = Paint.Align.CENTER; text.clearShadowLayer()
        text.color = Neon.hsv(50f, 0.9f, 1f); text.alpha = 200; text.textSize = h * 0.05f
        canvas.drawText(s, w / 2f, h * 0.5f, text)
        text.alpha = 255
    }

    private fun clip(s: String, n: Int) = if (s.length <= n) s else s.take(n - 1) + "…"
}
