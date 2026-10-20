package org.coresense.itantra.stt

import org.coresense.itantra.backend.speech.EnglishSpeechNormalizer
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

    @Test
    fun testConversationalEnglishPreservedWithoutDistortion() {
        // Ensure common words like fine, food, good, hear are NOT converted to fire, flood, etc.
        val input = "i am fine we have food and water good morning can you hear me"
        val normalized = EnglishSpeechNormalizer.normalize(input)
        assertEquals("i am fine we have food and water good morning can you hear me", normalized)
    }

    @Test
    fun testSpeechListenDifferentNormalization() {
        val input = "stt english is not respond properly that listen different an speech diffrent"
        val normalized = EnglishSpeechNormalizer.normalize(input)
        assertEquals("stt english is not respond properly that listen different an speech different", normalized)
    }
}
