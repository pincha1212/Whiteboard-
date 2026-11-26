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
import com.example.ui.theme.StudioIcons
import com.example.viewmodel.WhiteboardViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dock de 3 pinceles configurables conectado al [WhiteboardViewModel].
 * Tocar un slot inactivo lo activa; volver a tocar el activo o mantener presionado abre sus ajustes.
 */
@Composable
fun BrushDock(
    viewModel: WhiteboardViewModel,
    isVertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isBrushToolActive = uiState.currentTool == ToolType.PENCIL ||
        uiState.currentTool == ToolType.PEN ||
        uiState.currentTool == ToolType.TECHNICAL_INK ||
        uiState.currentTool == ToolType.FINE_MARKER ||
        uiState.currentTool == ToolType.CHISEL_MARKER ||
        uiState.currentTool == ToolType.PAINT_BRUSH ||
        uiState.currentTool == ToolType.WATERCOLOR ||
        uiState.currentTool == ToolType.AIRBRUSH ||
        uiState.currentTool == ToolType.HIGHLIGHTER

    BrushDock(
        brushSlots = viewModel.brushSlots,
        activeBrushSlotIndex = uiState.activeBrushSlotIndex,
        isBrushToolActive = isBrushToolActive,
        isBrushPopoverOpen = uiState.isBrushPopoverOpen,
        autoShapeRecognition = uiState.autoShapeRecognition,
        onSelectBrushSlot = { index -> viewModel.selectBrushSlot(index) },
        onDismissBrushPopover = { viewModel.dismissBrushPopover() },
        onUpdateBrushSlot = { index, brushType, width ->
            viewModel.updateBrushSlot(
                slotIndex = index,
                brushType = brushType,
                strokeWidth = width
            )
        },
        onUpdateSlotOpacity = { index, opacity ->
            viewModel.updateBrushSlot(slotIndex = index, opacity = opacity)
        },
        onOpenBrushStudio = { index -> viewModel.openBrushStudio(index) },
        onToggleAutoShape = { viewModel.toggleAutoShapeRecognition() },
        isVertical = isVertical,
        modifier = modifier
    )
}

/**
 * Renderiza los 3 slots de pincel y su popover de ajustes de punta, grosor, opacidad,
 * auto-formas y acceso al Estudio de Pincel.
 */
