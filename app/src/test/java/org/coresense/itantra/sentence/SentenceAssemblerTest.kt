package org.coresense.itantra.sentence

import org.junit.Assert.*
import org.junit.Test

class SentenceAssemblerTest {

    private val assembler = SentenceAssembler()

    @Test
    fun testNormalizeTrimsAndCleansWhitespace() {
        val input = "  नमस्ते    यह    परीक्षण   है   "
        val normalized = assembler.normalize(input)
        assertEquals("नमस्ते यह परीक्षण है", normalized)
    }

    @Test
    fun testSplitIntoSentencesDandaAndPunctuation() {
        val input = "यह पहला वाक्य है। क्या आप सुन रहे हैं? यह तीसरा वाक्य है!"
        val sentences = assembler.splitIntoSentences(input)
        assertEquals(3, sentences.size)
        assertEquals("यह पहला वाक्य है।", sentences[0])
        assertEquals("क्या आप सुन रहे हैं?", sentences[1])
        assertEquals("यह तीसरा वाक्य है!", sentences[2])
    }

    @Test
    fun testLongUtteranceWordBoundarySplit() {
        val longText = "This is a very long continuous speech utterance that exceeds the maximum sentence character limit and should be gracefully split at word boundaries instead of cutting words in half."
        val sentences = assembler.splitIntoSentences(longText, maxCharsPerSentence = 60)
        assertTrue("Should split into multiple sentences", sentences.size > 1)
        for (s in sentences) {
            assertTrue("Each sentence should be <= 60 chars", s.length <= 60)
            assertFalse("Should not end with trailing space", s.endsWith(" "))
        }
    }

    @Test
    fun testBlankRejection() {
        val empty = assembler.splitIntoSentences("   \t\n  ")
        assertTrue("Empty/whitespace string should yield empty list", empty.isEmpty())
    }
}
