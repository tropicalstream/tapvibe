package com.tropicalstream.tapvibe.scenes

import android.graphics.Canvas
import com.tropicalstream.tapvibe.audio.Audio
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Concept 3 — warp-speed frequency tunnel: rings recede, each ring segment's
 *  radius driven by a frequency band; you fly through the melody. */
class TunnelScene : Scene {
    override val name = "TUNNEL"

    private val seg = 24
    private val rings = 16

    override fun draw(canvas: Canvas, a: Audio, w: Int, h: Int, timeMs: Long) {
        val focal = minOf(w, h) * 0.62f
        val camZ = 0.25f
        val t = timeMs / 1000f
        val spacing = 0.5f
        val scroll = (t * 1.7f) % spacing
        val rot = t * 0.2f + a.bass * 0.5f
        val baseR = 0.95f

        for (i in rings downTo 1) {
            val z = i * spacing - scroll
            if (z < 0.06f) continue
            val depth = 1f - z / (rings * spacing)
            val hue = 210f + 150f * depth + a.treble * 50f + t * 18f
            val bright = ((0.45f + 0.55f * depth) * (0.65f + 0.5f * a.rms)).coerceIn(0f, 1f)
            val color = Neon.hsv(hue, 0.9f, bright)
            val glow = 0.7f + 0.6f * depth

            var prevX = Float.NaN
            var prevY = 0f
            for (s in 0..seg) {
                val ang = s.toFloat() / seg * (2f * PI.toFloat()) + rot
                val band = a.bands[s % Audio.BANDS]
                val r = baseR * (1f + 0.55f * band + 0.25f * a.bass)
                val p = Proj.project(cos(ang) * r, sin(ang) * r, z, w, h, camZ, focal)
                if (s > 0) Neon.line(canvas, prevX, prevY, p[0], p[1], color, 2f, glow)
                prevX = p[0]; prevY = p[1]
            }
        }
    }
}
