package com.example.ui.toolbar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BrushSlot
import com.example.model.StrokeBrushType
import com.example.model.ToolType
import kotlin.math.abs
import kotlin.math.roundToInt

enum class DockLayoutMode {
    COMPACT_PORTRAIT,
    COMPACT_LANDSCAPE_RAIL,
    EXPANDED_TABLET
}

@Immutable
data class DockColorOption(
    val hex: String,
    val label: String
)

@Immutable
data class DockWidthOption(
    val width: Float,
    val label: String
)

internal val DockPenColorPalette = listOf(
    DockColorOption("#000000", "Negro"),
    DockColorOption("#0F172A", "Pizarra"),
    DockColorOption("#2563EB", "Azul"),
    DockColorOption("#DC2626", "Rojo"),
    DockColorOption("#16A34A", "Verde"),
    DockColorOption("#7C3AED", "Violeta"),
    DockColorOption("#EA580C", "Naranja"),
    DockColorOption("#FACC15", "Amarillo")
)

internal val DockPenWidthOptions = listOf(
    DockWidthOption(3f, "Fino"),
    DockWidthOption(6f, "Medio"),
    DockWidthOption(12f, "Grueso"),
    DockWidthOption(18f, "Extra")
)

internal val DockStickyColorPalette = listOf(
    DockColorOption("#FFF275", "Amarillo"),
    DockColorOption("#CCFF90", "Verde"),
    DockColorOption("#A7FFEB", "Azul"),
    DockColorOption("#FDCFE8", "Rosa"),
    DockColorOption("#D7AEFB", "Púrpura"),
    DockColorOption("#FFE0B2", "Naranja")
)

internal val DockRainbowColors = listOf(
    Color(0xFFEF4444),
    Color(0xFFF59E0B),
    Color(0xFF10B981),
    Color(0xFF3B82F6),
    Color(0xFF8B5CF6),
    Color(0xFFEF4444)
)

internal fun ToolType.isInkingOrEraseTool(): Boolean = when (this) {
    ToolType.PEN,
    ToolType.FINE_MARKER,
    ToolType.HIGHLIGHTER,
    ToolType.LASER_POINTER,
    ToolType.ERASER,
    ToolType.PIXEL_ERASER -> true
    else -> false
}

internal fun parseDockColorSafe(hex: String, fallback: Color): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        fallback
    }
}

@Composable
internal fun DockHairlineDivider(
    vertical: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .then(
                if (vertical) {
                    Modifier
                        .padding(horizontal = 2.dp)
                        .width(1.dp)
                        .height(24.dp)
                } else {
                    Modifier
                        .padding(vertical = 2.dp)
                        .height(1.dp)
                        .width(28.dp)
                }
            )
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
internal fun DockActionButton(
    isSelected: Boolean,
    description: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    minWidthDp: Int = 44,
    content: @Composable () -> Unit
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    } else {
        Color.Transparent
    }
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    } else {
        Color.Transparent
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = minWidthDp.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(
                width = if (isSelected) 1.25.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = isSelected
            }
            .testTag(testTag)
    ) {
        content()
    }
}

