package com.tropicalstream.tapvibe.scenes

import android.graphics.Canvas
import com.tropicalstream.tapvibe.audio.Audio
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Concept 2 — audio-reactive orb: a wireframe sphere whose vertices are pushed
 *  along their normals by bass (organic pulse) with treble crystalline spikes. */
class OrbScene : Scene {
    override val name = "ORB"

    private val lat = 12
    private val lon = 18
    private val ux = Array(lat + 1) { FloatArray(lon + 1) }
    private val uy = Array(lat + 1) { FloatArray(lon + 1) }
    private val uz = Array(lat + 1) { FloatArray(lon + 1) }
    private val theta = Array(lat + 1) { FloatArray(lon + 1) }
    private val phi = Array(lat + 1) { FloatArray(lon + 1) }
    private val px = Array(lat + 1) { FloatArray(lon + 1) }
    private val py = Array(lat + 1) { FloatArray(lon + 1) }

    init {
        for (i in 0..lat) {
            val th = PI.toFloat() * i / lat
            for (j in 0..lon) {
                val ph = 2f * PI.toFloat() * j / lon
                theta[i][j] = th; phi[i][j] = ph
                ux[i][j] = sin(th) * cos(ph)
                uy[i][j] = cos(th)
                uz[i][j] = sin(th) * sin(ph)
            }
        }
    }

    override fun draw(canvas: Canvas, a: Audio, w: Int, h: Int, timeMs: Long) {
        val t = timeMs / 1000f
        val focal = minOf(w, h) * 1.15f
        val camZ = 3.1f
        val cy = cos(t * 0.5f); val sy = sin(t * 0.5f)
        val cx = cos(t * 0.22f); val sx = sin(t * 0.22f)

        for (i in 0..lat) for (j in 0..lon) {
            val n = 0.5f * (sin(3f * phi[i][j] + t * 1.7f) + cos(4f * theta[i][j] - t * 1.1f))
            val disp = 1f + a.bass * 0.6f * n + a.treble * 0.3f * sin(6f * phi[i][j] + t * 3f) + a.beat * 0.28f
            var x = ux[i][j] * disp; var y = uy[i][j] * disp; var z = uz[i][j] * disp
            // rotate Y then X
            var nx = x * cy + z * sy; var nz = -x * sy + z * cy; x = nx; z = nz
            val ny = y * cx - z * sx; nz = y * sx + z * cx; y = ny; z = nz
            val p = Proj.project(x, y, z, w, h, camZ, focal)
            px[i][j] = p[0]; py[i][j] = p[1]
        }

        val hue = 190f + a.bass * 80f + t * 25f
        for (i in 0..lat) for (j in 0..lon) {
            val v = (0.55f + 0.45f * a.bands[(i + j) % Audio.BANDS]).coerceIn(0f, 1f)
            val col = Neon.hsv(hue + i * 4f, 0.85f, v)
            if (j < lon) Neon.line(canvas, px[i][j], py[i][j], px[i][j + 1], py[i][j + 1], col, 1.7f, 0.7f + a.rms * 0.5f)
            if (i < lat) Neon.line(canvas, px[i][j], py[i][j], px[i + 1][j], py[i + 1][j], col, 1.7f, 0.7f + a.rms * 0.5f)
        }
    }
}
