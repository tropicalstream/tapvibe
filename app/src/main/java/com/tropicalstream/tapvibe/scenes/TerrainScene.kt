package com.tropicalstream.tapvibe.scenes

import android.graphics.Canvas
import com.tropicalstream.tapvibe.audio.Audio

/** Concept 1 — deformed vertex terrain: a scrolling FFT-history heightfield.
 *  Newest spectrum enters at the front and travels into the distance. */
class TerrainScene : Scene {
    override val name = "TERRAIN"

    private val cols = 18
    private val rows = 15
    private val height = Array(rows) { FloatArray(cols) }
    private val px = Array(rows) { FloatArray(cols) }
    private val py = Array(rows) { FloatArray(cols) }
    private val ok = Array(rows) { BooleanArray(cols) }

    override fun draw(canvas: Canvas, a: Audio, w: Int, h: Int, timeMs: Long) {
        // Scroll rows back, insert the current spectrum at the front (row 0).
        for (r in rows - 1 downTo 1) System.arraycopy(height[r - 1], 0, height[r], 0, cols)
        for (c in 0 until cols) {
            height[0][c] = a.bands[(c * Audio.BANDS / cols).coerceIn(0, Audio.BANDS - 1)]
        }

        val focal = minOf(w, h) * 0.95f
        val camZ = 0.6f
        val gridW = 3.4f
        val rowDepth = 0.3f
        val hScale = 0.85f
        val t = timeMs / 1000f

        for (r in 0 until rows) {
            val z = r * rowDepth
            for (c in 0 until cols) {
                val x = (c / (cols - 1f) - 0.5f) * gridW
                val yv = height[r][c] * hScale - 0.55f
                val p = Proj.project(x, yv, z, w, h, camZ, focal)
                px[r][c] = p[0]; py[r][c] = p[1]; ok[r][c] = !p[0].isNaN()
            }
        }

        for (r in 0 until rows) {
            val depth = 1f - r / (rows - 1f)
            val hue = 285f - 130f * depth + a.mid * 40f + t * 8f
            val col = Neon.hsv(hue, 0.85f, (0.35f + 0.6f * depth).coerceIn(0f, 1f))
            val glow = 0.5f + 0.5f * depth
            for (c in 1 until cols) if (ok[r][c] && ok[r][c - 1])
                Neon.line(canvas, px[r][c - 1], py[r][c - 1], px[r][c], py[r][c], col, 1.8f, glow)
            if (r > 0) for (c in 0 until cols) if (ok[r][c] && ok[r - 1][c])
                Neon.line(canvas, px[r - 1][c], py[r - 1][c], px[r][c], py[r][c], col, 1.4f, glow * 0.8f)
        }
    }
}