@Composable
internal fun ModeSwitcherGroup(
    isDrawingActive: Boolean,
    isSelectActive: Boolean,
    isHandActive: Boolean,
    isInkingTrayOpen: Boolean,
    activeInkingTool: ToolType,
    penColor: Color,
    isRainbowPen: Boolean,
    isVertical: Boolean,
    onClickDrawingMode: () -> Unit,
    onSelectLasso: () -> Unit,
    onSelectHand: () -> Unit,
    modifier: Modifier = Modifier
) {
    var swipeAccum by remember { mutableFloatStateOf(0f) }

    val containerModifier = modifier
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
        .padding(3.dp)
        .pointerInput(isHandActive, isSelectActive) {
            detectHorizontalDragGestures(
                onDragStart = { swipeAccum = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    swipeAccum += dragAmount
                    if (swipeAccum > 24f) {
                        onSelectHand()
                        swipeAccum = 0f
                        change.consume()
                    } else if (swipeAccum < -24f) {
                        onSelectLasso()
                        swipeAccum = 0f
                        change.consume()
                    }
                }
            )
        }
        .testTag("dock_navigation_swipe_switch")

    val content: @Composable () -> Unit = {
        // 1. Drawing / Inking mode button (displays active ink dot or tool state)
        val drawBg = if (isDrawingActive) MaterialTheme.colorScheme.primary else Color.Transparent
        val drawFg = if (isDrawingActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        val drawDesc = if (isInkingTrayOpen) {
            "Herramientas de trazado (ocultar bandeja)"
        } else {
            "Herramientas de trazado (mostrar bandeja)"
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = 44.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(drawBg)
                .clickable(role = Role.Button, onClick = onClickDrawingMode)
                .semantics {
                    contentDescription = drawDesc
                    selected = isDrawingActive
                }
                .testTag("dock_toggle_inking_tray")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                ActiveDrawingToolGlyph(
                    tool = activeInkingTool,
                    tint = drawFg,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(width = 14.dp, height = 3.dp)
                        .clip(CircleShape)
                        .then(
                            if (isRainbowPen) {
                                Modifier.background(Brush.horizontalGradient(DockRainbowColors))
                            } else {
                                Modifier.background(
                                    if (isDrawingActive && activeInkingTool != ToolType.ERASER && activeInkingTool != ToolType.PIXEL_ERASER) {
                                        penColor
                                    } else {
                                        drawFg.copy(alpha = 0.55f)
                                    }
                                )
                            }
                        )
                )
            }
        }

        // 2. Lasso Selection Mode (only active when SELECT_LASSO is active)
        ModeSegmentButton(
            icon = Icons.Default.NearMe,
            label = "Lazo",
            contentDesc = "Modo selección con lazo",
            isSelected = isSelectActive,
            onClick = onSelectLasso,
            testTag = "dock_tool_select"
        )

        // 3. Hand Pan-Only Mode (only active when HAND is active)
        ModeSegmentButton(
            icon = Icons.Default.PanTool,
            label = "Mover",
            contentDesc = "Mover lienzo",
            isSelected = isHandActive,
            onClick = onSelectHand,
            testTag = "dock_tool_hand"
        )
    }

    if (isVertical) {
        Column(
            modifier = containerModifier,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content()
        }
    } else {
        Row(
            modifier = containerModifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
private fun ActiveDrawingToolGlyph(
    tool: ToolType,
    tint: Color,
    modifier: Modifier = Modifier
) {
    when (tool) {
        ToolType.ERASER, ToolType.PIXEL_ERASER -> {
            Canvas(modifier = modifier) {
                val w = size.width
                val h = size.height
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.22f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.64f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.14f),
                    style = Stroke(width = w * 0.11f)
                )
                drawLine(
                    color = tint,
                    start = Offset(w * 0.22f, h * 0.52f),
                    end = Offset(w * 0.78f, h * 0.52f),
                    strokeWidth = w * 0.10f
                )
            }
        }
        ToolType.LASER_POINTER -> {
            Canvas(modifier = modifier) {
                val u = size.minDimension
                drawCircle(color = tint.copy(alpha = 0.28f), radius = u * 0.34f, center = Offset(u * 0.5f, u * 0.4f))
                drawCircle(color = tint, radius = u * 0.16f, center = Offset(u * 0.5f, u * 0.4f))
                drawLine(
                    color = tint,
                    start = Offset(u * 0.5f, u * 0.58f),
                    end = Offset(u * 0.5f, u * 0.88f),
                    strokeWidth = u * 0.14f,
                    cap = StrokeCap.Round
                )
            }
        }
        else -> {
            PhysicalPenSquiggleIcon(tint = tint, modifier = modifier)
        }
    }
}

@Composable
private fun ModeSegmentButton(
    icon: ImageVector,
    label: String,
    contentDesc: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fgColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = contentDesc
                selected = isSelected
            }
            .padding(horizontal = 5.dp, vertical = 3.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fgColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = fgColor,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InkingToolsRow(
    currentTool: ToolType,
    brushSlots: List<BrushSlot>,
    activeBrushSlotIndex: Int,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingShapeType: com.example.model.ShapeType?,
    pendingStickyNoteColor: String?,
    onSelectBrushSlot: (Int) -> Unit,
    onUpdateBrushSlot: (Int, StrokeBrushType?, String?, Float?, Boolean?) -> Unit,
    onToggleAutoShape: () -> Unit,
    onSelectTool: (ToolType) -> Unit,
    onToggleRuler: () -> Unit,
    showCollapseButton: Boolean = false,
    onCollapse: () -> Unit = {},
    isVertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    val hasPendingPlacement = pendingShapeType != null || pendingStickyNoteColor != null
    var expandedSlotIndex by remember { mutableStateOf<Int?>(null) }
    val haptic = LocalHapticFeedback.current
    val fallbackInk = MaterialTheme.colorScheme.onSurface
    val isAnyBrushToolActive = !hasPendingPlacement &&
        (currentTool == ToolType.PEN || currentTool == ToolType.FINE_MARKER || currentTool == ToolType.HIGHLIGHTER)

    val slots: @Composable () -> Unit = {
        brushSlots.forEachIndexed { index, slot ->
            val isSlotSelected = isAnyBrushToolActive && activeBrushSlotIndex == index
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
                CustomizableBrushSlotButton(
                    isSelected = isSlotSelected,
                    description = "$slotLabel ${index + 1}. Mantén presionado para elegir tipo, color y grosor",
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

        TrayToolSlot(
            isSelected = !hasPendingPlacement && currentTool == ToolType.LASER_POINTER,
            description = "Puntero láser",
            onClick = { onSelectTool(ToolType.LASER_POINTER) },
            testTag = "dock_menu_tool_laser"
        ) {
            PhysicalLaserIcon(
                modifier = Modifier.size(width = 26.dp, height = 38.dp)
            )
        }

        DockHairlineDivider(vertical = !isVertical)

        TrayToolSlot(
            isSelected = !hasPendingPlacement && currentTool == ToolType.ERASER,
            description = "Borrador de objetos",
            onClick = { onSelectTool(ToolType.ERASER) },
            testTag = "dock_menu_tool_eraser"
        ) {
            PhysicalEraserIcon(
                modifier = Modifier.size(width = 26.dp, height = 38.dp)
            )
        }

        TrayToolSlot(
            isSelected = !hasPendingPlacement && currentTool == ToolType.PIXEL_ERASER,
            description = "Borrador de precisión",
            onClick = { onSelectTool(ToolType.PIXEL_ERASER) },
            testTag = "dock_menu_tool_pixel_eraser"
        ) {
            PhysicalPixelEraserIcon(
                modifier = Modifier.size(width = 26.dp, height = 38.dp)
            )
        }

        TrayToolSlot(
            isSelected = isRulerActive,
            description = if (isRulerActive) "Ocultar regla" else "Mostrar regla",
            onClick = onToggleRuler,
            testTag = "dock_menu_toggle_ruler"
        ) {
            PhysicalRulerIcon(
                isActive = isRulerActive,
                modifier = Modifier.size(width = 26.dp, height = 38.dp)
            )
        }
    }

    if (isVertical) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            slots()
            if (showCollapseButton) {
                DockHairlineDivider(vertical = false)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClick = onCollapse)
                        .semantics { contentDescription = "Ocultar herramientas de trazado" }
                        .testTag("dock_close_inking_tray")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                slots()
            }
            if (showCollapseButton) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 36.dp, minHeight = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClick = onCollapse)
                        .semantics { contentDescription = "Ocultar panel de trazos" }
                        .testTag("dock_close_inking_tray")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CustomizableBrushSlotButton(
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
        // Small corner triangle indicating long-press customization (type, color & stroke width)
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
private fun TrayToolSlot(
    isSelected: Boolean,
    description: String,
    onClick: () -> Unit,
    testTag: String,
    iconContent: @Composable () -> Unit
) {
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .defaultMinSize(minWidth = 38.dp, minHeight = 48.dp)
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
            .clickable(role = Role.Button, onClick = onClick)
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
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        scrollState = rememberScrollState(),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .width(320.dp)
            .heightIn(max = 520.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .testTag("dock_brush_slot_dropdown")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Live Stroke Preview Strip
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
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
                        StrokeBrushType.HIGHLIGHTER -> (slot.strokeWidth * 1.4f).coerceIn(10f, 26f)
                        StrokeBrushType.FINE_MARKER -> (slot.strokeWidth * 0.7f).coerceIn(2f, 10f)
                        StrokeBrushType.FOUNTAIN_PEN -> slot.strokeWidth.coerceIn(2f, 24f)
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

            Spacer(modifier = Modifier.height(10.dp))

            // Customizable Tool Type Selector (Lápiz / Marcador / Resaltador)
            Text(
                text = "TIPO DE HERRAMIENTA",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            val brushTypeOptions = listOf(
                StrokeBrushType.FOUNTAIN_PEN to "Lápiz",
                StrokeBrushType.FINE_MARKER to "Marcador",
                StrokeBrushType.HIGHLIGHTER to "Resaltador"
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                brushTypeOptions.forEach { (type, label) ->
                    val isTypeSelected = slot.brushType == type
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isTypeSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                width = if (isTypeSelected) 1.5.dp else 1.dp,
                                color = if (isTypeSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onChangeBrushType(type) }
                            .padding(vertical = 8.dp)
                            .testTag("slot_type_${type.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (isTypeSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isTypeSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // Color Palette + Rainbow
            Text(
                text = "COLOR DE TRAZO",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                DockPenColorPalette.forEach { option ->
                    val swatchColor = remember(option.hex) { parseDockColorSafe(option.hex, Color.Black) }
                    val isColorSelected = slot.colorHex.equals(option.hex, ignoreCase = true) && !slot.isRainbow
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .border(
                                width = if (isColorSelected) 2.5.dp else 1.dp,
                                color = if (isColorSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                            .clickable { onUpdateColor(option.hex, false) }
                            .semantics {
                                contentDescription = option.label
                                selected = isColorSelected
                            }
                            .testTag("dock_color_${option.label}"),
                        contentAlignment = Alignment.Center
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

                // Rainbow Swatch
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Brush.sweepGradient(DockRainbowColors))
                        .border(
                            width = if (slot.isRainbow) 2.5.dp else 1.dp,
                            color = if (slot.isRainbow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                        .clickable { onUpdateColor(slot.colorHex, true) }
                        .semantics {
                            contentDescription = "Tinta arcoíris"
                            selected = slot.isRainbow
                        }
                        .testTag("dock_color_Arcoiris"),
                    contentAlignment = Alignment.Center
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

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // Width Presets + Continuous Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GROSOR",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${slot.strokeWidth.roundToInt()} px",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = slot.strokeWidth.coerceIn(2f, 24f),
                onValueChange = onUpdateWidth,
                valueRange = 2f..24f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .height(30.dp)
                    .testTag("dock_pen_width_slider")
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                DockPenWidthOptions.forEach { option ->
                    val isSelected = abs(slot.strokeWidth - option.width) < 1f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onUpdateWidth(option.width) }
                            .padding(vertical = 7.dp)
                            .testTag("dock_width_${option.label}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // Auto-Shape Recognition Toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (autoShapeRecognition) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else Color.Transparent
                    )
                    .clickable(onClick = onToggleAutoShape)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("dock_menu_toggle_auto_shape")
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = if (autoShapeRecognition) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Formas automáticas",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (autoShapeRecognition) FontWeight.SemiBold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Convierte trazos cerrados en figuras",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoShapeRecognition,
                    onCheckedChange = { onToggleAutoShape() }
                )
            }
        }
    }
}

@Composable
internal fun StickyNoteDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAddStickyNoteCenter: (String) -> Unit,
    onRequestPlaceStickyNote: (String) -> Unit
) {
    var placeOnTapMode by remember { mutableStateOf(false) }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .width(256.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .testTag("sticky_note_palette_menu")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "NUEVA NOTA ADHESIVA",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                DockStickyColorPalette.forEach { option ->
                    val swatchColor = remember(option.hex) { parseDockColorSafe(option.hex, Color.Yellow) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(swatchColor)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .clickable {
                                if (placeOnTapMode) {
                                    onRequestPlaceStickyNote(option.hex)
                                } else {
                                    onAddStickyNoteCenter(option.hex)
                                }
                                onDismiss()
                            }
                            .semantics { contentDescription = "Nota ${option.label}" }
                            .testTag("sticky_color_${option.label}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (placeOnTapMode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                    .clickable { placeOnTapMode = !placeOnTapMode }
                    .padding(horizontal = 8.dp, vertical = 7.dp)
                    .testTag("sticky_place_on_tap_toggle")
            ) {
                Icon(
                    imageVector = if (placeOnTapMode) Icons.Default.Check else Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = if (placeOnTapMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (placeOnTapMode) "Modo: tocar en el lienzo" else "Colocar tocando el lienzo",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
