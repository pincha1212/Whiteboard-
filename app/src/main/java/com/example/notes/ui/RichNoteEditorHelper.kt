package com.example.notes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

enum class NoteBlockFormat {
    NONE,
    H1,
    H2,
    QUOTE,
    LIST
}

enum class NoteInlineFormat(val marker: String) {
    BOLD("**"),
    ITALIC("*"),
    UNDERLINE("__"),
    STRIKE("~~"),
    CODE("`")
}

data class RichEditorThemeColors(
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val codeBackground: Color,
    val codeText: Color,
    val quoteBackground: Color
)

object RichNoteFormatter {

    fun detectBlockFormatAtCursor(text: String, cursorOffset: Int): NoteBlockFormat {
        val safeCursor = cursorOffset.coerceIn(0, text.length)
        val lineStart = findLineStart(text, safeCursor)
        val lineEnd = findLineEnd(text, safeCursor)
        val line = text.substring(lineStart, lineEnd)
        return when {
            line.startsWith("## ") -> NoteBlockFormat.H2
            line.startsWith("# ") -> NoteBlockFormat.H1
            line.startsWith("> ") -> NoteBlockFormat.QUOTE
            line.startsWith("- ") -> NoteBlockFormat.LIST
            else -> NoteBlockFormat.NONE
        }
    }

    fun detectInlineFormatsAtCursor(text: String, cursorOffset: Int): Set<NoteInlineFormat> {
        val safeCursor = cursorOffset.coerceIn(0, text.length)
        val lineStart = findLineStart(text, safeCursor)
        val lineEnd = findLineEnd(text, safeCursor)
        val line = text.substring(lineStart, lineEnd)
        val localCursor = safeCursor - lineStart

        val active = mutableSetOf<NoteInlineFormat>()
        val spans = parseInlineSpans(line)
        for (span in spans) {
            // Cursor is inside the inner content or at the closing delimiter boundary
            if (localCursor in span.innerStart..span.innerEnd) {
                active.add(span.format)
            }
        }
        return active
    }

    fun toggleBlockFormat(value: TextFieldValue, target: NoteBlockFormat): TextFieldValue {
        val text = value.text
        val safeStart = value.selection.min.coerceIn(0, text.length)
        val lineStart = findLineStart(text, safeStart)
        val lineEnd = findLineEnd(text, safeStart)
        val line = text.substring(lineStart, lineEnd)

        val currentPrefix = when {
            line.startsWith("## ") -> "## "
            line.startsWith("# ") -> "# "
            line.startsWith("> ") -> "> "
            line.startsWith("- ") -> "- "
            else -> ""
        }
        val currentBlock = when (currentPrefix) {
            "## " -> NoteBlockFormat.H2
            "# " -> NoteBlockFormat.H1
            "> " -> NoteBlockFormat.QUOTE
            "- " -> NoteBlockFormat.LIST
            else -> NoteBlockFormat.NONE
        }

        val newPrefix = if (currentBlock == target || target == NoteBlockFormat.NONE) {
            ""
        } else {
            when (target) {
                NoteBlockFormat.H1 -> "# "
                NoteBlockFormat.H2 -> "## "
                NoteBlockFormat.QUOTE -> "> "
                NoteBlockFormat.LIST -> "- "
                NoteBlockFormat.NONE -> ""
            }
        }

        val lineWithoutPrefix = line.removePrefix(currentPrefix)
        val newText = buildString {
            append(text.substring(0, lineStart))
            append(newPrefix)
            append(lineWithoutPrefix)
            append(text.substring(lineEnd))
        }

        val delta = newPrefix.length - currentPrefix.length
        val newCursor = (safeStart + delta).coerceIn(lineStart + newPrefix.length, newText.length)
        return TextFieldValue(
            text = newText,
            selection = TextRange(newCursor)
        )
    }

    fun insertDivider(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val pos = value.selection.max.coerceIn(0, text.length)
        val prefix = if (pos == 0 || text[pos - 1] == '\n') "" else "\n"
        val insertion = "$prefix---\n"
        val newText = text.substring(0, pos) + insertion + text.substring(pos)
        val newCursor = pos + insertion.length
        return TextFieldValue(text = newText, selection = TextRange(newCursor))
    }

