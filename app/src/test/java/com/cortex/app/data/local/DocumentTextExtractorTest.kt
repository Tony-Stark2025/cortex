package com.cortex.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class DocumentTextExtractorTest {

    @Test
    fun `extractTextFromPdfBytes extracts text operators and page count without raw PDF headers`() {
        val samplePdf = """
            %PDF-1.7
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            2 0 obj << /Type /Pages /Count 2 >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R >> endobj
            4 0 obj << /Type /Page /Parent 2 0 R >> endobj
            5 0 obj << /Length 88 >>
            stream
            BT
            /F1 12 Tf
            (Thermodynamics First Law: Energy is conserved in isolated systems.) Tj
            ET
            endstream
            endobj
            %%EOF
        """.trimIndent().toByteArray(StandardCharsets.ISO_8859_1)

        assertTrue(DocumentTextExtractor.hasPdfMagicHeader(samplePdf))

        val (extractedText, pageCount) = DocumentTextExtractor.extractTextFromPdfBytes(samplePdf, "Thermo_Slides.pdf")
        assertEquals(2, pageCount)
        assertTrue(extractedText.contains("Thermodynamics First Law: Energy is conserved in isolated systems."))
        assertFalse(extractedText.contains("%PDF-1.7"))
        assertFalse(extractedText.contains("endobj"))
    }

    @Test
    fun `extractTextFromPdfBytes preserves sentences with nested balanced parentheses and hex strings`() {
        val samplePdf = """
            %PDF-1.7
            1 0 obj << /Type /Page >> endobj
            2 0 obj << /Length 112 >>
            stream
            BT
            /F1 12 Tf
            (Glycolysis converts glucose (C6H12O6) into pyruvate (CH3COCOO-).) Tj
            <415450> Tj
            ET
            endstream
            endobj
            %%EOF
        """.trimIndent().toByteArray(StandardCharsets.ISO_8859_1)

        val (extractedText, pageCount) = DocumentTextExtractor.extractTextFromPdfBytes(samplePdf, "Bio_Slides.pdf")
        assertEquals(1, pageCount)
        assertTrue(extractedText.contains("Glycolysis converts glucose (C6H12O6) into pyruvate (CH3COCOO-)."))
        assertTrue(extractedText.contains("ATP"))
    }

    @Test
    fun `extractTextFromPdfBytes decodes ToUnicode CMap CID hex sequences and TJ kerning arrays`() {
        val samplePdf = """
            %PDF-1.7
            1 0 obj << /Type /Page >> endobj
            2 0 obj << /Length 140 >>
            stream
            beginbfchar
            <0001> <0044>
            <0002> <004E>
            <0003> <0041>
            endbfchar
            BT
            /F1 12 Tf
            [(T) -15 (hermodynamics) -250 (Law)] TJ
            <000100020003> Tj
            ET
            endstream
            endobj
            %%EOF
        """.trimIndent().toByteArray(StandardCharsets.ISO_8859_1)

        val (extractedText, _) = DocumentTextExtractor.extractTextFromPdfBytes(samplePdf, "CMap_Slides.pdf")
        assertTrue("TJ kerning should join intra-word and separate inter-word", extractedText.contains("Thermodynamics Law"))
        assertTrue("ToUnicode CMap should map <000100020003> to DNA", extractedText.contains("DNA"))
    }
}
