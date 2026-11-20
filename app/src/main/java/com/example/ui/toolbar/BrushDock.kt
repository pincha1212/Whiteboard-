package com.example.ui.toolbar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.BrushSlot
import com.example.model.StrokeBrushType
import com.example.model.ToolType
import com.example.viewmodel.WhiteboardViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dock de pinceles conectado al [WhiteboardViewModel] que observa directamente
 * la lista de 3 [BrushSlot] configurables, mostrando solo el color del pincel
 * y desplegando el menú vertical de iconos al mantener presionado.
 */
@Composable
fun BrushDock(
    viewModel: WhiteboardViewModel,
    isVertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasPendingPlacement = uiState.pendingShapeType != null || uiState.pendingStickyNoteColor != null
    val isBrushToolActive = !hasPendingPlacement && (
        uiState.currentTool == ToolType.PEN ||
            uiState.currentTool == ToolType.FINE_MARKER ||
            uiState.currentTool == ToolType.HIGHLIGHTER
        )

    BrushDock(
        brushSlots = viewModel.brushSlots,
        activeBrushSlotIndex = uiState.activeBrushSlotIndex,
        isBrushToolActive = isBrushToolActive,
        autoShapeRecognition = uiState.autoShapeRecognition,
        onSelectBrushSlot = { index -> viewModel.selectBrushSlot(index) },
        onUpdateBrushSlot = { index, brushType, hex, width, isRainbow ->
            viewModel.updateBrushSlot(
                slotIndex = index,
                brushType = brushType,
                colorHex = hex,
                strokeWidth = width,
                isRainbow = isRainbow
            )
        },
        onToggleAutoShape = { viewModel.toggleAutoShapeRecognition() },
        isVertical = isVertical,
        modifier = modifier
    )
}

/**
 * Componente visual que renderiza los tres [BrushSlot] en el dock mostrando únicamente
 * la representación coloreada de cada pincel y abriendo al mantener presionado el menú
 * vertical iconográfico de tipo, color y grosor.
 */
