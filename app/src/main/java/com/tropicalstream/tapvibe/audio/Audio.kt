package com.tropicalstream.tapvibe.audio

/**
 * Smoothed audio-analysis snapshot the scenes read each frame. All spectrum values
 * are 0..1 (attack/decay-smoothed); [wave] is the raw time-domain shape (−1..1).
 */
class Audio {
    companion object {
        const val BANDS = 24
        const val WAVE = 128
    }

    val bands = FloatArray(BANDS)   // log-binned spectrum
    var subBass = 0f                // 20–60 Hz
    var bass = 0f                   // 60–250 Hz
    var mid = 0f                    // 250–4000 Hz
    var treble = 0f                 // 4000 Hz+
    var rms = 0f                    // overall energy envelope (drives breathing)
    var beat = 0f                   // bass-onset transient pulse, decays
    val wave = FloatArray(WAVE)     // downsampled time-domain samples
    var active = false              // true while real audio is arriving
}
