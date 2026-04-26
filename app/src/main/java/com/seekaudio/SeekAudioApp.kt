package com.seekaudio

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * SeekAudio Application class.
 * Entry point for Hilt dependency injection.
 */
@HiltAndroidApp
class SeekAudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