@Composable
fun BrushDock(
    brushSlots: List<BrushSlot>,
    activeBrushSlotIndex: Int,
    isBrushToolActive: Boolean,
    autoShapeRecognition: Boolean,
    onSelectBrushSlot: (Int) -> Unit,
    onUpdateBrushSlot: (Int, StrokeBrushType?, String?, Float?, Boolean?) -> Unit,
    onToggleAutoShape: () -> Unit,
    isVertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expandedSlotIndex by remember { mutableStateOf<Int?>(null) }
    val haptic = LocalHapticFeedback.current
    val fallbackInk = MaterialTheme.colorScheme.onSurface

    val slotItems: @Composable () -> Unit = {
        brushSlots.take(3).forEachIndexed { index, slot ->
            val isSlotSelected = isBrushToolActive && activeBrushSlotIndex == index
            val slotColor = remember(slot.colorHex, fallbackInk) {
                parseDockColorSafe(slot.colorHex, fallbackInk)
            }
            val iconTintColor = if (slot.isRainbow) Color(0xFFEC4899) else slotColor
            val slotLabel = when (slot.brushType) {
                StrokeBrushType.FOUNTAIN_PEN -> "Lápiz"
                StrokeBrushType.FINE_MARKER -> "Marcador"
                StrokeBrushType.HIGHLIGHTER -> "Resaltador"
            }

            Box {
                BrushSlotButton(
                    isSelected = isSlotSelected,
                    description = "$slotLabel ${index + 1}. Mantén presionado para abrir ajustes",
                    onClick = { onSelectBrushSlot(index) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelectBrushSlot(index)
                        expandedSlotIndex = index
                    },
                    testTag = "dock_brush_slot_$index"
                ) {
                    when (slot.brushType) {
                        StrokeBrushType.FOUNTAIN_PEN -> PhysicalFountainPenIcon(
                            inkColor = iconTintColor,
                            modifier = Modifier.size(width = 26.dp, height = 38.dp)
                        )
                        StrokeBrushType.FINE_MARKER -> PhysicalMarkerIcon(
                            markerColor = iconTintColor,
                            modifier = Modifier.size(width = 26.dp, height = 38.dp)
                        )
                        StrokeBrushType.HIGHLIGHTER -> PhysicalHighlighterIcon(
                            highlighterColor = iconTintColor,
                            modifier = Modifier.size(width = 26.dp, height = 38.dp)
                        )
                    }
                }

                BrushSlotDropdownMenu(
                    expanded = expandedSlotIndex == index,
                    onDismiss = { expandedSlotIndex = null },
                    slot = slot,
                    parsedColor = slotColor,
                    autoShapeRecognition = autoShapeRecognition,
                    onChangeBrushType = { newType ->
                        onUpdateBrushSlot(index, newType, null, null, null)
                    },
                    onUpdateColor = { hex, isRainbow ->
                        onUpdateBrushSlot(index, null, hex, null, isRainbow)
                    },
                    onUpdateWidth = { width ->
                        onUpdateBrushSlot(index, null, null, width, null)
                    },
                    onToggleAutoShape = onToggleAutoShape
                )
            }
        }
    }

    if (isVertical) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier.testTag("brush_dock")
        ) {
            slotItems()
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = modifier.testTag("brush_dock")
        ) {
            slotItems()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrushSlotButton(
    isSelected: Boolean,
    description: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    testTag: String,
    iconContent: @Composable () -> Unit
) {
    val triangleColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    }

    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .defaultMinSize(minWidth = 42.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else Color.Transparent
            )
            .border(
                width = if (isSelected) 1.4.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .semantics {
                contentDescription = description
                selected = isSelected
            }
            .padding(top = 4.dp)
            .testTag(testTag)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            iconContent()
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
                    .size(width = 14.dp, height = 2.5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        // Corner triangle indicator signaling long-press opens the settings selector
        Canvas(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 3.dp, bottom = 3.dp)
                .size(7.dp)
        ) {
            val tri = Path().apply {
                moveTo(size.width, size.height)
                lineTo(size.width, 0f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path = tri, color = triangleColor)
        }
    }
}

@Composable
internal fun BrushSlotDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    slot: BrushSlot,
    parsedColor: Color,
    autoShapeRecognition: Boolean,
    onChangeBrushType: (StrokeBrushType) -> Unit,
    onUpdateColor: (String, isRainbow: Boolean) -> Unit,
    onUpdateWidth: (Float) -> Unit,
    onToggleAutoShape: () -> Unit
) {
    val activeInkTint = if (slot.isRainbow) Color(0xFFEC4899) else parsedColor
    val surfaceTrackColor = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        scrollState = rememberScrollState(),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .width(176.dp)
            .heightIn(max = 460.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .testTag("dock_brush_slot_dropdown")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // 1. Live Stroke Wave Preview (no labels)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    val h = size.height
                    val w = size.width
                    val path = Path().apply {
                        moveTo(0f, h * 0.56f)
                        cubicTo(w * 0.25f, h * 0.22f, w * 0.45f, h * 0.85f, w * 0.72f, h * 0.42f)
                        quadraticTo(w * 0.86f, h * 0.22f, w, h * 0.52f)
                    }
                    val isHighlighter = slot.brushType == StrokeBrushType.HIGHLIGHTER
                    val alpha = if (isHighlighter) 0.45f else 1f
                    val previewWidth = when (slot.brushType) {
                        StrokeBrushType.HIGHLIGHTER -> (slot.strokeWidth * 1.4f).coerceIn(10f, 24f)
                        StrokeBrushType.FINE_MARKER -> (slot.strokeWidth * 0.7f).coerceIn(2f, 10f)
                        StrokeBrushType.FOUNTAIN_PEN -> slot.strokeWidth.coerceIn(2f, 22f)
                    }
                    if (slot.isRainbow && !isHighlighter) {
                        drawPath(
                            path = path,
                            brush = Brush.horizontalGradient(DockRainbowColors),
                            style = Stroke(width = previewWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    } else {
                        drawPath(
                            path = path,
                            color = parsedColor.copy(alpha = alpha),
                            style = Stroke(width = previewWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }
            }

            // 2. Icon-Only Brush Type Selector (Lápiz / Marcador / Resaltador)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val brushTypes = listOf(
                    StrokeBrushType.FOUNTAIN_PEN to "Lápiz",
                    StrokeBrushType.FINE_MARKER to "Marcador",
                    StrokeBrushType.HIGHLIGHTER to "Resaltador"
                )
                brushTypes.forEach { (type, accessibilityLabel) ->
                    val isTypeSelected = slot.brushType == type
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isTypeSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            )
                            .border(
                                width = if (isTypeSelected) 1.5.dp else 1.dp,
                                color = if (isTypeSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(role = Role.Button) { onChangeBrushType(type) }
                            .semantics {
                                contentDescription = accessibilityLabel
                                selected = isTypeSelected
                            }
                            .testTag("slot_type_${type.name}")
                    ) {
                        when (type) {
                            StrokeBrushType.FOUNTAIN_PEN -> PhysicalFountainPenIcon(
                                inkColor = activeInkTint,
                                modifier = Modifier.size(width = 22.dp, height = 34.dp)
                            )
                            StrokeBrushType.FINE_MARKER -> PhysicalMarkerIcon(
                                markerColor = activeInkTint,
                                modifier = Modifier.size(width = 22.dp, height = 34.dp)
                            )
                            StrokeBrushType.HIGHLIGHTER -> PhysicalHighlighterIcon(
                                highlighterColor = activeInkTint,
                                modifier = Modifier.size(width = 22.dp, height = 34.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

            // 3. 3x3 Color Swatch Matrix (8 solid colors + Rainbow, zero words)
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val colorGridRows = listOf(
                    DockPenColorPalette.subList(0, 3),
                    DockPenColorPalette.subList(3, 6),
                    DockPenColorPalette.subList(6, 8)
                )
                colorGridRows.forEachIndexed { rowIdx, rowColors ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        rowColors.forEach { option ->
                            val swatchColor = remember(option.hex) { parseDockColorSafe(option.hex, Color.Black) }
                            val isColorSelected = slot.colorHex.equals(option.hex, ignoreCase = true) && !slot.isRainbow
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isColorSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else Color.Transparent
                                    )
                                    .clickable(role = Role.Button) { onUpdateColor(option.hex, false) }
                                    .semantics {
                                        contentDescription = option.label
                                        selected = isColorSelected
                                    }
                                    .testTag("dock_color_${option.label}")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .border(
                                            width = if (isColorSelected) 2.2.dp else 1.dp,
                                            color = if (isColorSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            shape = CircleShape
                                        )
                                ) {
                                    if (isColorSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(if (option.hex.equals("#FACC15", true)) Color(0xFF0F172A) else Color.White)
                                        )
                                    }
                                }
                            }
                        }

                        // Last cell of 3rd row: Rainbow swatch
                        if (rowIdx == 2) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (slot.isRainbow) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else Color.Transparent
                                    )
                                    .clickable(role = Role.Button) { onUpdateColor(slot.colorHex, true) }
                                    .semantics {
                                        contentDescription = "Tinta arcoíris"
                                        selected = slot.isRainbow
                                    }
                                    .testTag("dock_color_Arcoiris")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(Brush.sweepGradient(DockRainbowColors))
                                        .border(
                                            width = if (slot.isRainbow) 2.2.dp else 1.dp,
                                            color = if (slot.isRainbow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            shape = CircleShape
                                        )
                                ) {
                                    if (slot.isRainbow) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

            // 4. Visual Proportional Stroke Width Dots (zero text)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val dotSizesDp = listOf(5.dp, 8.dp, 12.dp, 16.dp)
                DockPenWidthOptions.forEachIndexed { idx, option ->
                    val isSelected = abs(slot.strokeWidth - option.width) < 1f
                    val dotSize = dotSizesDp.getOrElse(idx) { 10.dp }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable(role = Role.Button) { onUpdateWidth(option.width) }
                            .semantics {
                                contentDescription = "Grosor ${option.width.roundToInt()} px"
                                selected = isSelected
                            }
                            .testTag("dock_width_${option.label}")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(dotSize)
                                .clip(CircleShape)
                                .background(
                                    if (slot.isRainbow) Color(0xFFEC4899)
                                    else parsedColor
                                )
                                .border(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
                        )
                    }
                }
            }

            // 5. Custom Tapered Thickness Scrubber Bar (bespoke icon-free continuous control)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 8.dp)
                    .pointerInput(slot.id, onUpdateWidth) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onUpdateWidth(2f + frac * 22f)
                        }
                    }
                    .pointerInput(slot.id, onUpdateWidth) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onUpdateWidth(2f + frac * 22f)
                        }
                    }
                    .semantics {
                        contentDescription = "Ajustar grosor continuo (${slot.strokeWidth.roundToInt()} px)"
                    }
                    .testTag("dock_pen_width_slider")
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                ) {
                    val w = size.width
                    val cy = size.height / 2f
                    val frac = ((slot.strokeWidth.coerceIn(2f, 24f) - 2f) / 22f).coerceIn(0f, 1f)
                    val leftR = 1.8.dp.toPx()
                    val rightR = 6.5.dp.toPx()

                    // Tapered track wedge (thin left -> thick right)
                    val trackPath = Path().apply {
                        moveTo(0f, cy - leftR)
                        lineTo(w, cy - rightR)
                        lineTo(w, cy + rightR)
                        lineTo(0f, cy + leftR)
                        close()
                    }
                    drawPath(trackPath, color = surfaceTrackColor)

                    val thumbX = w * frac
                    val activeR = leftR + (rightR - leftR) * frac
                    val activePath = Path().apply {
                        moveTo(0f, cy - leftR)
                        lineTo(thumbX, cy - activeR)
                        lineTo(thumbX, cy + activeR)
                        lineTo(0f, cy + leftR)
                        close()
                    }
                    if (slot.isRainbow) {
                        drawPath(activePath, brush = Brush.horizontalGradient(DockRainbowColors))
                    } else {
                        drawPath(activePath, color = activeInkTint.copy(alpha = 0.85f))
                    }

                    // Crisp thumb indicator
                    drawCircle(
                        color = surfaceColor,
                        radius = 8.dp.toPx(),
                        center = Offset(thumbX, cy)
                    )
                    drawCircle(
                        color = primaryColor,
                        radius = 6.dp.toPx(),
                        center = Offset(thumbX, cy)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

            // 6. Icon-Only Auto-Shape Recognition Toggle
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (autoShapeRecognition) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                    .border(
                        width = if (autoShapeRecognition) 1.4.dp else 1.dp,
                        color = if (autoShapeRecognition) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable(role = Role.Switch, onClick = onToggleAutoShape)
                    .semantics {
                        contentDescription = if (autoShapeRecognition) {
                            "Formas automáticas activadas"
                        } else {
                            "Formas automáticas desactivadas"
                        }
                        selected = autoShapeRecognition
                    }
                    .testTag("dock_menu_toggle_auto_shape")
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = if (autoShapeRecognition) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                PhysicalShapesIcon(
                    tint = if (autoShapeRecognition) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                if (autoShapeRecognition) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}
