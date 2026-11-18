package com.example.ui.toolbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.model.ShapeType
import com.example.model.ToolType

@Composable
fun WhiteboardPenDock(
    currentTool: ToolType,
    currentPenColor: String,
    currentPenWidth: Float,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingShapeType: ShapeType? = null,
    onSelectTool: (ToolType) -> Unit,
    onUpdatePenColor: (String, isRainbow: Boolean) -> Unit,
    onUpdatePenWidth: (Float) -> Unit,
    onToggleAutoShape: () -> Unit,
    onToggleRuler: () -> Unit,
    onAddStickyNote: () -> Unit,
    onAddText: () -> Unit,
    onClearBoard: () -> Unit,
    isStylusModeEnabled: Boolean = false,
    onToggleStylusMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMoreMenu by remember { mutableStateOf(false) }
    var isInkingTrayOpen by remember { mutableStateOf(true) }

    val parsedPenColor = try {
        Color(android.graphics.Color.parseColor(currentPenColor))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.onSurface
    }

    val activeHighlightColor = parsedPenColor

    val colorPalette = listOf(
        "#000000" to "Negro",
        "#0F172A" to "Pizarra",
        "#2563EB" to "Azul",
        "#DC2626" to "Rojo",
        "#16A34A" to "Verde",
        "#7C3AED" to "Violeta",
        "#EA580C" to "Naranja",
        "#FACC15" to "Amarillo"
    )

    val widthOptions = listOf(
        3f to "Fino",
        6f to "Medio",
        12f to "Grueso"
    )

    val isHandActive = pendingShapeType == null && currentTool == ToolType.HAND
    val isDrawingOrInkingTool = pendingShapeType == null && currentTool != ToolType.HAND

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom
    ) {
        // Two-tier compact floating dock (Upper upright tools tray + Lower compact action bar)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag("whiteboard_pen_dock")
        ) {
            // 1. Upper Floating Inking Tray — upright physical tools peeking from bottom edge + close 'X'
            AnimatedVisibility(
                visible = isInkingTrayOpen,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 0.dp,
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .testTag("inking_tray_card")
                ) {
                    Row(
                        modifier = Modifier
                            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 0.dp)
                            .clipToBounds(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            UprightTrayToolSlot(
                                isSelected = pendingShapeType == null && currentTool == ToolType.PEN,
                                description = "Pluma",
                                onClick = { onSelectTool(ToolType.PEN) },
                                testTag = "dock_menu_tool_pen"
                            ) {
                                PhysicalFountainPenIcon(
                                    inkColor = parsedPenColor,
                                    modifier = Modifier.size(width = 28.dp, height = 40.dp)
                                )
                            }


                            UprightTrayToolSlot(
                                isSelected = pendingShapeType == null && currentTool == ToolType.FINE_MARKER,
                                description = "Fibra / Marcador fino",
                                onClick = { onSelectTool(ToolType.FINE_MARKER) },
                                testTag = "dock_menu_tool_fine_marker"
                            ) {
                                PhysicalMarkerIcon(
                                    markerColor = parsedPenColor,
                                    modifier = Modifier.size(width = 28.dp, height = 40.dp)
                                )
                            }

                            UprightTrayToolSlot(
                                isSelected = pendingShapeType == null && currentTool == ToolType.HIGHLIGHTER,
                                description = "Resaltador",
                                onClick = { onSelectTool(ToolType.HIGHLIGHTER) },
                                testTag = "dock_menu_tool_highlighter"
                            ) {
                                PhysicalHighlighterIcon(
                                    highlighterColor = activeHighlightColor,
                                    modifier = Modifier.size(width = 28.dp, height = 40.dp)
                                )
                            }


                            UprightTrayToolSlot(
                                isSelected = pendingShapeType == null && currentTool == ToolType.LASER_POINTER,
                                description = "Puntero láser",
                                onClick = { onSelectTool(ToolType.LASER_POINTER) },
                                testTag = "dock_menu_tool_laser"
                            ) {
                                PhysicalLaserIcon(
                                    modifier = Modifier.size(width = 28.dp, height = 40.dp)
                                )
                            }

                            UprightTrayToolSlot(
                                isSelected = pendingShapeType == null && currentTool == ToolType.ERASER,
                                description = "Goma de borrar",
                                onClick = { onSelectTool(ToolType.ERASER) },
                                testTag = "dock_menu_tool_eraser"
                            ) {
                                PhysicalEraserIcon(
                                    modifier = Modifier.size(width = 28.dp, height = 40.dp)
                                )
                            }

                            UprightTrayToolSlot(
                                isSelected = pendingShapeType == null && currentTool == ToolType.PIXEL_ERASER,
                                description = "Goma de precisión",
                                onClick = { onSelectTool(ToolType.PIXEL_ERASER) },
                                testTag = "dock_menu_tool_pixel_eraser"
                            ) {
                                PhysicalPixelEraserIcon(
                                    modifier = Modifier.size(width = 28.dp, height = 40.dp)
                                )
                            }

                            UprightTrayToolSlot(
                                isSelected = isRulerActive,
                                description = "Regla",
                                onClick = onToggleRuler,
                                testTag = "dock_menu_toggle_ruler"
                            ) {
                                PhysicalRulerIcon(
                                    isActive = isRulerActive,
                                    modifier = Modifier.size(width = 28.dp, height = 40.dp)
                                )
                            }

                            UprightTrayToolSlot(
                                isSelected = pendingShapeType == null && currentTool == ToolType.SELECT_LASSO,
                                description = "Lazo de selección",
                                onClick = { onSelectTool(ToolType.SELECT_LASSO) },
                                testTag = "dock_menu_tool_select_lasso",
                                anchorBottom = false
                            ) {
                                PhysicalLassoIcon(
                                    tint = if (pendingShapeType == null && currentTool == ToolType.SELECT_LASSO) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Compact close 'X' button on the right edge of the tray
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .size(width = 36.dp, height = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) { isInkingTrayOpen = false }
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

            // 2. Lower Compact Main Bar (matches Microsoft Whiteboard compact pill bar)
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 0.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .testTag("dock_main_bar")
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Toggle upper inking tray (active highlight when tray is open or drawing tool active)
                    val isInkBarActive = isInkingTrayOpen || isDrawingOrInkingTool
                    CompactDockActionButton(
                        isSelected = isInkingTrayOpen,
                        description = if (isInkingTrayOpen) "Ocultar trazos" else "Mostrar panel de trazos",
                        onClick = {
                            if (!isInkingTrayOpen) {
                                isInkingTrayOpen = true
                                if (currentTool == ToolType.HAND) {
                                    onSelectTool(ToolType.PEN)
                                }
                            } else {
                                isInkingTrayOpen = false
                            }
                        },
                        testTag = "dock_toggle_inking_tray"
                    ) {
                        PhysicalPenSquiggleIcon(
                            tint = if (isInkBarActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Independent stylus input mode
                    CompactDockActionButton(
                        isSelected = isStylusModeEnabled,
                        description = if (isStylusModeEnabled) "Desactivar modo lápiz óptico" else "Activar modo lápiz óptico",
                        onClick = onToggleStylusMode,
                        testTag = "dock_toggle_stylus_mode"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = if (isStylusModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // Sticky Note
                    CompactDockActionButton(
                        isSelected = false,
                        description = "Agregar nota adhesiva",
                        onClick = onAddStickyNote,
                        testTag = "dock_action_sticky_note"
                    ) {
                        PhysicalStickyNoteIcon(modifier = Modifier.size(22.dp))
                    }

                    // Color and thickness properties (...)
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .height(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (showMoreMenu) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .border(
                                    width = if (showMoreMenu) 1.5.dp else 0.dp,
                                    color = if (showMoreMenu) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { showMoreMenu = !showMoreMenu }
                                .padding(horizontal = 8.dp)
                                .testTag("dock_more_menu_button")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .clip(CircleShape)
                                    .then(
                                        if (isRainbowPen) {
                                            Modifier.background(
                                                Brush.sweepGradient(
                                                    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Magenta, Color.Red)
                                                )
                                            )
                                        } else {
                                            Modifier.background(parsedPenColor)
                                        }
                                    )
                                    .border(1.2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "Color, grosor y estilo de trazo",
                                tint = if (showMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            scrollState = rememberScrollState(),
                            containerColor = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .width(316.dp)
                                .heightIn(max = 460.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                .testTag("dock_more_dropdown_menu")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                // Color de Trazo
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    colorPalette.forEach { (hex, label) ->
                                        val swatchColor = Color(android.graphics.Color.parseColor(hex))
                                        val isColorSelected = currentPenColor.equals(hex, ignoreCase = true) && !isRainbowPen
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(swatchColor)
                                                .border(
                                                    width = if (isColorSelected) 2.5.dp else 1.dp,
                                                    color = if (isColorSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    onUpdatePenColor(hex, false)
                                                }
                                                .testTag("dock_color_$label"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isColorSelected) {
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

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))

                                // Grosor
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    widthOptions.forEach { (widthVal, label) ->
                                        val isSelected = kotlin.math.abs(currentPenWidth - widthVal) < 1f
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
                                                .clickable { onUpdatePenWidth(widthVal) }
                                                .padding(vertical = 7.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }


                                // Auto-shape recognition toggle inside properties menu
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (autoShapeRecognition) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                            else Color.Transparent
                                        )
                                        .clickable(onClick = onToggleAutoShape)
                                        .padding(horizontal = 8.dp, vertical = 8.dp)
                                        .testTag("dock_menu_toggle_auto_shape")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoFixHigh,
                                        contentDescription = null,
                                        tint = if (autoShapeRecognition) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Formas automáticas",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (autoShapeRecognition) FontWeight.SemiBold else FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (autoShapeRecognition) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                            )
                                            .padding(horizontal = 7.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (autoShapeRecognition) "ON" else "OFF",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (autoShapeRecognition) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))

                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Separated Floating Swipe Segmented Switcher ("Selección" / "Mover") — mirrors Header Pizarra/Notas switcher
        val isSelectSegmentActive = !isHandActive
        var swipeAccumDx by remember { mutableFloatStateOf(0f) }

        Surface(
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .pointerInput(currentTool) {
                    detectHorizontalDragGestures(
                        onDragStart = { swipeAccumDx = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            swipeAccumDx += dragAmount
                            if (swipeAccumDx > 24f) {
                                onSelectTool(ToolType.HAND)
                                swipeAccumDx = 0f
                                change.consume()
                            } else if (swipeAccumDx < -24f) {
                                onSelectTool(ToolType.SELECT_LASSO)
                                swipeAccumDx = 0f
                                change.consume()
                            }
                        }
                    )
                }
                .testTag("dock_navigation_swipe_switch")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(3.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(2.dp)
            ) {
                DockModeSegmentItem(
                    icon = Icons.Default.NearMe,
                    label = "Selec.",
                    contentDesc = "Modo selección",
                    isSelected = isSelectSegmentActive,
                    onClick = { onSelectTool(ToolType.SELECT_LASSO) },
                    testTag = "dock_tool_select"
                )
                Spacer(modifier = Modifier.width(2.dp))
                DockModeSegmentItem(
                    icon = Icons.Default.PanTool,
                    label = "Mover",
                    contentDesc = "Mover lienzo",
                    isSelected = isHandActive,
                    onClick = { onSelectTool(ToolType.HAND) },
                    testTag = "dock_tool_hand"
                )
            }
        }
    }
}

@Composable
private fun DockModeSegmentItem(
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
            .size(width = 44.dp, height = 46.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bgColor)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = contentDesc
                selected = isSelected
            }
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fgColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = fgColor,
            maxLines = 1
        )
    }
}

@Composable
private fun UprightTrayToolSlot(
    isSelected: Boolean,
    description: String,
    onClick: () -> Unit,
    testTag: String,
    anchorBottom: Boolean = true,
    iconContent: @Composable () -> Unit
) {
    val liftY by animateDpAsState(
        targetValue = if (isSelected) (-3).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 450f),
        label = "tray_tool_lift"
    )

    Box(
        contentAlignment = if (anchorBottom) Alignment.BottomCenter else Alignment.Center,
        modifier = Modifier
            .size(width = 36.dp, height = 48.dp)
            .padding(top = 2.dp, bottom = if (anchorBottom) 0.dp else 4.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else Color.Transparent
            )
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = RoundedCornerShape(7.dp)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = isSelected
            }
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = if (anchorBottom) Alignment.BottomCenter else Alignment.Center,
            modifier = Modifier.offset(y = liftY)
        ) {
            iconContent()
        }
    }
}

@Composable
private fun CompactDockActionButton(
    isSelected: Boolean,
    description: String,
    onClick: () -> Unit,
    testTag: String,
    content: @Composable () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 42.dp, height = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
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
