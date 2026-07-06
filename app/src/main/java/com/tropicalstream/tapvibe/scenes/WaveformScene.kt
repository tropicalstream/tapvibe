package com.tropicalstream.tapvibe.scenes

import android.graphics.Canvas
import com.tropicalstream.tapvibe.audio.Audio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Concept 6 — time-domain waveform helix: a double-ribbon whose radius tracks the
 *  raw wave amplitude, winding across the field of view like an oscillograph. */
class WaveformScene : Scene {
    override val name = "WAVEFORM"

    override fun draw(canvas: Canvas, a: Audio, w: Int, h: Int, timeMs: Long) {
        val t = timeMs / 1000f
        val cy = h / 2f
        val marginX = w * 0.06f
        val span = w - marginX * 2f
        val baseR = h * 0.20f
        val turns = 3.5f
        val nn = Audio.WAVE

        var prevAx = Float.NaN; var prevAy = 0f
        var prevBx = Float.NaN; var prevBy = 0f
        for (i in 0 until nn) {
            val f = i / (nn - 1f)
            val x = marginX + f * span
            val s = a.wave[i]
            val ang = f * turns * 2f * PI.toFloat() + t * 2.2f
            val rad = baseR * (0.35f + 0.65f * abs(s)) + a.rms * baseR * 0.4f
            val ay = cy + sin(ang) * rad
            val by = cy - sin(ang) * rad
            val hue = 170f + f * 160f + a.treble * 60f + t * 20f
            val colA = Neon.hsv(hue, 0.85f, 0.9f)
            val colB = Neon.hsv(hue + 40f, 0.85f, 0.8f)
            if (i > 0) {
                Neon.line(canvas, prevAx, prevAy, x, ay, colA, 2.2f, 0.9f)
                Neon.line(canvas, prevBx, prevBy, x, by, colB, 2.2f, 0.9f)
            }
            if (i % 6 == 0) Neon.line(canvas, x, ay, x, by, Neon.hsv(hue, 0.5f, 0.5f), 1.2f, 0.4f)
            prevAx = x; prevAy = ay; prevBx = x; prevBy = by
        }
    }
}
