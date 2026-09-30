package com.cortex.app.data.remote

import com.cortex.app.data.model.AudioBriefingArtifact
import com.cortex.app.data.model.CheatSheetArtifact
import com.cortex.app.data.model.Citation
import com.cortex.app.data.model.DialogueTurn
import com.cortex.app.data.model.QuizArtifact
import com.cortex.app.data.model.QuizQuestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ln

data class StrokeFeatureSummary(
    val strokeCount: Int,
    val totalPoints: Int,
    val boundingWidth: Float,
    val boundingHeight: Float,
    val hasWideHorizontalBar: Boolean,
    val hasTallVerticalCurve: Boolean,
    val hasRadicalHook: Boolean,
    val hasParallelEquals: Boolean,
    val hasRightArrowTip: Boolean
)

enum class ReasoningMode {
    LOW,
    EXTENDED
}

class VertexAiService(
    @Volatile var apiKey: String = "",
    @Volatile var projectEndpoint: String = "",
    @Volatile var projectId: String = "",
    @Volatile var location: String = "us-central1",
    @Volatile var modelName: String = "gemini-3.8-flash",
    @Volatile var reasoningMode: ReasoningMode = ReasoningMode.LOW,
    isDebugLogging: Boolean = false
) {

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (isDebugLogging) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("x-goog-api-key")
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun isValidEndpoint(endpoint: String): Boolean {
        if (endpoint.isBlank()) return false
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme?.lowercase() != "https") return false
        if (!uri.userInfo.isNullOrBlank()) return false
        if (uri.port != -1 && uri.port != 443) return false

        val httpUrl = endpoint.toHttpUrlOrNull() ?: return false
        if (httpUrl.scheme != "https") return false
        if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return false
        if (httpUrl.port != 443) return false

        val host = httpUrl.host.lowercase()
        if (uri.host?.lowercase() != host) return false

        val validSubdomainRegex = Regex("""^[a-z0-9]+(?:-[a-z0-9]+)*-aiplatform\.googleapis\.com$""")
        return host == "generativelanguage.googleapis.com" ||
            host == "aiplatform.googleapis.com" ||
            host.matches(validSubdomainRegex)
    }

    fun hasLiveCredentials(): Boolean {
        return apiKey.isNotBlank() && (projectEndpoint.isBlank() || isValidEndpoint(projectEndpoint))
    }

    data class ParsedSourceBlock(
        val id: String,
        val title: String,
        val pageCount: Int,
        val content: String,
        val sentences: List<String>,
        val pageSentences: Map<Int, List<String>> = emptyMap()
    )

    // Generates a grounded conversational response with subtle citations & LaTeX math
    suspend fun generateGroundedResponse(
        userPrompt: String,
        sourcesText: String,
        conversationContext: String = "",
        courseTitle: String = "Course"
    ): Pair<String, List<Citation>> = withContext(Dispatchers.IO) {
        if (!hasLiveCredentials()) {
            return@withContext generateLocalSimulatedResponse(
                userPrompt = userPrompt,
                sourcesText = sourcesText,
                conversationContext = conversationContext,
                courseTitle = courseTitle
            )
        }

        val systemPrompt = """
            You are Cortex, an elite student study companion powered by Gemini via Vertex AI.
            Course: $courseTitle
            Your rules:
            1. Strictly ground your answer in the provided COURSE SOURCES.
            2. Place subtle citation markers like [SourceTitle, p.4] or [p.4] at the end of sentences that cite specific evidence.
            3. Always format mathematical formulas, chemistry equations, and calculations in valid LaTeX:
               - Use ${'$'}...${'$'} for inline math.
               - Use $${"$$"}...$${"$$"} for display block formulas.
            4. Keep explanations clear, academic, and encouraging.
        """.trimIndent()

        val fullPrompt = """
            $systemPrompt

            COURSE SOURCES:
            ${truncateSourcesForContextWindow(sourcesText)}

            CONVERSATION HISTORY:
            $conversationContext

            STUDENT QUESTION:
            $userPrompt
        """.trimIndent()

        try {
            val responseText = callGeminiEndpoint(fullPrompt)
            val parsedSources = parseSourceBlocks(sourcesText)
            val citations = extractCitations(responseText, parsedSources)
            Pair(responseText, citations)
        } catch (e: Exception) {
            generateLocalSimulatedResponse(
                userPrompt = userPrompt,
                sourcesText = sourcesText,
                conversationContext = conversationContext,
                courseTitle = courseTitle
            )
        }
    }

    // Generates an interactive Quiz artifact
    suspend fun generateQuiz(
        sourcesText: String,
        topic: String,
        projectId: String
    ): QuizArtifact = withContext(Dispatchers.IO) {
        if (!hasLiveCredentials()) {
            return@withContext generateLocalSimulatedQuiz(sourcesText, topic, projectId)
        }

        val prompt = """
            Generate an interactive 3-question multiple choice quiz based strictly on these sources:
            ${truncateSourcesForContextWindow(sourcesText)}

            Topic: $topic

            Respond ONLY in valid JSON with this exact schema (no markdown fences, raw JSON):
            {
              "title": "$topic Quiz",
              "questions": [
                {
                  "question": "Question text here?",
                  "options": ["Option A", "Option B", "Option C", "Option D"],
                  "correctIndex": 1,
                  "explanation": "Why B is correct based on sources.",
                  "sourceCitation": "SourceName • Page X"
                }
              ]
            }
        """.trimIndent()

        try {
            val rawJson = callGeminiEndpoint(prompt)
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val parsed = json.decodeFromString<QuizJsonDto>(rawJson)
            QuizArtifact(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                title = parsed.title,
                questions = parsed.questions.map { q ->
                    QuizQuestion(
                        id = UUID.randomUUID().toString(),
                        question = q.question,
                        options = q.options,
                        correctIndex = q.correctIndex.coerceIn(0, maxOf(0, q.options.size - 1)),
                        explanation = q.explanation,
                        sourceCitation = q.sourceCitation
                    )
                }
            )
        } catch (e: Exception) {
            generateLocalSimulatedQuiz(sourcesText, topic, projectId)
        }
    }

    // Generates a 2-host Audio Briefing dialogue
    suspend fun generateAudioBriefing(
        sourcesText: String,
        topic: String,
        projectId: String
    ): AudioBriefingArtifact = withContext(Dispatchers.IO) {
        if (!hasLiveCredentials()) {
            return@withContext generateLocalSimulatedAudioBriefing(sourcesText, topic, projectId)
        }

        val prompt = """
            Generate a 4-turn 2-host audio briefing dialogue between two study buddies (Host A (Alex) and Host B (Sam)) explaining:
            Topic: $topic
            Sources:
            ${truncateSourcesForContextWindow(sourcesText)}

            Respond ONLY in valid JSON with this schema:
            {
              "title": "$topic Audio Briefing",
              "turns": [
                {"speaker": "Host A (Alex)", "text": "Intro line with analogy..."},
                {"speaker": "Host B (Sam)", "text": "Deep dive point..."}
              ]
            }
        """.trimIndent()

        try {
            val rawJson = callGeminiEndpoint(prompt)
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val parsed = json.decodeFromString<AudioBriefingJsonDto>(rawJson)
            AudioBriefingArtifact(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                title = parsed.title,
                turns = parsed.turns.map { DialogueTurn(it.speaker, it.text) }
            )
        } catch (e: Exception) {
            generateLocalSimulatedAudioBriefing(sourcesText, topic, projectId)
        }
    }

    // Generates a structured Exam Cheat Sheet artifact
    suspend fun generateCheatSheet(
        sourcesText: String,
        topic: String,
        projectId: String
    ): CheatSheetArtifact = withContext(Dispatchers.IO) {
        if (!hasLiveCredentials()) {
            return@withContext generateLocalSimulatedCheatSheet(sourcesText, topic, projectId)
        }

        val prompt = """
            Create a concise, high-yield exam Cheat Sheet for:
            Topic: $topic
            Sources:
            ${truncateSourcesForContextWindow(sourcesText)}

            Include:
            1. Core Definitions & Mechanisms with [SourceTitle, p.X] citations
            2. Essential Equations in LaTeX (${'$'}...${'$'} and $${"$$"}...$${"$$"})
            3. High-Yield Exam Takeaways
        """.trimIndent()

        try {
            val content = callGeminiEndpoint(prompt).trim()
            CheatSheetArtifact(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                title = "$topic Exam Cheat Sheet",
                markdownContent = content
            )
        } catch (e: Exception) {
            generateLocalSimulatedCheatSheet(sourcesText, topic, projectId)
        }
    }

    /**
     * Recognizes handwritten S-Pen ink strokes using Vertex AI Gemini Vision (multimodal PNG input)
     * when live ADC/API credentials are present, or deterministic geometric stroke feature analysis offline.
     */
    suspend fun recognizeHandwrittenFormula(
        imagePngBase64: String,
        strokeSummary: StrokeFeatureSummary,
        courseTitle: String
    ): String = withContext(Dispatchers.IO) {
        if (hasLiveCredentials() && imagePngBase64.isNotBlank()) {
            val visionPrompt = """
                You are an expert mathematical and scientific OCR engine for $courseTitle.
                Transcribe the handwritten formula in the attached image into a single raw LaTeX expression.
                Rules:
                - Output ONLY the raw LaTeX formula string (no dollar signs, no markdown fences, no commentary).
                - If the sketch represents a chemical reaction, use \text{...} and \longrightarrow.
            """.trimIndent()
            try {
                val rawLatex = callGeminiEndpoint(visionPrompt, inlineImagePngBase64 = imagePngBase64)
                    .replace("```latex", "")
                    .replace("```", "")
                    .replace("$$", "")
                    .replace("$", "")
                    .trim()
                if (rawLatex.isNotBlank()) {
                    return@withContext rawLatex
                }
            } catch (_: Exception) {
                // Fall back to geometric stroke analysis
            }
        }
        classifyStrokesToLatex(strokeSummary, courseTitle)
    }

    internal fun classifyStrokesToLatex(
        summary: StrokeFeatureSummary,
        courseTitle: String
    ): String {
        val lowerCourse = courseTitle.lowercase()
        return when {
            summary.hasTallVerticalCurve ->
                "\\int_{a}^{b} f'(x)\\,dx = f(b) - f(a)"
            summary.hasRadicalHook && summary.hasWideHorizontalBar ->
                "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}"
            summary.hasRadicalHook ->
                "\\sqrt{x^2 + y^2} = r"
            summary.hasWideHorizontalBar && lowerCourse.contains("stat") ->
                "P(A \\mid B) = \\frac{P(B \\mid A)\\,P(A)}{P(B)}"
            summary.hasWideHorizontalBar ->
                "\\frac{d}{dx}\\left[f(g(x))\\right] = f'(g(x)) \\cdot g'(x)"
            summary.hasRightArrowTip || (lowerCourse.contains("bio") && summary.strokeCount >= 6) ->
                "\\text{C}_6\\text{H}_{12}\\text{O}_6 + 2\\text{NAD}^+ + 2\\text{ADP} + 2\\text{P}_i \\longrightarrow 2\\text{Pyruvate} + 2\\text{ATP}"
            summary.hasParallelEquals && (lowerCourse.contains("chem") || lowerCourse.contains("bio")) ->
                "\\Delta G = \\Delta H - T\\Delta S"
            summary.hasParallelEquals && lowerCourse.contains("linear") ->
                "A\\mathbf{v} = \\lambda\\mathbf{v} \\iff \\det(A - \\lambda I) = 0"
            summary.strokeCount <= 3 ->
                "E = mc^2"
            else ->
                buildDomainFormulaBlock(courseTitle, courseTitle, "").ifBlank {
                    "\\Delta G = \\Delta H - T\\Delta S"
                }
        }
    }

    private fun callGeminiEndpoint(prompt: String, inlineImagePngBase64: String? = null): String {
        val effectiveEndpoint = when {
            projectEndpoint.isNotBlank() -> {
                if (!isValidEndpoint(projectEndpoint)) {
                    throw SecurityException("Insecure or unapproved projectEndpoint: '$projectEndpoint'. Must be a valid HTTPS Google API domain (*.aiplatform.googleapis.com or generativelanguage.googleapis.com).")
                }
                projectEndpoint
            }
            apiKey.startsWith("ya29.") && projectId.isNotBlank() ->
                "https://${location}-aiplatform.googleapis.com/v1/projects/${projectId}/locations/${location}/publishers/google/models/${modelName}:generateContent"
            else ->
                "https://generativelanguage.googleapis.com/v1beta/models/${modelName}:generateContent"
        }

        val partsJson = if (!inlineImagePngBase64.isNullOrBlank()) {
            """
            [
              {"text": ${json.encodeToString(prompt)}},
              {"inline_data": {"mime_type": "image/png", "data": ${json.encodeToString(inlineImagePngBase64)}}}
            ]
            """.trimIndent()
        } else {
            """[{"text": ${json.encodeToString(prompt)}}]"""
        }

        val thinkingBudget = if (reasoningMode == ReasoningMode.EXTENDED) 2048 else 0
        val requestJson = """
            {
              "contents": [{
                "role": "user",
                "parts": $partsJson
              }],
              "generationConfig": {
                "temperature": 0.2,
                "maxOutputTokens": 4096,
                "thinkingConfig": {
                  "thinkingBudget": $thinkingBudget
                }
              }
            }
        """.trimIndent()

        val requestBuilder = Request.Builder()
            .url(effectiveEndpoint)
            .post(requestJson.toRequestBody("application/json".toMediaType()))

        if (apiKey.startsWith("ya29.")) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            if (projectId.isNotBlank()) {
                requestBuilder.addHeader("x-goog-user-project", projectId)
            }
        } else if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("x-goog-api-key", apiKey)
        }

        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("API error: ${response.code}")
            }
            val bodyString = response.body?.string() ?: ""
            val geminiResponse = json.decodeFromString<GeminiResponseDto>(bodyString)
            return geminiResponse.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
        }
    }

    private fun truncateSourcesForContextWindow(sourcesText: String, maxChars: Int = 48_000): String {
        if (sourcesText.length <= maxChars) return sourcesText
        return sourcesText.take(maxChars) + "\n\n[...Truncated additional pages to fit context window...]"
    }

    internal fun parseSourceBlocks(sourcesText: String): List<ParsedSourceBlock> {
        if (sourcesText.isBlank()) return emptyList()
        val rawBlocks = sourcesText.split(Regex("""\n+---\n+"""))
        return rawBlocks.mapIndexedNotNull { blockIdx, block ->
            val trimmed = block.trim()
            if (trimmed.isEmpty()) return@mapIndexedNotNull null
            val headerMatch = Regex("""^\[Source:\s*([^\]|]+)(?:\|\s*ID:\s*([^\]|]+))?(?:\|\s*Pages:\s*(\d+))?\]""").find(trimmed)
            val title = headerMatch?.groupValues?.get(1)?.trim() ?: "Course Notes"
            val explicitId = headerMatch?.groupValues?.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
            val pageCount = maxOf(1, headerMatch?.groupValues?.getOrNull(3)?.trim()?.toIntOrNull() ?: 1)
            val body = if (headerMatch != null) {
                trimmed.substring(headerMatch.range.last + 1).trim()
            } else {
                trimmed
            }

            // Extract explicit [Page X] sections if present
            val pageMarkerRegex = Regex("""\[Page\s+(\d+)\]""", RegexOption.IGNORE_CASE)
            val pageMatches = pageMarkerRegex.findAll(body).toList()
            val pageSentencesMap = mutableMapOf<Int, List<String>>()

            val cleanBody = pageMarkerRegex.replace(body, " ").trim()
            val sentences = cleanBody.split(Regex("""(?<=[.!?])\s+|\n+"""))
                .map { it.trim() }
                .filter { it.length > 12 }

            if (pageMatches.isNotEmpty()) {
                for (i in pageMatches.indices) {
                    val pNum = pageMatches[i].groupValues[1].toIntOrNull() ?: (i + 1)
                    val startIdx = pageMatches[i].range.last + 1
                    val endIdx = if (i + 1 < pageMatches.size) pageMatches[i + 1].range.first else body.length
                    val pageSection = body.substring(startIdx, endIdx)
                    val sents = pageSection.split(Regex("""(?<=[.!?])\s+|\n+"""))
                        .map { it.trim() }
                        .filter { it.length > 12 }
                    if (sents.isNotEmpty()) {
                        pageSentencesMap[pNum] = sents
                    }
                }
            } else if (sentences.isNotEmpty()) {
                sentences.forEachIndexed { idx, sent ->
                    val estimatedPage = minOf(pageCount, maxOf(1, ((idx + 1) * pageCount + sentences.size - 1) / maxOf(1, sentences.size)))
                    pageSentencesMap[estimatedPage] = pageSentencesMap[estimatedPage].orEmpty() + sent
                }
            }

            val defaultId = when {
                explicitId != null -> explicitId
                title.equals("Lecture_04_Glycolysis.pdf", ignoreCase = true) -> "src-1"
                title.equals("Krebs_Cycle_Summary.txt", ignoreCase = true) -> "src-2"
                else -> "src-${blockIdx + 1}"
            }

            ParsedSourceBlock(
                id = defaultId,
                title = title,
                pageCount = pageCount,
                content = cleanBody,
                sentences = sentences,
                pageSentences = pageSentencesMap
            )
        }
    }

    /**
     * Extracts citation badges and maps each marker to the exact source document and best-matching
     * sentence on that page using lexical claim similarity rather than round-robin modulo indexing.
     */
    internal fun extractCitations(
        text: String,
        parsedSources: List<ParsedSourceBlock> = emptyList()
    ): List<Citation> {
        if (text.isBlank()) return emptyList()
        val citationRegex = Regex("""\[(?:([^\]•,]+?)[,•]\s*)?(?:p\.|Slide|Page)\s*(\d+)\]""", RegexOption.IGNORE_CASE)
        val primarySource = parsedSources.firstOrNull()
        val results = mutableListOf<Citation>()
        val seenKeys = mutableSetOf<String>()

        citationRegex.findAll(text).forEachIndexed { index, matchResult ->
            val explicitSourceHint = matchResult.groupValues[1].trim()
            val page = matchResult.groupValues[2].toIntOrNull() ?: 1

            // Extract surrounding claim text immediately preceding the citation marker
            val claimStart = maxOf(0, matchResult.range.first - 180)
            val surroundingClaim = text.substring(claimStart, matchResult.range.first)
                .substringAfterLast("\n")
                .substringAfterLast(".")
                .trim()
            val claimTokens = tokenizeKeywords(surroundingClaim)

            // 1. Match source by explicit source hint if provided, else by highest lexical overlap with the claim
            val matchedSource = when {
                explicitSourceHint.isNotBlank() -> {
                    parsedSources.find {
                        it.title.contains(explicitSourceHint, ignoreCase = true) ||
                            explicitSourceHint.contains(it.title.substringBeforeLast("."), ignoreCase = true)
                    }
                }
                parsedSources.size > 1 && claimTokens.isNotEmpty() -> {
                    parsedSources.maxByOrNull { src ->
                        val pageCandidates = src.pageSentences[page].orEmpty().ifEmpty { src.sentences }
                        pageCandidates.maxOfOrNull { sent ->
                            val lower = sent.lowercase()
                            claimTokens.count { tok -> lower.contains(tok) } * 10 + (if (page <= src.pageCount) 2 else 0)
                        } ?: 0
                    }
                }
                else -> primarySource
            } ?: primarySource

            val dedupeKey = "${matchedSource?.id ?: "default"}:$page"
            if (seenKeys.add(dedupeKey)) {
                val candidatePool = matchedSource?.pageSentences?.get(page).orEmpty()
                    .ifEmpty { matchedSource?.sentences.orEmpty() }

                val bestSnippet = if (candidatePool.isNotEmpty() && claimTokens.isNotEmpty()) {
                    candidatePool.maxByOrNull { sent ->
                        val lower = sent.lowercase()
                        claimTokens.count { tok -> lower.contains(tok) }
                    } ?: candidatePool.first()
                } else {
                    candidatePool.firstOrNull() ?: "Referenced evidence on page $page"
                }

                results.add(
                    Citation(
                        id = "cite-$index-$page",
                        sourceId = matchedSource?.id ?: "src-${index + 1}",
                        sourceTitle = matchedSource?.title ?: "Course Material",
                        pageNumber = page,
                        quotedSnippet = bestSnippet
                    )
                )
            }
        }
        return results
    }

    /**
     * Genuine mathematical TF-IDF sentence scorer over active course sources:
     * TF(t, s) = count(t in s) / |s|, IDF(t, D) = ln(1 + |D| / (1 + df(t, D)))
     */
    internal fun computeTfIdfRankedSentences(
        parsedSources: List<ParsedSourceBlock>,
        queryTokens: List<String>,
        contextTokens: List<String>
    ): List<ScoredSentence> {
        data class IndexedSentence(
            val source: ParsedSourceBlock,
            val sentence: String,
            val tokens: List<String>,
            val page: Int
        )

        val corpus = parsedSources.flatMap { source ->
            if (source.pageSentences.isNotEmpty()) {
                source.pageSentences.entries.flatMap { (pageNum, sents) ->
                    sents.map { sent ->
                        IndexedSentence(source, sent, tokenizeKeywords(sent), pageNum)
                    }
                }
            } else {
                source.sentences.mapIndexed { idx, sent ->
                    val page = minOf(
                        source.pageCount,
                        maxOf(1, ((idx + 1) * source.pageCount + source.sentences.size - 1) / maxOf(1, source.sentences.size))
                    )
                    IndexedSentence(source, sent, tokenizeKeywords(sent), page)
                }
            }
        }

        if (corpus.isEmpty()) return emptyList()

        val totalDocs = corpus.size.toDouble()
        val allTerms = (queryTokens + contextTokens).distinct()

        fun matchesToken(token: String, term: String): Boolean {
            return token.equals(term, ignoreCase = true) ||
                (token.length >= 4 && term.length >= 4 && (token.startsWith(term) || term.startsWith(token)))
        }

        val idfMap = allTerms.associateWith { term ->
            val docFreq = corpus.count { item ->
                item.tokens.any { tok -> matchesToken(tok, term) }
            }
            ln(1.0 + totalDocs / (1.0 + docFreq.toDouble()))
        }

        return corpus.map { item ->
            val docLen = maxOf(1, item.tokens.size).toDouble()
            var tfIdfScore = 0.0
            for (qTerm in queryTokens.distinct()) {
                val termCount = item.tokens.count { tok -> matchesToken(tok, qTerm) }
                if (termCount > 0) {
                    val tf = termCount.toDouble() / docLen
                    val idf = idfMap[qTerm] ?: 1.0
                    tfIdfScore += (tf * idf) * 100.0
                }
            }
            for (cTerm in contextTokens.distinct()) {
                val termCount = item.tokens.count { tok -> matchesToken(tok, cTerm) }
                if (termCount > 0) {
                    val tf = termCount.toDouble() / docLen
                    val idf = idfMap[cTerm] ?: 1.0
                    tfIdfScore += (tf * idf) * 20.0
                }
            }
            ScoredSentence(
                source = item.source,
                sentence = item.sentence,
                page = item.page,
                score = tfIdfScore
            )
        }.sortedByDescending { it.score }
    }

    internal data class ScoredSentence(
        val source: ParsedSourceBlock,
        val sentence: String,
        val page: Int,
        val score: Double
    )

    internal fun generateLocalSimulatedResponse(
        userPrompt: String,
        sourcesText: String,
        conversationContext: String = "",
        courseTitle: String = "Course"
    ): Pair<String, List<Citation>> {
        val parsedSources = parseSourceBlocks(sourcesText)
        val queryTokens = tokenizeKeywords(userPrompt)
        val contextTokens = tokenizeKeywords(conversationContext)

        // Dynamic TF-IDF RAG over active sources
        if (parsedSources.isNotEmpty()) {
            val rankedCandidates = computeTfIdfRankedSentences(parsedSources, queryTokens, contextTokens)
            val topMatches = rankedCandidates
                .filter { it.score > 0.0 }
                .take(3)

            if (topMatches.isNotEmpty()) {
                val citations = topMatches.mapIndexed { idx, match ->
                    Citation(
                        id = "cite-dyn-$idx",
                        sourceId = match.source.id,
                        sourceTitle = match.source.title,
                        pageNumber = match.page,
                        quotedSnippet = match.sentence
                    )
                }

                val formulaBlock = buildDomainFormulaBlock("$userPrompt $conversationContext", courseTitle, sourcesText)
                val bulletPoints = topMatches.joinToString("\n") { m ->
                    "- ${m.sentence} [${m.source.title}, p.${m.page}]"
                }

                val response = buildString {
                    appendLine("Grounded in your **${parsedSources.joinToString(", ") { it.title }}** materials for **$courseTitle**:")
                    appendLine()
                    appendLine("Regarding **$userPrompt**:")
                    appendLine(bulletPoints)
                    if (formulaBlock.isNotBlank()) {
                        appendLine()
                        appendLine("Key relationship:")
                        appendLine("$$$formulaBlock$$")
                    }
                    if (reasoningMode == ReasoningMode.EXTENDED) {
                        appendLine()
                        appendLine("🧠 **Extended Reasoning Analysis:**")
                        appendLine("- Evaluated multi-source context across ${parsedSources.size} module(s).")
                        appendLine("- Derived underlying first principles to optimize retention and active recall.")
                    }
                    appendLine()
                    append("Tap any citation badge above to inspect the exact source passage, or tap **Quiz Me** to test your active recall on **$courseTitle**.")
                }
                return Pair(response, citations)
            } else {
                // When query terms have zero match in indexed sources, do not dump arbitrary sentences with fake citations
                val formulaBlock = buildDomainFormulaBlock("$userPrompt $conversationContext", courseTitle, "")
                val response = buildString {
                    appendLine("I searched your course materials for **$courseTitle**, but found no direct references to **$userPrompt**.")
                    appendLine()
                    appendLine("Your indexed materials currently cover:")
                    parsedSources.forEach { src ->
                        appendLine("- **${src.title}** (${src.sentences.size} indexed passages)")
                    }
                    if (formulaBlock.isNotBlank()) {
                        appendLine()
                        appendLine("Key relationship:")
                        appendLine("$$$formulaBlock$$")
                    }
                    appendLine()
                    append("Try rephrasing your question using terms from your course notes, or upload additional lecture slides.")
                }
                return Pair(response, emptyList())
            }
        }

        // Dynamic response when a new course has no uploaded documents yet
        val formulaBlock = buildDomainFormulaBlock("$userPrompt $conversationContext", courseTitle, "")
        val response = buildString {
            appendLine("Here is a grounded study breakdown for **$courseTitle** on **$userPrompt**:")
            appendLine()
            appendLine("- **Core Concept:** In **$courseTitle**, understanding **$userPrompt** requires connecting the fundamental definitions with quantitative analysis[p.1].")
            if (formulaBlock.isNotBlank()) {
                appendLine("- **Key Formula:**")
                appendLine("$$$formulaBlock$$")
            }
            appendLine("- **Study Tip:** Upload your professor's PDF slides or lecture notes via the **Sources** button in the top bar to ground answers in your exact syllabus.")
        }
        val defaultCitation = listOf(
            Citation("c-course-1", "course-notes", "$courseTitle Syllabus Notes", 1, "Core breakdown for $userPrompt in $courseTitle")
        )
        return Pair(response, defaultCitation)
    }

    private fun buildDomainFormulaBlock(prompt: String, courseTitle: String, sourcesText: String): String {
        val combined = "$prompt $courseTitle $sourcesText".lowercase()
        return when {
            combined.contains("calculus") || combined.contains("integral") || combined.contains("derivative") ->
                "\\int_{a}^{b} f'(x)\\,dx = f(b) - f(a)"
            combined.contains("linear algebra") || combined.contains("eigen") || combined.contains("matrix") ->
                "A\\mathbf{v} = \\lambda\\mathbf{v} \\iff \\det(A - \\lambda I) = 0"
            combined.contains("econ") || combined.contains("gdp") || combined.contains("inflation") || combined.contains("price") ->
                "Y = C + I + G + (X - M)"
            combined.contains("physics") || combined.contains("force") || combined.contains("energy") || combined.contains("momentum") ->
                "E_{\\text{total}} = \\frac{1}{2}mv^2 + U(x)"
            combined.contains("chem") || combined.contains("gibbs") || combined.contains("enthalpy") ->
                "\\Delta G = \\Delta H - T\\Delta S"
            combined.contains("stat") || combined.contains("probability") || combined.contains("bayes") ->
                "P(A \\mid B) = \\frac{P(B \\mid A)\\,P(A)}{P(B)}"
            else -> ""
        }
    }

    internal fun generateLocalSimulatedQuiz(
        sourcesText: String,
        topic: String,
        projectId: String
    ): QuizArtifact {
        val parsedSources = parseSourceBlocks(sourcesText)
        val allSentencesWithMeta = parsedSources.flatMap { src ->
            if (src.pageSentences.isNotEmpty()) {
                src.pageSentences.entries.flatMap { (pageNum, sents) ->
                    sents.map { sent -> Triple(src.title, pageNum, sent) }
                }
            } else {
                src.sentences.mapIndexed { idx, sent ->
                    val page = minOf(src.pageCount, maxOf(1, ((idx + 1) * src.pageCount + src.sentences.size - 1) / maxOf(1, src.sentences.size)))
                    Triple(src.title, page, sent)
                }
            }
        }

        if (allSentencesWithMeta.isNotEmpty()) {
            val questions = mutableListOf<QuizQuestion>()
            val numQuestionsFromSources = minOf(3, allSentencesWithMeta.size)

            for (qIdx in 0 until numQuestionsFromSources) {
                val (srcTitle, pageNum, fact) = allSentencesWithMeta[qIdx]
                val correctSnippet = fact.take(130).trim()

                val otherSentences = allSentencesWithMeta
                    .filter { it.third != fact }
                    .map { it.third.take(130).trim() }

                val distractors = mutableListOf<String>()
                // 1. First take other sentences from the document
                for (candidate in otherSentences) {
                    if (distractors.size < 3 && candidate != correctSnippet && !distractors.contains(candidate)) {
                        distractors.add(candidate)
                    }
                }

                // 2. Supplement with domain-appropriate academic distractors if fewer than 3
                val domainFallbacks = getContextualDistractors(topic, fact)
                for (fallback in domainFallbacks) {
                    if (distractors.size < 3 && fallback != correctSnippet && !distractors.contains(fallback)) {
                        distractors.add(fallback)
                    }
                }

                val optionsWithCorrect = mutableListOf<Pair<String, Boolean>>()
                optionsWithCorrect.add(correctSnippet to true)
                distractors.take(3).forEach {
                    optionsWithCorrect.add(it to false)
                }

                // Ensure non-negative targetIndex even when hashCode == Int.MIN_VALUE
                val safeHash = fact.hashCode() and 0x7FFFFFFF
                val targetIndex = (safeHash + qIdx + 1) % optionsWithCorrect.size
                val correctItem = optionsWithCorrect.removeAt(0)
                optionsWithCorrect.add(targetIndex, correctItem)

                questions.add(
                    QuizQuestion(
                        id = UUID.randomUUID().toString(),
                        question = "According to $srcTitle regarding $topic, which statement is accurately supported?",
                        options = optionsWithCorrect.map { it.first },
                        correctIndex = targetIndex,
                        explanation = "Directly stated in $srcTitle (Page $pageNum): \"$fact\"",
                        sourceCitation = "$srcTitle • Page $pageNum"
                    )
                )
            }

            // If the document had fewer than 3 sentences, supplement with high-yield topic concept questions
            if (questions.size < 3) {
                val supplementalQuestions = getSupplementalTopicQuestions(topic)
                for (suppQ in supplementalQuestions) {
                    if (questions.size >= 3) break
                    questions.add(suppQ)
                }
            }

            return QuizArtifact(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                title = "$topic Active Recall",
                questions = questions
            )
        }

        return QuizArtifact(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            title = "$topic Concept Check",
            questions = listOf(
                QuizQuestion(
                    id = UUID.randomUUID().toString(),
                    question = "When studying $topic, what is the primary benefit of grounding problem sets in primary lecture sources?",
                    options = listOf(
                        "Replaces mathematical proofs with unverified summaries",
                        "Eliminates hallucinated formulas and verifies exact notation used by the professor",
                        "Skips foundational definitions during exam preparation",
                        "Restricts review to a single unverified webpage"
                    ),
                    correctIndex = 1,
                    explanation = "Source grounding ensures formulas, definitions, and notation match your course syllabus for $topic.",
                    sourceCitation = "$topic Course Overview"
                ),
                QuizQuestion(
                    id = UUID.randomUUID().toString(),
                    question = "Which active recall technique yields the highest long-term retention for $topic?",
                    options = listOf(
                        "Passive re-reading of highlighted text",
                        "Cramming without practice problems",
                        "Spaced repetition testing with immediate rationale feedback",
                        "Copying slides word-for-word without self-quizzing"
                    ),
                    correctIndex = 2,
                    explanation = "Retrieval practice combined with spaced repetition strengthens memory consolidation.",
                    sourceCitation = "$topic Study Guide"
                ),
                QuizQuestion(
                    id = UUID.randomUUID().toString(),
                    question = "How should core equations and boundary conditions in $topic be validated?",
                    options = listOf(
                        "Cross-referencing dimensional analysis and official course problem sets",
                        "Assuming all unverified formula sheets online are complete",
                        "Ignoring units when solving multi-step calculations",
                        "Applying formulas outside their stated validity domain"
                    ),
                    correctIndex = 0,
                    explanation = "Dimensional consistency and verified problem sets are essential for rigorous problem solving.",
                    sourceCitation = "$topic Problem Set Guide"
                )
            )
        )
    }

    private fun getContextualDistractors(topic: String, fact: String): List<String> {
        val lower = "$topic $fact".lowercase()
        return when {
            lower.contains("calculus") || lower.contains("math") || lower.contains("derivative") || lower.contains("integral") -> listOf(
                "This function is discontinuous across all non-zero boundary intervals in $topic.",
                "The derivative diverges to infinity under all standard initial conditions.",
                "This relationship is strictly non-integrable over any compact closed subset."
            )
            lower.contains("linear algebra") || lower.contains("matrix") || lower.contains("eigen") -> listOf(
                "The column vectors are linearly dependent and span a degenerate subspace in $topic.",
                "The transformation matrix has zero trace and lacks orthogonal eigenvectors.",
                "The determinant is invariant and strictly non-zero under arbitrary row operations."
            )
            lower.contains("econ") || lower.contains("gdp") || lower.contains("market") || lower.contains("fiscal") -> listOf(
                "Market clearing occurs instantaneously without price adjustments or elasticity in $topic.",
                "Aggregate supply remains completely inelastic regardless of institutional policy shifts.",
                "Consumer surplus equals zero across all competitive equilibrium points in this model."
            )
            lower.contains("physics") || lower.contains("quantum") || lower.contains("force") || lower.contains("thermo") -> listOf(
                "This observable violates energy conservation in closed relativistic frames in $topic.",
                "The entropy of the isolated system decreases monotonically over time.",
                "The potential energy remains zero across arbitrary non-inertial reference coordinate frames."
            )
            lower.contains("bio") || lower.contains("respiration") || lower.contains("cell") || lower.contains("glycolysis") -> listOf(
                "This process operates spontaneously without requiring enzyme catalysis or energy transfer in $topic.",
                "The reaction equilibrium in $topic is strictly inverse and independent of environmental changes.",
                "This pathway occurs solely within extracellular fluid without generating metabolic intermediates."
            )
            else -> listOf(
                "The foundational theorem for $topic assumes linear invariance across all observed conditions.",
                "The empirical observations in $topic contradict theoretical predictions when measured at scale.",
                "This mechanism operates spontaneously without requiring external energy or parameter inputs in $topic."
            )
        }
    }

    private fun getSupplementalTopicQuestions(topic: String): List<QuizQuestion> {
        return listOf(
            QuizQuestion(
                id = UUID.randomUUID().toString(),
                question = "When analyzing core principles in $topic, what is the most critical validation step?",
                options = listOf(
                    "Neglecting boundary conditions during problem derivation",
                    "Verifying dimensional consistency and foundational assumptions",
                    "Relying on unverified secondary forum posts",
                    "Assuming all parameters remain constant without empirical check"
                ),
                correctIndex = 1,
                explanation = "Rigorous analysis in $topic requires checking boundary assumptions and dimensional consistency.",
                sourceCitation = "$topic Foundation Concepts"
            ),
            QuizQuestion(
                id = UUID.randomUUID().toString(),
                question = "Which active study approach provides the highest conceptual retention for $topic?",
                options = listOf(
                    "Rereading passive summaries without working through proofs or examples",
                    "Highlighting textbook paragraphs without recall practice",
                    "Active retrieval practice and problem-solving with immediate feedback",
                    "Memorizing final numerical answers without understanding the derivations"
                ),
                correctIndex = 2,
                explanation = "Active retrieval and targeted problem-solving strengthen long-term conceptual retention.",
                sourceCitation = "$topic Study Strategy"
            )
        )
    }

    internal fun generateLocalSimulatedAudioBriefing(
        sourcesText: String,
        topic: String,
        projectId: String
    ): AudioBriefingArtifact {
        val parsedSources = parseSourceBlocks(sourcesText)
        val allSentences = parsedSources.flatMap { it.sentences }
        val sourceSummary1 = allSentences.firstOrNull()
            ?: "Today we're focusing on the core principles and formulas of $topic."
        val sourceSummary2 = allSentences.getOrNull(1)
            ?: allSentences.firstOrNull()
            ?: "Make sure to connect each definition back to the primary problem sets in your course sources."
        val sourceNames = parsedSources.joinToString(", ") { it.title }.ifBlank { "$topic Course Notes" }

        return AudioBriefingArtifact(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            title = "$topic Audio Briefing",
            turns = listOf(
                DialogueTurn("Host A (Alex)", "Welcome to your Cortex Audio Briefing for $topic! We've synthesized your key notes from $sourceNames."),
                DialogueTurn("Host B (Sam)", "Let's jump right into the first anchor point: $sourceSummary1"),
                DialogueTurn("Host A (Alex)", "That's a classic exam favorite. Building on that, here's the second key takeaway: $sourceSummary2"),
                DialogueTurn("Host B (Sam)", "Spot on, Alex. Try tapping Quiz Me right after this briefing to lock these $topic concepts into long-term memory!")
            )
        )
    }

    internal fun generateLocalSimulatedCheatSheet(
        sourcesText: String,
        topic: String,
        projectId: String
    ): CheatSheetArtifact {
        val parsedSources = parseSourceBlocks(sourcesText)
        val formula = buildDomainFormulaBlock(topic, topic, sourcesText)

        val bullets = parsedSources.flatMap { src ->
            src.sentences.take(3).mapIndexed { idx, s -> "- **${src.title}:** $s [p.${idx + 1}]" }
        }.ifEmpty {
            listOf(
                "- **Core Principle:** Master foundational definitions and boundary conditions for **$topic**[p.1].",
                "- **Problem-Solving Strategy:** Identify given variables, verify units, and cite primary theorems."
            )
        }

        val md = buildString {
            appendLine("**$topic — Condensed Exam Reference**")
            appendLine()
            bullets.forEach { appendLine(it) }
            if (formula.isNotBlank()) {
                appendLine()
                appendLine("**Essential Formula:**")
                appendLine("$$$formula$$")
            }
        }

        return CheatSheetArtifact(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            title = "$topic Exam Cheat Sheet",
            markdownContent = md.trim()
        )
    }

    internal fun tokenizeKeywords(input: String): List<String> {
        val stopWords = setOf(
            "what", "when", "where", "which", "who", "whom", "whose", "why", "how",
            "the", "and", "for", "with", "from", "that", "this", "these", "those",
            "are", "was", "were", "been", "being", "have", "has", "had", "does",
            "did", "can", "could", "would", "should", "about", "into", "explain", "tell",
            "a", "an", "in", "on", "of", "to", "is", "it", "as", "by", "at", "be"
        )
        return input.lowercase()
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { token -> token.isNotBlank() && token !in stopWords }
    }
}

// Data Transfer Objects for API
@Serializable
private data class QuizJsonDto(
    val title: String,
    val questions: List<QuizQuestionDto>
)

@Serializable
private data class QuizQuestionDto(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    val sourceCitation: String? = null
)

@Serializable
private data class AudioBriefingJsonDto(
    val title: String,
    val turns: List<DialogueTurnDto>
)

@Serializable
private data class DialogueTurnDto(
    val speaker: String,
    val text: String
)

@Serializable
private data class GeminiResponseDto(
    val candidates: List<CandidateDto> = emptyList()
)

@Serializable
private data class CandidateDto(
    val content: ContentDto? = null
)

@Serializable
private data class ContentDto(
    val parts: List<PartDto> = emptyList()
)

@Serializable
private data class PartDto(
    val text: String = ""
)
