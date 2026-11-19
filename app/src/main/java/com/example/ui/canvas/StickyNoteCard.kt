package com.example.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ink.StickyNoteGeometry
import com.example.ink.ViewportTransform
import com.example.model.StickyNote
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

val StickyNoteComputedFontSpKey = SemanticsPropertyKey<Float>("StickyNoteComputedFontSp")

private val StickyNotePalette: List<Pair<String, Color>> = listOf(
    "#FFF275" to Color(0xFFFFF275),
    "#86E3CE" to Color(0xFF86E3CE),
    "#A1F0A6" to Color(0xFFA1F0A6),
    "#FF9AA2" to Color(0xFFFF9AA2),
    "#D5AAFF" to Color(0xFFD5AAFF),
    "#FFB347" to Color(0xFFFFB347),
    "#2D3139" to Color(0xFF2D3139)
)

private val StickyNoteColorMap: Map<String, Color> =
    StickyNotePalette.associate { it.first.uppercase() to it.second }

@Immutable
private data class StickyNoteVisualContent(
    val id: String,
    val text: String,
    val title: String?,
    val width: Float,
    val height: Float,
    val colorHex: String,
    val isPinned: Boolean,
    val isLocked: Boolean,
    val isAspectRatioLocked: Boolean,
    val isHiddenByCollapse: Boolean
)

private fun StickyNote.toVisualContent(): StickyNoteVisualContent = StickyNoteVisualContent(
    id = id,
    text = text,
    title = title,
    width = width,
    height = height,
    colorHex = colorHex,
    isPinned = isPinned,
    isLocked = isLocked,
    isAspectRatioLocked = isAspectRatioLocked,
    isHiddenByCollapse = isHiddenByCollapse
)

/**
 * Computes the largest font size (in sp) that fits inside ([innerWidthPx], [innerHeightPx])
 * in world-space layout pixels without vertical overflow or mid-word horizontal breaks.
 */
fun computeAutoFitStickyFontSizeSp(
    text: String,
    innerWidthPx: Float,
    innerHeightPx: Float,
    textMeasurer: TextMeasurer,
    density: Density,
    title: String? = null
): TextUnit {
    val availW = innerWidthPx.roundToInt().coerceAtLeast(16)
    val availH = innerHeightPx.roundToInt().coerceAtLeast(16)
    val cleanTitle = title?.trim().orEmpty()
    val displayText = text.ifEmpty { "Escribe el contenido..." }

    val minPx = 5.5f
    val proportionalMaxPx = (min(innerWidthPx, innerHeightPx) * 0.40f).coerceAtLeast(minPx + 1f)
    val deterministicFitPx = StickyNoteGeometry.estimateAutoFitFontSizeWorld(
        text = displayText,
        innerWidthWorld = innerWidthPx,
        innerHeightWorld = innerHeightPx,
        title = title
    )
    val maxPx = min(proportionalMaxPx, (deterministicFitPx * 1.18f).coerceAtLeast(minPx + 0.5f))

    val dividerReservePx = 8f
    val titleRange = if (cleanTitle.isNotEmpty()) StickyNoteGeometry.findLongestTokenRange(cleanTitle) else 0 to 0
    val bodyRange = StickyNoteGeometry.findLongestTokenRange(displayText)
    val titleLen = titleRange.second - titleRange.first
    val bodyLen = bodyRange.second - bodyRange.first
    val longestToken = when {
        titleLen == 0 && bodyLen == 0 -> ""
        titleLen >= bodyLen -> cleanTitle.substring(titleRange.first, titleRange.second)
        else -> displayText.substring(bodyRange.first, bodyRange.second)
    }

    fun fitsAtPx(fontPx: Float): Boolean {
        val spVal = with(density) { fontPx.toSp() }
        val bodyStyle = TextStyle(
            fontSize = spVal,
            lineHeight = (spVal.value * 1.18f).sp,
            fontWeight = FontWeight.Medium
        )
        if (longestToken.isNotEmpty()) {
            val wordMeasure = textMeasurer.measure(
                text = longestToken,
                style = bodyStyle,
                constraints = Constraints(maxWidth = availW),
                maxLines = 1,
                softWrap = false
            )
            if (wordMeasure.hasVisualOverflow && fontPx > minPx * 1.35f) return false
        }
        var totalHeight = 0f
        if (cleanTitle.isNotEmpty() || title != null) {
            val titleString = cleanTitle.ifEmpty { "Título..." }
            val titleStyle = TextStyle(
                fontSize = (spVal.value * 1.15f).sp,
                lineHeight = (spVal.value * 1.32f).sp,
                fontWeight = FontWeight.Bold
            )
            val measuredTitle = textMeasurer.measure(
                text = titleString,
                style = titleStyle,
                constraints = Constraints(maxWidth = availW),
                softWrap = true
            )
            totalHeight += measuredTitle.size.height + dividerReservePx
        }
        val measuredBody = textMeasurer.measure(
            text = displayText,
            style = bodyStyle,
            constraints = Constraints(maxWidth = availW),
            softWrap = true
        )
        totalHeight += measuredBody.size.height
        return totalHeight <= availH
    }

    var lo = minPx
    var hi = maxPx.coerceAtLeast(lo + 0.5f)
    repeat(10) {
        val mid = (lo + hi) * 0.5f
        if (fitsAtPx(mid)) {
            lo = mid
        } else {
            hi = mid
        }
    }
    return with(density) { lo.toSp() }
}