    /**
     * Toggles an inline style on selection, or updates pending/active inline styles when cursor is collapsed.
     * Never inserts generic placeholder words.
     */
    fun toggleInlineFormat(
        value: TextFieldValue,
        format: NoteInlineFormat,
        pendingFormats: Set<NoteInlineFormat>
    ): Pair<TextFieldValue, Set<NoteInlineFormat>> {
        val text = value.text
        val selStart = value.selection.min.coerceIn(0, text.length)
        val selEnd = value.selection.max.coerceIn(0, text.length)
        val marker = format.marker

        if (selStart < selEnd) {
            // Selection exists: wrap or unwrap selected text without adding generic words
            val selectedText = text.substring(selStart, selEnd)
            val hasOuterMarkers = selStart >= marker.length &&
                selEnd + marker.length <= text.length &&
                text.substring(selStart - marker.length, selStart) == marker &&
                text.substring(selEnd, selEnd + marker.length) == marker

            if (hasOuterMarkers) {
                val newText = text.substring(0, selStart - marker.length) +
                    selectedText +
                    text.substring(selEnd + marker.length)
                val newStart = selStart - marker.length
                val newEnd = newStart + selectedText.length
                return TextFieldValue(newText, TextRange(newStart, newEnd)) to (pendingFormats - format)
            }

            if (selectedText.length >= marker.length * 2 &&
                selectedText.startsWith(marker) &&
                selectedText.endsWith(marker)
            ) {
                val unwrapped = selectedText.substring(marker.length, selectedText.length - marker.length)
                val newText = text.substring(0, selStart) + unwrapped + text.substring(selEnd)
                return TextFieldValue(newText, TextRange(selStart, selStart + unwrapped.length)) to (pendingFormats - format)
            }

            val newText = text.substring(0, selStart) + marker + selectedText + marker + text.substring(selEnd)
            val newStart = selStart + marker.length
            val newEnd = newStart + selectedText.length
            return TextFieldValue(newText, TextRange(newStart, newEnd)) to pendingFormats
        }

        // Collapsed cursor: check if cursor is already inside a matching span on this line
        val lineStart = findLineStart(text, selStart)
        val lineEnd = findLineEnd(text, selStart)
        val line = text.substring(lineStart, lineEnd)
        val localCursor = selStart - lineStart
        val matchingSpan = parseInlineSpans(line).firstOrNull {
            it.format == format && localCursor in it.innerStart..it.innerEnd
        }

        if (matchingSpan != null) {
            val absInnerStart = lineStart + matchingSpan.innerStart
            val absInnerEnd = lineStart + matchingSpan.innerEnd
            if (absInnerStart == absInnerEnd) {
                // Empty marker pair: remove it cleanly
                val absOuterStart = lineStart + matchingSpan.outerStart
                val absOuterEnd = lineStart + matchingSpan.outerEnd
                val newText = text.substring(0, absOuterStart) + text.substring(absOuterEnd)
                return TextFieldValue(newText, TextRange(absOuterStart)) to (pendingFormats - format)
            }
            if (selStart == absInnerEnd) {
                // Cursor is at end of styled span: step outside closing marker so next typed text is unstyled
                val outsideCursor = (lineStart + matchingSpan.outerEnd).coerceIn(0, text.length)
                return value.copy(selection = TextRange(outsideCursor)) to (pendingFormats - format)
            }
            // Cursor is in middle of span: unwrap the span so user doesn't lose text
            val innerText = text.substring(absInnerStart, absInnerEnd)
            val absOuterStart = lineStart + matchingSpan.outerStart
            val absOuterEnd = lineStart + matchingSpan.outerEnd
            val newText = text.substring(0, absOuterStart) + innerText + text.substring(absOuterEnd)
            val adjustedCursor = (selStart - marker.length).coerceIn(absOuterStart, absOuterStart + innerText.length)
            return TextFieldValue(newText, TextRange(adjustedCursor)) to (pendingFormats - format)
        }

        // Otherwise toggle pending state for upcoming typing (zero generic text inserted!)
        val nextPending = if (format in pendingFormats) pendingFormats - format else pendingFormats + format
        return value to nextPending
    }

