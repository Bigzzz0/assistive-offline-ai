package com.assistive.system

import android.app.Application
import com.assistive.system.logging.AppLogger as Log

class AssistiveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.init(this)
        Log.i("AssistiveApp", "Application initialized and AppLogger configured.")
    }
}
