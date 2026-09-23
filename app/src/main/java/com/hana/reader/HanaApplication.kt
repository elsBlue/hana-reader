package com.hana.reader

import android.app.Application
import com.hana.reader.tts.HanaPlayer

class HanaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationPermission.bind(this)
        HanaPlayer.get(this)
    }
}
