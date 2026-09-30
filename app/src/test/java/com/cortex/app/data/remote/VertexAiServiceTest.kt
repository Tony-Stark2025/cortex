package com.cortex.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VertexAiServiceTest {

    private val service = VertexAiService(apiKey = "")

    @Test
    fun `isValidEndpoint accepts valid Google AI and Vertex endpoints and rejects SSRF targets`() {
        // Valid HTTPS Google endpoints
        assertTrue(service.isValidEndpoint("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"))
        assertTrue(service.isValidEndpoint("https://us-central1-aiplatform.googleapis.com/v1/projects/test/locations/us-central1/publishers/google/models/gemini-2.0-flash:generateContent"))
        assertTrue(service.isValidEndpoint("https://aiplatform.googleapis.com/v1/projects/p/locations/l/publishers/google/models/m:generateContent"))
        assertTrue(service.isValidEndpoint("https://europe-west4-aiplatform.googleapis.com/v1/models/gemini-pro"))

        // Insecure or SSRF targets
        assertFalse(service.isValidEndpoint("http://generativelanguage.googleapis.com/v1beta/models"))
        assertFalse(service.isValidEndpoint("https://evil.com/models/gemini"))
        assertFalse(service.isValidEndpoint("https://generativelanguage.googleapis.com.attacker.com/v1"))
        assertFalse(service.isValidEndpoint("https://169.254.169.254/latest/meta-data"))
        assertFalse(service.isValidEndpoint("ftp://generativelanguage.googleapis.com"))
        assertFalse(service.isValidEndpoint(""))
        assertFalse(service.isValidEndpoint("not a valid url"))

        // Advanced SSRF injection vectors: Userinfo, non-standard ports, invalid subdomain labels
        assertFalse(service.isValidEndpoint("https://attacker.com@generativelanguage.googleapis.com"))
        assertFalse(service.isValidEndpoint("https://user:pass@generativelanguage.googleapis.com"))
        assertFalse(service.isValidEndpoint("https://generativelanguage.googleapis.com:8080/generate"))
        assertFalse(service.isValidEndpoint("https://aiplatform.googleapis.com:8443/v1"))
        assertFalse(service.isValidEndpoint("https://-aiplatform.googleapis.com/v1"))
        assertFalse(service.isValidEndpoint("https://aiplatform.googleapis.com-/v1"))
    }

    @Test
    fun `generateLocalSimulatedQuiz distributes correct answer indices across questions and matches source facts`() {
        val sources = """
            [Source: Quantum_Physics.pdf | ID: q-1 | Pages: 10]
            The photoelectric effect proves that electromagnetic radiation is quantized into discrete wave packets called photons.
            Heisenberg's uncertainty principle states that conjugate observables such as position and momentum cannot both be precisely measured simultaneously.
            Schrödinger's wave equation describes the deterministic time evolution of a quantum mechanical state vector.
            Quantum entanglement manifests non-local correlations between entangled particle pairs that violate Bell inequalities.
        """.trimIndent()

        val quiz = service.generateLocalSimulatedQuiz(
            sourcesText = sources,
            topic = "Quantum Mechanics",
            projectId = "qm-101"
        )

        assertEquals("Quantum Mechanics Active Recall", quiz.title)
        assertTrue("Must generate at least 3 questions", quiz.questions.size >= 3)

        val correctIndices = quiz.questions.map { it.correctIndex }
        // Verify that correctIndex is NOT static (e.g. not all 0)
        assertTrue("Correct indices must be distributed across different positions, found: $correctIndices", correctIndices.distinct().size > 1)

        // Verify that for each question, the option at correctIndex matches the fact from explanation
        for (q in quiz.questions) {
            val correctOption = q.options[q.correctIndex]
            assertTrue("Explanation must quote the fact corresponding to correct option", q.explanation.contains(correctOption) || correctOption.contains(q.explanation.substringAfter("\"").substringBeforeLast("\"")))
            assertEquals(4, q.options.size)
            assertEquals("All 4 options must be distinct", 4, q.options.distinct().size)
        }
    }

    @Test
    fun `generateLocalSimulatedQuiz fallback distributes correct answer indices`() {
        val quiz = service.generateLocalSimulatedQuiz(
            sourcesText = "",
            topic = "Thermodynamics",
            projectId = "thermo-1"
        )

        assertTrue(quiz.questions.size >= 3)
        val correctIndices = quiz.questions.map { it.correctIndex }
        assertTrue("Fallback quiz correct indices must be distributed: $correctIndices", correctIndices.distinct().size > 1)
        for (q in quiz.questions) {
            assertTrue(q.correctIndex in 0 until q.options.size)
        }
    }

    @Test
    fun `tokenizeKeywords preserves Unicode scientific terms and duplicate word frequency`() {
        val tokens = service.tokenizeKeywords("The α-helix and β-sheet stabilize protein folding with ΔG and λ parameters. ATP atp atp yields energy. 酶催化.")
        // Check unicode Greek letters are preserved
        assertTrue(tokens.contains("α"))
        assertTrue(tokens.contains("β"))
        assertTrue(tokens.contains("δg") || tokens.contains("Δg".lowercase()))
        assertTrue(tokens.contains("λ"))
        assertTrue(tokens.contains("酶催化"))
        // Check English stop words are filtered
        assertFalse(tokens.contains("the"))
        assertFalse(tokens.contains("and"))
        assertFalse(tokens.contains("with"))
        // Check duplicate tokens are NOT stripped so term frequency calculation is accurate
        val atpCount = tokens.count { it == "atp" }
        assertEquals(3, atpCount)
    }

    @Test
    fun `computeTfIdfRankedSentences ranks sentences with higher term frequency above irrelevant ones`() {
        val sourcesText = """
            [Source: Bioenergetics.pdf | ID: bio-1 | Pages: 5]
            Cellular respiration hydrolyzes ATP to generate metabolic work throughout the cell.
            ATP ATP ATP synthase phosphorylation couples proton motive force to synthesize ATP rapidly.
            Photosynthesis utilizes light reactions to fix carbon into hexose sugars.
        """.trimIndent()

        val parsed = service.parseSourceBlocks(sourcesText)
        val queryTokens = service.tokenizeKeywords("ATP phosphorylation")
        val contextTokens = emptyList<String>()

        val ranked = service.computeTfIdfRankedSentences(parsed, queryTokens, contextTokens)
        assertTrue(ranked.isNotEmpty())
        // The sentence with highest ATP frequency and phosphorylation should rank first
        assertTrue(ranked.first().sentence.contains("ATP synthase phosphorylation"))
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `generateLocalSimulatedResponse grounds answers in custom non-biology course sources`() {
        val customSources = """
            [Source: Macroeconomics_Lecture_02.pdf | ID: src-macro-2 | Pages: 10]
            Gross Domestic Product (GDP) measures the total market value of all final goods and services produced within a country.
            Fiscal policy uses government spending and taxation to influence aggregate demand across the business cycle.
        """.trimIndent()

        val (response, citations) = service.generateLocalSimulatedResponse(
            userPrompt = "How does fiscal policy affect GDP and aggregate demand?",
            sourcesText = customSources,
            courseTitle = "Macroeconomics 101"
        )

        assertFalse("Should not return static Glycolysis text for Macroeconomics", response.contains("Glycolysis", ignoreCase = true))
        assertTrue("Should reference Macroeconomics course", response.contains("Macroeconomics 101"))
        assertTrue("Should include relevant source content", response.contains("Fiscal policy", ignoreCase = true))
        assertTrue("Should return grounded citations from Macroeconomics_Lecture_02.pdf", citations.any { it.sourceTitle == "Macroeconomics_Lecture_02.pdf" && it.sourceId == "src-macro-2" })
    }

    @Test
    fun `generateLocalSimulatedAudioBriefing returns multi-turn dialogue between Alex and Sam`() {
        val briefing = service.generateLocalSimulatedAudioBriefing(
            sourcesText = "[Source: Calculus_Notes.txt | Pages: 4]\nThe Fundamental Theorem of Calculus connects differentiation and integration.",
            topic = "Calculus II",
            projectId = "calc-2"
        )

        assertTrue("Briefing must contain at least 4 dialogue turns", briefing.turns.size >= 4)
        assertTrue("Must include Host A (Alex)", briefing.turns.any { it.speaker.contains("Alex") })
        assertTrue("Must include Host B (Sam)", briefing.turns.any { it.speaker.contains("Sam") })
    }

    @Test
    fun `generateLocalSimulatedCheatSheet builds topic-specific markdown and formulas`() {
        val sheet = service.generateLocalSimulatedCheatSheet(
            sourcesText = "[Source: Calculus_Notes.txt | Pages: 4]\nIntegration by parts is derived from the product rule for derivatives.",
            topic = "Calculus II",
            projectId = "calc-2"
        )

        assertTrue(sheet.title.contains("Calculus II"))
        assertTrue(sheet.markdownContent.contains("Integration by parts"))
        assertTrue(sheet.markdownContent.contains("\\int"))
    }

    @Test
    fun `generateLocalSimulatedResponse prioritizes custom uploaded sources in Biology 101 over static Glycolysis fallback`() {
        val mixedBioSources = """
            [Source: Lecture_04_Glycolysis.pdf | ID: src-1 | Pages: 18]
            Glycolysis is the metabolic pathway that converts glucose (C6H12O6) into pyruvate.
            
            ---
            
            [Source: Mitosis_Cell_Division.pdf | ID: uuid-mitosis | Pages: 12]
            Mitosis divides the eukaryotic cell nucleus through prophase, metaphase, anaphase, and telophase.
            During metaphase, chromosomes align along the equatorial metaphase plate before sister chromatids separate in anaphase.
        """.trimIndent()

        val (response, citations) = service.generateLocalSimulatedResponse(
            userPrompt = "What happens to chromosomes during metaphase and anaphase of mitosis?",
            sourcesText = mixedBioSources,
            courseTitle = "Biology 101"
        )

        assertTrue("Should cite Mitosis_Cell_Division.pdf", citations.any { it.sourceTitle == "Mitosis_Cell_Division.pdf" && it.sourceId == "uuid-mitosis" })
        assertTrue("Should include metaphase/anaphase content", response.contains("metaphase", ignoreCase = true))
    }

    @Test
    fun `extractCitations matches surrounding claim to correct source rather than round-robin modulo`() {
        val sourcesText = """
            [Source: Thermodynamics.pdf | ID: src-thermo | Pages: 5]
            Entropy in an isolated system never decreases according to the Second Law.
            
            ---
            
            [Source: Quantum_Mechanics.pdf | ID: src-quantum | Pages: 5]
            Wavefunctions evolve deterministically according to the Schrodinger equation.
        """.trimIndent()

        val parsed = service.parseSourceBlocks(sourcesText)
        val aiOutput = "Wavefunctions evolve deterministically according to the Schrodinger equation[p.2]."
        val citations = service.extractCitations(aiOutput, parsed)

        assertEquals(1, citations.size)
        assertEquals("Quantum_Mechanics.pdf", citations.first().sourceTitle)
        assertEquals("src-quantum", citations.first().sourceId)
        assertTrue(citations.first().quotedSnippet.contains("Schrodinger"))
    }

    @Test
    fun `classifyStrokesToLatex maps geometric stroke features to distinct formulas`() {
        val integralStrokes = StrokeFeatureSummary(
            strokeCount = 4,
            totalPoints = 60,
            boundingWidth = 180f,
            boundingHeight = 90f,
            hasWideHorizontalBar = false,
            hasTallVerticalCurve = true,
            hasRadicalHook = false,
            hasParallelEquals = true,
            hasRightArrowTip = false
        )
        val quadraticStrokes = StrokeFeatureSummary(
            strokeCount = 6,
            totalPoints = 85,
            boundingWidth = 220f,
            boundingHeight = 75f,
            hasWideHorizontalBar = true,
            hasTallVerticalCurve = false,
            hasRadicalHook = true,
            hasParallelEquals = true,
            hasRightArrowTip = false
        )

        assertTrue(service.classifyStrokesToLatex(integralStrokes, "Calculus").contains("\\int"))
        assertTrue(service.classifyStrokesToLatex(quadraticStrokes, "Algebra").contains("\\sqrt{b^2 - 4ac}"))
    }

    @Test
    fun `generateLocalSimulatedQuiz with single sentence generates complete 3-question quiz with distinct options`() {
        val singleSentenceSource = """
            [Source: Microeconomics.txt | ID: econ-1 | Pages: 2]
            Elasticity of demand quantifies the percentage change in quantity demanded relative to a percentage change in price.
        """.trimIndent()

        val quiz = service.generateLocalSimulatedQuiz(
            sourcesText = singleSentenceSource,
            topic = "Microeconomics",
            projectId = "econ-101"
        )

        assertEquals("Must generate at least 3 questions even from a 1-sentence source", 3, quiz.questions.size)
        for (q in quiz.questions) {
            assertEquals("Each question must have exactly 4 options", 4, q.options.size)
            assertEquals("All options must be distinct", 4, q.options.distinct().size)
            assertTrue("correctIndex must be within bounds", q.correctIndex in 0..3)
            // Distractors in Economics must not talk about biology enzyme catalysis
            assertFalse("Distractors in Economics must not contain enzyme catalysis", q.options.any { it.contains("enzyme catalysis", ignoreCase = true) })
            assertFalse("Distractors in Economics must not contain metabolic intermediates", q.options.any { it.contains("metabolic intermediates", ignoreCase = true) })
        }
    }

    @Test
    fun `generateLocalSimulatedQuiz produces domain-aware distractors for Linear Algebra`() {
        val algebraSource = """
            [Source: Linear_Algebra.pdf | ID: la-1 | Pages: 4]
            An invertible square matrix has a non-zero determinant and full column rank.
            The eigenvalues of a triangular matrix are the entries on its main diagonal.
            Gram-Schmidt orthogonalization constructs an orthonormal basis from any linearly independent set.
        """.trimIndent()

        val quiz = service.generateLocalSimulatedQuiz(
            sourcesText = algebraSource,
            topic = "Linear Algebra",
            projectId = "math-201"
        )

        assertEquals(3, quiz.questions.size)
        for (q in quiz.questions) {
            assertEquals(4, q.options.size)
            assertEquals(4, q.options.distinct().size)
            assertFalse("Linear algebra distractors must not contain enzyme catalysis", q.options.any { it.contains("enzyme catalysis", ignoreCase = true) })
        }
    }

    @Test
    fun `generateLocalSimulatedResponse handles zero-match queries honestly without fake citations`() {
        val bioSources = """
            [Source: Cell_Biology.pdf | ID: bio-src | Pages: 3]
            Mitochondria generate cellular ATP through oxidative phosphorylation along the inner membrane.
        """.trimIndent()

        val (response, citations) = service.generateLocalSimulatedResponse(
            userPrompt = "Who was Napoleon Bonaparte and when was the battle of Waterloo?",
            sourcesText = bioSources,
            courseTitle = "Cell Biology"
        )

        // Must not fabricate citations for an unrelated query
        assertTrue("Zero-match query must produce 0 citations", citations.isEmpty())
        assertTrue("Must state that no direct references were found", response.contains("no direct references", ignoreCase = true))
        assertFalse("Must not claim cell biology answers Napoleon", response.contains("Regarding **Who was Napoleon", ignoreCase = true))
    }
}