    /**
     * Handles typing while pending inline formats are active, plus smart Enter continuation for lists/quotes.
     */
    fun processTextChange(
        previous: TextFieldValue,
        incoming: TextFieldValue,
        pendingFormats: Set<NoteInlineFormat>
    ): Pair<TextFieldValue, Set<NoteInlineFormat>> {
        val prevText = previous.text
        val newText = incoming.text

        // 1. Smart Enter continuation for Lista ("- ") and Cita ("> ")
        if (newText.length == prevText.length + 1 &&
            incoming.selection.collapsed &&
            incoming.selection.start > 0 &&
            newText[incoming.selection.start - 1] == '\n'
        ) {
            val newlineIdx = incoming.selection.start - 1
            val prevLineStart = findLineStart(newText, newlineIdx)
            val prevLine = newText.substring(prevLineStart, newlineIdx)
            val continuePrefix = when {
                prevLine.startsWith("- ") -> "- "
                prevLine.startsWith("> ") -> "> "
                else -> null
            }
            if (continuePrefix != null) {
                if (prevLine == continuePrefix) {
                    // Empty list/quote item + Enter -> exit list/quote cleanly
                    val updated = newText.substring(0, prevLineStart) + "\n" + newText.substring(newlineIdx + 1)
                    val cursor = prevLineStart + 1
                    return TextFieldValue(updated, TextRange(cursor)) to pendingFormats
                } else {
                    val updated = newText.substring(0, newlineIdx + 1) + continuePrefix + newText.substring(newlineIdx + 1)
                    val cursor = newlineIdx + 1 + continuePrefix.length
                    return TextFieldValue(updated, TextRange(cursor)) to emptySet()
                }
            }
        }

        // 2. Apply pending inline styles automatically to newly typed characters
        if (pendingFormats.isNotEmpty() &&
            incoming.selection.collapsed &&
            newText.length > prevText.length &&
            previous.selection.collapsed
        ) {
            val insertStart = previous.selection.start.coerceIn(0, prevText.length)
            val insertedCount = newText.length - prevText.length
            if (insertStart + insertedCount <= newText.length &&
                newText.substring(0, insertStart) == prevText.substring(0, insertStart)
            ) {
                val typedChunk = newText.substring(insertStart, insertStart + insertedCount)
                // Do not wrap standalone newlines
                if (!typedChunk.contains('\n')) {
                    val orderedFormats = listOf(
                        NoteInlineFormat.BOLD,
                        NoteInlineFormat.ITALIC,
                        NoteInlineFormat.UNDERLINE,
                        NoteInlineFormat.STRIKE,
                        NoteInlineFormat.CODE
                    ).filter { it in pendingFormats }

                    val openMarkers = orderedFormats.joinToString("") { it.marker }
                    val closeMarkers = orderedFormats.reversed().joinToString("") { it.marker }

                    val wrappedText = newText.substring(0, insertStart) +
                        openMarkers + typedChunk + closeMarkers +
                        newText.substring(insertStart + insertedCount)
                    val cursorInside = insertStart + openMarkers.length + typedChunk.length
                    // Cursor is now inside the active markers, so subsequent keystrokes stay inside!
                    return TextFieldValue(wrappedText, TextRange(cursorInside)) to emptySet()
                }
            }
        }

        // 3. Clean up empty marker pairs left behind when backspacing the last character inside them
        val cleaned = cleanupEmptyInlineWrappersAroundCursor(incoming)
        return cleaned to pendingFormats
    }

    private fun cleanupEmptyInlineWrappersAroundCursor(value: TextFieldValue): TextFieldValue {
        var text = value.text
        var cursor = value.selection.start.coerceIn(0, text.length)
        val emptyWrappers = listOf("****", "____", "~~~~", "``")
        for (wrapper in emptyWrappers) {
            val idx = text.indexOf(wrapper)
            if (idx >= 0 && cursor in idx..(idx + wrapper.length)) {
                text = text.substring(0, idx) + text.substring(idx + wrapper.length)
                cursor = idx.coerceIn(0, text.length)
                return TextFieldValue(text, TextRange(cursor))
            }
        }
        return value
    }

    /**
     * Strips empty inline wrappers from persisted content on save/close.
     */
    fun stripEmptyWrappers(content: String): String {
        return content
            .replace("****", "")
            .replace("____", "")
            .replace("~~~~", "")
            .replace("``", "")
    }

    internal data class ParsedInlineSpan(
        val format: NoteInlineFormat,
        val outerStart: Int,
        val innerStart: Int,
        val innerEnd: Int,
        val outerEnd: Int
    )

