package com.example.ui.toolbar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ToolType
import com.example.model.PenToolSlot
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
            .defaultMinSize(minWidth = minWidthDp.dp, minHeight = WhiteboardUiMetrics.touchTarget)
            .clip(RoundedCornerShape(WhiteboardUiMetrics.groupCorner))
            .background(bgColor)
            .border(
                width = if (isSelected) 1.25.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(WhiteboardUiMetrics.groupCorner)
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
        ToolType.PEN -> PhysicalPencilIcon(
            tipColor = tint,
            modifier = modifier
        )
        ToolType.FINE_MARKER -> PhysicalMarkerIcon(
            markerColor = tint,
            modifier = modifier
        )
        ToolType.HIGHLIGHTER -> PhysicalHighlighterIcon(
            highlighterColor = tint,
            modifier = modifier
        )
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

@Composable
internal fun InkingToolsRow(
    currentTool: ToolType,
    penColor: Color,
    penToolSlots: List<PenToolSlot>,
    activePenSlotIndex: Int,
    onConfigurePenSlot: (Int, ToolType) -> Unit,
    onClearPenSlot: (Int) -> Unit,
    onSelectPenSlot: (Int) -> Unit,
    onUpdatePenColor: (String) -> Unit,
    onUpdatePenWidth: (Float) -> Unit,
    onUpdatePenOpacity: (Float) -> Unit,
    isRulerActive: Boolean,
    pendingShapeType: com.example.model.ShapeType?,
    pendingStickyNoteColor: String?,
    onSelectTool: (ToolType) -> Unit,
    onToggleRuler: () -> Unit,
    showCollapseButton: Boolean = false,
    onCollapse: () -> Unit = {},
    isVertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    val hasPendingPlacement = pendingShapeType != null || pendingStickyNoteColor != null

    val slots: @Composable () -> Unit = {
        ConfigurablePenSlots(
            slots = penToolSlots,
            activeIndex = activePenSlotIndex,
            onSelectSlot = onSelectPenSlot,
            onConfigureSlot = onConfigurePenSlot,
            onClearSlot = onClearPenSlot,
            onUpdateColor = onUpdatePenColor,
            onUpdateWidth = onUpdatePenWidth,
            onUpdateOpacity = onUpdatePenOpacity
        )

        DockHairlineDivider(vertical = !isVertical)

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
internal fun PenStyleTriggerButton(
    parsedColor: Color,
    penWidth: Float,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isExpanded) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
            .border(
                width = if (isExpanded) 1.25.dp else 1.dp,
                color = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "Color, grosor (${penWidth.roundToInt()} px) y formas automáticas"
                selected = isExpanded
            }
            .padding(horizontal = 8.dp)
            .testTag("dock_more_menu_button")
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .then(
                    if (isRainbowPen) {
                        Modifier.background(Brush.sweepGradient(DockRainbowColors))
                    } else {
                        Modifier.background(parsedColor)
                    }
                )
                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = "${penWidth.roundToInt()}px",
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isExpanded) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            if (autoShapeRecognition) {
                Text(
                    text = "Auto",
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
internal fun PenStyleDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    currentPenColor: String,
    parsedPenColor: Color,
    currentPenWidth: Float,
    currentTool: ToolType,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    onUpdatePenColor: (String, isRainbow: Boolean) -> Unit,
    onUpdatePenWidth: (Float) -> Unit,
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
            .heightIn(max = 460.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .testTag("dock_more_dropdown_menu")
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
                    val alpha = if (currentTool == ToolType.HIGHLIGHTER) 0.45f else 1f
                    val previewWidth = when (currentTool) {
                        ToolType.HIGHLIGHTER -> (currentPenWidth * 1.4f).coerceIn(10f, 26f)
                        ToolType.FINE_MARKER -> (currentPenWidth * 0.7f).coerceIn(2f, 10f)
                        else -> currentPenWidth.coerceIn(2f, 24f)
                    }
                    if (isRainbowPen && currentTool != ToolType.HIGHLIGHTER) {
                        drawPath(
                            path = path,
                            brush = Brush.horizontalGradient(DockRainbowColors),
                            style = Stroke(width = previewWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    } else {
                        drawPath(
                            path = path,
                            color = parsedPenColor.copy(alpha = alpha),
                            style = Stroke(width = previewWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }
            }

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
                    val isColorSelected = currentPenColor.equals(option.hex, ignoreCase = true) && !isRainbowPen
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
                            .clickable { onUpdatePenColor(option.hex, false) }
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

                // Rainbow Swatch (connects existing ViewModel isRainbowPen capability)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Brush.sweepGradient(DockRainbowColors))
                        .border(
                            width = if (isRainbowPen) 2.5.dp else 1.dp,
                            color = if (isRainbowPen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                        .clickable { onUpdatePenColor(currentPenColor, true) }
                        .semantics {
                            contentDescription = "Tinta arcoíris"
                            selected = isRainbowPen
                        }
                        .testTag("dock_color_Arcoiris"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRainbowPen) {
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
                    text = "${currentPenWidth.roundToInt()} px",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = currentPenWidth.coerceIn(2f, 24f),
                onValueChange = onUpdatePenWidth,
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
                    val isSelected = abs(currentPenWidth - option.width) < 1f
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
                            .clickable { onUpdatePenWidth(option.width) }
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


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConfigurablePenSlots(
    slots: List<PenToolSlot>,
    activeIndex: Int,
    onSelectSlot: (Int) -> Unit,
    onConfigureSlot: (Int, ToolType) -> Unit,
    onClearSlot: (Int) -> Unit,
    onUpdateColor: (String) -> Unit,
    onUpdateWidth: (Float) -> Unit,
    onUpdateOpacity: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    val normalizedSlots = (slots.take(3) + PenToolSlot.EMPTY_SLOTS.drop(slots.take(3).size)).take(3)
    val toolOptions = listOf(
        Triple(ToolType.PEN, "Lápiz", "Trazo preciso"),
        Triple(ToolType.FINE_MARKER, "Marcador", "Trazo sólido"),
        Triple(ToolType.HIGHLIGHTER, "Resaltador", "Trazo translúcido")
    )

    Surface(
        modifier = modifier.testTag("configurable_pen_slots"),
        shape = RoundedCornerShape(WhiteboardUiMetrics.dockCorner),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            normalizedSlots.forEachIndexed { index, slot ->
                Box {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .size(width = 48.dp, height = 48.dp)
                            .clip(RoundedCornerShape(WhiteboardUiMetrics.groupCorner))
                            .background(
                                if (activeIndex == index && slot.isConfigured) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            )
                            .border(
                                if (activeIndex == index && slot.isConfigured) 2.dp else 1.dp,
                                if (activeIndex == index && slot.isConfigured) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                RoundedCornerShape(WhiteboardUiMetrics.groupCorner)
                            )
                            .combinedClickable(
                                role = Role.Button,
                                onClick = {
                                    if (slot.isConfigured) onSelectSlot(index) else editingIndex = index
                                },
                                onLongClick = { if (slot.isConfigured) editingIndex = index }
                            )
                            .semantics {
                                contentDescription = if (slot.isConfigured) {
                                    "Ranura ${index + 1}, ${slot.tool}, ${if (activeIndex == index) "activa" else "configurada"}. Mantener presionado para editar."
                                } else "Ranura ${index + 1} vacía. Tocar para elegir herramienta."
                                selected = slot.isConfigured && activeIndex == index
                            }
                            .testTag("pen_slot_$index")
                    ) {
                        if (slot.isConfigured) {
                            ActiveDrawingToolGlyph(
                                tool = slot.tool!!,
                                tint = parseDockColorSafe(slot.colorHex, MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier.size(width = 19.dp, height = 27.dp)
                            )
                            Canvas(Modifier.padding(top = 3.dp).size(width = 27.dp, height = 7.dp)) {
                                drawLine(
                                    color = parseDockColorSafe(slot.colorHex, Color.Black).copy(alpha = slot.opacity.coerceIn(0.05f, 1f)),
                                    start = Offset(1f, size.height / 2f),
                                    end = Offset(size.width - 1f, size.height / 2f),
                                    strokeWidth = (slot.width / 4f).coerceIn(1.5f, 5f),
                                    cap = StrokeCap.Round
                                )
                            }
                        } else {
                            Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 25.sp, lineHeight = 25.sp)
                            Text("Añadir", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    DropdownMenu(
                        expanded = editingIndex == index,
                        onDismissRequest = { editingIndex = null },
                        modifier = Modifier.widthIn(min = 248.dp, max = 290.dp)
                    ) {
                        Text(
                            if (slot.isConfigured) "PERSONALIZAR RANURA ${index + 1}" else "ELEGIR HERRAMIENTA",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        toolOptions.forEach { (tool, label, description) ->
                            val selectedTool = slot.isConfigured && slot.tool == tool
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selectedTool) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f) else Color.Transparent)
                                    .clickable {
                                        onConfigureSlot(index, tool)
                                        editingIndex = null
                                    }
                                    .padding(horizontal = 9.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(11.dp)
                            ) {
                                Box(
                                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ActiveDrawingToolGlyph(
                                        tool,
                                        if (slot.isConfigured) parseDockColorSafe(slot.colorHex, MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.primary,
                                        Modifier.size(width = 21.dp, height = 29.dp)
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val previewStrokeColor = MaterialTheme.colorScheme.primary
                                Canvas(Modifier.size(width = 38.dp, height = 22.dp)) {
                                    val alpha = if (tool == ToolType.HIGHLIGHTER) 0.38f else 1f
                                    val stroke = when (tool) {
                                        ToolType.PEN -> 2.5f
                                        ToolType.FINE_MARKER -> 6f
                                        else -> 9f
                                    }
                                    drawLine(
                                        previewStrokeColor.copy(alpha = alpha),
                                        Offset(2f, size.height / 2f),
                                        Offset(size.width - 2f, size.height / 2f),
                                        stroke,
                                        cap = StrokeCap.Round
                                    )
                                }
                            }
                        }
                        if (slot.isConfigured) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Text("COLOR", Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                DockPenColorPalette.forEach { option ->
                                    Box(
                                        Modifier.size(29.dp).clip(CircleShape)
                                            .background(parseDockColorSafe(option.hex, Color.Black))
                                            .border(
                                                if (slot.colorHex.equals(option.hex, true)) 2.dp else 1.dp,
                                                if (slot.colorHex.equals(option.hex, true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                CircleShape
                                            )
                                            .clickable {
                                                onSelectSlot(index)
                                                onUpdateColor(option.hex)
                                            }
                                            .testTag("pen_slot_color_${index}_${option.label.lowercase()}")
                                    )
                                }
                            }
                            Text("GROSOR", Modifier.padding(horizontal = 14.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = slot.width.coerceIn(1f, 48f),
                                onValueChange = { onSelectSlot(index); onUpdateWidth(it) },
                                valueRange = 1f..48f,
                                modifier = Modifier.padding(horizontal = 12.dp).height(34.dp)
                            )
                            Text("OPACIDAD ${(slot.opacity * 100).roundToInt()}%", Modifier.padding(horizontal = 14.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = slot.opacity.coerceIn(0.05f, 1f),
                                onValueChange = { onSelectSlot(index); onUpdateOpacity(it) },
                                valueRange = 0.05f..1f,
                                modifier = Modifier.padding(horizontal = 12.dp).height(34.dp)
                            )
                            Row(
                                Modifier.fillMaxWidth().clickable { onClearSlot(index); editingIndex = null }.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(17.dp))
                                Text("Vaciar ranura", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}