package com.cortex.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cortex.app.data.model.Citation

internal data class InlineSegment(
    val text: String,
    val isMath: Boolean
)

@Composable
fun MarkdownMathView(
    text: String,
    citations: List<Citation> = emptyList(),
    modifier: Modifier = Modifier,
    onCitationClick: ((Citation) -> Unit)? = null
) {
    // Splits text by double dollar signs $$ for display math blocks
    val segments = text.split("$$")
    val uniqueCitations = citations.distinctBy { "${it.sourceTitle}:${it.pageNumber}" }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEachIndexed { index, segment ->
            if (index % 2 == 1) {
                // Display Block Math (centered formula card)
                DisplayMathBlock(formula = segment.trim())
            } else {
                // Regular prose containing possible inline math
                if (segment.isNotBlank()) {
                    ProseSegment(prose = segment.trim())
                }
            }
        }

        // Render deduplicated citation badges ONCE per message at the bottom with horizontal scroll safety
        if (uniqueCitations.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                uniqueCitations.forEach { citation ->
                    SubtleCitationBadge(
                        citation = citation,
                        onClick = { onCitationClick?.invoke(citation) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplayMathBlock(formula: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = formula.cleanLatexForDisplay(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun ProseSegment(prose: String) {
    val mathPrimaryColor = MaterialTheme.colorScheme.primary
    val annotatedText = buildAnnotatedString {
        val lines = prose.split("\n")
        lines.forEachIndexed { lineIdx, line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("### ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp)) {
                        formatInlineMarkdown(trimmed.removePrefix("### ").trim(), mathPrimaryColor)
                    }
                }
                trimmed.startsWith("## ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)) {
                        formatInlineMarkdown(trimmed.removePrefix("## ").trim(), mathPrimaryColor)
                    }
                }
                trimmed.startsWith("# ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) {
                        formatInlineMarkdown(trimmed.removePrefix("# ").trim(), mathPrimaryColor)
                    }
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    append(" • ")
                    formatInlineMarkdown(trimmed.substring(2), mathPrimaryColor)
                }
                else -> {
                    formatInlineMarkdown(line, mathPrimaryColor)
                }
            }
            if (lineIdx < lines.size - 1) append("\n")
        }
    }

    Text(
        text = annotatedText,
        style = MaterialTheme.typography.bodyMedium.copy(
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
fun SubtleCitationBadge(
    citation: Citation,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = "p.${citation.pageNumber} • ${citation.sourceTitle.substringBeforeLast(".").take(14)}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        )
    }
}

private fun AnnotatedString.Builder.formatInlineMarkdown(input: String, mathColor: Color) {
    // Process **bold** while also supporting inline math inside bold spans
    val parts = input.split("**")
    parts.forEachIndexed { i, part ->
        val isBold = i % 2 == 1 && i < parts.size - 1 || (i % 2 == 1 && input.endsWith("**"))
        val inlineSegments = tokenizeInlineMath(part)
        inlineSegments.forEach { seg ->
            if (seg.isMath) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.SemiBold,
                        color = mathColor
                    )
                ) {
                    append(seg.text.cleanLatexForDisplay())
                }
            } else if (isBold) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(seg.text)
                }
            } else {
                appendWithItalics(seg.text)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendWithItalics(text: String) {
    if (!text.contains('*')) {
        append(text)
        return
    }
    val italicParts = text.split("*")
    italicParts.forEachIndexed { idx, sub ->
        val isItalic = idx % 2 == 1 && idx < italicParts.size - 1 && sub.isNotBlank()
        if (isItalic) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(sub)
            }
        } else {
            append(sub)
        }
    }
}

/**
 * Tokenizes inline `$...$` math expressions while avoiding false positives on currency amounts
 * such as "The price rose from $10 to $25", "Price is $10-$25", or "Costs $25 and $x = 10$".
 */
internal fun tokenizeInlineMath(input: String): List<InlineSegment> {
    if (!input.contains('$')) return listOf(InlineSegment(input, isMath = false))

    val result = mutableListOf<InlineSegment>()
    var textStart = 0
    var openIdx = input.indexOf('$')

    while (openIdx != -1 && openIdx < input.length - 1) {
        val closeIdx = input.indexOf('$', openIdx + 1)
        if (closeIdx == -1) break

        val candidate = input.substring(openIdx + 1, closeIdx)
        val charAfterClose = if (closeIdx + 1 < input.length) input[closeIdx + 1] else null
        // If the closing '$' is immediately followed by a digit (e.g., "$10-$25" or "$10 to $25"),
        // that second '$' is actually the opening currency symbol of "$25", not a closing math delimiter.
        val closeIsCurrencyStart = charAfterClose != null && charAfterClose.isDigit()

        if (!closeIsCurrencyStart && isValidInlineMath(candidate)) {
            if (openIdx > textStart) {
                result.add(InlineSegment(input.substring(textStart, openIdx), isMath = false))
            }
            result.add(InlineSegment(candidate, isMath = true))
            textStart = closeIdx + 1
            openIdx = input.indexOf('$', textStart)
        } else {
            // Do not consume closeIdx; allow closeIdx to be tested as the next opening '$'
            openIdx = closeIdx
        }
    }

    if (textStart < input.length) {
        result.add(InlineSegment(input.substring(textStart), isMath = false))
    }

    return if (result.isEmpty()) listOf(InlineSegment(input, isMath = false)) else result
}

internal fun isValidInlineMath(candidate: String): Boolean {
    if (candidate.isEmpty() || candidate.contains('\n')) return false
    // Inline LaTeX cannot begin or end with whitespace (e.g., "$10 to $25" has candidate "10 to " ending with space)
    if (candidate.first().isWhitespace() || candidate.last().isWhitespace()) return false
    // Currency collision check: if it starts with a number followed by space and prose words without math operators
    val looksLikeCurrencySpan = Regex("""^\d+(?:[.,]\d+)?\s+[a-zA-Z]+""").containsMatchIn(candidate) &&
        !candidate.any { it in charArrayOf('\\', '^', '_', '=', '+', '-', '*', '/') }
    return !looksLikeCurrencySpan
}

internal fun String.cleanLatexForDisplay(): String {
    var s = this

    // 1. Unwrap \text{...}, \mathbf{...}, \mathrm{...}, \textit{...}
    val textWrapRegex = Regex("""\\(?:text|mathbf|mathrm|textit|mathit)\{([^{}]*)\}""")
    repeat(4) {
        s = textWrapRegex.replace(s) { it.groupValues[1] }
    }

    // 2. Iteratively resolve inner-most \sqrt{expr} and \frac{num}{den} so nested expressions like \sqrt{\frac{a}{b}} work cleanly
    val sqrtRegex = Regex("""\\sqrt\s*\{([^{}]+)\}""")
    val fracRegex = Regex("""\\frac\s*\{([^{}]+)\}\s*\{([^{}]+)\}""")
    repeat(4) {
        s = sqrtRegex.replace(s) { "√(${it.groupValues[1].trim()})" }
        s = fracRegex.replace(s) { m ->
            val num = m.groupValues[1].trim()
            val den = m.groupValues[2].trim()
            val fNum = if (num.length > 1 && num.any { it in "+- " }) "($num)" else num
            val fDen = if (den.length > 1 && den.any { it in "+- " }) "($den)" else den
            "$fNum/$fDen"
        }
    }

    // 3. Convert braced subscripts _{...} and single-char subscripts _x
    val subBracedRegex = Regex("""_\{([^{}]+)\}""")
    s = subBracedRegex.replace(s) { toSubscriptString(it.groupValues[1]) }
    val subSingleRegex = Regex("""_([0-9a-zA-Z])""")
    s = subSingleRegex.replace(s) { toSubscriptString(it.groupValues[1]) }

    // 4. Convert braced superscripts ^{...} and single-char superscripts ^x
    val supBracedRegex = Regex("""\^\{([^{}]+)\}""")
    s = supBracedRegex.replace(s) { toSuperscriptString(it.groupValues[1]) }
    val supSingleRegex = Regex("""\^([0-9a-zA-Z+\-])""")
    s = supSingleRegex.replace(s) { toSuperscriptString(it.groupValues[1]) }

    // 5. Replace standard LaTeX operators and Greek symbols
    s = s
        .replace("\\left", "")
        .replace("\\right", "")
        .replace("\\longrightarrow", " ⟶ ")
        .replace("\\rightarrow", " → ")
        .replace("\\leftarrow", " ← ")
        .replace("\\iff", " ⟺ ")
        .replace("\\implies", " ⟹ ")
        .replace("\\pm", " ± ")
        .replace("\\times", " × ")
        .replace("\\cdot", " · ")
        .replace("\\leq", " ≤ ")
        .replace("\\geq", " ≥ ")
        .replace("\\neq", " ≠ ")
        .replace("\\approx", " ≈ ")
        .replace("\\infty", "∞")
        .replace("\\int", "∫")
        .replace("\\sum", "∑")
        .replace("\\prod", "∏")
        .replace("\\Delta", "Δ")
        .replace("\\alpha", "α")
        .replace("\\beta", "β")
        .replace("\\gamma", "γ")
        .replace("\\lambda", "λ")
        .replace("\\pi", "π")
        .replace("\\theta", "θ")
        .replace("\\mid", " | ")
        .replace("\\,", " ")
        .replace("{", "")
        .replace("}", "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    return s
}

private fun toSubscriptString(input: String): String {
    val map = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
        'v' to 'ᵥ', 'x' to 'ₓ'
    )
    if (input.all { it in map }) {
        return input.map { map[it]!! }.joinToString("")
    }
    return "_($input)"
}

private fun toSuperscriptString(input: String): String {
    val map = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
        'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
        'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
        'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
        'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
        'T' to 'ᵀ'
    )
    if (input.all { it in map }) {
        return input.map { map[it]!! }.joinToString("")
    }
    return "^($input)"
}
