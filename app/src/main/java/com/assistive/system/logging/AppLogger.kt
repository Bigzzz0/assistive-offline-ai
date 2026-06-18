package com.assistive.system.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object AppLogger {
    private var appContext: Context? = null
    private val logScope = CoroutineScope(Dispatchers.IO)
    private val _logFlow = MutableStateFlow("")
    val logFlow: StateFlow<String> = _logFlow

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (appContext != null) return // Only initialize once
        appContext = context.applicationContext
        setupUncaughtExceptionHandler()
        loadLogs()
    }

    private fun getLogFile(): File? {
        val dir = appContext?.filesDir ?: return null
        return File(dir, "app_logs.txt")
    }

    private fun loadLogs() {
        logScope.launch {
            val file = getLogFile()
            if (file != null && file.exists()) {
                val content = try {
                    file.readText().takeLast(100000) // keep last 100k characters for view
                } catch (e: Exception) {
                    "Error loading logs: ${e.message}"
                }
                _logFlow.value = content
            } else {
                _logFlow.value = "Logs started.\n"
            }
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeLog("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeLog("INFO", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        writeLog("WARN", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val fullMsg = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            "$message\n$sw"
        } else {
            message
        }
        writeLog("ERROR", tag, fullMsg)
    }

    private fun writeLog(level: String, tag: String, message: String) {
        val file = getLogFile() ?: return
        logScope.launch {
            try {
                val timeStr = dateFormat.format(Date())
                val formatted = "[$timeStr] [$level] [$tag] $message\n"
                
                FileWriter(file, true).use { writer ->
                    writer.write(formatted)
                }
                
                // Append to state flow for real-time display in UI
                val current = _logFlow.value
                val newContent = if (current.length > 50000) {
                    current.substring(current.length - 30000) + formatted
                } else {
                    current + formatted
                }
                _logFlow.value = newContent
            } catch (e: Exception) {
                Log.e("AppLogger", "Failed to write log to file: ${e.message}")
            }
        }
    }

    private fun writeLogSync(level: String, tag: String, message: String) {
        val file = getLogFile() ?: return
        try {
            val timeStr = dateFormat.format(Date())
            val formatted = "[$timeStr] [$level] [$tag] $message\n"
            FileWriter(file, true).use { writer ->
                writer.write(formatted)
            }
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to write log to file synchronously: ${e.message}")
        }
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val fullMsg = "Uncaught exception on thread ${thread.name}: ${throwable.message}\n$sw"
            
            // Log to standard logcat
            Log.e("CRASH", fullMsg, throwable)
            
            // Write to our logfile synchronously to guarantee it is written before process termination
            writeLogSync("FATAL", "CRASH", fullMsg)
            
            // Forward to original handler (which normally terminates the app / shows crash dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun clearLogs() {
        val file = getLogFile() ?: return
        logScope.launch {
            try {
                if (file.exists()) {
                    file.delete()
                }
                _logFlow.value = "Logs cleared.\n"
            } catch (e: Exception) {
                Log.e("AppLogger", "Failed to clear logs: ${e.message}")
            }
        }
    }
}
