package com.cortex.app.data.repository

import android.content.Context
import com.cortex.app.data.model.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.core.util.AtomicFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class CortexRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val fileMutex = Mutex()
    private val storageDir: File get() = File(context.filesDir, "cortex_data").apply { mkdirs() }

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _selectedProject = MutableStateFlow<Project?>(null)
    val selectedProject: StateFlow<Project?> = _selectedProject.asStateFlow()

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _activeQuiz = MutableStateFlow<QuizArtifact?>(null)
    val activeQuiz: StateFlow<QuizArtifact?> = _activeQuiz.asStateFlow()

    private val _activeAudioBriefing = MutableStateFlow<AudioBriefingArtifact?>(null)
    val activeAudioBriefing: StateFlow<AudioBriefingArtifact?> = _activeAudioBriefing.asStateFlow()

    private val _activeCheatSheet = MutableStateFlow<CheatSheetArtifact?>(null)
    val activeCheatSheet: StateFlow<CheatSheetArtifact?> = _activeCheatSheet.asStateFlow()

    init {
        ioScope.launch {
            loadOrInitializeData()
        }
    }

    private suspend fun loadOrInitializeData() = withContext(ioDispatcher) {
        fileMutex.withLock {
            val projectsFile = File(storageDir, "projects.json")
            val content = readAtomically(projectsFile)
            if (!content.isNullOrBlank()) {
                try {
                    val list = json.decodeFromString<List<Project>>(content)
                    if (list.isNotEmpty()) {
                        _projects.value = list
                        selectProjectInternalLocked(list.first())
                        return@withContext
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Initialize with default demo project for seamless first run & hackathon judging
            val demoProject = Project(
                id = "bio-101",
                title = "Biology 101",
                description = "Cellular Respiration & Metabolic Pathways",
                colorHex = "#4F46E5"
            )

            val demoSource1 = Source(
                id = "src-1",
                projectId = demoProject.id,
                title = "Lecture_04_Glycolysis.pdf",
                textContent = """
                    Glycolysis is the metabolic pathway that converts glucose (C6H12O6) into pyruvate (CH3COCOO-).
                    The free energy released in this process is used to form the high-energy molecules adenosine triphosphate (ATP)
                    and reduced nicotinamide adenine dinucleotide (NADH).
                    Phase 1: Energy Investment Phase requires 2 ATP. Hexokinase phosphorylates glucose, and Phosphofructokinase-1 (PFK-1)
                    catalyzes the committed step forming fructose-1,6-bisphosphate.
                    Phase 2: Energy Payoff Phase yields 4 ATP and 2 NADH. Net reaction produces 2 ATP, 2 NADH, and 2 Pyruvate.
                """.trimIndent(),
                pageCount = 18,
                isSelected = true
            )

            val demoSource2 = Source(
                id = "src-2",
                projectId = demoProject.id,
                title = "Krebs_Cycle_Summary.txt",
                textContent = """
                    The Citric Acid Cycle (Krebs cycle) takes place in the mitochondrial matrix.
                    Pyruvate dehydrogenase converts pyruvate into Acetyl-CoA, producing 1 NADH and 1 CO2.
                    Oxaloacetate combines with Acetyl-CoA to form Citrate.
                    Each turn of the cycle generates 3 NADH, 1 FADH2, 1 GTP (ATP), and releases 2 CO2.
                """.trimIndent(),
                pageCount = 6,
                isSelected = true
            )

            val welcomeMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                projectId = demoProject.id,
                sender = MessageSender.AI,
                text = """
                    Ready for **Biology 101**. I've indexed your lecture materials on Glycolysis and the Krebs Cycle[p.4].
                    
                    The overall reaction for glycolysis is:
                    $$\text{C}_6\text{H}_{12}\text{O}_6 + 2\text{NAD}^+ + 2\text{ADP} + 2\text{P}_i \longrightarrow 2\text{Pyruvate} + 2\text{NADH} + 2\text{H}^+ + 2\text{ATP}$$
                    
                    Ask anything about the pathway, or tap **Quiz Me**, **Audio Briefing**, or **Cheat Sheet** below!
                """.trimIndent(),
                citations = listOf(
                    Citation("c1", demoSource1.id, "Lecture_04_Glycolysis.pdf", 4, "Glycolysis converts glucose (C6H12O6) into pyruvate (CH3COCOO-) with net yield of 2 ATP and 2 NADH.")
                )
            )

            val initialQuiz = QuizArtifact(
                id = "quiz-1",
                projectId = demoProject.id,
                title = "Cellular Respiration Recall",
                questions = listOf(
                    QuizQuestion(
                        id = "q1",
                        question = "Which enzyme catalyzes the committed, irreversible step of glycolysis that converts fructose-6-phosphate to fructose-1,6-bisphosphate?",
                        options = listOf("Hexokinase", "Phosphofructokinase-1 (PFK-1)", "Pyruvate Kinase", "Aldolase"),
                        correctIndex = 1,
                        explanation = "PFK-1 is the key rate-limiting enzyme regulated allosterically by ATP and AMP.",
                        sourceCitation = "Lecture_04_Glycolysis.pdf • Slide 6"
                    ),
                    QuizQuestion(
                        id = "q2",
                        question = "What is the net gain of ATP molecules produced from one molecule of glucose through glycolysis alone?",
                        options = listOf("1 ATP", "2 ATP", "4 ATP", "36 ATP"),
                        correctIndex = 1,
                        explanation = "4 ATP are produced in the payoff phase, but 2 ATP are consumed in the investment phase, resulting in a net yield of 2 ATP.",
                        sourceCitation = "Lecture_04_Glycolysis.pdf • Slide 9"
                    ),
                    QuizQuestion(
                        id = "q3",
                        question = "Where does the Citric Acid Cycle (Krebs cycle) occur in eukaryotic cells?",
                        options = listOf("Cytosol", "Mitochondrial matrix", "Endoplasmic reticulum", "Golgi apparatus"),
                        correctIndex = 1,
                        explanation = "Unlike glycolysis which occurs in the cytosol, the Krebs cycle enzymes are localized in the mitochondrial matrix.",
                        sourceCitation = "Krebs_Cycle_Summary.txt • Page 1"
                    )
                )
            )

            val initialAudio = AudioBriefingArtifact(
                id = "audio-1",
                projectId = demoProject.id,
                title = "Biology 101 Audio Briefing",
                turns = listOf(
                    DialogueTurn("Host A (Alex)", "Hey everyone, welcome back! Today we are tackling cellular respiration, starting with glycolysis in the cytosol."),
                    DialogueTurn("Host B (Sam)", "Right! The key takeaway is that you invest 2 ATP up front using Hexokinase and PFK-1 to split glucose."),
                    DialogueTurn("Host A (Alex)", "And then in the payoff phase, you generate 4 ATP and 2 NADH, giving a net yield of 2 ATP and 2 pyruvate molecules."),
                    DialogueTurn("Host B (Sam)", "From there, pyruvate enters the mitochondrial matrix as Acetyl-CoA to power the Krebs cycle, producing 3 NADH, 1 FADH2, and 1 GTP per turn!")
                )
            )

            val initialCheatSheet = CheatSheetArtifact(
                id = "sheet-1",
                projectId = demoProject.id,
                title = "Biology 101 High-Yield Exam Cheat Sheet",
                markdownContent = """
                    **1. Glycolysis (Cytosol — Anaerobic)**
                    - **Committed Step:** Catalyzed by **Phosphofructokinase-1 (PFK-1)** converting fructose-6-phosphate to fructose-1,6-bisphosphate[p.4].
                    - **Energy Balance:** Invests $2\text{ ATP}$, produces $4\text{ ATP}$ and $2\text{ NADH}$.
                    $$\text{Net Yield} = 2\text{ Pyruvate} + 2\text{ ATP} + 2\text{ NADH}$$
                    
                    **2. Pyruvate Oxidation & Krebs Cycle (Mitochondrial Matrix)**
                    - **Link Reaction:** Pyruvate dehydrogenase converts Pyruvate into $\text{Acetyl-CoA} + \text{NADH} + \text{CO}_2$[p.1].
                    - **First Condensation:** $\text{Oxaloacetate} + \text{Acetyl-CoA} \longrightarrow \text{Citrate}$.
                    - **Yield per Turn:** $3\text{ NADH} + 1\text{ FADH}_2 + 1\text{ GTP (ATP)} + 2\text{ CO}_2$.
                """.trimIndent()
            )

            _projects.value = listOf(demoProject)
            _selectedProject.value = demoProject
            _sources.value = listOf(demoSource1, demoSource2)
            _messages.value = listOf(welcomeMsg)
            _activeQuiz.value = initialQuiz
            _activeAudioBriefing.value = initialAudio
            _activeCheatSheet.value = initialCheatSheet

            saveProjectsSync(listOf(demoProject))
            saveSourcesSync(demoProject.id, listOf(demoSource1, demoSource2))
            saveMessagesSync(demoProject.id, listOf(welcomeMsg))
            saveQuizSync(demoProject.id, initialQuiz)
            saveAudioSync(demoProject.id, initialAudio)
            saveCheatSheetSync(demoProject.id, initialCheatSheet)
        }
    }

    fun selectProject(project: Project) {
        val isSwitch = _selectedProject.value?.id != project.id
        _selectedProject.value = project
        if (isSwitch) {
            _sources.value = _sources.value.filter { it.projectId == project.id }
            _messages.value = _messages.value.filter { it.projectId == project.id }
            _activeQuiz.value = _activeQuiz.value?.takeIf { it.projectId == project.id }
            _activeAudioBriefing.value = _activeAudioBriefing.value?.takeIf { it.projectId == project.id }
            _activeCheatSheet.value = _activeCheatSheet.value?.takeIf { it.projectId == project.id }
        }
        ioScope.launch {
            fileMutex.withLock {
                selectProjectInternalLocked(project)
            }
        }
    }

    private fun selectProjectInternalLocked(project: Project) {
        _selectedProject.value = project
        _sources.value = loadSourcesSync(project.id)
        _messages.value = loadMessagesSync(project.id)
        _activeQuiz.value = loadQuizSync(project.id)
        _activeAudioBriefing.value = loadAudioSync(project.id)
        _activeCheatSheet.value = loadCheatSheetSync(project.id)
    }

    fun addProject(title: String, description: String = "", colorHex: String = "#6366F1"): Project {
        val newProject = Project(
            id = UUID.randomUUID().toString(),
            title = title,
            description = description,
            colorHex = colorHex
        )
        val updatedProjects = _projects.value + newProject
        _projects.value = updatedProjects
        _selectedProject.value = newProject
        _sources.value = emptyList()

        val welcomeMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            projectId = newProject.id,
            sender = MessageSender.AI,
            text = "Welcome to **$title**${if (description.isNotBlank()) " ($description)" else ""}.\n\nUpload your lecture PDFs or notes using the **+** button or **Sources** pill, or ask any question about **$title** to get started!"
        )
        _messages.value = listOf(welcomeMsg)
        _activeQuiz.value = null
        _activeAudioBriefing.value = null
        _activeCheatSheet.value = null

        ioScope.launch {
            fileMutex.withLock {
                saveProjectsSync(updatedProjects)
                saveSourcesSync(newProject.id, emptyList())
                saveMessagesSync(newProject.id, listOf(welcomeMsg))
            }
        }
        return newProject
    }

    fun addSource(title: String, content: String, pageCount: Int = 1, targetProjectId: String? = null) {
        val projectId = targetProjectId ?: _selectedProject.value?.id ?: return
        val newSource = Source(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            title = title,
            textContent = content,
            pageCount = maxOf(1, pageCount),
            isSelected = true
        )
        if (_selectedProject.value?.id == projectId) {
            val updated = _sources.value.filter { it.projectId == projectId } + newSource
            _sources.value = updated
            ioScope.launch {
                fileMutex.withLock {
                    saveSourcesSync(projectId, updated)
                }
            }
        } else {
            ioScope.launch {
                fileMutex.withLock {
                    val existing = loadSourcesSync(projectId)
                    saveSourcesSync(projectId, existing + newSource)
                }
            }
        }
    }

    fun toggleSourceSelection(sourceId: String) {
        val current = _selectedProject.value ?: return
        val updated = _sources.value
            .filter { it.projectId == current.id }
            .map { if (it.id == sourceId) it.copy(isSelected = !it.isSelected) else it }
        _sources.value = updated
        ioScope.launch {
            fileMutex.withLock {
                saveSourcesSync(current.id, updated)
            }
        }
    }

    fun addMessage(
        sender: MessageSender,
        text: String,
        citations: List<Citation> = emptyList(),
        targetProjectId: String? = null
    ) {
        val projectId = targetProjectId ?: _selectedProject.value?.id ?: return
        val newMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            sender = sender,
            text = text,
            citations = citations
        )
        if (_selectedProject.value?.id == projectId) {
            val updated = _messages.value.filter { it.projectId == projectId } + newMsg
            _messages.value = updated
            ioScope.launch {
                fileMutex.withLock {
                    saveMessagesSync(projectId, updated)
                }
            }
        } else {
            // Project changed while AI was generating response: persist to original project file without cross-contaminating UI state
            ioScope.launch {
                fileMutex.withLock {
                    val existing = loadMessagesSync(projectId)
                    saveMessagesSync(projectId, existing + newMsg)
                }
            }
        }
    }

    fun setQuiz(quiz: QuizArtifact?, targetProjectId: String? = null) {
        val projectId = targetProjectId ?: quiz?.projectId ?: _selectedProject.value?.id ?: return
        if (_selectedProject.value?.id == projectId) {
            _activeQuiz.value = quiz
        }
        ioScope.launch {
            fileMutex.withLock {
                saveQuizSync(projectId, quiz)
            }
        }
    }

    fun setAudioBriefing(audio: AudioBriefingArtifact?, targetProjectId: String? = null) {
        val projectId = targetProjectId ?: audio?.projectId ?: _selectedProject.value?.id ?: return
        if (_selectedProject.value?.id == projectId) {
            _activeAudioBriefing.value = audio
        }
        ioScope.launch {
            fileMutex.withLock {
                saveAudioSync(projectId, audio)
            }
        }
    }

    fun setCheatSheet(sheet: CheatSheetArtifact?, targetProjectId: String? = null) {
        val projectId = targetProjectId ?: sheet?.projectId ?: _selectedProject.value?.id ?: return
        if (_selectedProject.value?.id == projectId) {
            _activeCheatSheet.value = sheet
        }
        ioScope.launch {
            fileMutex.withLock {
                saveCheatSheetSync(projectId, sheet)
            }
        }
    }

    fun getActiveSourcesText(): String {
        return _sources.value
            .filter { it.isSelected }
            .joinToString("\n\n---\n\n") { "[Source: ${it.title} | ID: ${it.id} | Pages: ${it.pageCount}]\n${it.textContent}" }
    }

    fun getConversationContext(maxMessages: Int = 6): String {
        return _messages.value
            .takeLast(maxMessages)
            .joinToString("\n") { msg ->
                val role = if (msg.sender == MessageSender.USER) "STUDENT" else "CORTEX"
                "$role: ${msg.text.take(400)}"
            }
    }

    private fun writeAtomically(file: File, content: String) {
        val atomicFile = AtomicFile(file)
        var fos: FileOutputStream? = null
        try {
            fos = atomicFile.startWrite()
            fos.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(fos)
        } catch (e: Exception) {
            if (fos != null) {
                atomicFile.failWrite(fos)
            }
            throw e
        }
    }

    private fun readAtomically(file: File): String? {
        val backup = File(file.path + ".bak")
        if (!file.exists() && !backup.exists()) return null
        return try {
            val atomicFile = AtomicFile(file)
            atomicFile.readFully().toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveProjectsSync(list: List<Project>) {
        try {
            writeAtomically(File(storageDir, "projects.json"), json.encodeToString(list))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadSourcesSync(projectId: String): List<Source> {
        val file = File(storageDir, "sources_$projectId.json")
        val content = readAtomically(file) ?: return emptyList()
        return try {
            json.decodeFromString(content)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSourcesSync(projectId: String, list: List<Source>) {
        try {
            writeAtomically(File(storageDir, "sources_$projectId.json"), json.encodeToString(list))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadMessagesSync(projectId: String): List<ChatMessage> {
        val file = File(storageDir, "messages_$projectId.json")
        val content = readAtomically(file) ?: return emptyList()
        return try {
            json.decodeFromString(content)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveMessagesSync(projectId: String, list: List<ChatMessage>) {
        try {
            writeAtomically(File(storageDir, "messages_$projectId.json"), json.encodeToString(list))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadQuizSync(projectId: String): QuizArtifact? {
        val file = File(storageDir, "quiz_$projectId.json")
        val content = readAtomically(file) ?: return null
        return try {
            json.decodeFromString(content)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveQuizSync(projectId: String, quiz: QuizArtifact?) {
        try {
            val file = File(storageDir, "quiz_$projectId.json")
            val atomicFile = AtomicFile(file)
            if (quiz == null) {
                atomicFile.delete()
            } else {
                writeAtomically(file, json.encodeToString(quiz))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadAudioSync(projectId: String): AudioBriefingArtifact? {
        val file = File(storageDir, "audio_$projectId.json")
        val content = readAtomically(file) ?: return null
        return try {
            json.decodeFromString(content)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveAudioSync(projectId: String, audio: AudioBriefingArtifact?) {
        try {
            val file = File(storageDir, "audio_$projectId.json")
            val atomicFile = AtomicFile(file)
            if (audio == null) {
                atomicFile.delete()
            } else {
                writeAtomically(file, json.encodeToString(audio))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadCheatSheetSync(projectId: String): CheatSheetArtifact? {
        val file = File(storageDir, "cheatsheet_$projectId.json")
        val content = readAtomically(file) ?: return null
        return try {
            json.decodeFromString(content)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCheatSheetSync(projectId: String, sheet: CheatSheetArtifact?) {
        try {
            val file = File(storageDir, "cheatsheet_$projectId.json")
            val atomicFile = AtomicFile(file)
            if (sheet == null) {
                atomicFile.delete()
            } else {
                writeAtomically(file, json.encodeToString(sheet))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
