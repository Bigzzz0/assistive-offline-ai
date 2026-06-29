package com.assistive.system.monitoring

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.assistive.system.logging.AppLogger as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ข้อมูล Performance Metrics ตาม ProjectProposal.md Section 6.1
 */
data class PerformanceMetrics(
    val lastInferenceLatencyMs: Long = 0L,       // < 3,000 ms (P95)
    val tokenRatePerSec: Float = 0f,             // >= 8 tokens/sec (GPU)
    val memoryUsageMB: Long = 0L,                // < 4,000 MB
    val temperatureCelsius: Float = 0f,          // < 45°C
    val batteryDrainMahPerMin: Float = 0f,       // < 15 mAh/min
    val isVlmReal: Boolean = false,
    val isAsrReal: Boolean = false,
    val totalInferenceCount: Int = 0,
    val averageLatencyMs: Long = 0L
)

/**
 * ตรวจสอบและบันทึก Performance Metrics ตาม ProjectProposal.md
 */
class PerformanceMonitor(private val context: Context) {

    private val TAG = "PerformanceMonitor"

    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics

    // Running averages
    private var totalLatencyMs = 0L
    private var totalInferences = 0
    private var latencyHistory = ArrayDeque<Long>(20) // Keep last 20 samples

    // Battery tracking
    private var batteryStartLevel: Int = -1
    private var batteryStartTimeMs: Long = -1L

    /**
     * บันทึก latency และ token rate หลัง inference ครั้งหนึ่ง
     */
    fun recordInference(latencyMs: Long, tokenCount: Int) {
        totalInferences++
        totalLatencyMs += latencyMs
        latencyHistory.addLast(latencyMs)
        if (latencyHistory.size > 20) latencyHistory.removeFirst()

        val tokenRate = if (latencyMs > 0) tokenCount.toFloat() / (latencyMs / 1000f) else 0f
        val avgLatency = if (totalInferences > 0) totalLatencyMs / totalInferences else 0L

        Log.d(TAG, "Inference #$totalInferences: ${latencyMs}ms, ${tokenRate}t/s")
        updateMetrics { copy(
            lastInferenceLatencyMs = latencyMs,
            tokenRatePerSec = tokenRate,
            totalInferenceCount = totalInferences,
            averageLatencyMs = avgLatency
        )}
    }

    /**
     * อัปเดตสถานะ VLM (Real/Mock)
     */
    fun updateVlmMode(isReal: Boolean) {
        updateMetrics { copy(isVlmReal = isReal) }
    }

    /**
     * อัปเดตสถานะ ASR (Real/Mock)
     */
    fun updateAsrMode(isReal: Boolean) {
        updateMetrics { copy(isAsrReal = isReal) }
    }

    /**
     * วัด JVM Memory ณ ขณะนั้น
     */
    fun refreshMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        updateMetrics { copy(memoryUsageMB = usedMB) }
    }

    /**
     * วัดอุณหภูมิอุปกรณ์ผ่าน PowerManager (API 29+)
     */
    fun refreshTemperature() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val thermalStatus = powerManager.currentThermalStatus
                // Map thermal status to approximate temp (heuristic)
                val approxTemp = when (thermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> 30f
                    PowerManager.THERMAL_STATUS_LIGHT -> 38f
                    PowerManager.THERMAL_STATUS_MODERATE -> 43f
                    PowerManager.THERMAL_STATUS_SEVERE -> 48f
                    PowerManager.THERMAL_STATUS_CRITICAL -> 55f
                    PowerManager.THERMAL_STATUS_EMERGENCY -> 60f
                    else -> 35f
                }
                updateMetrics { copy(temperatureCelsius = approxTemp) }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read temperature: ${e.message}")
            }
        }
    }

    /**
     * เริ่มติดตาม Battery drain
     */
    fun startBatteryTracking() {
        val batteryIntent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        batteryStartLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        batteryStartTimeMs = System.currentTimeMillis()
    }

    /**
     * อัปเดต battery drain rate (mAh/min)
     */
    fun refreshBatteryDrain() {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentNowMicroA = batteryManager.getLongProperty(
            BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
        )
        if (currentNowMicroA != Long.MIN_VALUE) {
            // Discharge current (negative when discharging)
            val dischargeMa = Math.abs(currentNowMicroA / 1000f) // Convert µA → mA
            val drainPerMin = dischargeMa / 60f // Approximate mAh/min
            updateMetrics { copy(batteryDrainMahPerMin = drainPerMin) }
        }
    }

    /**
     * P95 latency จาก history
     */
    fun getP95LatencyMs(): Long {
        if (latencyHistory.isEmpty()) return 0L
        val sorted = latencyHistory.toList().sorted()
        val p95Index = (sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)
        return sorted[p95Index]
    }

    /**
     * ตรวจสอบว่าผ่าน Performance Targets หรือไม่
     */
    fun getPerformanceReport(): String {
        val m = _metrics.value
        val latencyOk = m.lastInferenceLatencyMs < 3000
        val tokenOk = m.tokenRatePerSec >= 8f || !m.isVlmReal
        val memOk = m.memoryUsageMB < 4000
        val tempOk = m.temperatureCelsius < 45f
        val batOk = m.batteryDrainMahPerMin < 15f

        return buildString {
            appendLine("=== Performance Report ===")
            appendLine("VLM Mode: ${if (m.isVlmReal) "Real ✅" else "Mock ⚠️"}")
            appendLine("ASR Mode: ${if (m.isAsrReal) "Real ✅" else "Mock ⚠️"}")
            appendLine("Latency: ${m.lastInferenceLatencyMs}ms ${if (latencyOk) "✅" else "❌ (>3000ms)"}")
            appendLine("Token/sec: ${"%.1f".format(m.tokenRatePerSec)} ${if (tokenOk) "✅" else "❌ (<8)"}")
            appendLine("Memory: ${m.memoryUsageMB} MB ${if (memOk) "✅" else "❌ (>4000MB)"}")
            appendLine("Temp: ${"%.1f".format(m.temperatureCelsius)}°C ${if (tempOk) "✅" else "❌ (>45°C)"}")
            appendLine("Battery: ${"%.1f".format(m.batteryDrainMahPerMin)} mAh/min ${if (batOk) "✅" else "❌ (>15)"}")
            appendLine("Inferences: ${m.totalInferenceCount} (avg ${m.averageLatencyMs}ms)")
        }
    }

    private fun updateMetrics(update: PerformanceMetrics.() -> PerformanceMetrics) {
        _metrics.value = _metrics.value.update()
    }
}
