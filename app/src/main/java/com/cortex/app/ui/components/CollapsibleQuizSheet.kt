package com.cortex.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cortex.app.data.model.QuizArtifact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsibleQuizSheet(
    quiz: QuizArtifact,
    onDismiss: () -> Unit
) {
    var currentQuestionIdx by remember { mutableStateOf(0) }
    var selectedOptionIdx by remember { mutableStateOf<Int?>(null) }
    var score by remember { mutableStateOf(0) }

    val question = quiz.questions.getOrNull(currentQuestionIdx)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "QUIZ ARTIFACT",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    Text(
                        text = quiz.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (question != null) {
                // Progress counter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Question ${currentQuestionIdx + 1} of ${quiz.questions.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "Score: $score / ${currentQuestionIdx + if (selectedOptionIdx != null) 1 else 0}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Question Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = question.question,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Options List
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    question.options.forEachIndexed { optIdx, optionText ->
                        val isSelected = selectedOptionIdx == optIdx
                        val isCorrect = optIdx == question.correctIndex
                        val isAnswered = selectedOptionIdx != null

                        val borderColor = when {
                            isAnswered && isCorrect -> Color(0xFF10B981) // Emerald Green
                            isAnswered && isSelected && !isCorrect -> Color(0xFFEF4444) // Rose Red
                            isSelected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }

                        val bgColor = when {
                            isAnswered && isCorrect -> Color(0xFF10B981).copy(alpha = 0.15f)
                            isAnswered && isSelected && !isCorrect -> Color(0xFFEF4444).copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.surface
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(bgColor)
                                .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
                                .clickable(enabled = !isAnswered) {
                                    selectedOptionIdx = optIdx
                                    if (optIdx == question.correctIndex) score++
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${('A' + optIdx)}. ",
                                    fontWeight = FontWeight.Bold,
                                    color = if (isAnswered && isCorrect) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = optionText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Explanation & Source citation reveal
                AnimatedVisibility(visible = selectedOptionIdx != null) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (selectedOptionIdx == question.correctIndex) "✓ Correct Rationale" else "ℹ️ Explanation",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = question.explanation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                question.sourceCitation?.let { cit ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Source: $cit",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Next Button
                Button(
                    onClick = {
                        if (currentQuestionIdx < quiz.questions.size - 1) {
                            currentQuestionIdx++
                            selectedOptionIdx = null
                        } else {
                            onDismiss()
                        }
                    },
                    enabled = selectedOptionIdx != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (currentQuestionIdx < quiz.questions.size - 1) "Next Question →" else "Finish Quiz")
                }
            }
        }
    }
}
