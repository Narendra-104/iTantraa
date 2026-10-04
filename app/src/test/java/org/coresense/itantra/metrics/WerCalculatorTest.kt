package org.coresense.itantra.metrics

import org.junit.Assert.*
import org.junit.Test

class WerCalculatorTest {

    @Test
    fun testExactMatchZeroWer() {
        val ref = "आपातकालीन स्थिति में तुरंत सहायता भेजें"
        val hyp = "आपातकालीन स्थिति में तुरंत सहायता भेजें"
        val res = WerCalculator.calculate(ref, hyp)
        assertEquals(0f, res.wer, 0.001f)
        assertEquals(0, res.substitutions)
        assertEquals(0, res.deletions)
        assertEquals(0, res.insertions)
        assertEquals(6, res.referenceWordsCount)
    }

    @Test
    fun testOneSubstitution() {
        val ref = "send immediate medical assistance"
        val hyp = "send quick medical assistance"
        val res = WerCalculator.calculate(ref, hyp)
        // 1 substitution out of 4 words = 25% WER
        assertEquals(0.25f, res.wer, 0.001f)
        assertEquals(1, res.substitutions)
        assertEquals(0, res.deletions)
        assertEquals(0, res.insertions)
    }

    @Test
    fun testOneDeletion() {
        val ref = "send immediate medical assistance"
        val hyp = "send medical assistance"
        val res = WerCalculator.calculate(ref, hyp)
        // 1 deletion out of 4 words = 25% WER
        assertEquals(0.25f, res.wer, 0.001f)
        assertEquals(0, res.substitutions)
        assertEquals(1, res.deletions)
        assertEquals(0, res.insertions)
    }

    @Test
    fun testOneInsertion() {
        val ref = "send medical assistance"
        val hyp = "please send medical assistance"
        val res = WerCalculator.calculate(ref, hyp)
        // 1 insertion out of 3 words = 33.3% WER
        assertEquals(1f / 3f, res.wer, 0.001f)
        assertEquals(1, res.insertions)
    }
}
