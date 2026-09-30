package com.cortex.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val title: String,
    val description: String = "",
    val colorHex: String = "#6366F1", // Default Indigo
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Source(
    val id: String,
    val projectId: String,
    val title: String,
    val textContent: String,
    val pageCount: Int = 1,
    val isSelected: Boolean = true
)

@Serializable
enum class MessageSender {
    USER, AI
}

@Serializable
data class Citation(
    val id: String,
    val sourceId: String,
    val sourceTitle: String,
    val pageNumber: Int,
    val quotedSnippet: String
)

@Serializable
data class ChatMessage(
    val id: String,
    val projectId: String,
    val sender: MessageSender,
    val text: String,
    val citations: List<Citation> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

// First-class Artifacts
@Serializable
data class QuizQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    val sourceCitation: String? = null
)

@Serializable
data class QuizArtifact(
    val id: String,
    val projectId: String,
    val title: String,
    val questions: List<QuizQuestion>,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class DialogueTurn(
    val speaker: String, // "Host A (Alex)", "Host B (Sam)"
    val text: String
)

@Serializable
data class AudioBriefingArtifact(
    val id: String,
    val projectId: String,
    val title: String,
    val turns: List<DialogueTurn>,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class CheatSheetArtifact(
    val id: String,
    val projectId: String,
    val title: String,
    val markdownContent: String,
    val createdAt: Long = System.currentTimeMillis()
)
