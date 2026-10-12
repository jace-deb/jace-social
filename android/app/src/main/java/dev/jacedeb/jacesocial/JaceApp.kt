package dev.jacedeb.jacesocial

import android.app.Application
import dev.jacedeb.jacesocial.data.Notify
import dev.jacedeb.jacesocial.rtc.Rtc

class JaceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Rtc.init(this)
        Notify.init(this)
    }
}