    internal fun parseInlineSpans(line: String): List<ParsedInlineSpan> {
        val result = mutableListOf<ParsedInlineSpan>()
        val tokens = listOf(
            NoteInlineFormat.BOLD to "**",
            NoteInlineFormat.UNDERLINE to "__",
            NoteInlineFormat.STRIKE to "~~",
            NoteInlineFormat.CODE to "`",
            NoteInlineFormat.ITALIC to "*"
        )

        var i = 0
        while (i < line.length) {
            // Check triple asterisk ***...*** (bold + italic)
            if (line.startsWith("***", i)) {
                val close = line.indexOf("***", i + 3)
                if (close > i + 3) {
                    result.add(ParsedInlineSpan(NoteInlineFormat.BOLD, i, i + 3, close, close + 3))
                    result.add(ParsedInlineSpan(NoteInlineFormat.ITALIC, i, i + 3, close, close + 3))
                    i += 3
                    continue
                }
            }

            var matched = false
            for ((fmt, marker) in tokens) {
                if (line.startsWith(marker, i)) {
                    // For single '*' make sure it isn't '**'
                    if (marker == "*" && line.startsWith("**", i)) continue
                    val close = findClosingMarker(line, marker, i + marker.length)
                    if (close > i + marker.length) {
                        result.add(
                            ParsedInlineSpan(
                                format = fmt,
                                outerStart = i,
                                innerStart = i + marker.length,
                                innerEnd = close,
                                outerEnd = close + marker.length
                            )
                        )
                        i += marker.length
                        matched = true
                        break
                    }
                }
            }
            if (!matched) {
                i++
            }
        }
        return result
    }

    private fun findClosingMarker(line: String, marker: String, fromIndex: Int): Int {
        var idx = fromIndex
        while (idx < line.length) {
            val found = line.indexOf(marker, idx)
            if (found < 0) return -1
            if (marker == "*" && line.startsWith("**", found)) {
                idx = found + 2
                continue
            }
            return found
        }
        return -1
    }

    private fun findLineStart(text: String, pos: Int): Int {
        if (pos <= 0) return 0
        val idx = text.lastIndexOf('\n', pos - 1)
        return if (idx < 0) 0 else idx + 1
    }

    private fun findLineEnd(text: String, pos: Int): Int {
        if (pos >= text.length) return text.length
        val idx = text.indexOf('\n', pos)
        return if (idx < 0) text.length else idx
    }

    /**
     * Renders clean visual AnnotatedString for NoteCardView and Read-only Preview (no raw markdown markers).
     */
    fun buildDisplayAnnotatedString(
        content: String,
        colors: RichEditorThemeColors
    ): AnnotatedString {
        return transformRichText(
            original = content,
            colors = colors,
            cursorOffset = -1,
            pendingFormats = emptySet()
        ).text
    }

