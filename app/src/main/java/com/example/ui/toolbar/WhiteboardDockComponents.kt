package com.example.ui.toolbar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ToolType
import com.example.ui.theme.StudioMonoTextStyle
import kotlin.math.roundToInt

enum class DockLayoutMode {
    COMPACT_PORTRAIT,
    COMPACT_LANDSCAPE_RAIL,
    EXPANDED_TABLET
}

@Immutable
data class DockWidthOption(
    val width: Float,
    val label: String
)

internal val DockPenWidthOptions = listOf(
    DockWidthOption(3f, "Fino"),
    DockWidthOption(6f, "Medio"),
    DockWidthOption(12f, "Grueso"),
    DockWidthOption(18f, "Extra")
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
    ToolType.PENCIL,
    ToolType.PEN,
    ToolType.TECHNICAL_INK,
    ToolType.FINE_MARKER,
    ToolType.CHISEL_MARKER,
    ToolType.PAINT_BRUSH,
    ToolType.WATERCOLOR,
    ToolType.AIRBRUSH,
    ToolType.HIGHLIGHTER,
    ToolType.SMUDGE,
    ToolType.FLOOD_FILL,
    ToolType.ERASER,
    ToolType.PIXEL_ERASER,
    ToolType.SOFT_ERASER,
    ToolType.EYEDROPPER -> true
    else -> false
}

internal fun ToolType.isEraserTool(): Boolean =
    this == ToolType.SOFT_ERASER || this == ToolType.PIXEL_ERASER || this == ToolType.ERASER

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
    minWidthDp: Int = 48,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrayToolSlot(
    isSelected: Boolean,
    description: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    showCornerTriangle: Boolean = false,
    testTag: String,
    iconContent: @Composable () -> Unit
) {
    val triangleColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    }

    val interactionModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
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
            .then(interactionModifier)
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
        if (showCornerTriangle) {
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
}

@Composable
internal fun FloodFillDockButton(
    isSelected: Boolean,
    fillColor: Color,
    tolerance: Int,
    onSelectFloodFill: () -> Unit,
    onChangeTolerance: (Int) -> Unit
) {
    var showTolerancePopover by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Box {
        TrayToolSlot(
            isSelected = isSelected,
            description = "Relleno de cubeta. Toca de nuevo o mantén presionado para tolerancia",
            onClick = {
                if (isSelected) {
                    showTolerancePopover = !showTolerancePopover
                } else {
                    showTolerancePopover = false
                    onSelectFloodFill()
                }
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSelectFloodFill()
                showTolerancePopover = true
            },
            showCornerTriangle = true,
            testTag = "dock_menu_tool_flood_fill"
        ) {
            PhysicalFloodFillIcon(
                fillColor = fillColor,
                modifier = Modifier.size(width = 24.dp, height = 36.dp)
            )
        }

        DropdownMenu(
            expanded = showTolerancePopover,
            onDismissRequest = { showTolerancePopover = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .width(224.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .testTag("flood_fill_tolerance_menu")
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tolerancia de relleno",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$tolerance",
                        style = StudioMonoTextStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = tolerance.toFloat(),
                    onValueChange = { onChangeTolerance(it.roundToInt().coerceIn(4, 96)) },
                    valueRange = 4f..96f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .testTag("slider_flood_tolerance")
                )
            }
        }
    }
}

@Composable
internal fun EraserDockButton(
    currentTool: ToolType,
    lastEraserTool: ToolType,
    onSelectTool: (ToolType) -> Unit
) {
    var showEraserMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val isEraserActive = currentTool.isEraserTool()
    val displayedEraser = if (isEraserActive) currentTool else lastEraserTool

    Box {
        TrayToolSlot(
            isSelected = isEraserActive,
            description = "Borrador. Toca de nuevo o mantén presionado para cambiar modo",
            onClick = {
                if (isEraserActive) {
                    showEraserMenu = !showEraserMenu
                } else {
                    showEraserMenu = false
                    onSelectTool(lastEraserTool)
                }
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (!isEraserActive) {
                    onSelectTool(lastEraserTool)
                }
                showEraserMenu = true
            },
            showCornerTriangle = true,
            testTag = "dock_eraser_button"
        ) {
            when (displayedEraser) {
                ToolType.PIXEL_ERASER -> PhysicalPixelEraserIcon(modifier = Modifier.size(width = 24.dp, height = 36.dp))
                ToolType.ERASER -> PhysicalEraserIcon(modifier = Modifier.size(width = 24.dp, height = 36.dp))
                else -> PhysicalSoftEraserIcon(modifier = Modifier.size(width = 24.dp, height = 36.dp))
            }
        }

        DropdownMenu(
            expanded = showEraserMenu,
            onDismissRequest = { showEraserMenu = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .width(196.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .testTag("dock_eraser_mode_menu")
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "MODO DE BORRADOR",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )

                EraserModeOptionRow(
                    label = "Suave",
                    subtitle = "Borrador gradual",
                    isSelected = currentTool == ToolType.SOFT_ERASER,
                    testTag = "dock_menu_tool_soft_eraser",
                    onClick = {
                        onSelectTool(ToolType.SOFT_ERASER)
                        showEraserMenu = false
                    }
                ) {
                    PhysicalSoftEraserIcon(modifier = Modifier.size(width = 20.dp, height = 28.dp))
                }

                EraserModeOptionRow(
                    label = "Precisión",
                    subtitle = "Segmentos y píxeles",
                    isSelected = currentTool == ToolType.PIXEL_ERASER,
                    testTag = "dock_menu_tool_pixel_eraser",
                    onClick = {
                        onSelectTool(ToolType.PIXEL_ERASER)
                        showEraserMenu = false
                    }
                ) {
                    PhysicalPixelEraserIcon(modifier = Modifier.size(width = 20.dp, height = 28.dp))
                }

                EraserModeOptionRow(
                    label = "Objetos",
                    subtitle = "Trazo o figura completa",
                    isSelected = currentTool == ToolType.ERASER,
                    testTag = "dock_menu_tool_eraser",
                    onClick = {
                        onSelectTool(ToolType.ERASER)
                        showEraserMenu = false
                    }
                ) {
                    PhysicalEraserIcon(modifier = Modifier.size(width = 20.dp, height = 28.dp))
                }
            }
        }
    }
}

@Composable
private fun EraserModeOptionRow(
    label: String,
    subtitle: String,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        iconContent()
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
