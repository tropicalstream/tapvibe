package com.tropicalstream.tapvibe

import android.app.Application

/** Renders via its own dual-draw BinocularSbsLayout; Mercury AAR optional. */
class TapVibeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            val cls = Class.forName("com.ffalcon.mercury.android.sdk.MercurySDK")
            cls.getMethod("init", Application::class.java).invoke(null, this)
        }
    }
}
