package com.cortex.app.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun `serialize and deserialize Project model`() {
        val original = Project(
            id = "proj-1",
            title = "Linear Algebra",
            description = "Matrix decompositions and eigenvalues",
            colorHex = "#10B981"
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<Project>(serialized)

        assertEquals(original.id, deserialized.id)
        assertEquals(original.title, deserialized.title)
        assertEquals(original.description, deserialized.description)
        assertEquals(original.colorHex, deserialized.colorHex)
    }

    @Test
    fun `serialize and deserialize ChatMessage with Citations`() {
        val citation = Citation(
            id = "c-1",
            sourceId = "src-1",
            sourceTitle = "Lecture_01.pdf",
            pageNumber = 3,
            quotedSnippet = "Eigenvectors satisfy Av = lambda v."
        )
        val message = ChatMessage(
            id = "msg-1",
            projectId = "proj-1",
            sender = MessageSender.AI,
            text = "Here is the eigenvalue definition with citation[p.3].",
            citations = listOf(citation)
        )

        val serialized = json.encodeToString(message)
        val deserialized = json.decodeFromString<ChatMessage>(serialized)

        assertEquals(message.id, deserialized.id)
        assertEquals(message.sender, deserialized.sender)
        assertEquals(1, deserialized.citations.size)
        assertEquals(3, deserialized.citations.first().pageNumber)
    }

    @Test
    fun `serialize and deserialize QuizArtifact with questions`() {
        val quiz = QuizArtifact(
            id = "quiz-1",
            projectId = "proj-1",
            title = "Linear Algebra Recall",
            questions = listOf(
                QuizQuestion(
                    id = "q-1",
                    question = "What is the determinant of an identity matrix?",
                    options = listOf("0", "1", "-1", "undefined"),
                    correctIndex = 1,
                    explanation = "The determinant of any identity matrix I_n is exactly 1.",
                    sourceCitation = "Lecture_01.pdf • Page 5"
                )
            )
        )

        val serialized = json.encodeToString(quiz)
        val deserialized = json.decodeFromString<QuizArtifact>(serialized)

        assertEquals(quiz.id, deserialized.id)
        assertEquals(1, deserialized.questions.size)
        assertEquals(1, deserialized.questions.first().correctIndex)
    }

    @Test
    fun `serialize and deserialize AudioBriefingArtifact`() {
        val briefing = AudioBriefingArtifact(
            id = "audio-1",
            projectId = "proj-1",
            title = "Audio Overview",
            turns = listOf(
                DialogueTurn("Host A (Alex)", "Welcome to today's review!"),
                DialogueTurn("Host B (Sam)", "Let's dive into eigenvalues.")
            )
        )

        val serialized = json.encodeToString(briefing)
        val deserialized = json.decodeFromString<AudioBriefingArtifact>(serialized)

        assertEquals(2, deserialized.turns.size)
        assertEquals("Host A (Alex)", deserialized.turns[0].speaker)
    }
}
