package com.tropicalstream.tapvibe.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two capture modes, one analysis pipeline:
 *  - startSession(id) — attaches android.media.audiofx.Visualizer to OUR MusicPlayer's
 *    audio session. This is the featured path for uploaded music: it's our own audio,
 *    so capture is permitted and clean (no ambient bleed).
 *  - startMic() — AudioRecord from the microphone, for "live" ambient visualization.
 *    (Visualizer on session 0 / the global mix is silenced for third-party apps, so
 *    the mic is the only reliable way to react to *external* audio.)
 *
 * Both feed [feedSpectrum] (Hann-window is applied on the mic path; the Visualizer
 * FFT is already windowed) + [feedWave]: log bins + Sub/Bass/Mid/Treble, auto-gain,
 * asymmetric attack/decay, RMS envelope, bass beat.
 */
class AudioAnalyzer {

    val audio = Audio()

    private val micN = 1024
    private val re = FloatArray(micN)
    private val im = FloatArray(micN)
    private val window = FloatArray(micN) { 0.5f - 0.5f * cos(2.0 * PI * it / (micN - 1)).toFloat() }
    private var micMag = FloatArray(micN / 2)
    private val rawBands = FloatArray(Audio.BANDS)

    private var record: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var micRunning = false

    private var visualizer: Visualizer? = null
    private var sessionMag = FloatArray(0)

    private var agc = AGC_FLOOR
    private var bassEma = 0f
    private var lastSignalMs = 0L

