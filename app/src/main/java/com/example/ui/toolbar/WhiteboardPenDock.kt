package com.example.ui.toolbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ShapeType
import com.example.model.StrokeStyle
import com.example.model.ToolType

@Composable
fun WhiteboardPenDock(
    currentTool: ToolType,
    currentPenColor: String,
    currentPenWidth: Float,
    currentPenStyle: StrokeStyle,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingShapeType: ShapeType? = null,
    onSelectTool: (ToolType) -> Unit,
    onUpdatePenColor: (String, isRainbow: Boolean) -> Unit,
    onUpdatePenWidth: (Float) -> Unit,
    onUpdatePenStyle: (StrokeStyle) -> Unit,
    onToggleAutoShape: () -> Unit,
    onToggleRuler: () -> Unit,
    onOpenShapePicker: () -> Unit,
    onAddStickyNote: () -> Unit,
    onAddText: () -> Unit,
    onOpenTemplates: () -> Unit,
    onClearBoard: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showToolsMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    var expandedWriting by remember { mutableStateOf(false) }
    var expandedEmphasis by remember { mutableStateOf(false) }
    var expandedEraser by remember { mutableStateOf(false) }

    val parsedPenColor = try {
        Color(android.graphics.Color.parseColor(currentPenColor))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.onSurface
    }

    val colorPalette = listOf(
        "#0F172A" to "Pizarra",
        "#2563EB" to "Azul",
        "#DC2626" to "Rojo",
        "#16A34A" to "Verde",
        "#7C3AED" to "Violeta",
        "#EA580C" to "Naranja"
    )

    val widthOptions = listOf(
        3f to "Fino",
        6f to "Medio",
        12f to "Grueso"
    )

    val isWritingTool = currentTool == ToolType.PEN || currentTool == ToolType.PENCIL || currentTool == ToolType.FINE_MARKER
    val isEmphasisTool = currentTool == ToolType.HIGHLIGHTER || currentTool == ToolType.BRUSH
    val isEraserTool = currentTool == ToolType.ERASER || currentTool == ToolType.PIXEL_ERASER

    val writingTitle = when (currentTool) {
        ToolType.PENCIL -> "Lápiz"
        ToolType.FINE_MARKER -> "Marcador fino"
        else -> "Pluma"
    }
    val writingIcon = when (currentTool) {
        ToolType.PENCIL -> Icons.Default.Create
        ToolType.FINE_MARKER -> Icons.Default.Gesture
        else -> Icons.Default.Edit
    }

    val emphasisTitle = when (currentTool) {
        ToolType.BRUSH -> "Pincel"
        else -> "Resaltador"
    }
    val emphasisIcon = when (currentTool) {
        ToolType.BRUSH -> Icons.Default.Brush
        else -> Icons.Default.Highlight
    }

    val eraserTitle = when (currentTool) {
        ToolType.PIXEL_ERASER -> "Borrador de píxel"
        else -> "Borrador de objeto"
    }

    val activeShortLabel = when (currentTool) {
        ToolType.PEN -> "Pluma"
        ToolType.PENCIL -> "Lápiz"
        ToolType.FINE_MARKER -> "Marcador"
        ToolType.HIGHLIGHTER -> "Resalt."
        ToolType.BRUSH -> "Pincel"
        ToolType.ERASER -> "Borr. objeto"
        ToolType.PIXEL_ERASER -> "Borr. píxel"
        ToolType.SELECT_LASSO -> "Lazo"
        ToolType.HAND -> "Mover"
        else -> "Pluma"
    }

    val activeToolIcon = when (currentTool) {
        ToolType.PENCIL -> Icons.Default.Create
        ToolType.FINE_MARKER -> Icons.Default.Gesture
        ToolType.HIGHLIGHTER -> Icons.Default.Highlight
        ToolType.BRUSH -> Icons.Default.Brush
        ToolType.ERASER, ToolType.PIXEL_ERASER -> Icons.Default.CleaningServices
        ToolType.SELECT_LASSO -> Icons.Default.NearMe
        ToolType.HAND -> Icons.Default.PanTool
        else -> Icons.Default.Edit
    }

    val isStrokeTool = isWritingTool || isEmphasisTool
    val activeHighlightColor = if (currentTool == ToolType.HIGHLIGHTER &&
        (currentPenColor.equals("#0F172A", true) || currentPenColor.equals("#1E1E1E", true))
    ) {
        Color(0xFFFACC15)
    } else {
        parsedPenColor
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .testTag("whiteboard_pen_dock")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Selector "Trazos"
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (pendingShapeType == null) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                width = 1.dp,
                                color = if (pendingShapeType == null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(11.dp)
                            )
                            .clickable {
                                // Default sub-panel opened to current tool category
                                if (!showToolsMenu) {
                                    expandedWriting = isWritingTool
                                    expandedEmphasis = isEmphasisTool
                                    expandedEraser = isEraserTool
                                }
                                showToolsMenu = !showToolsMenu
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("dock_tools_menu_button")
                    ) {
                        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = activeToolIcon,
                                contentDescription = "Seleccionar herramienta de trazos",
                                tint = if (pendingShapeType == null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            if (isStrokeTool) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .align(Alignment.BottomEnd)
                                        .clip(CircleShape)
                                        .background(activeHighlightColor)
                                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Trazos",
                                fontSize = 9.sp,
                                lineHeight = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = activeShortLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (pendingShapeType == null) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Desplegar trazos",
                            tint = if (pendingShapeType == null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showToolsMenu,
                        onDismissRequest = { showToolsMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .width(260.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .testTag("dock_tools_dropdown_menu")
                    ) {
                        // Grupo 1: Pluma (con sub-panel desplegable: Pluma, Lápiz, Marcador fino)
                        ExpandableGroupRow(
                            icon = writingIcon,
                            label = writingTitle,
                            isGroupActive = isWritingTool,
                            isExpanded = expandedWriting,
                            onToggleExpand = { expandedWriting = !expandedWriting },
                            onSelectDefault = {
                                onSelectTool(if (isWritingTool) currentTool else ToolType.PEN)
                                showToolsMenu = false
                            },
                            testTag = "dock_menu_group_pen"
                        )
                        AnimatedVisibility(visible = expandedWriting) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .padding(vertical = 2.dp)
                            ) {
                                CleanSubToolItem(
                                    icon = Icons.Default.Edit,
                                    label = "Pluma",
                                    isSelected = currentTool == ToolType.PEN,
                                    onClick = {
                                        onSelectTool(ToolType.PEN)
                                        showToolsMenu = false
                                    },
                                    testTag = "dock_menu_tool_pen"
                                )
                                CleanSubToolItem(
                                    icon = Icons.Default.Create,
                                    label = "Lápiz",
                                    isSelected = currentTool == ToolType.PENCIL,
                                    onClick = {
                                        onSelectTool(ToolType.PENCIL)
                                        showToolsMenu = false
                                    },
                                    testTag = "dock_menu_tool_pencil"
                                )
                                CleanSubToolItem(
                                    icon = Icons.Default.Gesture,
                                    label = "Marcador fino",
                                    isSelected = currentTool == ToolType.FINE_MARKER,
                                    onClick = {
                                        onSelectTool(ToolType.FINE_MARKER)
                                        showToolsMenu = false
                                    },
                                    testTag = "dock_menu_tool_fine_marker"
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))

                        // Grupo 2: Resaltador (con sub-panel desplegable: Resaltador, Pincel)
                        ExpandableGroupRow(
                            icon = emphasisIcon,
                            label = emphasisTitle,
                            isGroupActive = isEmphasisTool,
                            isExpanded = expandedEmphasis,
                            onToggleExpand = { expandedEmphasis = !expandedEmphasis },
                            onSelectDefault = {
                                onSelectTool(if (isEmphasisTool) currentTool else ToolType.HIGHLIGHTER)
                                showToolsMenu = false
                            },
                            testTag = "dock_menu_group_emphasis"
                        )
                        AnimatedVisibility(visible = expandedEmphasis) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .padding(vertical = 2.dp)
                            ) {
                                CleanSubToolItem(
                                    icon = Icons.Default.Highlight,
                                    label = "Resaltador",
                                    isSelected = currentTool == ToolType.HIGHLIGHTER,
                                    onClick = {
                                        onSelectTool(ToolType.HIGHLIGHTER)
                                        showToolsMenu = false
                                    },
                                    testTag = "dock_menu_tool_highlighter"
                                )
                                CleanSubToolItem(
                                    icon = Icons.Default.Brush,
                                    label = "Pincel",
                                    isSelected = currentTool == ToolType.BRUSH,
                                    onClick = {
                                        onSelectTool(ToolType.BRUSH)
                                        showToolsMenu = false
                                    },
                                    testTag = "dock_menu_tool_brush"
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))

                        // Grupo 3: Borrador (con sub-panel desplegable: Borrador de objeto, Borrador de píxel)
                        ExpandableGroupRow(
                            icon = Icons.Default.CleaningServices,
                            label = eraserTitle,
                            isGroupActive = isEraserTool,
                            isExpanded = expandedEraser,
                            onToggleExpand = { expandedEraser = !expandedEraser },
                            onSelectDefault = {
                                onSelectTool(if (isEraserTool) currentTool else ToolType.ERASER)
                                showToolsMenu = false
                            },
                            testTag = "dock_menu_group_eraser"
                        )
                        AnimatedVisibility(visible = expandedEraser) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .padding(vertical = 2.dp)
                            ) {
                                CleanSubToolItem(
                                    icon = Icons.Default.CleaningServices,
                                    label = "Borrador de objeto",
                                    isSelected = currentTool == ToolType.ERASER,
                                    onClick = {
                                        onSelectTool(ToolType.ERASER)
                                        showToolsMenu = false
                                    },
                                    testTag = "dock_menu_tool_eraser"
                                )
                                CleanSubToolItem(
                                    icon = Icons.Default.CleaningServices,
                                    label = "Borrador de píxel",
                                    isSelected = currentTool == ToolType.PIXEL_ERASER,
                                    onClick = {
                                        onSelectTool(ToolType.PIXEL_ERASER)
                                        showToolsMenu = false
                                    },
                                    testTag = "dock_menu_tool_pixel_eraser"
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))

                        // Manipulación (sin títulos ni subtítulos)
                        CleanToolRow(
                            icon = Icons.Default.NearMe,
                            label = "Lazo de selección",
                            isSelected = pendingShapeType == null && currentTool == ToolType.SELECT_LASSO,
                            onClick = {
                                onSelectTool(ToolType.SELECT_LASSO)
                                showToolsMenu = false
                            },
                            testTag = "dock_menu_tool_select_lasso"
                        )

                        CleanToolRow(
                            icon = Icons.Default.PanTool,
                            label = "Mover lienzo",
                            isSelected = pendingShapeType == null && currentTool == ToolType.HAND,
                            onClick = {
                                onSelectTool(ToolType.HAND)
                                showToolsMenu = false
                            },
                            testTag = "dock_menu_tool_hand"
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))

                        // Regla Toggle (sin subtítulo)
                        CleanToggleRow(
                            icon = Icons.Default.Straighten,
                            label = "Regla",
                            isActive = isRulerActive,
                            onClick = onToggleRuler,
                            testTag = "dock_menu_toggle_ruler"
                        )

                        // Formas automáticas Toggle (sin subtítulo)
                        CleanToggleRow(
                            icon = Icons.Default.AutoFixHigh,
                            label = "Formas automáticas",
                            isActive = autoShapeRecognition,
                            onClick = onToggleAutoShape,
                            testTag = "dock_menu_toggle_auto_shape"
                        )
                    }
                }

                // 2. Botón Formas Preestablecidas
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (pendingShapeType != null) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            width = 1.dp,
                            color = if (pendingShapeType != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(11.dp)
                        )
                        .clickable(onClick = onOpenShapePicker)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("dock_tool_shapes")
                ) {
                    Icon(
                        imageVector = Icons.Default.Category,
                        contentDescription = "Formas preestablecidas",
                        tint = if (pendingShapeType != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (pendingShapeType != null) shortShapeDockLabel(pendingShapeType) else "Formas",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (pendingShapeType != null) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }

                // Hairline Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                // 3. Status Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = 1.dp,
                            color = if (autoShapeRecognition || isRulerActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(11.dp)
                        )
                        .clickable { showMoreMenu = true }
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                        .testTag("dock_selected_indicator")
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(activeHighlightColor)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    val statusBadges = buildString {
                        append("${currentPenWidth.toInt()}px")
                        if (autoShapeRecognition) append(" · Auto")
                        if (isRulerActive) append(" · Regla")
                    }
                    Text(
                        text = statusBadges,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 4. 3-Dots Menu (Color, Grosor, Estilo de Línea)
                Box {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (showMoreMenu) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                width = 1.dp,
                                color = if (showMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(11.dp)
                            )
                            .clickable { showMoreMenu = !showMoreMenu }
                            .testTag("dock_more_menu_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Propiedades del trazo",
                            tint = if (showMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .width(264.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .testTag("dock_more_dropdown_menu")
                    ) {
                        // Color de Trazo
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
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

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))

                        // Estilo de Línea
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp)
                        ) {
                            StrokeStyle.values().forEach { style ->
                                val isSelected = currentPenStyle == style
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
                                        .clickable { onUpdatePenStyle(style) }
                                        .padding(vertical = 7.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = style.displayName,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableGroupRow(
    icon: ImageVector,
    label: String,
    isGroupActive: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectDefault: () -> Unit,
    testTag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isGroupActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isGroupActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isGroupActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isGroupActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Plegar" else "Desplegar",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun CleanSubToolItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Activo",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun CleanToolRow(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Activo",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun CleanToggleRow(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (isActive) "ON" else "OFF",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun shortShapeDockLabel(type: ShapeType): String {
    return when (type) {
        ShapeType.RECTANGLE -> "Rect."
        ShapeType.ROUNDED_RECT -> "Redond."
        ShapeType.CIRCLE -> "Círculo"
        ShapeType.TRIANGLE -> "Triáng."
        ShapeType.DIAMOND -> "Rombo"
        ShapeType.LINE -> "Línea"
        ShapeType.ARROW -> "Flecha"
        ShapeType.DOUBLE_ARROW -> "Doble"
        ShapeType.STAR -> "Estrella"
        ShapeType.HEXAGON -> "Hexág."
        ShapeType.CLOUD_CALLOUT -> "Diálogo"
        ShapeType.CYLINDER -> "Cilindro"
    }
}
