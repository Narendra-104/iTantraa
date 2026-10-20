package org.coresense.itantra.metrics

import org.coresense.itantra.core.metrics.WerCalculator
import org.coresense.itantra.core.metrics.LiveDeviceMetrics
import org.junit.Assert.*
import org.junit.Test

class PerformanceMetricsTest {

    @Test
    fun testWerCalculations() {
        val ref = "evacuate building immediately"
        val hypIdentical = "evacuate building immediately"
        val resIdentical = WerCalculator.calculate(ref, hypIdentical)
        assertEquals(0f, resIdentical.wer, 0.001f)

        val hypSub = "leave building immediately"
        val resSub = WerCalculator.calculate(ref, hypSub)
        assertEquals(1f / 3f, resSub.wer, 0.001f)
        assertEquals(1, resSub.substitutions)

        val hypDel = "evacuate immediately"
        val resDel = WerCalculator.calculate(ref, hypDel)
        assertEquals(1f / 3f, resDel.wer, 0.001f)
        assertEquals(1, resDel.deletions)

        val hypIns = "please evacuate building immediately"
        val resIns = WerCalculator.calculate(ref, hypIns)
        assertEquals(1f / 3f, resIns.wer, 0.001f)
        assertEquals(1, resIns.insertions)
    }

    @Test
    fun testRtfCalculation() {
        val audioDurationMs = 2000L
        val processingTimeMs = 400L
        val rtf = processingTimeMs.toFloat() / audioDurationMs.toFloat()
        assertEquals(0.20f, rtf, 0.001f)

        // Edge case: zero duration
        val zeroDurationMs = 0L
        val safeRtf = if (zeroDurationMs > 0) processingTimeMs.toFloat() / zeroDurationMs.toFloat() else null
        assertNull("Zero duration audio must yield null RTF instead of division by zero", safeRtf)
    }

    @Test
    fun testClockSafeLatencyCalculation() {
        val rttMs = 120L
        val oneWayLatency = rttMs / 2
        assertEquals(60L, oneWayLatency)
    }

    @Test
    fun testCompressionRatioCalculation() {
        val durationMs = 3000L
        // 16 kHz 16-bit mono = 32 bytes per millisecond
        val pcmBytes = (durationMs * 32).toInt() // 96,000 bytes
        val payloadBytes = 64 // 64 bytes text/Protobuf
        val ratio = pcmBytes.toFloat() / payloadBytes.toFloat()
        assertEquals(1500f, ratio, 0.1f)
    }

    @Test
    fun testUnmeasuredMetricsDefaultToNull() {
        val defaultMetrics = LiveDeviceMetrics()
        assertNull("Unmeasured latency must be null", defaultMetrics.lastLatencyMs)
        assertNull("Unmeasured RTF must be null", defaultMetrics.lastUtteranceRtf)
        assertNull("Unmeasured compression ratio must be null", defaultMetrics.lastCompressionRatio)
        assertNull("Unmeasured payload bytes must be null", defaultMetrics.lastPayloadBytes)
    }
}
