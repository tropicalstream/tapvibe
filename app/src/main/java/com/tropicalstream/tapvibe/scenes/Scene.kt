package com.tropicalstream.tapvibe.scenes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.tropicalstream.tapvibe.audio.Audio

/** One audio-reactive visualization. Scenes are stateful (own their geometry). */
interface Scene {
    val name: String
    fun draw(canvas: Canvas, a: Audio, w: Int, h: Int, timeMs: Long)
}

/** Neon draw helpers: 2-pass glow (wide faint + crisp core) on a black canvas. */
object Neon {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, glow: Float = 1f) {
        if (x1.isNaN() || x2.isNaN()) return
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.alpha = (55 * glow).toInt().coerceIn(0, 255)
        paint.strokeWidth = width * 3f
        c.drawLine(x1, y1, x2, y2, paint)
        paint.alpha = 255
        paint.strokeWidth = width
        c.drawLine(x1, y1, x2, y2, paint)
    }

    fun dot(c: Canvas, x: Float, y: Float, r: Float, color: Int, glow: Float = 1f) {
        if (x.isNaN()) return
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = (45 * glow).toInt().coerceIn(0, 255)
        c.drawCircle(x, y, r * 2.4f, paint)
        paint.alpha = 255
        c.drawCircle(x, y, r, paint)
    }

    fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, width: Float, glow: Float = 1f) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.alpha = (50 * glow).toInt().coerceIn(0, 255)
        paint.strokeWidth = width * 3f
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
        paint.alpha = 255
        paint.strokeWidth = width
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
    }

    fun hsv(h: Float, s: Float, v: Float, a: Int = 255): Int {
        val rgb = Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }
}

/** Minimal perspective projection to screen space. */
object Proj {
    /** Returns [screenX, screenY, scale]; scale/x NaN when behind the camera. */
    fun project(x: Float, y: Float, z: Float, w: Int, h: Int, camZ: Float, focal: Float): FloatArray {
        val zc = z + camZ
        if (zc <= 0.05f) return floatArrayOf(Float.NaN, Float.NaN, 0f)
        val s = focal / zc
        return floatArrayOf(w / 2f + x * s, h / 2f - y * s, s)
    }
}
