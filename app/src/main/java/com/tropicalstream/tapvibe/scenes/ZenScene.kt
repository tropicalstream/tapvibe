package com.tropicalstream.tapvibe.scenes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.tropicalstream.tapvibe.audio.Audio
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Zen breathing: concentric torus rings expand/contract to a calm box-breathing
 *  pace (4s inhale · 4s hold · 4s exhale · 4s hold). Ambient audio delicately
 *  ripples the ring surfaces without altering the breathing rhythm. */
class ZenScene : Scene {
    override val name = "ZEN"

    private val rings = 5
    private val seg = 48
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    override fun draw(canvas: Canvas, a: Audio, w: Int, h: Int, timeMs: Long) {
        val t = timeMs / 1000f
        val period = 16f
        val ph = t % period
        val (phase, scale) = when {
            ph < 4f -> "INHALE" to smooth(ph / 4f)
            ph < 8f -> "HOLD" to 1f
            ph < 12f -> "EXHALE" to (1f - smooth((ph - 8f) / 4f))
            else -> "HOLD" to 0f
        }

        val cx = w / 2f
        val cy = h / 2f
        val maxR = minOf(w, h) * 0.42f
        val breath = 0.42f + 0.58f * scale
        val ripple = a.treble * 0.06f + a.rms * 0.03f

        for (r in 0 until rings) {
            val ringR = maxR * ((r + 1f) / rings) * breath
            val hue = 175f + r * 12f + t * 5f
            val col = Neon.hsv(hue, 0.55f, (0.55f + 0.35f * scale).coerceIn(0f, 1f))
            var prevX = Float.NaN; var prevY = 0f
            for (s in 0..seg) {
                val ang = s.toFloat() / seg * 2f * PI.toFloat()
                val rr = ringR * (1f + ripple * sin(6f * ang + t * 2f + r))
                val x = cx + cos(ang) * rr
                val y = cy + sin(ang) * rr
                if (s > 0) Neon.line(canvas, prevX, prevY, x, y, col, 2f, 0.7f + 0.4f * scale)
                prevX = x; prevY = y
            }
        }

        // Soft centre that swells with the breath.
        Neon.dot(canvas, cx, cy, maxR * 0.10f * breath, Neon.hsv(185f, 0.5f, 0.9f), 1f)

        // Guidance.
        label.textSize = h * 0.09f
        label.color = Neon.hsv(185f, 0.4f, 1f)
        label.alpha = 200
        canvas.drawText(phase, cx, cy - h * 0.30f, label)
        label.textSize = h * 0.04f
        label.color = Color.WHITE
        label.alpha = 110
        canvas.drawText("breathe with the rings", cx, h * 0.93f, label)
    }

    private fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
}