    // ---- session (uploaded music) mode ----
    fun startSession(sessionId: Int): Boolean {
        stop()
        return try {
            val v = Visualizer(sessionId)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            val sr = (v.samplingRate / 1000).coerceIn(8000, 48000)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer, data: ByteArray, rate: Int) {
                    val nn = Audio.WAVE
                    val step = (data.size / nn).coerceAtLeast(1)
                    var sumSq = 0.0
                    for (i in 0 until nn) {
                        val s = ((data[(i * step).coerceIn(0, data.size - 1)].toInt() and 0xFF) - 128) / 128f
                        audio.wave[i] = s
                        sumSq += (s * s).toDouble()
                    }
                    feedWave(sqrt(sumSq / nn).toFloat())
                }
                override fun onFftDataCapture(vis: Visualizer, data: ByteArray, rate: Int) {
                    val half = data.size / 2
                    if (sessionMag.size != half) sessionMag = FloatArray(half)
                    sessionMag[0] = kotlin.math.abs(data[0].toInt()).toFloat()
                    for (k in 1 until half) {
                        sessionMag[k] = hypot(data[2 * k].toDouble(), data[2 * k + 1].toDouble()).toFloat()
                    }
                    feedSpectrum(sessionMag, sr)
                }
            }, Visualizer.getMaxCaptureRate(), true, true)
            v.enabled = true
            visualizer = v
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startSession failed: ${t.message}")
            false
        }
    }

    // ---- microphone (live) mode ----
    fun startMic(): Boolean {
        stop()
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, micN * 2)
            val r = buildRecord(MediaRecorder.AudioSource.UNPROCESSED, bufSize)
                ?: buildRecord(MediaRecorder.AudioSource.MIC, bufSize)
                ?: return false
            record = r
            r.startRecording()
            micRunning = true
            micThread = Thread { micLoop() }.apply { isDaemon = true; start() }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startMic failed: ${t.message}")
            false
        }
    }

    private fun buildRecord(source: Int, bufSize: Int): AudioRecord? = try {
        @Suppress("MissingPermission")
        val r = AudioRecord(source, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
    } catch (t: Throwable) {
        null
    }

    private fun micLoop() {
        val buf = ShortArray(micN)
        while (micRunning) {
            var got = 0
            while (got < micN && micRunning) {
                val r = record?.read(buf, got, micN - got) ?: -1
                if (r <= 0) break
                got += r
            }
            if (got < micN) continue
            var sumSq = 0.0
            for (i in 0 until micN) {
                val s = buf[i] / 32768f
                re[i] = s * window[i]; im[i] = 0f
                sumSq += (s * s).toDouble()
            }
            for (i in 0 until Audio.WAVE) audio.wave[i] = buf[i * (micN / Audio.WAVE)] / 32768f
            feedWave(sqrt(sumSq / micN).toFloat())
            fft(re, im)
            for (k in 0 until micN / 2) micMag[k] = hypot(re[k].toDouble(), im[k].toDouble()).toFloat()
            feedSpectrum(micMag, 44100)
        }
    }

    fun stop() {
        micRunning = false
        runCatching { micThread?.join(200) }
        runCatching { record?.stop(); record?.release() }
        record = null; micThread = null
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
    }

    // ---- shared analysis ----
    private fun feedWave(rms: Float) {
        audio.rms = ad(audio.rms, (rms * 3f).coerceIn(0f, 1f), 0.5f, 0.06f)
        val now = SystemClock.uptimeMillis()
        if (rms > RMS_FLOOR) lastSignalMs = now
        audio.active = now - lastSignalMs < 1200L
    }

    private fun feedSpectrum(mag: FloatArray, sr: Int) {
        val binHz = sr.toFloat() / (mag.size * 2)
        val nyquist = sr / 2f
        val fMin = 30f
        var frameMax = 0f
        for (b in 0 until Audio.BANDS) {
            val lo = fMin * (nyquist / fMin).pow(b.toFloat() / Audio.BANDS)
            val hi = fMin * (nyquist / fMin).pow((b + 1f) / Audio.BANDS)
            rawBands[b] = rawBand(mag, lo, hi, binHz)
            if (rawBands[b] > frameMax) frameMax = rawBands[b]
        }
        agc = maxOf(agc * AGC_DECAY, frameMax, AGC_FLOOR)

        val gate = audio.active
        for (b in 0 until Audio.BANDS) {
            val v = if (gate) sqrt((rawBands[b] / agc).coerceIn(0f, 1f)) else 0f
            audio.bands[b] = ad(audio.bands[b], v, 0.55f, 0.11f)
        }
        val bass = norm(rawBand(mag, 60f, 250f, binHz), gate)
        audio.subBass = ad(audio.subBass, norm(rawBand(mag, 20f, 60f, binHz), gate), 0.6f, 0.10f)
        audio.bass = ad(audio.bass, bass, 0.6f, 0.10f)
        audio.mid = ad(audio.mid, norm(rawBand(mag, 250f, 4000f, binHz), gate), 0.5f, 0.12f)
        audio.treble = ad(audio.treble, norm(rawBand(mag, 4000f, nyquist, binHz), gate), 0.45f, 0.14f)

        bassEma = bassEma * 0.92f + bass * 0.08f
        val beatTarget = if (bass > bassEma * 1.35f && bass > 0.14f) 1f else 0f
        audio.beat = ad(audio.beat, beatTarget, 0.8f, 0.08f)
    }

    private fun norm(raw: Float, gate: Boolean): Float =
        if (gate) sqrt((raw / agc).coerceIn(0f, 1f)) else 0f

    private fun rawBand(mag: FloatArray, loHz: Float, hiHz: Float, binHz: Float): Float {
        val kLo = (loHz / binHz).toInt().coerceAtLeast(1)
        val kHi = (hiHz / binHz).toInt().coerceIn(kLo + 1, mag.size)
        var sum = 0f
        for (k in kLo until kHi) sum += mag[k]
        return sum / (kHi - kLo)
    }

    private fun ad(cur: Float, target: Float, atk: Float, dec: Float): Float =
        (cur + (target - cur) * (if (target > cur) atk else dec)).coerceIn(0f, 1f)

    private fun fft(re: FloatArray, im: FloatArray) {
        val size = re.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < size) {
                var curWr = 1f; var curWi = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val a = i + k; val b = i + k + half
                    val vr = re[b] * curWr - im[b] * curWi
                    val vi = re[b] * curWi + im[b] * curWr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] += vr; im[a] += vi
                    val nwr = curWr * wr - curWi * wi
                    curWi = curWr * wi + curWi * wr
                    curWr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        private const val TAG = "AudioAnalyzer"
        private const val RMS_FLOOR = 0.010f
        private const val AGC_DECAY = 0.9975f
        private const val AGC_FLOOR = 4f
    }
}
