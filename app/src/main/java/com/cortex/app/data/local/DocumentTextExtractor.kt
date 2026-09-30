package com.cortex.app.data.local

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

data class ExtractedDocument(
    val fileName: String,
    val textContent: String,
    val pageCount: Int,
    val isPdf: Boolean
)

object DocumentTextExtractor {

    private val PDF_MAGIC = "%PDF-".toByteArray(StandardCharsets.US_ASCII)
    private const val MAX_FILE_READ_BYTES = 32 * 1024 * 1024 // 32 MB safety bound

    suspend fun extractFromUri(context: Context, uri: Uri): ExtractedDocument = withContext(Dispatchers.IO) {
        val fileName = resolveFileName(context, uri)
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (total < MAX_FILE_READ_BYTES) {
                val read = stream.read(chunk, 0, minOf(chunk.size, MAX_FILE_READ_BYTES - total))
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                total += read
            }
            buffer.toByteArray()
        } ?: ByteArray(0)

        val isPdf = mimeType.equals("application/pdf", ignoreCase = true) ||
            fileName.endsWith(".pdf", ignoreCase = true) ||
            hasPdfMagicHeader(rawBytes)

        if (isPdf) {
            val nativePageCount = tryGetPdfRendererPageCount(context, uri)
            val (extractedText, estimatedPages) = extractTextFromPdfBytes(rawBytes, fileName)
            val finalPageCount = nativePageCount ?: estimatedPages
            ExtractedDocument(
                fileName = fileName,
                textContent = extractedText,
                pageCount = maxOf(1, finalPageCount),
                isPdf = true
            )
        } else {
            val cleanText = extractPlainTextFromBytes(rawBytes)
            val estimatedPages = maxOf(1, (cleanText.length + 1499) / 1500)
            ExtractedDocument(
                fileName = fileName,
                textContent = cleanText,
                pageCount = estimatedPages,
                isPdf = false
            )
        }
    }

    fun hasPdfMagicHeader(bytes: ByteArray): Boolean {
        if (bytes.size < PDF_MAGIC.size) return false
        for (i in PDF_MAGIC.indices) {
            if (bytes[i] != PDF_MAGIC[i]) return false
        }
        return true
    }

    fun extractPlainTextFromBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val decoded = String(bytes, StandardCharsets.UTF_8)
        return decoded
            .replace("\uFFFD", "")
            .filter { ch -> ch == '\n' || ch == '\r' || ch == '\t' || ch.code in 32..0xD7FF || ch.code in 0xE000..0xFFFD }
            .trim()
    }

    /**
     * Pure-Kotlin PDF stream and text-object parser (`BT`..`ET`, `Tj`, `TJ`, `/ToUnicode` CMaps, and `FlateDecode` streams).
     * Preserves page-level provenance without dumping raw binary PDF headers into prompt context.
     */
    fun extractTextFromPdfBytes(bytes: ByteArray, fallbackTitle: String = "Document.pdf"): Pair<String, Int> {
        if (bytes.isEmpty()) {
            return Pair("[Empty PDF document: $fallbackTitle]", 1)
        }

        val asciiLatin = String(bytes, StandardCharsets.ISO_8859_1)

        // Count "/Type /Page" (excluding "/Type /Pages") to estimate page count
        val pageMarkerRegex = Regex("""/Type\s*/Page\b(?!s)""")
        val detectedPages = maxOf(1, pageMarkerRegex.findAll(asciiLatin).count())

        val decodedStreams = mutableListOf<String>()
        // 1. Collect uncompressed text and decompress FlateDecode streams
        val streamRegex = Regex("""stream\r?\n([\s\S]*?)\r?\nendstream""")
        for (match in streamRegex.findAll(asciiLatin)) {
            val rawStream = match.groupValues[1]
            val streamBytes = rawStream.toByteArray(StandardCharsets.ISO_8859_1)
            val decompressed = tryInflateStream(streamBytes)
            if (decompressed != null) {
                decodedStreams.add(decompressed)
            } else {
                decodedStreams.add(rawStream)
            }
        }

        val combinedStreams = decodedStreams.joinToString("\n")
        val targetCorpus = if (combinedStreams.isNotEmpty()) {
            "$combinedStreams\n$asciiLatin"
        } else {
            asciiLatin
        }

        // Parse any /ToUnicode CMap tables (beginbfchar / beginbfrange) for CIDFont decoding
        val toUnicodeMap = parseToUnicodeCMap(targetCorpus)

        val extractedChunks = mutableListOf<String>()

        // 2. Parse BT ... ET text blocks with balanced-parenthesis, TJ kerning, and hex-string (<...>) support
        val btEtRegex = Regex("""BT\b([\s\S]*?)\bET""")

        for (block in btEtRegex.findAll(targetCorpus)) {
            val blockContent = block.groupValues[1]
            val lineBuilder = StringBuilder()
            for (decoded in extractPdfStringsFromBlock(blockContent, toUnicodeMap)) {
                if (decoded.isNotBlank()) {
                    lineBuilder.append(decoded)
                    if (!decoded.endsWith(" ")) lineBuilder.append(" ")
                }
            }
            val cleanedLine = sanitizeReadableText(lineBuilder.toString())
            if (cleanedLine.isNotBlank() && !isPdfMetadataNoise(cleanedLine)) {
                extractedChunks.add(cleanedLine)
            }
        }

        // 3. Fallback: if PDF did not use standard BT..ET delimiters, scan balanced parenthesized literals
        if (extractedChunks.isEmpty()) {
            for (rawLiteral in extractBalancedParenthesizedStrings(targetCorpus)) {
                val decoded = sanitizeReadableText(unescapePdfLiteral(rawLiteral))
                if (decoded.length >= 3 && !isPdfMetadataNoise(decoded)) {
                    extractedChunks.add(decoded)
                }
            }
        }

        val distinctChunks = extractedChunks.distinct()
        val finalText = if (detectedPages > 1 && distinctChunks.size >= detectedPages) {
            // Group extracted blocks proportionally into [Page X] sections for accurate citation attribution
            val chunksPerPage = maxOf(1, (distinctChunks.size + detectedPages - 1) / detectedPages)
            distinctChunks.chunked(chunksPerPage).mapIndexed { idx, pageLines ->
                val pNum = minOf(detectedPages, idx + 1)
                "[Page $pNum]\n" + pageLines.joinToString("\n")
            }.joinToString("\n\n").trim()
        } else {
            distinctChunks.joinToString("\n").trim()
        }

        val resultText = if (finalText.isNotBlank()) {
            finalText
        } else {
            "Indexed PDF Document ($fallbackTitle, $detectedPages pages). Ready for grounded study analysis."
        }

        return Pair(resultText, detectedPages)
    }

    /**
     * Parses `/ToUnicode` CMap sections (`beginbfchar` and `beginbfrange`) so CID-encoded hex strings
     * in academic LaTeX/PowerPoint PDFs can be translated back to Unicode characters.
     */
    internal fun parseToUnicodeCMap(corpus: String): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val bfCharBlockRegex = Regex("""beginbfchar([\s\S]*?)endbfchar""")
        val pairRegex = Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""")

        for (block in bfCharBlockRegex.findAll(corpus)) {
            for (pair in pairRegex.findAll(block.groupValues[1])) {
                val srcCode = pair.groupValues[1].toIntOrNull(16) ?: continue
                val targetUnicode = decodeUnicodeHexSequence(pair.groupValues[2])
                if (targetUnicode.isNotEmpty()) {
                    map[srcCode] = targetUnicode
                }
            }
        }

        val bfRangeBlockRegex = Regex("""beginbfrange([\s\S]*?)endbfrange""")
        val rangeRegex = Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""")
        for (block in bfRangeBlockRegex.findAll(corpus)) {
            for (range in rangeRegex.findAll(block.groupValues[1])) {
                val start = range.groupValues[1].toIntOrNull(16) ?: continue
                val end = range.groupValues[2].toIntOrNull(16) ?: continue
                var target = range.groupValues[3].toIntOrNull(16) ?: continue
                if (end >= start && (end - start) <= 256) {
                    for (code in start..end) {
                        map[code] = String(Character.toChars(target))
                        target++
                    }
                }
            }
        }
        return map
    }

    private fun decodeUnicodeHexSequence(hex: String): String {
        val clean = hex.filter { !it.isWhitespace() }
        if (clean.isEmpty()) return ""
        val sb = StringBuilder()
        val step = if (clean.length % 4 == 0) 4 else 2
        var i = 0
        while (i + step <= clean.length) {
            val codePoint = clean.substring(i, i + step).toIntOrNull(16)
            if (codePoint != null && codePoint > 0) {
                sb.append(codePoint.toChar())
            }
            i += step
        }
        return sb.toString()
    }

    /**
     * Extracts both balanced parenthesized strings `(Glycolysis (C6H12O6) pathway)`, `TJ` kerning arrays,
     * and hex strings `<48656C6C6F>` from a PDF `BT .. ET` text object stream in order of appearance.
     */
    internal fun extractPdfStringsFromBlock(
        block: String,
        toUnicodeMap: Map<Int, String> = emptyMap()
    ): List<String> {
        val results = mutableListOf<String>()
        var i = 0
        while (i < block.length) {
            val c = block[i]
            if (c == '[') {
                // Parse TJ array: e.g. [(T) -15 (hermodynamics) -250 (First)] TJ
                val closeBracket = findClosingBracketOutsideParens(block, i)
                if (closeBracket != -1) {
                    val arrayContent = block.substring(i + 1, closeBracket)
                    val joined = parseTjArrayContent(arrayContent, toUnicodeMap)
                    if (joined.isNotBlank()) {
                        results.add(joined)
                    }
                    i = closeBracket + 1
                    continue
                }
            }
            if (c == '(') {
                var depth = 1
                val sb = StringBuilder()
                i++
                while (i < block.length && depth > 0) {
                    val ch = block[i]
                    if (ch == '\\' && i + 1 < block.length) {
                        sb.append(ch).append(block[i + 1])
                        i += 2
                        continue
                    }
                    if (ch == '(') {
                        depth++
                        sb.append(ch)
                    } else if (ch == ')') {
                        depth--
                        if (depth > 0) sb.append(ch)
                    } else {
                        sb.append(ch)
                    }
                    i++
                }
                results.add(unescapePdfLiteral(sb.toString()))
            } else if (c == '<' && (i + 1 >= block.length || block[i + 1] != '<')) {
                val closeIdx = block.indexOf('>', i + 1)
                if (closeIdx != -1) {
                    val hexContent = block.substring(i + 1, closeIdx)
                    val decodedHex = decodeHexPdfString(hexContent, toUnicodeMap)
                    if (decodedHex.isNotBlank()) {
                        results.add(decodedHex)
                    }
                    i = closeIdx + 1
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return results
    }

    private fun findClosingBracketOutsideParens(block: String, openBracketIdx: Int): Int {
        var i = openBracketIdx + 1
        var parenDepth = 0
        while (i < block.length) {
            val ch = block[i]
            if (ch == '\\' && i + 1 < block.length) {
                i += 2
                continue
            }
            if (ch == '(') parenDepth++
            else if (ch == ')' && parenDepth > 0) parenDepth--
            else if (ch == ']' && parenDepth == 0) return i
            i++
        }
        return -1
    }

    private fun parseTjArrayContent(arrayContent: String, toUnicodeMap: Map<Int, String>): String {
        val sb = StringBuilder()
        var i = 0
        val numBuilder = StringBuilder()

        fun flushKerningNumber() {
            val numStr = numBuilder.toString().trim()
            numBuilder.clear()
            val kernVal = numStr.toDoubleOrNull()
            // In PDF TJ arrays, negative numbers below -120 represent inter-word spacing
            if (kernVal != null && kernVal <= -120.0 && sb.isNotEmpty() && !sb.endsWith(" ")) {
                sb.append(" ")
            }
        }

        while (i < arrayContent.length) {
            val c = arrayContent[i]
            if (c == '(') {
                flushKerningNumber()
                var depth = 1
                val lit = StringBuilder()
                i++
                while (i < arrayContent.length && depth > 0) {
                    val ch = arrayContent[i]
                    if (ch == '\\' && i + 1 < arrayContent.length) {
                        lit.append(ch).append(arrayContent[i + 1])
                        i += 2
                        continue
                    }
                    if (ch == '(') {
                        depth++
                        lit.append(ch)
                    } else if (ch == ')') {
                        depth--
                        if (depth > 0) lit.append(ch)
                    } else {
                        lit.append(ch)
                    }
                    i++
                }
                sb.append(unescapePdfLiteral(lit.toString()))
            } else if (c == '<') {
                flushKerningNumber()
                val closeIdx = arrayContent.indexOf('>', i + 1)
                if (closeIdx != -1) {
                    sb.append(decodeHexPdfString(arrayContent.substring(i + 1, closeIdx), toUnicodeMap))
                    i = closeIdx + 1
                } else {
                    i++
                }
            } else {
                numBuilder.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun extractBalancedParenthesizedStrings(corpus: String): List<String> {
        val results = mutableListOf<String>()
        var i = 0
        while (i < corpus.length) {
            if (corpus[i] == '(') {
                var depth = 1
                val sb = StringBuilder()
                i++
                while (i < corpus.length && depth > 0) {
                    val ch = corpus[i]
                    if (ch == '\\' && i + 1 < corpus.length) {
                        sb.append(ch).append(corpus[i + 1])
                        i += 2
                        continue
                    }
                    if (ch == '(') {
                        depth++
                        sb.append(ch)
                    } else if (ch == ')') {
                        depth--
                        if (depth > 0) sb.append(ch)
                    } else {
                        sb.append(ch)
                    }
                    i++
                }
                results.add(sb.toString())
            } else {
                i++
            }
        }
        return results
    }

    private fun decodeHexPdfString(hexRaw: String, toUnicodeMap: Map<Int, String> = emptyMap()): String {
        val cleaned = hexRaw.filter { !it.isWhitespace() }
        if (cleaned.isEmpty() || !cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return ""

        // 1. If a /ToUnicode CMap is available, try 2-byte and 1-byte CID lookups first
        if (toUnicodeMap.isNotEmpty()) {
            if (cleaned.length % 4 == 0) {
                val sb = StringBuilder()
                var matchedAny = false
                for (i in 0 until cleaned.length step 4) {
                    val cid = cleaned.substring(i, i + 4).toIntOrNull(16)
                    val mapped = if (cid != null) toUnicodeMap[cid] else null
                    if (mapped != null) {
                        sb.append(mapped)
                        matchedAny = true
                    }
                }
                if (matchedAny) return sb.toString()
            }
        }

        val padded = if (cleaned.length % 2 == 1) "${cleaned}0" else cleaned
        val bytes = ByteArray(padded.length / 2)
        for (idx in bytes.indices) {
            bytes[idx] = padded.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
        return if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        } else {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
    }

    private fun tryInflateStream(streamBytes: ByteArray): String? {
        for (nowrap in listOf(false, true)) {
            val inflater = Inflater(nowrap)
            try {
                inflater.setInput(streamBytes)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var loops = 0
                while (!inflater.finished() && loops < 4096) { // Up to 16 MB per decompressed stream
                    val count = inflater.inflate(buffer)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        out.write(buffer, 0, count)
                    }
                    loops++
                }
                if (out.size() > 0) {
                    return String(out.toByteArray(), StandardCharsets.ISO_8859_1)
                }
            } catch (_: Exception) {
                // Not a deflate stream or uses custom predictor
            } finally {
                inflater.end()
            }
        }
        return null
    }

    private fun unescapePdfLiteral(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                val next = raw[i + 1]
                when (next) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    '(' -> { sb.append('('); i += 2 }
                    ')' -> { sb.append(')'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    in '0'..'7' -> {
                        val octalDigits = StringBuilder()
                        var j = i + 1
                        while (j < raw.length && j < i + 4 && raw[j] in '0'..'7') {
                            octalDigits.append(raw[j])
                            j++
                        }
                        val charCode = octalDigits.toString().toIntOrNull(8)
                        if (charCode != null && charCode in 32..126) {
                            sb.append(charCode.toChar())
                        }
                        i = j
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun sanitizeReadableText(input: String): String {
        return input
            .filter { ch -> ch == '\n' || ch == '\t' || ch.code in 32..126 || ch.code in 160..0x2FFF }
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isPdfMetadataNoise(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("adobe") ||
            lower.startsWith("pdf-") ||
            lower.contains("flatedecode") ||
            lower.contains("mediabox") ||
            lower.contains("fontdescriptor")
    }

    private fun tryGetPdfRendererPageCount(context: Context, uri: Uri): Int? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.pageCount
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast("/")?.takeIf { it.isNotBlank() } ?: "Uploaded_Document.pdf"
    }
}