@Composable
fun BrushDock(
    brushSlots: List<BrushSlot>,
    activeBrushSlotIndex: Int,
    isBrushToolActive: Boolean,
    isBrushPopoverOpen: Boolean = false,
    autoShapeRecognition: Boolean,
    onSelectBrushSlot: (Int) -> Unit,
    onDismissBrushPopover: () -> Unit = {},
    onUpdateBrushSlot: (Int, StrokeBrushType?, Float?) -> Unit,
    onUpdateSlotOpacity: (Int, Float) -> Unit = { _, _ -> },
    onOpenBrushStudio: (Int) -> Unit = {},
    onToggleAutoShape: () -> Unit,
    isVertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    var longPressedSlotIndex by remember { mutableStateOf<Int?>(null) }
    val haptic = LocalHapticFeedback.current
    val fallbackInk = MaterialTheme.colorScheme.onSurface

    val slotItems: @Composable () -> Unit = {
        brushSlots.take(3).forEachIndexed { index, slot ->
            val isSlotSelected = isBrushToolActive && activeBrushSlotIndex == index
            val isMenuExpanded = (isSlotSelected && isBrushPopoverOpen) || longPressedSlotIndex == index
            val slotColor = remember(slot.colorHex, fallbackInk) {
                parseDockColorSafe(slot.colorHex, fallbackInk)
            }
            val iconTintColor = if (slot.isRainbow) Color(0xFFEC4899) else slotColor
            val slotLabel = slot.brushType.displayName

            Box {
                BrushSlotButton(
                    isSelected = isSlotSelected,
                    description = "$slotLabel ${index + 1}. Toca de nuevo o mantén presionado para ajustes",
                    onClick = {
                        longPressedSlotIndex = null
                        onSelectBrushSlot(index)
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!isSlotSelected) {
                            onSelectBrushSlot(index)
                            longPressedSlotIndex = index
                        } else if (!isBrushPopoverOpen) {
                            onSelectBrushSlot(index)
                        }
                    },
                    testTag = "dock_brush_slot_$index"
                ) {
                    RenderBrushSlotPhysicalIcon(
                        brushType = slot.brushType,
                        tintColor = iconTintColor,
                        modifier = Modifier.size(width = 24.dp, height = 36.dp)
                    )
                }

                BrushSlotDropdownMenu(
                    expanded = isMenuExpanded,
                    onDismiss = {
                        longPressedSlotIndex = null
                        onDismissBrushPopover()
                    },
                    slot = slot,
                    parsedColor = slotColor,
                    autoShapeRecognition = autoShapeRecognition,
                    onChangeBrushType = { newType ->
                        onUpdateBrushSlot(index, newType, null)
                    },
                    onUpdateWidth = { width ->
                        onUpdateBrushSlot(index, null, width)
                    },
                    onUpdateOpacity = { opacity ->
                        onUpdateSlotOpacity(index, opacity)
                    },
                    onOpenBrushStudio = {
                        longPressedSlotIndex = null
                        onDismissBrushPopover()
                        onOpenBrushStudio(index)
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

@Composable
private fun RenderBrushSlotPhysicalIcon(
    brushType: StrokeBrushType,
    tintColor: Color,
    modifier: Modifier = Modifier
) {
    when (brushType) {
        StrokeBrushType.PENCIL -> PhysicalPencilIcon(graphiteColor = tintColor, modifier = modifier)
        StrokeBrushType.FOUNTAIN_PEN -> PhysicalFountainPenIcon(inkColor = tintColor, modifier = modifier)
        StrokeBrushType.TECHNICAL_INK -> PhysicalTechnicalInkIcon(inkColor = tintColor, modifier = modifier)
        StrokeBrushType.FINE_MARKER -> PhysicalMarkerIcon(markerColor = tintColor, modifier = modifier)
        StrokeBrushType.CHISEL_MARKER -> PhysicalChiselMarkerIcon(markerColor = tintColor, modifier = modifier)
        StrokeBrushType.PAINT_BRUSH -> PhysicalBrushIcon(paintColor = tintColor, modifier = modifier)
        StrokeBrushType.WATERCOLOR -> PhysicalWatercolorIcon(pigmentColor = tintColor, modifier = modifier)
        StrokeBrushType.AIRBRUSH -> PhysicalAirbrushIcon(sprayColor = tintColor, modifier = modifier)
        StrokeBrushType.HIGHLIGHTER -> PhysicalHighlighterIcon(highlighterColor = tintColor, modifier = modifier)
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
            .defaultMinSize(minWidth = 44.dp, minHeight = 48.dp)
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
    onUpdateWidth: (Float) -> Unit,
    onUpdateOpacity: (Float) -> Unit,
    onOpenBrushStudio: () -> Unit = {},
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
            .width(192.dp)
            .heightIn(max = 520.dp)
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
            // 1. Live Stroke Wave Preview
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
                    val alpha = (if (isHighlighter) minOf(slot.opacity, 0.45f) else slot.opacity).coerceIn(0.08f, 1f)
                    val previewWidth = when (slot.brushType) {
                        StrokeBrushType.HIGHLIGHTER -> (slot.strokeWidth * 1.4f).coerceIn(10f, 24f)
                        StrokeBrushType.CHISEL_MARKER -> (slot.strokeWidth * 1.15f).coerceIn(4f, 22f)
                        StrokeBrushType.TECHNICAL_INK -> (slot.strokeWidth * 0.75f).coerceIn(2f, 12f)
                        StrokeBrushType.FINE_MARKER -> (slot.strokeWidth * 0.85f).coerceIn(3f, 16f)
                        StrokeBrushType.PAINT_BRUSH -> (slot.strokeWidth * 1.25f).coerceIn(6f, 24f)
                        StrokeBrushType.WATERCOLOR -> (slot.strokeWidth * 1.35f).coerceIn(6f, 26f)
                        StrokeBrushType.AIRBRUSH -> (slot.strokeWidth * 1.6f).coerceIn(8f, 26f)
                        StrokeBrushType.PENCIL -> (slot.strokeWidth * 0.75f).coerceIn(2f, 12f)
                        StrokeBrushType.FOUNTAIN_PEN -> slot.strokeWidth.coerceIn(2f, 20f)
                    }
                    if (slot.isRainbow && !isHighlighter) {
                        drawPath(
                            path = path,
                            brush = Brush.horizontalGradient(DockRainbowColors),
                            alpha = alpha,
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

            // 2. 3x3 Brush Type Grid (9 Sketchbook brushes)
            val brushGridRows = listOf(
                listOf(StrokeBrushType.PENCIL, StrokeBrushType.FOUNTAIN_PEN, StrokeBrushType.TECHNICAL_INK),
                listOf(StrokeBrushType.FINE_MARKER, StrokeBrushType.CHISEL_MARKER, StrokeBrushType.HIGHLIGHTER),
                listOf(StrokeBrushType.PAINT_BRUSH, StrokeBrushType.WATERCOLOR, StrokeBrushType.AIRBRUSH)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                brushGridRows.forEach { rowTypes ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        rowTypes.forEach { type ->
                            val isTypeSelected = slot.brushType == type
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
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
                                        contentDescription = type.displayName
                                        selected = isTypeSelected
                                    }
                                    .testTag("slot_type_${type.name}")
                            ) {
                                RenderBrushSlotPhysicalIcon(
                                    brushType = type,
                                    tintColor = activeInkTint,
                                    modifier = Modifier.size(width = 20.dp, height = 32.dp)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

            // 3. Visual Proportional Stroke Width Dots
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
                            .height(38.dp)
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

            // 4. Tapered Thickness Scrubber Bar
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .padding(horizontal = 8.dp)
                    .pointerInput(slot.id, onUpdateWidth) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onUpdateWidth(1f + frac * 63f)
                        }
                    }
                    .pointerInput(slot.id, onUpdateWidth) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onUpdateWidth(1f + frac * 63f)
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
                        .height(22.dp)
                ) {
                    val w = size.width
                    val cy = size.height / 2f
                    val frac = ((slot.strokeWidth.coerceIn(1f, 64f) - 1f) / 63f).coerceIn(0f, 1f)
                    val leftR = 1.8.dp.toPx()
                    val rightR = 6.2.dp.toPx()

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

                    drawCircle(
                        color = surfaceColor,
                        radius = 7.5.dp.toPx(),
                        center = Offset(thumbX, cy)
                    )
                    drawCircle(
                        color = primaryColor,
                        radius = 5.5.dp.toPx(),
                        center = Offset(thumbX, cy)
                    )
                }
            }

            // 5. Opacity Scrubber Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
            ) {
                Icon(
                    imageVector = StudioIcons.Opacity,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .pointerInput(slot.id, onUpdateOpacity) {
                            detectTapGestures { offset ->
                                val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                onUpdateOpacity(0.05f + frac * 0.95f)
                            }
                        }
                        .pointerInput(slot.id, onUpdateOpacity) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                onUpdateOpacity(0.05f + frac * 0.95f)
                            }
                        }
                        .semantics {
                            contentDescription = "Opacidad ${(slot.opacity * 100f).roundToInt()} por ciento"
                        }
                        .testTag("dock_pen_opacity_slider")
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                    ) {
                        val w = size.width
                        val cy = size.height / 2f
                        val frac = ((slot.opacity.coerceIn(0.05f, 1f) - 0.05f) / 0.95f).coerceIn(0f, 1f)
                        drawLine(
                            brush = Brush.horizontalGradient(
                                listOf(activeInkTint.copy(alpha = 0.08f), activeInkTint)
                            ),
                            start = Offset(0f, cy),
                            end = Offset(w, cy),
                            strokeWidth = 6.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        val thumbX = w * frac
                        drawCircle(color = surfaceColor, radius = 6.5.dp.toPx(), center = Offset(thumbX, cy))
                        drawCircle(color = primaryColor, radius = 4.5.dp.toPx(), center = Offset(thumbX, cy))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

            // 6. Auto-Shape Recognition + Parametric Brush Studio Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
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
                        imageVector = StudioIcons.AutoFixHigh,
                        contentDescription = null,
                        tint = if (autoShapeRecognition) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    PhysicalShapesIcon(
                        tint = if (autoShapeRecognition) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    if (autoShapeRecognition) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(48.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onOpenBrushStudio)
                        .semantics { contentDescription = "Estudio de pincel paramétrico" }
                        .testTag("dock_open_brush_studio")
                ) {
                    Icon(
                        imageVector = StudioIcons.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