/**
 * High-performance Sticky Note Card:
 * - Observes only its own [StateFlow<StickyNote>].
 * - Position (x, y), rotation, and camera scale/pan are read exclusively inside [graphicsLayer],
 *   resulting in zero recompositions and zero layout invalidations during drag, rotate, pan, or zoom.
 * - All internal dimensions use consistent board-space units converted via [LocalDensity].
 */
@Composable
fun StickyNoteCard(
    noteFlow: StateFlow<StickyNote>,
    cameraState: State<ViewportTransform>,
    textMeasurer: TextMeasurer,
    isSelected: Boolean,
    isPanOnlyMode: Boolean,
    isPlacementPending: Boolean,
    hasViewport: Boolean,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    onSelect: () -> Unit,
    onBeginTransform: () -> Unit,
    onEndTransform: () -> Unit,
    onUpdateText: (String) -> Unit,
    onUpdateTitle: (String) -> Unit,
    onUpdateColor: (String) -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val noteState = noteFlow.collectAsState()
    val visualContent by remember(noteState) {
        derivedStateOf { noteState.value.toVisualContent() }
    }
    if (visualContent.isHiddenByCollapse) return

    val isWithinViewport by remember(
        noteState,
        cameraState,
        isSelected,
        hasViewport,
        viewportWidthPx,
        viewportHeightPx
    ) {
        derivedStateOf {
            if (isSelected || !hasViewport) return@derivedStateOf true
            val cam = cameraState.value
            val n = noteState.value
            val safeScale = cam.scale.coerceAtLeast(ViewportTransform.MIN_SCALE)
            val margin = 220f / safeScale + max(n.width, n.height) * 0.45f
            val visibleLeft = -cam.panX / safeScale - margin
            val visibleTop = -cam.panY / safeScale - margin
            val visibleRight = (viewportWidthPx - cam.panX) / safeScale + margin
            val visibleBottom = (viewportHeightPx - cam.panY) / safeScale + margin
            (n.x + n.width) >= visibleLeft &&
                n.x <= visibleRight &&
                (n.y + n.height) >= visibleTop &&
                n.y <= visibleBottom
        }
    }
    if (!isWithinViewport) return

    val density = LocalDensity.current
    val currentIsSelected by rememberUpdatedState(isSelected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnBeginTransform by rememberUpdatedState(onBeginTransform)
    val currentOnEndTransform by rememberUpdatedState(onEndTransform)
    val currentOnDragDelta by rememberUpdatedState(onDragDelta)

    val cardWidthDp = with(density) { visualContent.width.coerceAtLeast(16f).toDp() }
    val cardHeightDp = with(density) { visualContent.height.coerceAtLeast(16f).toDp() }

    val canInteract = !isPanOnlyMode && !isPlacementPending
    val gestureModifier = if (!canInteract) {
        Modifier
    } else {
        Modifier.pointerInput(visualContent.id, visualContent.isLocked) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Consume down so underlying Canvas (requireUnconsumed = true) never draws an accidental stroke.
                down.consume()
                var isDragging = false
                var beganSnapshot = false
                var totalDragWorld = 0f
                val safeScale = cameraState.value.scale.coerceAtLeast(ViewportTransform.MIN_SCALE)
                val touchSlopWorld = viewConfiguration.touchSlop / safeScale

                do {
                    val event = awaitPointerEvent()
                    var activePointer: PointerInputChange? = null
                    var activeCount = 0
                    for (pointerChange in event.changes) {
                        if (pointerChange.pressed) {
                            activeCount++
                            if (activeCount == 1) activePointer = pointerChange
                        }
                    }
                    if (activeCount >= 2 || event.changes.any { it.isConsumed }) {
                        // Yield immediately to multi-touch canvas pan/pinch-to-zoom.
                        isDragging = false
                        break
                    }
                    val change = activePointer ?: break
                    val delta = change.positionChange()
                    if (!isDragging) {
                        totalDragWorld += hypot(delta.x, delta.y)
                        if (totalDragWorld > touchSlopWorld) {
                            isDragging = true
                            if (!currentIsSelected) {
                                currentOnSelect()
                            }
                            if (!visualContent.isLocked && !beganSnapshot) {
                                beganSnapshot = true
                                currentOnBeginTransform()
                            }
                        }
                    }
                    if (isDragging && !visualContent.isLocked && (delta.x != 0f || delta.y != 0f)) {
                        val rot = noteState.value.rotation
                        val worldDx: Float
                        val worldDy: Float
                        if (rot != 0f) {
                            val angleRad = rot * (PI.toFloat() / 180f)
                            val cosA = cos(angleRad)
                            val sinA = sin(angleRad)
                            worldDx = delta.x * cosA - delta.y * sinA
                            worldDy = delta.x * sinA + delta.y * cosA
                        } else {
                            worldDx = delta.x
                            worldDy = delta.y
                        }
                        currentOnDragDelta(worldDx, worldDy)
                        change.consume()
                    }
                } while (event.changes.any { it.pressed })

                if (!isDragging) {
                    currentOnSelect()
                }
                if (beganSnapshot) {
                    currentOnEndTransform()
                }
            }
        }
    }

    val cornerRadiusDp = with(density) { 10f.toDp() }
    val borderWidthDp = with(density) { (if (isSelected && !isPanOnlyMode) 2.5f else 1f).toDp() }
    val noteBg = remember(visualContent.colorHex) {
        StickyNoteColorMap[visualContent.colorHex.uppercase()] ?: try {
            Color(android.graphics.Color.parseColor(visualContent.colorHex))
        } catch (_: Exception) {
            Color(0xFFFFF275)
        }
    }

    Box(
        modifier = modifier
            .zIndex(if (isSelected) 10f else 0f)
            .size(cardWidthDp, cardHeightDp)
            .graphicsLayer {
                val camera = cameraState.value
                val n = noteState.value
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = n.x * camera.scale + camera.panX
                translationY = n.y * camera.scale + camera.panY
                scaleX = camera.scale
                scaleY = camera.scale
            }
            .graphicsLayer {
                transformOrigin = TransformOrigin.Center
                rotationZ = noteState.value.rotation
            }
            .clip(RoundedCornerShape(cornerRadiusDp))
            .background(noteBg)
            .border(
                width = borderWidthDp,
                color = if (isSelected && !isPanOnlyMode) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Black.copy(alpha = 0.16f)
                },
                shape = RoundedCornerShape(cornerRadiusDp)
            )
            .then(gestureModifier)
            .testTag("sticky_note_${visualContent.id}")
    ) {
        StickyNoteCardBody(
            content = visualContent,
            noteBg = noteBg,
            textMeasurer = textMeasurer,
            isSelected = isSelected,
            isPanOnlyMode = isPanOnlyMode,
            onSelect = currentOnSelect,
            onUpdateText = onUpdateText,
            onUpdateTitle = onUpdateTitle,
            onUpdateColor = onUpdateColor,
            onTogglePin = onTogglePin,
            onDelete = onDelete
        )
    }
}

