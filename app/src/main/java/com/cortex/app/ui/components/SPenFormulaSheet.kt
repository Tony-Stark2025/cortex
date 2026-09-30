package com.cortex.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cortex.app.data.remote.StrokeFeatureSummary
import com.cortex.app.data.remote.VertexAiService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SPenFormulaSheet(
    courseTitle: String,
    onInsertFormula: (String) -> Unit,
    onDismiss: () -> Unit,
    aiService: VertexAiService = remember { VertexAiService() }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var isRecognizing by remember { mutableStateOf(false) }
    var recognitionJob by remember { mutableStateOf<Job?>(null) }

    val presetFormulas = remember(courseTitle) {
        listOf(
            "Glycolysis" to "\\text{C}_6\\text{H}_{12}\\text{O}_6 + 2\\text{NAD}^+ + 2\\text{ADP} + 2\\text{P}_i \\longrightarrow 2\\text{Pyruvate} + 2\\text{ATP}",
            "Gibbs Free Energy" to "\\Delta G = \\Delta H - T\\Delta S",
            "Quadratic" to "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}",
            "Calculus FTC" to "\\int_{a}^{b} f'(x)\\,dx = f(b) - f(a)"
        )
    }

    var recognizedLatex by remember {
        mutableStateOf(presetFormulas.first().second)
    }

    fun triggerInkRecognition(allStrokes: List<List<Offset>>) {
        if (allStrokes.isEmpty()) return
        recognitionJob?.cancel()
        recognitionJob = coroutineScope.launch {
            // Short debounce so multi-stroke characters settle before Vision OCR runs
            delay(350)
            isRecognizing = true
            try {
                val summary = extractStrokeFeatureSummary(allStrokes)
                val pngBase64 = renderStrokesToBase64Png(allStrokes)
                recognizedLatex = aiService.recognizeHandwrittenFormula(
                    imagePngBase64 = pngBase64,
                    strokeSummary = summary,
                    courseTitle = courseTitle
                )
            } finally {
                isRecognizing = false
            }
        }
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "✍️ S-Pen Formula & LaTeX Studio",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        if (isRecognizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = if (isRecognizing) "Recognizing handwritten ink via Vertex AI Vision..."
                        else "Sketch with S-Pen or finger to recognize & export LaTeX",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRecognizing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interactive S-Pen Ink Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                currentStroke = listOf(offset)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                currentStroke = currentStroke + change.position
                            },
                            onDragEnd = {
                                if (currentStroke.isNotEmpty()) {
                                    strokes.add(currentStroke)
                                    currentStroke = emptyList()
                                    triggerInkRecognition(strokes.toList())
                                }
                            }
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val allPaths = strokes + if (currentStroke.isNotEmpty()) listOf(currentStroke) else emptyList()
                    allPaths.forEach { points ->
                        if (points.size > 1) {
                            val path = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                for (i in 1 until points.size) {
                                    lineTo(points[i].x, points[i].y)
                                }
                            }
                            drawPath(
                                path = path,
                                color = Color(0xFF6366F1),
                                style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                }

                if (strokes.isEmpty() && currentStroke.isEmpty()) {
                    Text(
                        text = "Write formula here with S-Pen...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                Row(modifier = Modifier.align(Alignment.TopEnd)) {
                    if (strokes.isNotEmpty()) {
                        IconButton(
                            onClick = { triggerInkRecognition(strokes.toList()) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Recognize Ink Now",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            recognitionJob?.cancel()
                            isRecognizing = false
                            strokes.clear()
                            currentStroke = emptyList()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Clear Ink")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Formula Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presetFormulas.forEach { (label, latex) ->
                    SuggestionChip(
                        onClick = { recognizedLatex = latex },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = recognizedLatex,
                onValueChange = { recognizedLatex = it },
                label = { Text("Recognized LaTeX Expression") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Live Rendered Preview
            MarkdownMathView(text = "$$$recognizedLatex$$")

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("LaTeX Formula", "$$$recognizedLatex$$"))
                        Toast.makeText(context, "LaTeX copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy LaTeX")
                }

                Button(
                    onClick = {
                        onInsertFormula("Explain this formula: $$$recognizedLatex$$")
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ask in Chat")
                }
            }
        }
    }
}

internal fun extractStrokeFeatureSummary(strokes: List<List<Offset>>): StrokeFeatureSummary {
    val allPoints = strokes.flatten()
    if (allPoints.isEmpty()) {
        return StrokeFeatureSummary(0, 0, 0f, 0f, false, false, false, false, false)
    }

    val minX = allPoints.minOf { it.x }
    val maxX = allPoints.maxOf { it.x }
    val minY = allPoints.minOf { it.y }
    val maxY = allPoints.maxOf { it.y }
    val totalW = maxOf(1f, maxX - minX)
    val totalH = maxOf(1f, maxY - minY)

    var wideHorizontalCount = 0
    var hasTallVerticalCurve = false
    var hasRadicalHook = false
    var hasRightArrowTip = false

    for (stroke in strokes) {
        if (stroke.size < 2) continue
        val sMinX = stroke.minOf { it.x }
        val sMaxX = stroke.maxOf { it.x }
        val sMinY = stroke.minOf { it.y }
        val sMaxY = stroke.maxOf { it.y }
        val w = sMaxX - sMinX
        val h = sMaxY - sMinY

        if (w > 70f && h < 18f) {
            wideHorizontalCount++
        }
        if (h > 65f && w < h * 0.48f) {
            hasTallVerticalCurve = true
        }
        // Radical √ hook: starts mid-Y, dips down, rises sharply, then extends horizontally right
        if (stroke.size >= 5 && w > 55f && h > 25f) {
            val start = stroke.first()
            val end = stroke.last()
            val lowestIdx = stroke.indices.maxByOrNull { stroke[it].y } ?: 0
            if (lowestIdx in 1 until (stroke.size - 2) && end.x > start.x + 40f && end.y < stroke[lowestIdx].y - 18f) {
                hasRadicalHook = true
            }
        }
        // Right arrow tip `>` at end of reaction arrow
        if (stroke.size >= 3 && w in 12f..50f && h in 12f..50f) {
            val first = stroke.first()
            val mid = stroke[stroke.size / 2]
            val last = stroke.last()
            if (mid.x > first.x + 6f && mid.x > last.x + 6f && abs(first.y - last.y) > 10f) {
                hasRightArrowTip = true
            }
        }
    }

    return StrokeFeatureSummary(
        strokeCount = strokes.size,
        totalPoints = allPoints.size,
        boundingWidth = totalW,
        boundingHeight = totalH,
        hasWideHorizontalBar = wideHorizontalCount >= 1 && strokes.any { (it.maxOf { p -> p.x } - it.minOf { p -> p.x }) > 110f },
        hasTallVerticalCurve = hasTallVerticalCurve,
        hasRadicalHook = hasRadicalHook,
        hasParallelEquals = wideHorizontalCount >= 2,
        hasRightArrowTip = hasRightArrowTip
    )
}

private fun renderStrokesToBase64Png(strokes: List<List<Offset>>): String {
    return try {
        val width = 640
        val height = 260
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            isAntiAlias = true
        }

        for (stroke in strokes) {
            if (stroke.size > 1) {
                val path = android.graphics.Path()
                path.moveTo(stroke.first().x, stroke.first().y)
                for (i in 1 until stroke.size) {
                    path.lineTo(stroke[i].x, stroke[i].y)
                }
                canvas.drawPath(path, paint)
            }
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
        ""
    }
}
