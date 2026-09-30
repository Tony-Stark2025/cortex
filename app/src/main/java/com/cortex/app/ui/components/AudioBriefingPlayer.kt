package com.cortex.app.ui.components

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cortex.app.data.model.AudioBriefingArtifact
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioBriefingSheet(
    briefing: AudioBriefingArtifact,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var isPlaying by remember { mutableStateOf(false) }
    var currentTurnIdx by remember { mutableIntStateOf(0) }
    var isTtsReady by remember { mutableStateOf(false) }
    var pendingTurnIdx by remember { mutableStateOf<Int?>(null) }
    var ttsInstance: TextToSpeech? by remember { mutableStateOf(null) }

    fun speakTurnAt(engine: TextToSpeech?, index: Int) {
        val turn = briefing.turns.getOrNull(index) ?: run {
            isPlaying = false
            return
        }
        if (engine == null || !isTtsReady) {
            pendingTurnIdx = index
            return
        }

        val isSam = turn.speaker.contains("Sam", ignoreCase = true) ||
            turn.speaker.contains("Host B", ignoreCase = true)

        // Differentiate voices between Host A (Alex) and Host B (Sam)
        try {
            val usVoices = engine.voices
                ?.filter { it.locale == Locale.US && !it.isNetworkConnectionRequired }
                ?.sortedBy { it.name }
                .orEmpty()
            if (usVoices.size >= 2) {
                engine.voice = if (isSam) usVoices[1] else usVoices[0]
            }
        } catch (_: Exception) {
        }

        engine.setPitch(if (isSam) 1.15f else 0.92f)
        engine.setSpeechRate(if (isSam) 1.05f else 1.0f)
        val result = engine.speak(turn.text, TextToSpeech.QUEUE_FLUSH, null, "turn_$index")
        if (result == TextToSpeech.ERROR) {
            isPlaying = false
        }
    }

    DisposableEffect(briefing.id) {
        var localTts: TextToSpeech? = null
        localTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                localTts?.language = Locale.US
                localTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post { isPlaying = true }
                    }

                    override fun onDone(utteranceId: String?) {
                        val completedIdx = utteranceId?.removePrefix("turn_")?.toIntOrNull() ?: currentTurnIdx
                        mainHandler.post {
                            val nextIdx = completedIdx + 1
                            if (nextIdx < briefing.turns.size) {
                                currentTurnIdx = nextIdx
                                speakTurnAt(localTts, nextIdx)
                            } else {
                                isPlaying = false
                                currentTurnIdx = 0
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { isPlaying = false }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post { isPlaying = false }
                    }
                })
                mainHandler.post {
                    isTtsReady = true
                    val queuedIdx = pendingTurnIdx
                    if (queuedIdx != null && isPlaying) {
                        pendingTurnIdx = null
                        speakTurnAt(localTts, queuedIdx)
                    }
                }
            }
        }
        ttsInstance = localTts

        onDispose {
            localTts?.stop()
            localTts?.shutdown()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            ttsInstance?.stop()
            isPlaying = false
            pendingTurnIdx = null
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🎧 Audio Briefing",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                IconButton(onClick = {
                    ttsInstance?.stop()
                    isPlaying = false
                    pendingTurnIdx = null
                    onDismiss()
                }) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = briefing.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (!isTtsReady && isPlaying) "Initializing TTS Voice Engine..."
                else "2-Host Study Conversation • Turn ${currentTurnIdx + 1} of ${maxOf(1, briefing.turns.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Animated Soundwave Visualizer
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(44.dp)
            ) {
                val waveBars = 12
                val infiniteTransition = rememberInfiniteTransition(label = "AudioWaveform")
                for (i in 0 until waveBars) {
                    val animatedRatio by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 320 + (i * 40), easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "Bar_$i"
                    )
                    val effectiveRatio = if (isPlaying && isTtsReady) animatedRatio else 0.2f
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height((44 * effectiveRatio).dp)
                            .clip(CircleShape)
                            .background(
                                if (currentTurnIdx % 2 == 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Multi-Turn Interactive Dialogue Transcript List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(briefing.turns) { idx, turn ->
                    val isActiveTurn = idx == currentTurnIdx
                    val isSam = turn.speaker.contains("Sam", ignoreCase = true) ||
                        turn.speaker.contains("Host B", ignoreCase = true)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                width = if (isActiveTurn) 1.5.dp else 0.dp,
                                color = if (isActiveTurn) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                currentTurnIdx = idx
                                isPlaying = true
                                speakTurnAt(ttsInstance, idx)
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActiveTurn)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = turn.speaker,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isSam) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (isSam) "Voice B (1.15x)" else "Voice A (0.92x)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = turn.text,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Transport Controls: Previous Turn, Play/Stop, Next Turn
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                IconButton(
                    onClick = {
                        val prevIdx = maxOf(0, currentTurnIdx - 1)
                        currentTurnIdx = prevIdx
                        if (isPlaying) {
                            speakTurnAt(ttsInstance, prevIdx)
                        }
                    },
                    enabled = currentTurnIdx > 0,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(imageVector = Icons.Default.SkipPrevious, contentDescription = "Previous Turn")
                }

                IconButton(
                    onClick = {
                        if (isPlaying) {
                            ttsInstance?.stop()
                            isPlaying = false
                            pendingTurnIdx = null
                        } else if (briefing.turns.isNotEmpty()) {
                            isPlaying = true
                            speakTurnAt(ttsInstance, currentTurnIdx)
                        }
                    },
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Stop" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(
                    onClick = {
                        val nextIdx = minOf(maxOf(0, briefing.turns.size - 1), currentTurnIdx + 1)
                        currentTurnIdx = nextIdx
                        if (isPlaying) {
                            speakTurnAt(ttsInstance, nextIdx)
                        }
                    },
                    enabled = currentTurnIdx < briefing.turns.size - 1,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Next Turn")
                }
            }
        }
    }
}
