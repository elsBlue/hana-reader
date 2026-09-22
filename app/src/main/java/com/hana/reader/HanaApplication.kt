package com.hana.reader

import android.app.Application
import com.hana.reader.tts.HanaPlayer

class HanaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HanaPlayer.get(this)
    }
}