@Composable
private fun StickyNoteCardBody(
    content: StickyNoteVisualContent,
    noteBg: Color,
    textMeasurer: TextMeasurer,
    isSelected: Boolean,
    isPanOnlyMode: Boolean,
    onSelect: () -> Unit,
    onUpdateText: (String) -> Unit,
    onUpdateTitle: (String) -> Unit,
    onUpdateColor: (String) -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val isDarkBg = content.colorHex.equals("#2D3139", ignoreCase = true)
    val textColor = if (isDarkBg) Color.White else Color(0xFF1B1D1F)
    val headerBg = remember(noteBg) { noteBg.copy(alpha = 0.85f) }

    val headerHeightWorld = StickyNoteGeometry.headerHeightWorld(content.height)
    val innerPadWorld = StickyNoteGeometry.innerPaddingWorld(content.width, content.height)
    val (innerWidthWorld, innerHeightWorld) = StickyNoteGeometry.innerTextBoxWorld(content.width, content.height)

    val headerHeightDp = with(density) { headerHeightWorld.toDp() }
    val innerPadDp = with(density) { innerPadWorld.toDp() }
    val headerHorizontalPadDp = with(density) { (innerPadWorld * 0.8f).coerceAtLeast(4f).toDp() }
    val iconDp = with(density) { (headerHeightWorld * 0.62f).coerceIn(8f, 16f).toDp() }
    val dividerThicknessDp = with(density) { 1f.toDp() }
    val dividerVerticalPadDp = with(density) { 3f.toDp() }

    val autoFitFontSp = remember(
        content.title,
        content.text,
        innerWidthWorld,
        innerHeightWorld,
        density.density,
        density.fontScale
    ) {
        computeAutoFitStickyFontSizeSp(
            text = content.text,
            innerWidthPx = innerWidthWorld,
            innerHeightPx = innerHeightWorld,
            textMeasurer = textMeasurer,
            density = density,
            title = content.title
        )
    }

    val canEditNote = isSelected && !content.isLocked && !isPanOnlyMode

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeightDp)
                .background(headerBg)
                .padding(horizontal = headerHorizontalPadDp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(with(density) { 4f.toDp() })
            ) {
                if (content.isAspectRatioLocked && !isPanOnlyMode) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Proporción 1:1 bloqueada",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(with(density) { (headerHeightWorld * 0.52f).coerceAtLeast(7f).toDp() })
                    )
                }
                if (content.isPinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Fijada",
                        tint = textColor.copy(alpha = 0.75f),
                        modifier = Modifier
                            .size(iconDp)
                            .let { if (isPanOnlyMode) it else it.clickable { onTogglePin() } }
                    )
                }
            }
        }

        val titleStyle = TextStyle(
            color = textColor,
            fontSize = (autoFitFontSp.value * 1.15f).sp,
            lineHeight = (autoFitFontSp.value * 1.32f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        val effectiveStyle = TextStyle(
            color = textColor,
            fontSize = autoFitFontSp,
            lineHeight = (autoFitFontSp.value * 1.18f).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Start
        )
        val bodyMinHeightDp = with(density) { (innerHeightWorld * 0.55f).toDp() }

        if (canEditNote) {
            val scrollState = rememberScrollState()
            LaunchedEffect(content.text, content.title) {
                if (scrollState.maxValue > 0) {
                    scrollState.scrollTo(scrollState.maxValue)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(innerPadDp)
                    .verticalScroll(scrollState)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = content.title.orEmpty(),
                        onValueChange = onUpdateTitle,
                        textStyle = titleStyle,
                        cursorBrush = SolidColor(textColor),
                        decorationBox = { inner ->
                            if (content.title.isNullOrEmpty()) {
                                Text(
                                    text = "Título...",
                                    style = titleStyle.copy(color = textColor.copy(alpha = 0.45f))
                                )
                            }
                            inner()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) onSelect()
                            }
                            .testTag("sticky_note_title_input_${content.id}")
                    )

                    HorizontalDivider(
                        color = textColor.copy(alpha = 0.16f),
                        thickness = dividerThicknessDp,
                        modifier = Modifier.padding(vertical = dividerVerticalPadDp)
                    )

                    BasicTextField(
                        value = content.text,
                        onValueChange = onUpdateText,
                        textStyle = effectiveStyle,
                        cursorBrush = SolidColor(textColor),
                        decorationBox = { inner ->
                            if (content.text.isEmpty()) {
                                Text(
                                    text = "Escribe el contenido...",
                                    style = effectiveStyle.copy(color = textColor.copy(alpha = 0.42f))
                                )
                            }
                            inner()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = bodyMinHeightDp)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) onSelect()
                            }
                            .semantics {
                                set(StickyNoteComputedFontSpKey, autoFitFontSp.value)
                            }
                            .testTag("sticky_note_input_${content.id}")
                    )
                }
            }
        } else {
            // Read-only fast path: zero ScrollState, LaunchedEffect, or BasicTextField overhead.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(innerPadDp)
            ) {
                Text(
                    text = content.title?.takeIf { it.isNotEmpty() } ?: "Título...",
                    style = if (content.title.isNullOrEmpty()) {
                        titleStyle.copy(color = textColor.copy(alpha = 0.45f))
                    } else {
                        titleStyle
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sticky_note_title_input_${content.id}")
                )

                HorizontalDivider(
                    color = textColor.copy(alpha = 0.16f),
                    thickness = dividerThicknessDp,
                    modifier = Modifier.padding(vertical = dividerVerticalPadDp)
                )

                Text(
                    text = content.text.ifEmpty { "Escribe el contenido..." },
                    style = if (content.text.isEmpty()) {
                        effectiveStyle.copy(color = textColor.copy(alpha = 0.42f))
                    } else {
                        effectiveStyle
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = bodyMinHeightDp)
                        .semantics {
                            set(StickyNoteComputedFontSpKey, autoFitFontSp.value)
                        }
                        .testTag("sticky_note_input_${content.id}")
                )
            }
        }
    }
}
