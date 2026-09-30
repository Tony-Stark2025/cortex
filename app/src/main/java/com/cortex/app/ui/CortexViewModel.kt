package com.cortex.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cortex.app.BuildConfig
import com.cortex.app.data.model.MessageSender
import com.cortex.app.data.remote.ReasoningMode
import com.cortex.app.data.remote.VertexAiService
import com.cortex.app.data.repository.CortexRepository
import com.cortex.app.notifications.OneSignalManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CortexViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("cortex_settings", Context.MODE_PRIVATE)

    val repository: CortexRepository = CortexRepository(application.applicationContext)

    val aiService: VertexAiService = VertexAiService(
        apiKey = prefs.getString("gemini_api_key", null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GEMINI_API_KEY,
        projectEndpoint = prefs.getString("vertex_endpoint", null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.VERTEX_PROJECT_ENDPOINT,
        projectId = BuildConfig.VERTEX_PROJECT_ID,
        location = BuildConfig.VERTEX_LOCATION,
        modelName = "gemini-3.8-flash",
        reasoningMode = if (prefs.getString("gemini_reasoning_mode", "LOW") == "EXTENDED") ReasoningMode.EXTENDED else ReasoningMode.LOW,
        isDebugLogging = BuildConfig.DEBUG
    )

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    fun sendUserMessage(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) return
        val targetProject = repository.selectedProject.value ?: return
        val targetProjectId = targetProject.id
        val courseTitle = targetProject.title
        val activeSourcesSnapshot = repository.getActiveSourcesText()
        val historyContext = repository.getConversationContext()

        repository.addMessage(MessageSender.USER, query, targetProjectId = targetProjectId)

        viewModelScope.launch {
            _isThinking.value = true
            try {
                val (response, citations) = aiService.generateGroundedResponse(
                    userPrompt = query,
                    sourcesText = activeSourcesSnapshot,
                    conversationContext = historyContext,
                    courseTitle = courseTitle
                )
                repository.addMessage(
                    sender = MessageSender.AI,
                    text = response,
                    citations = citations,
                    targetProjectId = targetProjectId
                )
            } finally {
                _isThinking.value = false
            }
        }
    }

    fun generateQuiz(onReady: () -> Unit = {}) {
        val targetProject = repository.selectedProject.value
        val targetProjectId = targetProject?.id ?: "default"
        val topic = targetProject?.title ?: "General"
        val activeSourcesSnapshot = repository.getActiveSourcesText()

        viewModelScope.launch {
            _isThinking.value = true
            try {
                val quiz = aiService.generateQuiz(
                    sourcesText = activeSourcesSnapshot,
                    topic = topic,
                    projectId = targetProjectId
                )
                repository.setQuiz(quiz, targetProjectId = targetProjectId)
                OneSignalManager.scheduleSpacedRepetitionReminder(getApplication(), topic)
                if (repository.selectedProject.value?.id == targetProjectId) {
                    onReady()
                }
            } finally {
                _isThinking.value = false
            }
        }
    }

    fun generateAudioBriefing(onReady: () -> Unit = {}) {
        val targetProject = repository.selectedProject.value
        val targetProjectId = targetProject?.id ?: "default"
        val topic = targetProject?.title ?: "General"
        val activeSourcesSnapshot = repository.getActiveSourcesText()

        viewModelScope.launch {
            _isThinking.value = true
            try {
                val audio = aiService.generateAudioBriefing(
                    sourcesText = activeSourcesSnapshot,
                    topic = topic,
                    projectId = targetProjectId
                )
                repository.setAudioBriefing(audio, targetProjectId = targetProjectId)
                if (repository.selectedProject.value?.id == targetProjectId) {
                    onReady()
                }
            } finally {
                _isThinking.value = false
            }
        }
    }

    fun generateCheatSheet(onReady: () -> Unit = {}) {
        val targetProject = repository.selectedProject.value
        val targetProjectId = targetProject?.id ?: "default"
        val topic = targetProject?.title ?: "General"
        val activeSourcesSnapshot = repository.getActiveSourcesText()

        viewModelScope.launch {
            _isThinking.value = true
            try {
                val sheet = aiService.generateCheatSheet(
                    sourcesText = activeSourcesSnapshot,
                    topic = topic,
                    projectId = targetProjectId
                )
                repository.setCheatSheet(sheet, targetProjectId = targetProjectId)
                if (repository.selectedProject.value?.id == targetProjectId) {
                    onReady()
                }
            } finally {
                _isThinking.value = false
            }
        }
    }
}
