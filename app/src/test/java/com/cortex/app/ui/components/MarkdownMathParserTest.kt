package com.cortex.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathParserTest {

    @Test
    fun `cleanLatexForDisplay formats fractions without dangling braces`() {
        val raw = "\\frac{a+b}{c}"
        val cleaned = raw.cleanLatexForDisplay()
        assertEquals("(a+b)/c", cleaned)
        assertFalse(cleaned.contains("{"))
        assertFalse(cleaned.contains("}"))
    }

    @Test
    fun `cleanLatexForDisplay resolves nested sqrt and frac expressions`() {
        val raw = "\\sqrt{\\frac{a+b}{c}}"
        val cleaned = raw.cleanLatexForDisplay()
        assertEquals("√((a+b)/c)", cleaned)
    }

    @Test
    fun `cleanLatexForDisplay formats chemical formulas and subscripts`() {
        val raw = "\\text{C}_6\\text{H}_{12}\\text{O}_6 + 2\\text{NAD}^+ \\longrightarrow 2\\text{Pyruvate}"
        val cleaned = raw.cleanLatexForDisplay()
        assertEquals("C₆H₁₂O₆ + 2NAD⁺ ⟶ 2Pyruvate", cleaned)
    }

    @Test
    fun `tokenizeInlineMath does not misidentify currency prices as math`() {
        val prose = "The price rose from $10 to $25 during the semester."
        val segments = tokenizeInlineMath(prose)
        assertEquals(1, segments.size)
        assertFalse(segments.first().isMath)
        assertEquals(prose, segments.first().text)
    }

    @Test
    fun `tokenizeInlineMath parses valid inline LaTeX formulas`() {
        val prose = "Energy investment requires $2\\text{ ATP}$ and net yield is $+2\\text{ ATP}$."
        val segments = tokenizeInlineMath(prose)
        val mathSegments = segments.filter { it.isMath }
        assertEquals(2, mathSegments.size)
        assertEquals("2\\text{ ATP}", mathSegments[0].text)
        assertEquals("+2\\text{ ATP}", mathSegments[1].text)
    }

    @Test
    fun `tokenizeInlineMath parses inline LaTeX even after a single currency price on the same line`() {
        val prose = "The textbook costs $25 and the formula is \$x = 10\$."
        val segments = tokenizeInlineMath(prose)
        val mathSegments = segments.filter { it.isMath }
        assertEquals(1, mathSegments.size)
        assertEquals("x = 10", mathSegments.first().text)
        assertEquals("The textbook costs $25 and the formula is ", segments.first().text)
    }

    @Test
    fun `tokenizeInlineMath does not misidentify hyphenated price ranges as math`() {
        val prose = "Textbook prices range from $10-$25 per semester."
        val segments = tokenizeInlineMath(prose)
        assertEquals(1, segments.size)
        assertFalse(segments.first().isMath)
        assertEquals(prose, segments.first().text)
    }

    @Test
    fun `cleanLatexForDisplay formats integral limits with letter superscripts`() {
        val raw = "\\int_{a}^{b} f'(x)\\,dx = f(b) - f(a)"
        val cleaned = raw.cleanLatexForDisplay()
        assertEquals("∫ₐᵇ f'(x) dx = f(b) - f(a)", cleaned)
    }
}
