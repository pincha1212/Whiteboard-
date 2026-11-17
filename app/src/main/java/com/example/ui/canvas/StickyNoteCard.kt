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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ink.StickyNoteGeometry
import com.example.ink.StickyNoteResizeHandle
import com.example.ink.ViewportTransform
import com.example.model.StickyNote
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

val StickyNoteComputedFontSpKey = SemanticsPropertyKey<Float>("StickyNoteComputedFontSp")

/**
 * Computes the largest font size (in sp) that fits inside ([innerWidthPx], [innerHeightPx])
 * without vertical overflow, internal scrolling, or mid-word horizontal breaks.
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

    val minPx = (6f * density.density.coerceAtLeast(1f) * 0.65f).coerceAtLeast(4f)
    val proportionalMaxPx = (min(innerWidthPx, innerHeightPx) * 0.40f).coerceAtLeast(minPx + 1f)
    val deterministicFitPx = with(density) {
        val wWorld = innerWidthPx / density.density
        val hWorld = innerHeightPx / density.density
        StickyNoteGeometry.estimateAutoFitFontSizeWorld(
            text = displayText,
            innerWidthWorld = wWorld,
            innerHeightWorld = hWorld,
            title = title
        ).sp.toPx()
    }
    val maxPx = min(proportionalMaxPx, (deterministicFitPx * 1.15f).coerceAtLeast(minPx + 0.5f))

    val dividerReservePx = with(density) { 8.dp.toPx() }
    val longestToken = (if (cleanTitle.isNotEmpty()) "$cleanTitle $displayText" else displayText)
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .maxByOrNull { it.length }
        .orEmpty()

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
    repeat(14) {
        val mid = (lo + hi) * 0.5f
        if (fitsAtPx(mid)) {
            lo = mid
        } else {
            hi = mid
        }
    }
    return with(density) { lo.toSp() }
}

@Composable
fun StickyNoteView(
    note: StickyNote,
    scale: Float,
    panX: Float,
    panY: Float,
    onUpdateText: (String) -> Unit,
    onUpdateTitle: (String) -> Unit = {},
    onUpdateColor: (String) -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit = {},
    onBeginTransform: () -> Unit = {},
    onEndTransform: () -> Unit = {},
    onDragDelta: (Float, Float) -> Unit,
    onResizeHandle: (StickyNoteResizeHandle, Float, Float) -> Unit = { _, _, _ -> },
    onRotate: (Float) -> Unit = {},
    isSelected: Boolean = false,
    isPanOnlyMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnBeginTransform by rememberUpdatedState(onBeginTransform)
    val currentOnEndTransform by rememberUpdatedState(onEndTransform)
    val currentOnUpdateTitle by rememberUpdatedState(onUpdateTitle)
    val currentOnDragDelta by rememberUpdatedState(onDragDelta)
    val currentOnResizeHandle by rememberUpdatedState(onResizeHandle)
    val currentOnRotate by rememberUpdatedState(onRotate)
    val currentNoteRotation by rememberUpdatedState(note.rotation)

    val noteBg = try {
        Color(android.graphics.Color.parseColor(note.colorHex))
    } catch (_: Exception) {
        Color(0xFFFFF275)
    }

    val isDarkBg = note.colorHex.equals("#2D3139", ignoreCase = true)
    val textColor = if (isDarkBg) Color.White else Color(0xFF1B1D1F)
    val headerBg = noteBg.copy(alpha = 0.85f)

    val colors = listOf(
        "#FFF275" to "Amarillo",
        "#86E3CE" to "Cian",
        "#A1F0A6" to "Menta",
        "#FF9AA2" to "Rosa",
        "#D5AAFF" to "Lavanda",
        "#FFB347" to "Naranja",
        "#2D3139" to "Pizarra"
    )

    var showPalette by remember { mutableStateOf(false) }

    // World Space -> Screen Space projection (isotropic, zero percentage / rigid DP clamping)
    val camera = ViewportTransform(scale = scale, panX = panX, panY = panY)
    val screenXPx = camera.worldToScreenX(note.x)
    val screenYPx = camera.worldToScreenY(note.y)
    // Keep the note's internal layout in board-space units. Camera zoom is applied
    // once to the outer layer, so text and child layouts are not resized/re-measured.
    val cardWidthPx = note.width.coerceAtLeast(16f)
    val cardHeightPx = note.height.coerceAtLeast(16f)
    val cardWidthDp = with(density) { cardWidthPx.toDp() }
    val cardHeightDp = with(density) { cardHeightPx.toDp() }

    val cornerRadiusDp = 8.dp
    val headerHeightWorld = StickyNoteGeometry.headerHeightWorld(note.height)
    val headerHeightDp = with(density) { headerHeightWorld.toDp() }

    val innerPadWorld = StickyNoteGeometry.innerPaddingWorld(note.width, note.height)
    val innerPadDp = with(density) { innerPadWorld.toDp() }

    val (innerWidthWorld, innerHeightWorld) = StickyNoteGeometry.innerTextBoxWorld(note.width, note.height)
    val innerHeightPx = innerHeightWorld.coerceAtLeast(16f)

    // Compute auto-fit font size once in world dimensions (keyed on text/size, NOT camera scale)
    // so pinch-to-zoom and pan incur zero TextMeasurer binary-search recomputations.
    val baseWorldFontSp = remember(note.title, note.text, innerWidthWorld, innerHeightWorld, density.density, density.fontScale) {
        computeAutoFitStickyFontSizeSp(
            text = note.text,
            innerWidthPx = innerWidthWorld.coerceAtLeast(16f),
            innerHeightPx = innerHeightWorld.coerceAtLeast(16f),
            textMeasurer = textMeasurer,
            density = density,
            title = note.title
        )
    }
    // Font size remains constant in board space; outer camera transform scales it visually.
    val autoFitFontSp = baseWorldFontSp

    // Symmetrical gutters: 24.dp horizontal, 64.dp vertical (48.dp rotation handle + 40.dp guide stem fully inside outer Box)
    // Box center matches card center exactly, ensuring pure transform-origin: center center rotation
    val handleTargetDp = 48.dp
    val sideGutterDp = 24.dp
    val sideGutterPx = with(density) { sideGutterDp.toPx() }
    val verticalGutterDp = 64.dp
    val verticalGutterPx = with(density) { verticalGutterDp.toPx() }
    val activeSelectionHandles = isSelected && !note.isLocked && !isPanOnlyMode

    var cardCenterRoot by remember { mutableStateOf(Offset.Zero) }
    var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var isRotating by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .zIndex(if (isSelected) 10f else 0f)
            .offset {
                IntOffset(
                    (screenXPx - sideGutterPx * scale).roundToInt(),
                    (screenYPx - verticalGutterPx * scale).roundToInt()
                )
            }
            .size(cardWidthDp + sideGutterDp * 2, cardHeightDp + verticalGutterDp * 2)
            // Scale the completed note as a single layer instead of changing every
            // child's dp/sp dimensions on each zoom update.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .graphicsLayer {
                rotationZ = note.rotation
                transformOrigin = TransformOrigin.Center
            }
    ) {
        val cardGestureModifier = if (isPanOnlyMode) {
            Modifier
        } else {
            Modifier.pointerInput(note.id, note.isLocked) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var isDragging = false
                    var beganSnapshot = false
                    var totalDragPx = 0f
                    val touchSlop = viewConfiguration.touchSlop

                    do {
                        val event = awaitPointerEvent()
                        val activePointers = event.changes.filter { it.pressed }
                        if (activePointers.size >= 2 || event.changes.any { it.isConsumed }) {
                            // Yield immediately to multi-touch canvas pan/pinch-to-zoom
                            isDragging = false
                            break
                        }
                        val change = activePointers.firstOrNull() ?: break
                        val delta = change.positionChange()
                        if (!isDragging) {
                            totalDragPx += hypot(delta.x, delta.y)
                            if (totalDragPx > touchSlop) {
                                isDragging = true
                                currentOnSelect()
                                if (!note.isLocked && !beganSnapshot) {
                                    beganSnapshot = true
                                    currentOnBeginTransform()
                                }
                            }
                        }
                        if (isDragging && !note.isLocked && (delta.x != 0f || delta.y != 0f)) {
                            val angleRad = currentNoteRotation * (PI.toFloat() / 180f)
                            val cosA = cos(angleRad)
                            val sinA = sin(angleRad)
                            val screenDx = delta.x * cosA - delta.y * sinA
                            val screenDy = delta.x * sinA + delta.y * cosA
                            currentOnDragDelta(screenDx, screenDy)
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

        // Centered sub-container for the note card and 8 resize handles
        Box(
            modifier = Modifier
                .size(cardWidthDp + sideGutterDp * 2, cardHeightDp + sideGutterDp * 2)
                .align(Alignment.Center)
        ) {
            // Actual Sticky Note Card (exact world-projected bounds inside gutter; zero heavy shadow overdraw)
            Box(
                modifier = Modifier
                    .padding(sideGutterDp)
                    .fillMaxSize()
                    .then(
                        if (activeSelectionHandles) {
                            Modifier.onGloballyPositioned { coords ->
                                cardCenterRoot = coords.boundsInRoot().center
                            }
                        } else {
                            Modifier
                        }
                    )
                    .clip(RoundedCornerShape(cornerRadiusDp))
                    .background(noteBg)
                    .border(
                        width = if (isSelected && !isPanOnlyMode) 2.dp else 1.dp,
                        color = if (isSelected && !isPanOnlyMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Black.copy(alpha = 0.16f)
                        },
                        shape = RoundedCornerShape(cornerRadiusDp)
                    )
                    .then(cardGestureModifier)
                    .testTag("sticky_note_${note.id}")
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Proportional Header Bar
                val iconDp = (headerHeightDp * 0.62f).coerceIn(8.dp, 16.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(headerHeightDp)
                        .background(headerBg)
                        .padding(horizontal = (innerPadDp * 0.8f).coerceAtLeast(3.dp)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size((headerHeightDp * 0.58f).coerceIn(7.dp, 15.dp))
                            .clip(CircleShape)
                            .background(textColor.copy(alpha = 0.25f))
                            .let {
                                if (isPanOnlyMode) it else it.clickable {
                                    currentOnSelect()
                                    showPalette = !showPalette
                                }
                            }
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (note.isAspectRatioLocked && !isPanOnlyMode) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Proporción 1:1 bloqueada",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size((iconDp * 0.85f).coerceAtLeast(7.dp))
                            )
                        }
                        if (note.isPinned) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Fijada",
                                tint = textColor.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .size(iconDp)
                                    .let { if (isPanOnlyMode) it else it.clickable { onTogglePin() } }
                            )
                        }

                        if (!isPanOnlyMode) {
                            Box(
                                modifier = Modifier
                                    .size((headerHeightDp * 0.88f).coerceAtLeast(10.dp))
                                    .clip(CircleShape)
                                    .clickable { onDelete() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Eliminar nota",
                                    tint = textColor.copy(alpha = 0.72f),
                                    modifier = Modifier.size(iconDp)
                                )
                            }
                        }
                    }
                }

                // Color Palette Flyout
                if (showPalette && !isPanOnlyMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.95f))
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        colors.forEach { (hex, _) ->
                            val c = Color(android.graphics.Color.parseColor(hex))
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .clickable {
                                        onUpdateColor(hex)
                                        showPalette = false
                                    }
                            )
                        }
                    }
                }

                // Auto-Fit Content Container (adapts dynamically and stays in view at all times)
                val scrollState = rememberScrollState()
                LaunchedEffect(note.text, note.title) {
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
                        val titleStyle = TextStyle(
                            color = textColor,
                            fontSize = (autoFitFontSp.value * 1.15f).sp,
                            lineHeight = (autoFitFontSp.value * 1.32f).sp,
                            fontWeight = FontWeight.Bold
                        )
                        val canEditNote = isSelected && !note.isLocked && !isPanOnlyMode
                        if (canEditNote) {
                            BasicTextField(
                                value = note.title.orEmpty(),
                                onValueChange = { currentOnUpdateTitle(it) },
                                textStyle = titleStyle,
                                cursorBrush = SolidColor(textColor),
                                decorationBox = { inner ->
                                    if (note.title.isNullOrEmpty()) {
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
                                        if (focusState.isFocused) currentOnSelect()
                                    }
                                    .testTag("sticky_note_title_input_${note.id}")
                            )
                        } else {
                            Text(
                                text = note.title?.takeIf { it.isNotEmpty() } ?: "Título...",
                                style = if (note.title.isNullOrEmpty()) titleStyle.copy(color = textColor.copy(alpha = 0.45f)) else titleStyle,
                                modifier = Modifier.fillMaxWidth().testTag("sticky_note_title_input_${note.id}")
                            )
                        }

                        HorizontalDivider(
                            color = textColor.copy(alpha = 0.16f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )

                        val effectiveStyle = TextStyle(
                            color = textColor,
                            fontSize = autoFitFontSp,
                            lineHeight = (autoFitFontSp.value * 1.18f).sp,
                            fontWeight = FontWeight.Medium
                        )
                        val bodyModifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = with(density) { (innerHeightPx * 0.55f).toDp() })
                            .semantics {
                                set(StickyNoteComputedFontSpKey, autoFitFontSp.value)
                            }
                            .testTag("sticky_note_input_${note.id}")
                        if (canEditNote) {
                            BasicTextField(
                                value = note.text,
                                onValueChange = onUpdateText,
                                textStyle = effectiveStyle,
                                cursorBrush = SolidColor(textColor),
                                decorationBox = { inner ->
                                    if (note.text.isEmpty()) {
                                        Text(
                                            text = "Escribe el contenido...",
                                            style = effectiveStyle.copy(color = textColor.copy(alpha = 0.42f))
                                        )
                                    }
                                    inner()
                                },
                                modifier = bodyModifier.onFocusChanged { focusState ->
                                    if (focusState.isFocused) currentOnSelect()
                                }
                            )
                        } else {
                            Text(
                                text = note.text.ifEmpty { "Escribe el contenido..." },
                                style = if (note.text.isEmpty()) effectiveStyle.copy(color = textColor.copy(alpha = 0.42f)) else effectiveStyle,
                                modifier = bodyModifier
                            )
                        }
                    }
                }
            }
        }

        // 8 Mobile Touch Resize Handles (Corners + Edges), each 48.dp x 48.dp touch target
        if (activeSelectionHandles) {
            val handles = listOf(
                StickyNoteResizeHandle.TOP_LEFT to Alignment.TopStart,
                StickyNoteResizeHandle.TOP to Alignment.TopCenter,
                StickyNoteResizeHandle.TOP_RIGHT to Alignment.TopEnd,
                StickyNoteResizeHandle.RIGHT to Alignment.CenterEnd,
                StickyNoteResizeHandle.BOTTOM_RIGHT to Alignment.BottomEnd,
                StickyNoteResizeHandle.BOTTOM to Alignment.BottomCenter,
                StickyNoteResizeHandle.BOTTOM_LEFT to Alignment.BottomStart,
                StickyNoteResizeHandle.LEFT to Alignment.CenterStart
            )

            handles.forEach { (handle, alignment) ->
                val isCorner = handle == StickyNoteResizeHandle.TOP_LEFT ||
                    handle == StickyNoteResizeHandle.TOP_RIGHT ||
                    handle == StickyNoteResizeHandle.BOTTOM_RIGHT ||
                    handle == StickyNoteResizeHandle.BOTTOM_LEFT
                val isHorizontalEdge = handle == StickyNoteResizeHandle.TOP ||
                    handle == StickyNoteResizeHandle.BOTTOM

                Box(
                    modifier = Modifier
                        .align(alignment)
                        .size(handleTargetDp)
                        .pointerInput(note.id, handle, note.isLocked) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var beganResize = false
                                do {
                                    val event = awaitPointerEvent()
                                    val activePointers = event.changes.filter { it.pressed }
                                    if (activePointers.size >= 2 || event.changes.any { it.isConsumed }) {
                                        break
                                    }
                                    val change = activePointers.firstOrNull() ?: break
                                    val delta = change.positionChange()
                                    if (delta.x != 0f || delta.y != 0f) {
                                        if (!beganResize) {
                                            beganResize = true
                                            currentOnSelect()
                                            currentOnBeginTransform()
                                        }
                                        currentOnResizeHandle(handle, delta.x, delta.y)
                                        change.consume()
                                    }
                                } while (event.changes.any { it.pressed })
                                if (beganResize) {
                                    currentOnEndTransform()
                                }
                            }
                        }
                        .testTag("sticky_note_handle_${handle.name}_${note.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    val accentColor = if (note.isAspectRatioLocked) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    when {
                        isCorner -> {
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .shadow(3.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .border(2.5.dp, accentColor, CircleShape)
                            )
                        }
                        isHorizontalEdge -> {
                            Box(
                                modifier = Modifier
                                    .size(width = 22.dp, height = 7.dp)
                                    .shadow(2.dp, RoundedCornerShape(4.dp))
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White)
                                    .border(1.8.dp, accentColor, RoundedCornerShape(4.dp))
                            )
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .size(width = 7.dp, height = 22.dp)
                                    .shadow(2.dp, RoundedCornerShape(4.dp))
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White)
                                    .border(1.8.dp, accentColor, RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }
            }
        }
        } // End centered sub-container for note card & 8 resize handles

        // Protruding Rotation Handle placed directly inside the Outer Box (unclipped 0..64.dp top gutter)
        if (activeSelectionHandles) {
            // 1. Anchor tick centered on the actual top edge of the card (y = 64.dp).
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 60.5.dp)
                    .size(7.dp)
                    .shadow(2.dp, RoundedCornerShape(2.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )

            // 2. Guide stem running from handle center (y = 24.dp) to card top edge (y = 64.dp).
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 24.dp)
                    .width(2.dp)
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )

            // 3. Rotation handle (48x48.dp touch target) positioned cleanly inside Outer Box top gutter (y = 0..48.dp).
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 0.dp)
                    .zIndex(2f)
                    .size(handleTargetDp)
                    .onGloballyPositioned { handleCoords = it }
                    .pointerInput(note.id, note.isLocked) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            currentOnSelect()
                            var beganRotation = false

                            val fallbackCenterX = camera.worldToScreenX(note.x + note.width / 2f)
                            val fallbackCenterY = camera.worldToScreenY(note.y + note.height / 2f)

                            do {
                                val event = awaitPointerEvent()
                                val activePointers = event.changes.filter { it.pressed }
                                if (activePointers.size >= 2 || event.changes.any { it.isConsumed }) {
                                    break
                                }
                                val change = activePointers.firstOrNull() ?: break
                                val moveDelta = change.positionChange()
                                if (moveDelta.x != 0f || moveDelta.y != 0f) {
                                    if (!beganRotation) {
                                        beganRotation = true
                                        isRotating = true
                                        currentOnBeginTransform()
                                    }
                                    val pointerRoot = handleCoords?.localToRoot(change.position)
                                    val pX = pointerRoot?.x ?: (fallbackCenterX + change.position.x)
                                    val pY = pointerRoot?.y ?: (fallbackCenterY + change.position.y)

                                    val cX = if (cardCenterRoot != Offset.Zero) cardCenterRoot.x else fallbackCenterX
                                    val cY = if (cardCenterRoot != Offset.Zero) cardCenterRoot.y else fallbackCenterY

                                    val targetDegrees = StickyNoteGeometry.calculateRotationDegrees(
                                        centerX = cX,
                                        centerY = cY,
                                        pointerX = pX,
                                        pointerY = pY,
                                        snapThresholdDegrees = 3f
                                    )
                                    currentOnRotate(targetDegrees)
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            isRotating = false
                            if (beganRotation) {
                                currentOnEndTransform()
                            }
                        }
                    }
                    .semantics {
                        contentDescription = "Girar nota adhesiva"
                    }
                    .testTag("sticky_note_rotation_handle_${note.id}"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            if (isRotating) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(x = 38.dp, y = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${note.rotation.roundToInt()}°",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            }
        }
    }
}
