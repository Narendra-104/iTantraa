package org.coresense.itantra.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishSpeechNormalizerTest {

    @Test
    fun testDirectTacticalMapping() {
        val input = "may day may day we need amblance fast"
        val normalized = EnglishSpeechNormalizer.normalize(input)
        assertEquals("mayday mayday we need ambulance fast", normalized)
    }

    @Test
    fun testPhoneticSubstitutions() {
        val input = "halp me i need dockter at the hospatal"
        val normalized = EnglishSpeechNormalizer.normalize(input)
        assertEquals("help me i need doctor at the hospital", normalized)
    }

    @Test
    fun testStutterCollapse() {
        val input = "pleeeease send heeeelp immmediatly"
        val normalized = EnglishSpeechNormalizer.normalize(input)
        assertTrue(normalized.contains("please"))
        assertTrue(normalized.contains("help"))
        assertTrue(normalized.contains("immediately"))
    }

    @Test
    fun testLevenshteinFuzzyMatch() {
        val input = "there is a firee in the builting"
        val normalized = EnglishSpeechNormalizer.normalize(input)
        assertEquals("there is a fire in the building", normalized)
    }

    @Test
    fun testEmergencyKeywordsPreserved() {
        val input = "critical situation need evacuation and oxygen"
        val normalized = EnglishSpeechNormalizer.normalize(input)
        assertEquals("critical situation need evacuation and oxygen", normalized)
    }
}
