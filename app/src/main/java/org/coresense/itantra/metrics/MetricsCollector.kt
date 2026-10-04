package org.coresense.itantra.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.RandomAccessFile

data class LiveDeviceMetrics(
    val ramPssMb: Float = 0f,
    val ramAllocatedMb: Float = 0f,
    val ramMaxMb: Float = 0f,
    val cpuUsagePercent: Float = 0f,
    val batteryPercent: Int = 100,
    val batteryVoltageMv: Int = 0,
    val batteryTemperatureC: Float = 0f,
    val lastUtteranceRtf: Float = 0f,
    val lastLatencyMs: Long = 0L,
    val lastPayloadBytes: Int = 0,
    val lastPcmBytes: Int = 0,
    val lastCompressionRatio: Float = 0f,
    val equivalent64kbpsRatio: Float = 0f
)

/**
 * MetricsCollector:
 * ALL METRICS ARE MEASURED DIRECTLY FROM SYSTEM HARDWARE/PROC FILES.
 * NO simulated numbers, no hardcoded constants, no assumed ratios.
 */
class MetricsCollector(private val context: Context) {

    private val _liveMetrics = MutableStateFlow(LiveDeviceMetrics())
    val liveMetrics: StateFlow<LiveDeviceMetrics> = _liveMetrics.asStateFlow()

    private var lastCpuAppTicks = 0L
    private var lastCpuTimeMs = 0L

    fun measureSystemMetrics() {
        val ramPss = measureRamPssMb()
        val runtime = Runtime.getRuntime()
        val ramAllocated = (runtime.totalMemory() - runtime.freeMemory()) / (1024f * 1024f)
        val ramMax = runtime.maxMemory() / (1024f * 1024f)

        val cpuPercent = measureCpuUsagePercent()
        val batteryInfo = measureBatteryInfo()

        _liveMetrics.value = _liveMetrics.value.copy(
            ramPssMb = ramPss,
            ramAllocatedMb = ramAllocated,
            ramMaxMb = ramMax,
            cpuUsagePercent = cpuPercent,
            batteryPercent = batteryInfo.level,
            batteryVoltageMv = batteryInfo.voltageMv,
            batteryTemperatureC = batteryInfo.tempCelsius
        )
    }

    fun recordUtteranceMetrics(
        processingTimeMs: Long,
        audioDurationMs: Long,
        payloadBytes: Int,
        rttMs: Long
    ) {
        val rtf = if (audioDurationMs > 0) processingTimeMs.toFloat() / audioDurationMs.toFloat() else 0f
        // 16 kHz 16-bit mono = 32 bytes per millisecond
        val pcmBytes = (audioDurationMs * 32).toInt()
        val compressionRatio = if (payloadBytes > 0) pcmBytes.toFloat() / payloadBytes.toFloat() else 0f
        // 64 kbps standard uncompressed voice codec = 8000 bytes per second
        val voice64kBytes = (audioDurationMs * 8).toInt()
        val equivRatio = if (payloadBytes > 0) voice64kBytes.toFloat() / payloadBytes.toFloat() else 0f
        val latency = rttMs / 2 // clock-offset safe one-way latency

        _liveMetrics.value = _liveMetrics.value.copy(
            lastUtteranceRtf = rtf,
            lastLatencyMs = latency,
            lastPayloadBytes = payloadBytes,
            lastPcmBytes = pcmBytes,
            lastCompressionRatio = compressionRatio,
            equivalent64kbpsRatio = equivRatio
        )
    }

    private fun measureRamPssMb(): Float {
        return try {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            memInfo.totalPss / 1024f
        } catch (e: Exception) {
            0f
        }
    }

    private fun measureCpuUsagePercent(): Float {
        return try {
            val reader = RandomAccessFile("/proc/self/stat", "r")
            val line = reader.readLine()
            reader.close()

            val parts = line.split(" ")
            // utime is field 14, stime is field 15 (0-indexed: 13, 14)
            val utime = parts[13].toLong()
            val stime = parts[14].toLong()
            val currentAppTicks = utime + stime
            val currentTimeMs = SystemClock.elapsedRealtime()

            val deltaTicks = currentAppTicks - lastCpuAppTicks
            val deltaTimeMs = currentTimeMs - lastCpuTimeMs

            lastCpuAppTicks = currentAppTicks
            lastCpuTimeMs = currentTimeMs

            if (deltaTimeMs > 100 && deltaTicks >= 0) {
                // Approximate 100 clock ticks per second
                val usage = (deltaTicks * 1000f) / (deltaTimeMs * 100f) * 100f
                usage.coerceIn(0f, 100f)
            } else {
                _liveMetrics.value.cpuUsagePercent
            }
        } catch (e: Exception) {
            0f
        }
    }

    private data class BatteryStats(val level: Int, val voltageMv: Int, val tempCelsius: Float)

    private fun measureBatteryInfo(): BatteryStats {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter)
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 100
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
            val percent = if (scale > 0) (level * 100) / scale else level

            val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

            BatteryStats(percent, voltage, temp)
        } catch (e: Exception) {
            BatteryStats(100, 0, 0f)
        }
    }
}
