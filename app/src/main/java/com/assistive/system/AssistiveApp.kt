package com.assistive.system

import android.app.Application
import android.util.Log

class AssistiveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("AssistiveApp", "Application initialized.")
    }
}
