package com.assistive.system.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class HapticManager(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Level 1: General Info - A single short buzz.
     */
    fun vibrateGeneralInfo() {
        Log.d("HapticManager", "Vibrating: Level 1 (General Info)")
        if (vibrator == null || !vibrator.hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }

    /**
     * Level 2: Caution/Warning - Two distinct pulses.
     */
    fun vibrateWarning() {
        Log.d("HapticManager", "Vibrating: Level 2 (Warning)")
        if (vibrator == null || !vibrator.hasVibrator()) return

        val timings = longArrayOf(0, 150, 150, 150) // Off, On, Off, On
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }

    /**
     * Level 3: Danger/Obstacle - Continuous pulses.
     */
    fun vibrateDanger() {
        Log.d("HapticManager", "Vibrating: Level 3 (Danger)")
        if (vibrator == null || !vibrator.hasVibrator()) return

        val timings = longArrayOf(0, 400, 100, 400, 100, 400) // Repeated longer vibrations
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitudes = intArrayOf(
                0, VibrationEffect.DEFAULT_AMPLITUDE, 
                0, VibrationEffect.DEFAULT_AMPLITUDE, 
                0, VibrationEffect.DEFAULT_AMPLITUDE
            )
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }
}
