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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
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
    density: Density
): TextUnit {
    val availW = innerWidthPx.roundToInt().coerceAtLeast(16)
    val availH = innerHeightPx.roundToInt().coerceAtLeast(16)
    val displayText = text.ifEmpty { "Escribe tu idea aquí..." }

    val minPx = (6f * density.density.coerceAtLeast(1f) * 0.65f).coerceAtLeast(4f)
    val proportionalMaxPx = (min(innerWidthPx, innerHeightPx) * 0.44f).coerceAtLeast(minPx + 1f)
    val deterministicFitPx = with(density) {
        val wWorld = innerWidthPx / density.density
        val hWorld = innerHeightPx / density.density
        StickyNoteGeometry.estimateAutoFitFontSizeWorld(
            text = displayText,
            innerWidthWorld = wWorld,
            innerHeightWorld = hWorld
        ).sp.toPx()
    }
    val maxPx = min(proportionalMaxPx, (deterministicFitPx * 1.15f).coerceAtLeast(minPx + 0.5f))

    val longestToken = displayText
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .maxByOrNull { it.length }
        .orEmpty()

    fun fitsAtPx(fontPx: Float): Boolean {
        val spVal = with(density) { fontPx.toSp() }
        val style = TextStyle(
            fontSize = spVal,
            lineHeight = (spVal.value * 1.18f).sp,
            fontWeight = FontWeight.Medium
        )
        if (longestToken.isNotEmpty()) {
            val wordMeasure = textMeasurer.measure(
                text = longestToken,
                style = style,
                constraints = Constraints(maxWidth = availW),
                maxLines = 1,
                softWrap = false
            )
            if (wordMeasure.hasVisualOverflow && fontPx > minPx * 1.35f) return false
        }
        val measured = textMeasurer.measure(
            text = displayText,
            style = style,
            constraints = Constraints(maxWidth = availW),
            softWrap = true
        )
        return measured.size.height <= availH
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
    onUpdateColor: (String) -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit = {},
    onBeginTransform: () -> Unit = {},
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
    val cardWidthPx = camera.worldLengthToScreenPx(note.width).coerceAtLeast(16f)
    val cardHeightPx = camera.worldLengthToScreenPx(note.height).coerceAtLeast(16f)

    val cardWidthDp = with(density) { cardWidthPx.toDp() }
    val cardHeightDp = with(density) { cardHeightPx.toDp() }

    val cornerRadiusDp = with(density) { (8f * scale).coerceIn(3f, 18f).toDp() }
    val headerHeightWorld = StickyNoteGeometry.headerHeightWorld(note.height)
    val headerHeightPx = camera.worldLengthToScreenPx(headerHeightWorld)
    val headerHeightDp = with(density) { headerHeightPx.toDp() }

    val innerPadWorld = StickyNoteGeometry.innerPaddingWorld(note.width, note.height)
    val innerPadPx = camera.worldLengthToScreenPx(innerPadWorld)
    val innerPadDp = with(density) { innerPadPx.toDp() }

    val (innerWidthWorld, innerHeightWorld) = StickyNoteGeometry.innerTextBoxWorld(note.width, note.height)
    val innerWidthPx = camera.worldLengthToScreenPx(innerWidthWorld).coerceAtLeast(16f)
    val innerHeightPx = camera.worldLengthToScreenPx(innerHeightWorld).coerceAtLeast(16f)

    val autoFitFontSp = remember(note.text, innerWidthPx, innerHeightPx, density.density, density.fontScale) {
        computeAutoFitStickyFontSizeSp(
            text = note.text,
            innerWidthPx = innerWidthPx,
            innerHeightPx = innerHeightPx,
            textMeasurer = textMeasurer,
            density = density
        )
    }

    // Symmetrical gutters: 24.dp horizontal, 56.dp vertical (for 28.dp stem + 48.dp rotation handle)
    // Box center matches card center exactly, ensuring pure transform-origin: center center rotation
    val handleTargetDp = 48.dp
    val sideGutterDp = 24.dp
    val sideGutterPx = with(density) { sideGutterDp.toPx() }
    val verticalGutterDp = 56.dp
    val verticalGutterPx = with(density) { verticalGutterDp.toPx() }
    val activeSelectionHandles = isSelected && !note.isLocked && !isPanOnlyMode

    var cardCenterRoot by remember { mutableStateOf(Offset.Zero) }
    var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = modifier
            .zIndex(if (isSelected) 10f else 0f)
            .offset {
                IntOffset(
                    (screenXPx - sideGutterPx).roundToInt(),
                    (screenYPx - verticalGutterPx).roundToInt()
                )
            }
            .size(cardWidthDp + sideGutterDp * 2, cardHeightDp + verticalGutterDp * 2)
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
                }
            }
        }

        // Centered sub-container for the note card and 8 resize handles
        Box(
            modifier = Modifier
                .size(cardWidthDp + sideGutterDp * 2, cardHeightDp + sideGutterDp * 2)
                .align(Alignment.Center)
        ) {
            // Actual Sticky Note Card (exact world-projected bounds inside gutter)
            Box(
                modifier = Modifier
                    .padding(sideGutterDp)
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        cardCenterRoot = coords.boundsInRoot().center
                    }
                    .shadow(
                        elevation = if (isSelected && !isPanOnlyMode) 12.dp else 5.dp,
                        shape = RoundedCornerShape(cornerRadiusDp)
                    )
                    .clip(RoundedCornerShape(cornerRadiusDp))
                    .background(noteBg)
                    .border(
                        width = if (isSelected && !isPanOnlyMode) 2.dp else 0.5.dp,
                        color = if (isSelected && !isPanOnlyMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Black.copy(alpha = 0.14f)
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
                        note.title?.takeIf { it.isNotBlank() }?.let { title ->
                            Text(
                                text = title,
                                color = textColor,
                                fontSize = (autoFitFontSp.value * 1.18f).sp,
                                lineHeight = (autoFitFontSp.value * 1.34f).sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp)
                            )
                        }

                        val effectiveStyle = TextStyle(
                            color = textColor,
                            fontSize = autoFitFontSp,
                            lineHeight = (autoFitFontSp.value * 1.18f).sp,
                            fontWeight = FontWeight.Medium
                        )
                        BasicTextField(
                            value = note.text,
                            onValueChange = onUpdateText,
                            enabled = !note.isLocked && !isPanOnlyMode,
                            readOnly = note.isLocked || isPanOnlyMode,
                            textStyle = effectiveStyle,
                            cursorBrush = SolidColor(textColor),
                            decorationBox = { inner ->
                                if (note.text.isEmpty()) {
                                    Text(
                                        text = "Escribe tu idea aquí...",
                                        style = effectiveStyle.copy(color = textColor.copy(alpha = 0.42f))
                                    )
                                }
                                inner()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = with(density) { innerHeightPx.toDp() })
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused && !isPanOnlyMode) {
                                        currentOnSelect()
                                    }
                                }
                                .semantics {
                                    set(StickyNoteComputedFontSpKey, autoFitFontSp.value)
                                }
                                .testTag("sticky_note_input_${note.id}")
                        )
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

        // Protruding Rotation Handle connected via guide stem line from top-center edge of the card
        if (activeSelectionHandles) {
            // 1. Anchor tick sits on the actual top edge of the card. The inner selection box is vertically centered inside the larger rotation-gutter container.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 20.5.dp)
                    .size(7.dp)
                    .shadow(2.dp, RoundedCornerShape(2.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )

            // 2. Guide stem runs outward from the card top edge to the rotation handle.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-4).dp)
                    .width(2.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )

            // 3. Rotation handle centered 28.dp above the card, outside its bounds.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-28).dp)
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
                        .size(14.dp)
                        .shadow(3.dp, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, Color.White, CircleShape)
                )
            }
        }
    }
}
}