    fun transformRichText(
        original: String,
        colors: RichEditorThemeColors,
        cursorOffset: Int,
        pendingFormats: Set<NoteInlineFormat>
    ): TransformedText {
        val len = original.length
        val origToTrans = IntArray(len + 1)
        val transToOrig = ArrayList<Int>(len + 16)
        val builder = AnnotatedString.Builder()

        fun emitHidden(origStart: Int, origEnd: Int) {
            val transPos = builder.length
            for (k in origStart until origEnd) {
                origToTrans[k] = transPos
            }
        }

        fun emitVisualPrefix(origStart: Int, origEnd: Int, visual: String, style: SpanStyle) {
            val startTrans = builder.length
            origToTrans[origStart] = startTrans
            builder.pushStyle(style)
            builder.append(visual)
            builder.pop()
            for (k in 0 until visual.length) {
                transToOrig.add(origStart)
            }
            val afterTrans = builder.length
            for (k in (origStart + 1) until origEnd) {
                origToTrans[k] = afterTrans
            }
        }

        fun emitPendingEmptyPreview(origPos: Int) {
            if (pendingFormats.isEmpty() || cursorOffset != origPos) return
            val style = spanStyleForFormats(pendingFormats, colors, baseBlock = NoteBlockFormat.NONE)
            val previewChars = if (NoteInlineFormat.CODE in pendingFormats) "  " else "\u200B"
            val startTrans = builder.length
            builder.pushStyle(style)
            builder.append(previewChars)
            builder.pop()
            for (k in 0 until previewChars.length) {
                transToOrig.add(origPos)
            }
            origToTrans[origPos] = if (previewChars.length >= 2) startTrans + 1 else startTrans
        }

        var lineStart = 0
        while (lineStart <= len) {
            val nlIdx = original.indexOf('\n', lineStart)
            val lineEnd = if (nlIdx < 0) len else nlIdx
            val line = original.substring(lineStart, lineEnd)

            // Determine block prefix on this line
            val block: NoteBlockFormat
            val prefixLen: Int
            when {
                line.startsWith("## ") -> {
                    block = NoteBlockFormat.H2
                    prefixLen = 3
                }
                line.startsWith("# ") -> {
                    block = NoteBlockFormat.H1
                    prefixLen = 2
                }
                line.startsWith("> ") -> {
                    block = NoteBlockFormat.QUOTE
                    prefixLen = 2
                }
                line.startsWith("- ") -> {
                    block = NoteBlockFormat.LIST
                    prefixLen = 2
                }
                else -> {
                    block = NoteBlockFormat.NONE
                    prefixLen = 0
                }
            }

            // Emit block prefix visually or hide it
            if (prefixLen > 0) {
                when (block) {
                    NoteBlockFormat.H1, NoteBlockFormat.H2 -> {
                        emitHidden(lineStart, lineStart + prefixLen)
                    }
                    NoteBlockFormat.QUOTE -> {
                        emitVisualPrefix(
                            origStart = lineStart,
                            origEnd = lineStart + prefixLen,
                            visual = "▎ ",
                            style = SpanStyle(
                                color = colors.primary,
                                fontWeight = FontWeight.Bold,
                                background = colors.quoteBackground
                            )
                        )
                    }
                    NoteBlockFormat.LIST -> {
                        emitVisualPrefix(
                            origStart = lineStart,
                            origEnd = lineStart + prefixLen,
                            visual = "• ",
                            style = SpanStyle(
                                color = colors.primary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    NoteBlockFormat.NONE -> {}
                }
            }

            val contentStart = lineStart + prefixLen
            val contentText = original.substring(contentStart, lineEnd)

            // If divider line "---"
            if (block == NoteBlockFormat.NONE && contentText.trim() == "---" && contentText.isNotEmpty()) {
                val startTrans = builder.length
                for (k in contentStart until lineEnd) {
                    origToTrans[k] = startTrans
                }
                val rule = "──────────────"
                builder.pushStyle(SpanStyle(color = colors.onSurfaceVariant.copy(alpha = 0.5f)))
                builder.append(rule)
                builder.pop()
                for (k in rule.indices) {
                    transToOrig.add(contentStart)
                }
            } else {
                // Parse inline spans within contentText
                val lineSpans = parseInlineSpans(contentText)
                val delimiterMask = BooleanArray(contentText.length)
                for (sp in lineSpans) {
                    for (k in sp.outerStart until sp.innerStart) {
                        delimiterMask[k] = true
                    }
                    for (k in sp.innerEnd until sp.outerEnd) {
                        delimiterMask[k] = true
                    }
                }

                // If empty heading line (H1/H2) and no pending inline preview, emit zero-width metric character so cursor reflects H1/H2 size
                if (contentText.isEmpty() && (block == NoteBlockFormat.H1 || block == NoteBlockFormat.H2)) {
                    val metricStyle = spanStyleForFormats(pendingFormats, colors, block)
                    val zwChar = if (NoteInlineFormat.CODE in pendingFormats && cursorOffset == contentStart) "  " else "\u200B"
                    val startTrans = builder.length
                    builder.pushStyle(metricStyle)
                    builder.append(zwChar)
                    builder.pop()
                    for (k in zwChar.indices) {
                        transToOrig.add(contentStart)
                    }
                    origToTrans[contentStart] = if (zwChar.length >= 2) startTrans + 1 else startTrans
                } else {
                    for (idx in contentText.indices) {
                        val absIdx = contentStart + idx
                        if (cursorOffset == absIdx && pendingFormats.isNotEmpty()) {
                            emitPendingEmptyPreview(absIdx)
                        }
                        if (delimiterMask[idx]) {
                            // Hide markdown marker character
                            if (!(cursorOffset == absIdx && pendingFormats.isNotEmpty())) {
                                origToTrans[absIdx] = builder.length
                            }
                        } else {
                            val activeAtChar = lineSpans
                                .filter { idx in it.innerStart until it.innerEnd }
                                .map { it.format }
                                .toSet()
                            val charStyle = spanStyleForFormats(activeAtChar, colors, block)
                            if (!(cursorOffset == absIdx && pendingFormats.isNotEmpty())) {
                                origToTrans[absIdx] = builder.length
                            }
                            builder.pushStyle(charStyle)
                            builder.append(contentText[idx])
                            builder.pop()
                            transToOrig.add(absIdx)
                        }
                    }

                    // Cursor at very end of this line
                    if (cursorOffset == lineEnd && pendingFormats.isNotEmpty()) {
                        emitPendingEmptyPreview(lineEnd)
                    }
                }
            }

            val handledLineEnd = (cursorOffset == lineEnd && pendingFormats.isNotEmpty()) ||
                (contentText.isEmpty() && (block == NoteBlockFormat.H1 || block == NoteBlockFormat.H2))

            if (nlIdx < 0) {
                if (!handledLineEnd) {
                    origToTrans[len] = builder.length
                }
                break
            } else {
                // Emit newline
                if (!handledLineEnd) {
                    origToTrans[nlIdx] = builder.length
                }
                builder.append('\n')
                transToOrig.add(nlIdx)
                lineStart = nlIdx + 1
            }
        }

        // Ensure final sentinel for transToOrig
        transToOrig.add(len)

        val finalAnnotated = builder.toAnnotatedString()
        val finalTransLen = finalAnnotated.length

        // Clamp arrays defensively
        for (i in origToTrans.indices) {
            origToTrans[i] = origToTrans[i].coerceIn(0, finalTransLen)
        }
        val transMapArray = IntArray(finalTransLen + 1) { idx ->
            val v = if (idx < transToOrig.size) transToOrig[idx] else len
            v.coerceIn(0, len)
        }

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                return origToTrans[offset.coerceIn(0, len)]
            }

            override fun transformedToOriginal(offset: Int): Int {
                return transMapArray[offset.coerceIn(0, finalTransLen)]
            }
        }

        return TransformedText(finalAnnotated, mapping)
    }

    private fun spanStyleForFormats(
        formats: Set<NoteInlineFormat>,
        colors: RichEditorThemeColors,
        baseBlock: NoteBlockFormat
    ): SpanStyle {
        val isBold = NoteInlineFormat.BOLD in formats || baseBlock == NoteBlockFormat.H1 || baseBlock == NoteBlockFormat.H2
        val isItalic = NoteInlineFormat.ITALIC in formats || baseBlock == NoteBlockFormat.QUOTE
        val isUnderline = NoteInlineFormat.UNDERLINE in formats
        val isStrike = NoteInlineFormat.STRIKE in formats
        val isCode = NoteInlineFormat.CODE in formats

        val decorations = mutableListOf<TextDecoration>()
        if (isUnderline) decorations.add(TextDecoration.Underline)
        if (isStrike) decorations.add(TextDecoration.LineThrough)
        val combinedDecoration = when {
            decorations.isEmpty() -> TextDecoration.None
            decorations.size == 1 -> decorations.first()
            else -> TextDecoration.combine(decorations)
        }

        val fontSize = when {
            baseBlock == NoteBlockFormat.H1 -> 24.sp
            baseBlock == NoteBlockFormat.H2 -> 19.sp
            isCode -> 14.sp
            else -> 15.sp
        }

        val fontWeight = when {
            baseBlock == NoteBlockFormat.H1 -> FontWeight.Bold
            baseBlock == NoteBlockFormat.H2 -> FontWeight.SemiBold
            isBold -> FontWeight.Bold
            isCode -> FontWeight.Medium
            else -> FontWeight.Normal
        }

        val textColor = when {
            isCode -> colors.codeText
            baseBlock == NoteBlockFormat.QUOTE -> colors.onSurfaceVariant
            else -> colors.onSurface
        }

        val bgColor = when {
            isCode -> colors.codeBackground
            baseBlock == NoteBlockFormat.QUOTE -> colors.quoteBackground
            else -> Color.Transparent
        }

        return SpanStyle(
            color = textColor,
            background = bgColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = if (isCode) FontFamily.Monospace else FontFamily.Default,
            textDecoration = combinedDecoration
        )
    }
}

class RichNoteVisualTransformation(
    private val colors: RichEditorThemeColors,
    private val cursorOffset: Int,
    private val pendingFormats: Set<NoteInlineFormat>
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return RichNoteFormatter.transformRichText(
            original = text.text,
            colors = colors,
            cursorOffset = cursorOffset,
            pendingFormats = pendingFormats
        )
    }
}
