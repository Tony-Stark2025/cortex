package com.cortex.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cortex.app.FoldPosture
import com.cortex.app.data.local.DocumentTextExtractor
import com.cortex.app.data.model.ChatMessage
import com.cortex.app.data.model.Citation
import com.cortex.app.data.model.MessageSender
import com.cortex.app.data.remote.ReasoningMode
import com.cortex.app.data.remote.VertexAiService
import com.cortex.app.data.repository.CortexRepository
import com.cortex.app.monetization.RevenueCatManager
import com.cortex.app.notifications.OneSignalManager
import com.cortex.app.ui.CortexViewModel
import com.cortex.app.ui.components.AudioBriefingSheet
import com.cortex.app.ui.components.CollapsibleCheatSheet
import com.cortex.app.ui.components.CollapsibleQuizSheet
import com.cortex.app.ui.components.CollapsibleSourcesSheet
import com.cortex.app.ui.components.MarkdownMathView
import com.cortex.app.ui.components.SPenFormulaSheet
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    repository: CortexRepository,
    aiService: VertexAiService,
    onOpenProjects: () -> Unit,
    onOpenPaywall: () -> Unit,
    isFlexMode: Boolean = false,
    foldPosture: FoldPosture = FoldPosture.NORMAL,
    viewModel: CortexViewModel? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val currentProject by repository.selectedProject.collectAsState()
    val sources by repository.sources.collectAsState()
    val messages by repository.messages.collectAsState()
    val activeQuiz by repository.activeQuiz.collectAsState()
    val activeAudio by repository.activeAudioBriefing.collectAsState()
    val activeCheatSheet by repository.activeCheatSheet.collectAsState()
    val isPro by RevenueCatManager.isProUser.collectAsState()
    val vmThinking by (viewModel?.isThinking?.collectAsState() ?: remember { mutableStateOf(false) })

    var manualFlexMode by rememberSaveable { mutableStateOf(false) }
    val effectiveFlexMode = isFlexMode || manualFlexMode
    val isBookVerticalPosture = foldPosture == FoldPosture.BOOK_VERTICAL && !manualFlexMode

    var showSourcesSheet by rememberSaveable { mutableStateOf(false) }
    var showQuizSheet by rememberSaveable { mutableStateOf(false) }
    var showAudioSheet by rememberSaveable { mutableStateOf(false) }
    var showCheatSheet by rememberSaveable { mutableStateOf(false) }
    var showSPenSheet by rememberSaveable { mutableStateOf(false) }
    var showApiSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var selectedCitation by remember { mutableStateOf<Citation?>(null) }

    var inputText by rememberSaveable { mutableStateOf("") }
    var localThinking by remember { mutableStateOf(false) }
    val isThinking = vmThinking || localThinking

    // Load saved API key / Vertex endpoint from SharedPreferences on first composition
    val prefs = remember { context.getSharedPreferences("cortex_settings", Context.MODE_PRIVATE) }
    var apiKeyInput by remember { mutableStateOf(prefs.getString("gemini_api_key", aiService.apiKey) ?: "") }
    var endpointInput by remember { mutableStateOf(prefs.getString("vertex_endpoint", aiService.projectEndpoint) ?: "") }
    var selectedReasoningMode by remember {
        mutableStateOf(
            if (prefs.getString("gemini_reasoning_mode", "LOW") == "EXTENDED") ReasoningMode.EXTENDED else ReasoningMode.LOW
        )
    }

    LaunchedEffect(Unit) {
        if (apiKeyInput.isNotBlank()) aiService.apiKey = apiKeyInput
        if (endpointInput.isNotBlank()) aiService.projectEndpoint = endpointInput
        aiService.modelName = "gemini-3.8-flash"
        aiService.reasoningMode = selectedReasoningMode
    }

    // Scroll to bottom when messages update
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Non-blocking PDF & Document File Picker on Dispatchers.IO
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val targetProjectId = currentProject?.id
            coroutineScope.launch {
                try {
                    val extracted = DocumentTextExtractor.extractFromUri(context, it)
                    repository.addSource(
                        title = extracted.fileName,
                        content = extracted.textContent,
                        pageCount = extracted.pageCount,
                        targetProjectId = targetProjectId
                    )
                    Toast.makeText(
                        context,
                        "Indexed ${extracted.fileName} (${extracted.pageCount} pages)",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Could not read file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Voice Speech Recognizer for Hands-free Desk input
    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                inputText = spokenText
            }
        }
    }

    fun launchSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Cortex anything about ${currentProject?.title ?: "your lectures"}...")
        }
        try {
            speechRecognizerLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Voice recognizer not available on this device; populating sample query.", Toast.LENGTH_SHORT).show()
            inputText = "Explain the key steps and formulas in ${currentProject?.title ?: "this lecture"}"
        }
    }

    fun triggerVoiceInput() {
        launchSpeechRecognition()
    }

    fun sendUserMessage(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) return
        inputText = ""
        if (viewModel != null) {
            viewModel.sendUserMessage(query)
        } else {
            val targetProjectId = currentProject?.id
            val courseTitle = currentProject?.title ?: "Course"
            val sourcesSnap = repository.getActiveSourcesText()
            val historyContext = repository.getConversationContext()
            repository.addMessage(MessageSender.USER, query, targetProjectId = targetProjectId)
            coroutineScope.launch {
                localThinking = true
                try {
                    val (response, citations) = aiService.generateGroundedResponse(
                        userPrompt = query,
                        sourcesText = sourcesSnap,
                        conversationContext = historyContext,
                        courseTitle = courseTitle
                    )
                    repository.addMessage(MessageSender.AI, response, citations, targetProjectId = targetProjectId)
                } finally {
                    localThinking = false
                }
            }
        }
    }

    fun triggerQuizGeneration() {
        if (viewModel != null) {
            viewModel.generateQuiz { showQuizSheet = true }
        } else {
            val targetProjectId = currentProject?.id ?: "default"
            val topic = currentProject?.title ?: "General"
            val sourcesSnap = repository.getActiveSourcesText()
            coroutineScope.launch {
                localThinking = true
                try {
                    val quiz = aiService.generateQuiz(sourcesSnap, topic, targetProjectId)
                    repository.setQuiz(quiz, targetProjectId = targetProjectId)
                    showQuizSheet = true
                    OneSignalManager.scheduleSpacedRepetitionReminder(context, topic)
                } finally {
                    localThinking = false
                }
            }
        }
    }

    fun triggerAudioBriefingGeneration() {
        if (viewModel != null) {
            viewModel.generateAudioBriefing { showAudioSheet = true }
        } else {
            val targetProjectId = currentProject?.id ?: "default"
            val topic = currentProject?.title ?: "General"
            val sourcesSnap = repository.getActiveSourcesText()
            coroutineScope.launch {
                localThinking = true
                try {
                    val audio = aiService.generateAudioBriefing(sourcesSnap, topic, targetProjectId)
                    repository.setAudioBriefing(audio, targetProjectId = targetProjectId)
                    showAudioSheet = true
                } finally {
                    localThinking = false
                }
            }
        }
    }

    fun triggerCheatSheetGeneration() {
        if (viewModel != null) {
            viewModel.generateCheatSheet { showCheatSheet = true }
        } else {
            val targetProjectId = currentProject?.id ?: "default"
            val topic = currentProject?.title ?: "General"
            val sourcesSnap = repository.getActiveSourcesText()
            coroutineScope.launch {
                localThinking = true
                try {
                    val sheet = aiService.generateCheatSheet(sourcesSnap, topic, targetProjectId)
                    repository.setCheatSheet(sheet, targetProjectId = targetProjectId)
                    showCheatSheet = true
                } finally {
                    localThinking = false
                }
            }
        }
    }

    val projectColor = remember(currentProject?.colorHex) {
        try {
            Color(android.graphics.Color.parseColor(currentProject?.colorHex ?: "#6366F1"))
        } catch (_: Exception) {
            Color(0xFF6366F1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Project Selector Pill
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { onOpenProjects() },
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(projectColor)
                                )
                                Text(
                                    text = currentProject?.title ?: "Select Subject",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
                    // Collapsible Source Pill
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { showSourcesSheet = true },
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "${sources.count { it.isSelected }} Sources",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }

                    // Flex Mode Toggle (auto-activates on Foldable HALF_OPENED or manual toggle)
                    IconButton(onClick = { manualFlexMode = !manualFlexMode }) {
                        Icon(
                            imageVector = Icons.Default.DevicesFold,
                            contentDescription = "Toggle Tabletop Flex Mode",
                            tint = if (effectiveFlexMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }

                    // AI Settings Dialog Trigger
                    IconButton(onClick = { showApiSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "AI Engine Settings",
                            tint = if (aiService.hasLiveCredentials()) Color(0xFF10B981) else MaterialTheme.colorScheme.outline
                        )
                    }

                    // Pro Paywall & Status Badge
                    IconButton(onClick = onOpenPaywall) {
                        BadgedBox(
                            badge = {
                                if (isPro) {
                                    Badge(containerColor = Color(0xFF10B981)) {
                                        Text("PRO", fontSize = 7.sp, color = Color.White)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Cortex Pro",
                                tint = if (isPro) Color(0xFFF59E0B) else MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            if (!effectiveFlexMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    // Quick Artifact Trigger Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = { triggerQuizGeneration() },
                            label = { Text("🎯 Quiz Me", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            shape = RoundedCornerShape(14.dp)
                        )

                        AssistChip(
                            onClick = { triggerAudioBriefingGeneration() },
                            label = { Text("🎧 Audio Briefing", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            shape = RoundedCornerShape(14.dp)
                        )

                        AssistChip(
                            onClick = { triggerCheatSheetGeneration() },
                            label = { Text("📄 Cheat Sheet", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            shape = RoundedCornerShape(14.dp)
                        )

                        AssistChip(
                            onClick = { showSPenSheet = true },
                            label = { Text("✍️ S-Pen Math", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            shape = RoundedCornerShape(14.dp)
                        )
                    }

                    ChatInputRow(
                        inputText = inputText,
                        onInputChange = { inputText = it },
                        onUploadClick = { filePickerLauncher.launch(arrayOf("application/pdf", "text/plain")) },
                        onSendClick = { sendUserMessage(inputText) },
                        onVoiceClick = { triggerVoiceInput() }
                    )
                }
            }
        }
    ) { paddingValues ->
        if (effectiveFlexMode && isBookVerticalPosture) {
            // SAMSUNG GALAXY Z FOLD BOOK MODE (Vertical Hinge Split: Left Display • Right Control Deck)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight()
                ) {
                    MessageStreamList(
                        messages = messages,
                        isThinking = isThinking,
                        listState = listState,
                        onCitationClick = { selectedCitation = it }
                    )
                }

                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )

                Column(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "📖 Galaxy Book Posture (Vertical Hinge)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        FlexDeckButton(
                            title = "🎯 Active Recall Quiz",
                            subtitle = "Test course mastery",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { triggerQuizGeneration() }
                        )
                        FlexDeckButton(
                            title = "🎧 2-Host Briefing",
                            subtitle = "Alex & Sam podcast",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { triggerAudioBriefingGeneration() }
                        )
                        FlexDeckButton(
                            title = "📄 Exam Cheat Sheet",
                            subtitle = "Formulas & takeaways",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { triggerCheatSheetGeneration() }
                        )
                        FlexDeckButton(
                            title = "✍️ S-Pen LaTeX Pad",
                            subtitle = "Handwrite equations",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showSPenSheet = true }
                        )
                    }

                    ChatInputRow(
                        inputText = inputText,
                        onInputChange = { inputText = it },
                        onUploadClick = { filePickerLauncher.launch(arrayOf("application/pdf", "text/plain")) },
                        onSendClick = { sendUserMessage(inputText) },
                        onVoiceClick = { triggerVoiceInput() }
                    )
                }
            }
        } else if (effectiveFlexMode) {
            // SAMSUNG GALAXY TABLETOP FLEX MODE (Horizontal 90° Hinge Split Layout)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // TOP SCREEN (Angled toward user's eyes): Grounded Answers, Math & Citations
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    MessageStreamList(
                        messages = messages,
                        isThinking = isThinking,
                        listState = listState,
                        onCitationClick = { selectedCitation = it }
                    )
                }

                // FOLDABLE HINGE CREASE DIVIDER
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📐 Galaxy Tabletop Flex Mode (90° Posture)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Top: Display • Bottom: Control Deck",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // BOTTOM SCREEN (Flat on desk): Ergonomic Tabletop Control Pad, S-Pen & Voice Deck
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 2x2 Ergonomic Tabletop Action Pad
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FlexDeckButton(
                                title = "🎯 Active Recall Quiz",
                                subtitle = "Test course mastery",
                                modifier = Modifier.weight(1f),
                                onClick = { triggerQuizGeneration() }
                            )
                            FlexDeckButton(
                                title = "🎧 2-Host Briefing",
                                subtitle = "Alex & Sam podcast",
                                modifier = Modifier.weight(1f),
                                onClick = { triggerAudioBriefingGeneration() }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FlexDeckButton(
                                title = "📄 Exam Cheat Sheet",
                                subtitle = "Formulas & takeaways",
                                modifier = Modifier.weight(1f),
                                onClick = { triggerCheatSheetGeneration() }
                            )
                            FlexDeckButton(
                                title = "✍️ S-Pen LaTeX Pad",
                                subtitle = "Handwrite equations",
                                modifier = Modifier.weight(1f),
                                onClick = { showSPenSheet = true }
                            )
                        }
                    }

                    ChatInputRow(
                        inputText = inputText,
                        onInputChange = { inputText = it },
                        onUploadClick = { filePickerLauncher.launch(arrayOf("application/pdf", "text/plain")) },
                        onSendClick = { sendUserMessage(inputText) },
                        onVoiceClick = { triggerVoiceInput() }
                    )
                }
            }
        } else {
            // STANDARD FULL-SCREEN PORTRAIT STREAM
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                MessageStreamList(
                    messages = messages,
                    isThinking = isThinking,
                    listState = listState,
                    onCitationClick = { selectedCitation = it }
                )
            }
        }
    }

    // Collapsible Bottom Sheets & Modals
    if (showSourcesSheet) {
        CollapsibleSourcesSheet(
            projectTitle = currentProject?.title ?: "Project",
            sources = sources,
            onToggleSource = { repository.toggleSourceSelection(it) },
            onAddSourceClick = {
                filePickerLauncher.launch(arrayOf("application/pdf", "text/plain"))
            },
            onDismiss = { showSourcesSheet = false }
        )
    }

    if (showQuizSheet) {
        activeQuiz?.let { quiz ->
            CollapsibleQuizSheet(
                quiz = quiz,
                onDismiss = { showQuizSheet = false }
            )
        }
    }

    if (showAudioSheet) {
        activeAudio?.let { audio ->
            AudioBriefingSheet(
                briefing = audio,
                onDismiss = { showAudioSheet = false }
            )
        }
    }

    if (showCheatSheet) {
        activeCheatSheet?.let { sheet ->
            CollapsibleCheatSheet(
                cheatSheet = sheet,
                onDismiss = { showCheatSheet = false }
            )
        }
    }

    if (showSPenSheet) {
        SPenFormulaSheet(
            courseTitle = currentProject?.title ?: "Course",
            onInsertFormula = { query -> sendUserMessage(query) },
            onDismiss = { showSPenSheet = false },
            aiService = aiService
        )
    }

    // Interactive Source Citation Inspector Modal
    selectedCitation?.let { citation ->
        val matchedSource = sources.find { it.id == citation.sourceId || it.title.equals(citation.sourceTitle, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { selectedCitation = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = citation.sourceTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Verified Source Citation • Page / Slide ${citation.pageNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "\"${citation.quotedSnippet}\"",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    if (matchedSource != null) {
                        Text(
                            text = "Full Source Context (${matchedSource.pageCount} pages):",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = matchedSource.textContent.take(360) + if (matchedSource.textContent.length > 360) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedCitation = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Gemini / Vertex AI ADC Configuration Dialog
    if (showApiSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showApiSettingsDialog = false },
            title = { Text("Vertex AI & Gemini Configuration", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (aiService.hasLiveCredentials()) {
                        Surface(
                            color = Color(0xFF10B981).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "✓ Vertex AI / Gemini Active (Project: ${aiService.projectId})",
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = Color(0xFF10B981),
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                    Text(
                        text = "Judges: No API key is required to evaluate! When left blank, Cortex runs its on-device grounded RAG engine to generate instant quizzes, audio briefings, and LaTeX citations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Model:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "gemini-3.8-flash",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Text(
                        text = "Reasoning Depth:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedReasoningMode == ReasoningMode.LOW,
                            onClick = { selectedReasoningMode = ReasoningMode.LOW },
                            label = { Text("⚡ Low Reasoning (Fast)") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedReasoningMode == ReasoningMode.EXTENDED,
                            onClick = { selectedReasoningMode = ReasoningMode.EXTENDED },
                            label = { Text("🧠 Extended Reasoning") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("Gemini API Key or Bearer Token (Optional)") },
                        placeholder = { Text("Leave blank for on-device demo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = endpointInput,
                        onValueChange = { endpointInput = it },
                        label = { Text("Custom Vertex Endpoint (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmedEndpoint = endpointInput.trim()
                        if (trimmedEndpoint.isNotBlank() && !aiService.isValidEndpoint(trimmedEndpoint)) {
                            Toast.makeText(context, "Invalid endpoint domain: must be HTTPS Google API domain (*.aiplatform.googleapis.com or generativelanguage.googleapis.com)", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        aiService.apiKey = apiKeyInput.trim()
                        aiService.projectEndpoint = trimmedEndpoint
                        aiService.modelName = "gemini-3.8-flash"
                        aiService.reasoningMode = selectedReasoningMode
                        prefs.edit()
                            .putString("gemini_api_key", aiService.apiKey)
                            .putString("vertex_endpoint", aiService.projectEndpoint)
                            .putString("gemini_reasoning_mode", selectedReasoningMode.name)
                            .apply()
                        showApiSettingsDialog = false
                        val modeLabel = if (selectedReasoningMode == ReasoningMode.EXTENDED) "Extended Reasoning" else "Low Reasoning"
                        Toast.makeText(context, "Gemini 3.8 Flash configured ($modeLabel)!", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FlexDeckButton(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ChatInputRow(
    inputText: String,
    onInputChange: (String) -> Unit,
    onUploadClick: () -> Unit,
    onSendClick: () -> Unit,
    onVoiceClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Upload button
        IconButton(
            onClick = onUploadClick,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add Source",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Text Input
        TextField(
            value = inputText,
            onValueChange = onInputChange,
            placeholder = { Text("Ask your sources or formulas...", fontSize = 13.sp) },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 46.dp),
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            singleLine = true
        )

        // Send or Mic Button
        if (inputText.isNotBlank()) {
            IconButton(
                onClick = onSendClick,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        } else {
            IconButton(
                onClick = onVoiceClick,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice Input",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun MessageStreamList(
    messages: List<ChatMessage>,
    isThinking: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onCitationClick: (Citation) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            ChatMessageItem(
                message = message,
                onCitationClick = onCitationClick
            )
        }

        if (isThinking) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Cortex is synthesizing grounded sources...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    onCitationClick: (Citation) -> Unit
) {
    val isUser = message.sender == MessageSender.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("C", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 20.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Box(modifier = Modifier.padding(14.dp)) {
                if (isUser) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    MarkdownMathView(
                        text = message.text,
                        citations = message.citations,
                        onCitationClick = onCitationClick
                    )
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Tabletop Flex Controls Preview", showBackground = true, widthDp = 600, heightDp = 300)
@Composable
private fun TabletopFlexControlsPreview() {
    MaterialTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FlexDeckButton(
                title = "🎧 2-Host Audio",
                subtitle = "Alex & Sam Review",
                modifier = Modifier.weight(1f),
                onClick = {}
            )
            FlexDeckButton(
                title = "✍ S-Pen Math",
                subtitle = "Handwrite & LaTeX",
                modifier = Modifier.weight(1f),
                onClick = {}
            )
        }
    }
}
