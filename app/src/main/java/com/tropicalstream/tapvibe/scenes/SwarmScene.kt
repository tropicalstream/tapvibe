package com.tropicalstream.tapvibe.scenes

import android.graphics.Canvas
import com.tropicalstream.tapvibe.audio.Audio
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Concept 4 — swarm attractor: particles pulled between four gravity anchors
 *  whose strengths track Sub-bass / Bass / Mid / Treble. */
class SwarmScene : Scene {
    override val name = "SWARM"

    private val n = 220
    private val x = FloatArray(n); private val y = FloatArray(n); private val z = FloatArray(n)
    private val vx = FloatArray(n); private val vy = FloatArray(n); private val vz = FloatArray(n)
    private var seeded = false

    private val ax = floatArrayOf(-1.25f, 1.25f, 0f, 0f)
    private val ay = floatArrayOf(0.7f, 0.7f, -1.0f, 0f)
    private val az = floatArrayOf(0f, 0f, 0.6f, -0.5f)

    private fun seed() {
        for (i in 0 until n) {
            x[i] = Random.nextFloat() * 2f - 1f
            y[i] = Random.nextFloat() * 2f - 1f
            z[i] = Random.nextFloat() * 2f - 1f
        }
        seeded = true
    }

    override fun draw(canvas: Canvas, a: Audio, w: Int, h: Int, timeMs: Long) {
        if (!seeded) seed()
        val strength = floatArrayOf(a.subBass, a.bass, a.mid, a.treble)
        val dt = 0.032f
        val focal = minOf(w, h) * 1.1f
        val camZ = 3.3f
        val t = timeMs / 1000f
        val cY = cos(t * 0.3f); val sY = sin(t * 0.3f)

        for (i in 0 until n) {
            var fx = -x[i] * 0.05f; var fy = -y[i] * 0.05f; var fz = -z[i] * 0.05f  // mild centering
            for (k in 0 until 4) {
                val dx = ax[k] - x[i]; val dy = ay[k] - y[i]; val dz = az[k] - z[i]
                val d2 = dx * dx + dy * dy + dz * dz + 0.35f
                val f = (0.02f + strength[k] * 0.6f) / d2
                fx += dx * f; fy += dy * f; fz += dz * f
            }
            vx[i] = (vx[i] + fx * dt) * 0.95f
            vy[i] = (vy[i] + fy * dt) * 0.95f
            vz[i] = (vz[i] + fz * dt) * 0.95f
            x[i] += vx[i] * dt * 6f; y[i] += vy[i] * dt * 6f; z[i] += vz[i] * dt * 6f

            val rx = x[i] * cY + z[i] * sY
            val rz = -x[i] * sY + z[i] * cY
            val p = Proj.project(rx, y[i], rz, w, h, camZ, focal)
            if (p[0].isNaN()) continue
            val speed = (vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i])
            val col = Neon.hsv(260f + speed * 900f + a.treble * 60f, 0.8f, (0.6f + a.rms * 0.5f).coerceIn(0f, 1f))
            val r = (p[2] * 0.010f + a.beat * 1.5f).coerceIn(1.2f, 5f)
            Neon.dot(canvas, p[0], p[1], r, col, 0.9f)
        }
    }
}
