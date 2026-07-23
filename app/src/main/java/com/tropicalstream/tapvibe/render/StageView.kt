package com.tropicalstream.tapvibe.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.tropicalstream.tapvibe.audio.AudioAnalyzer
import com.tropicalstream.tapvibe.radio.RadioBrowser
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
        fun radioPlay(url: String, name: String)
        fun radioBuffering(): Boolean
        fun startMic()
        fun stopAudio()
        fun nowPlaying(): String?
        fun isPlaying(): Boolean
        fun progress(): Float
    }

    var host: Host? = null

    enum class Mode { MENU, ZEN, LIBRARY, RADIO, VISUALIZER }
    private enum class Source { MUSIC, MIC, RADIO }

    var mode = Mode.MENU
        private set
    private var source = Source.MUSIC

    private val menu = listOf("ZEN  BREATHING", "MUSIC  LIBRARY", "IP  RADIO", "LIVE  VISUALIZER (mic)")
    private var menuIndex = 0
    private var libIndex = 0

    private val radio = RadioBrowser(context).apply {
        onChanged = { postInvalidate() }
        onPlay = { st -> host?.radioPlay(st.url, st.name) }
    }

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

    fun releaseRadio() = radio.release()

    // ---- gesture intents ----
    fun onSelect(dir: Int) {
        val step = if (dir >= 0) 1 else -1
        when (mode) {
            Mode.MENU -> menuIndex = (menuIndex + step + menu.size) % menu.size
            Mode.LIBRARY -> {
                val n = host?.tracks()?.size ?: 0
                if (n > 0) libIndex = (libIndex + step + n) % n
            }
            Mode.RADIO -> radio.onSelect(dir)
            else -> {}
        }
    }

    fun onCycle(dir: Int) {
        when (mode) {
            Mode.VISUALIZER -> {
                val n = visualScenes.size
                sceneIndex = (sceneIndex + (if (dir >= 0) 1 else -1) + n) % n
            }
            Mode.RADIO -> radio.onCycle(dir)
            else -> {}
        }
    }

    fun onConfirm() {
        when (mode) {
            Mode.MENU -> when (menuIndex) {
                0 -> mode = Mode.ZEN
                1 -> { libIndex = 0; mode = Mode.LIBRARY }
                2 -> { radio.enter(); mode = Mode.RADIO }
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
            Mode.RADIO -> if (radio.onConfirm() == RadioBrowser.Confirm.PLAY) {
                source = Source.RADIO
                mode = Mode.VISUALIZER
            }
            Mode.VISUALIZER -> if (source != Source.MIC) host?.togglePlay()
            Mode.ZEN -> {}
        }
    }

    fun onLongPress() {
        if (mode == Mode.RADIO) radio.onLongPress()
    }

    fun onBack(): Boolean {
        return when (mode) {
            Mode.MENU -> false
            Mode.ZEN, Mode.LIBRARY -> { host?.stopAudio(); mode = Mode.MENU; true }
            Mode.RADIO -> {
                if (radio.onBack()) true
                else { mode = Mode.MENU; true }   // at radio home → back to main menu
            }
            Mode.VISUALIZER -> {
                host?.stopAudio()
                mode = when (source) {
                    Source.MUSIC -> Mode.LIBRARY
                    Source.RADIO -> Mode.RADIO      // station list is still loaded
                    Source.MIC -> Mode.MENU
                }
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
            Mode.RADIO -> drawRadio(canvas, w, h)
            Mode.VISUALIZER -> {
                visualScenes[sceneIndex].draw(canvas, a, w, h, frameTime)
                header(canvas, w, h, "${sceneIndex + 1}/${visualScenes.size}  ${visualScenes[sceneIndex].name}")
                when (source) {
                    Source.MUSIC -> drawNowPlaying(canvas, w, h)
                    Source.RADIO -> drawRadioNowPlaying(canvas, w, h)
                    Source.MIC -> {
                        levelMeter(canvas, w, h, a.rms, a.active)
                        if (!a.active) hintCenter(canvas, w, h, "no audio — play music (heard via the glasses)")
                    }
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
        canvas.drawText("TAPVIBE", w / 2f, h * 0.2f, text)
        text.clearShadowLayer()
        val hues = floatArrayOf(160f, 285f, 200f, 45f)
        val ys = floatArrayOf(0.40f, 0.52f, 0.64f, 0.76f)
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
        text.textSize = h * (if (sel) 0.072f else 0.058f)
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

    // ---- IP radio ----
    private fun drawRadio(canvas: Canvas, w: Int, h: Int) {
        when (radio.screen) {
            RadioBrowser.Screen.HOME -> radioSimpleList(
                canvas, w, h, "IP RADIO", radio.homeItems, radio.index,
                hue = 200f, footer = "swipe ↑ ↓  choose   ·   tap  open   ·   double-tap  menu"
            )
            RadioBrowser.Screen.GENRES -> radioSimpleList(
                canvas, w, h, "GENRES", radio.genres.map { it.first }, radio.index,
                hue = 160f, footer = "swipe ↑ ↓  choose   ·   tap  open   ·   double-tap  back"
            )
            RadioBrowser.Screen.COUNTRIES -> {
                val rows = radio.countryList().map { "${it.name}   (${it.count})" }
                if (rows.isEmpty() && radio.loading) {
                    radioTitle(canvas, w, h, "COUNTRIES", 45f)
                    loadingCenter(canvas, w, h, "loading countries…")
                } else radioSimpleList(
                    canvas, w, h, "COUNTRIES", rows, radio.index,
                    hue = 45f, footer = "swipe ↑ ↓  choose   ·   tap  open   ·   double-tap  back"
                )
            }
            RadioBrowser.Screen.STATIONS -> drawStations(canvas, w, h)
        }
        radio.status?.let { toast(canvas, w, h, it) }
    }

    private fun radioTitle(canvas: Canvas, w: Int, h: Int, t: String, hue: Float) {
        text.textAlign = Paint.Align.CENTER
        text.color = Neon.hsv(hue, 0.85f, 1f)
        text.textSize = h * 0.072f
        text.setShadowLayer(h * 0.02f, 0f, 0f, Neon.hsv(hue, 0.85f, 1f))
        canvas.drawText(t, w / 2f, h * 0.13f, text)
        text.clearShadowLayer()
    }

    private fun radioSimpleList(
        canvas: Canvas, w: Int, h: Int, title: String, items: List<String>,
        sel: Int, hue: Float, footer: String
    ) {
        radioTitle(canvas, w, h, title, hue)
        if (items.isEmpty()) {
            hintCenter(canvas, w, h, "nothing here yet")
            footer(canvas, w, h, footer)
            return
        }
        val rows = 6
        val start = (sel - rows / 2).coerceIn(0, maxOf(0, items.size - rows))
        text.textAlign = Paint.Align.LEFT
        var y = h * 0.28f
        for (i in start until minOf(start + rows, items.size)) {
            val on = i == sel
            text.color = if (on) Neon.hsv(hue, 0.9f, 1f) else Color.WHITE
            text.alpha = if (on) 255 else 125
            text.textSize = h * (if (on) 0.058f else 0.05f)
            if (on) text.setShadowLayer(h * 0.018f, 0f, 0f, Neon.hsv(hue, 0.9f, 1f)) else text.clearShadowLayer()
            canvas.drawText((if (on) "▸ " else "   ") + clip(items[i], 30), w * 0.10f, y, text)
            y += h * 0.098f
        }
        text.clearShadowLayer(); text.alpha = 255; text.textAlign = Paint.Align.CENTER
        footer(canvas, w, h, footer)
    }

    private fun drawStations(canvas: Canvas, w: Int, h: Int) {
        val hue = 200f
        // title + current ranking
        text.textAlign = Paint.Align.LEFT
        text.color = Neon.hsv(hue, 0.85f, 1f); text.textSize = h * 0.06f
        text.setShadowLayer(h * 0.018f, 0f, 0f, Neon.hsv(hue, 0.85f, 1f))
        canvas.drawText(clip(radio.title, 24), w * 0.06f, h * 0.12f, text)
        text.clearShadowLayer()
        if (!radio.isFavoritesScreen()) {
            text.textAlign = Paint.Align.RIGHT; text.color = Neon.hsv(45f, 0.85f, 1f)
            text.alpha = 220; text.textSize = h * 0.042f
            canvas.drawText("⇄ ${radio.orderLabel}", w * 0.94f, h * 0.12f, text)
            text.alpha = 255
        }

        val list = radio.stationList()
        if (list.isEmpty()) {
            if (radio.loading) loadingCenter(canvas, w, h, "finding stations…")
            else hintCenter(canvas, w, h, if (radio.isFavoritesScreen()) "no favorites yet — hold on a station to ★" else "no stations")
            footer(canvas, w, h, "double-tap  back")
            return
        }

        val sel = radio.index.coerceIn(0, list.size - 1)
        val rows = 5
        val start = (sel - rows / 2).coerceIn(0, maxOf(0, list.size - rows))
        text.textAlign = Paint.Align.LEFT
        var y = h * 0.26f
        for (i in start until minOf(start + rows, list.size)) {
            val on = i == sel
            val st = list[i]
            val star = if (radio.isFav(st.url)) "★ " else ""
            text.color = if (on) Neon.hsv(hue, 0.9f, 1f) else Color.WHITE
            text.alpha = if (on) 255 else 120
            text.textSize = h * (if (on) 0.055f else 0.048f)
            if (on) text.setShadowLayer(h * 0.016f, 0f, 0f, Neon.hsv(hue, 0.9f, 1f)) else text.clearShadowLayer()
            canvas.drawText((if (on) "▸ " else "   ") + star + clip(st.name, 28), w * 0.06f, y, text)
            y += h * 0.088f
        }
        text.clearShadowLayer()

        // selected station's meta line
        val meta = list[sel].meta
        if (meta.isNotBlank()) {
            text.textAlign = Paint.Align.LEFT; text.color = Neon.hsv(285f, 0.55f, 1f)
            text.alpha = 200; text.textSize = h * 0.04f
            canvas.drawText(clip(meta, 46), w * 0.06f, h * 0.76f, text)
            text.alpha = 255
        }
        if (radio.loading) {
            text.textAlign = Paint.Align.RIGHT; text.color = Neon.hsv(200f, 0.7f, 1f)
            text.alpha = (140 + 100 * kotlin.math.sin(frameTime * 0.006f)).toInt().coerceIn(60, 255)
            text.textSize = h * 0.04f
            canvas.drawText("· · ·", w * 0.94f, h * 0.76f, text)
            text.alpha = 255
        }
        text.textAlign = Paint.Align.CENTER
        footer(canvas, w, h, "↑↓ browse   ·   ⇄ rank   ·   tap play   ·   hold ★   ·   2× back")
    }

    private fun loadingCenter(canvas: Canvas, w: Int, h: Int, s: String) {
        text.textAlign = Paint.Align.CENTER; text.clearShadowLayer()
        val pulse = (170 + 85 * kotlin.math.sin(frameTime * 0.006f)).toInt().coerceIn(80, 255)
        text.color = Neon.hsv(200f, 0.7f, 1f); text.alpha = pulse; text.textSize = h * 0.055f
        canvas.drawText(s, w / 2f, h * 0.5f, text)
        text.alpha = 255
    }

    private fun toast(canvas: Canvas, w: Int, h: Int, s: String) {
        text.textAlign = Paint.Align.CENTER; text.clearShadowLayer()
        text.color = Neon.hsv(50f, 0.9f, 1f); text.alpha = 235; text.textSize = h * 0.05f
        text.setShadowLayer(h * 0.02f, 0f, 0f, Neon.hsv(50f, 0.9f, 1f))
        canvas.drawText(s, w / 2f, h * 0.88f, text)
        text.clearShadowLayer(); text.alpha = 255
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

    // ---- now playing (radio visualizer) ----
    private fun drawRadioNowPlaying(canvas: Canvas, w: Int, h: Int) {
        val name = host?.nowPlaying() ?: return
        val buffering = host?.radioBuffering() ?: false
        text.textAlign = Paint.Align.LEFT; text.clearShadowLayer()
        text.color = Neon.hsv(200f, 0.75f, 1f); text.alpha = 230; text.textSize = h * 0.05f
        canvas.drawText("📻 " + clip(name, 28), w * 0.04f, h * 0.9f, text)
        // live / buffering pill, top-right
        text.textAlign = Paint.Align.RIGHT
        if (buffering) {
            val pulse = (150 + 90 * kotlin.math.sin(frameTime * 0.008f)).toInt().coerceIn(70, 255)
            text.color = Neon.hsv(45f, 0.9f, 1f); text.alpha = pulse
        } else {
            text.color = Neon.hsv(0f, 0.85f, 1f); text.alpha = 235
        }
        text.textSize = h * 0.045f
        canvas.drawText(if (buffering) "⟳ buffering" else "◉ LIVE", w * 0.96f, h * 0.09f, text)
        text.textAlign = Paint.Align.CENTER; text.alpha = 255
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
