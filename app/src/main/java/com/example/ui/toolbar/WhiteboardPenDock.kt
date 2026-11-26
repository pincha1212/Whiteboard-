package com.example.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.model.BrushSlot
import com.example.model.StrokeBrushType
import com.example.model.ToolType
import com.example.ui.theme.StudioIcons

/**
 * Barra inferior única (o rail lateral en landscape compacto) del estudio.
 * Orden fijo de 4 grupos sin herramientas repetidas ni cajones secundarios:
 * 1. Navegación/Selección: Mover, Lazo
 * 2. Pinceles y Color: 3 slots configurables + puck de Estudio de Color
 * 3. Pintura y Borrado: Mezcla, Cubeta (con tolerancia), Borrador unificado (3 modos)
 * 4. Lienzo: Texto flotante, Modo Enfoque
 */
@Composable
fun WhiteboardPenDock(
    currentTool: ToolType,
    brushSlots: List<BrushSlot>,
    activeBrushSlotIndex: Int,
    isBrushPopoverOpen: Boolean,
    currentPenColor: String,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    floodFillTolerance: Int,
    layoutMode: DockLayoutMode = DockLayoutMode.COMPACT_PORTRAIT,
    onSelectTool: (ToolType) -> Unit,
    onSelectBrushSlot: (Int) -> Unit,
    onDismissBrushPopover: () -> Unit,
    onUpdateBrushSlot: (Int, StrokeBrushType?, Float?) -> Unit,
    onUpdateSlotOpacity: (Int, Float) -> Unit = { _, _ -> },
    onOpenColorStudio: () -> Unit = {},
    onOpenBrushStudio: (Int) -> Unit = {},
    onToggleAutoShape: () -> Unit,
    onChangeFloodFillTolerance: (Int) -> Unit,
    onAddText: () -> Unit = {},
    onEnterZenMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var lastEraserTool by remember {
        mutableStateOf(if (currentTool.isEraserTool()) currentTool else ToolType.SOFT_ERASER)
    }

    LaunchedEffect(currentTool) {
        if (currentTool.isEraserTool()) {
            lastEraserTool = currentTool
        }
    }

    val fallbackColor = MaterialTheme.colorScheme.onSurface
    val activeSlot = brushSlots.getOrNull(activeBrushSlotIndex)
    val resolvedHex = activeSlot?.colorHex ?: currentPenColor
    val resolvedRainbow = activeSlot?.isRainbow ?: isRainbowPen
    val parsedPenColor = remember(resolvedHex, fallbackColor) {
        parseDockColorSafe(resolvedHex, fallbackColor)
    }

    val isBrushToolActive = currentTool == ToolType.PENCIL ||
        currentTool == ToolType.PEN ||
        currentTool == ToolType.TECHNICAL_INK ||
        currentTool == ToolType.FINE_MARKER ||
        currentTool == ToolType.CHISEL_MARKER ||
        currentTool == ToolType.PAINT_BRUSH ||
        currentTool == ToolType.WATERCOLOR ||
        currentTool == ToolType.AIRBRUSH ||
        currentTool == ToolType.HIGHLIGHTER

    val isVertical = layoutMode == DockLayoutMode.COMPACT_LANDSCAPE_RAIL

    val dockItems: @Composable () -> Unit = {
        // 1. Navegación y Selección
        DockActionButton(
            isSelected = currentTool == ToolType.HAND,
            description = "Mover lienzo",
            onClick = { onSelectTool(ToolType.HAND) },
            testTag = "dock_tool_hand",
            minWidthDp = 44
        ) {
            Icon(
                imageVector = StudioIcons.PanTool,
                contentDescription = null,
                tint = if (currentTool == ToolType.HAND) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }

        DockActionButton(
            isSelected = currentTool == ToolType.SELECT_LASSO,
            description = "Selección con lazo",
            onClick = { onSelectTool(ToolType.SELECT_LASSO) },
            testTag = "dock_tool_select",
            minWidthDp = 44
        ) {
            Icon(
                imageVector = StudioIcons.NearMe,
                contentDescription = null,
                tint = if (currentTool == ToolType.SELECT_LASSO) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }

        DockHairlineDivider(vertical = !isVertical)

        // 2. Pinceles y Color
        BrushDock(
            brushSlots = brushSlots,
            activeBrushSlotIndex = activeBrushSlotIndex,
            isBrushToolActive = isBrushToolActive,
            isBrushPopoverOpen = isBrushPopoverOpen,
            autoShapeRecognition = autoShapeRecognition,
            onSelectBrushSlot = onSelectBrushSlot,
            onDismissBrushPopover = onDismissBrushPopover,
            onUpdateBrushSlot = onUpdateBrushSlot,
            onUpdateSlotOpacity = onUpdateSlotOpacity,
            onOpenBrushStudio = onOpenBrushStudio,
            onToggleAutoShape = onToggleAutoShape,
            isVertical = isVertical
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onOpenColorStudio)
                .semantics { contentDescription = "Estudio de color HSB" }
                .testTag("dock_color_puck_button")
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (resolvedRainbow) Brush.sweepGradient(DockRainbowColors)
                        else Brush.radialGradient(listOf(parsedPenColor, parsedPenColor))
                    )
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }

        DockHairlineDivider(vertical = !isVertical)

        // 3. Pintura y Borrado
        TrayToolSlot(
            isSelected = currentTool == ToolType.SMUDGE,
            description = "Difuminar y mezclar pigmento",
            onClick = { onSelectTool(ToolType.SMUDGE) },
            testTag = "dock_menu_tool_smudge"
        ) {
            PhysicalSmudgeIcon(
                modifier = Modifier.size(width = 24.dp, height = 36.dp)
            )
        }

        FloodFillDockButton(
            isSelected = currentTool == ToolType.FLOOD_FILL,
            fillColor = if (resolvedRainbow) Color(0xFFEC4899) else parsedPenColor,
            tolerance = floodFillTolerance,
            onSelectFloodFill = { onSelectTool(ToolType.FLOOD_FILL) },
            onChangeTolerance = onChangeFloodFillTolerance
        )

        EraserDockButton(
            currentTool = currentTool,
            lastEraserTool = lastEraserTool,
            onSelectTool = onSelectTool
        )

        DockHairlineDivider(vertical = !isVertical)

        // 4. Lienzo: Texto flotante & Modo Enfoque
        DockActionButton(
            isSelected = false,
            description = "Insertar texto en lienzo",
            onClick = onAddText,
            testTag = "dock_action_add_text",
            minWidthDp = 44
        ) {
            Icon(
                imageVector = StudioIcons.TextFields,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }

        DockActionButton(
            isSelected = false,
            description = "Modo Enfoque",
            onClick = onEnterZenMode,
            testTag = "dock_action_zen_mode",
            minWidthDp = 44
        ) {
            Icon(
                imageVector = StudioIcons.Fullscreen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (isVertical) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = modifier
                .padding(start = 10.dp, top = 64.dp, bottom = 10.dp)
                .testTag("whiteboard_pen_dock")
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .testTag("dock_main_bar")
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    dockItems()
                }
            }
        }
    } else {
        val horizontalPadding = if (layoutMode == DockLayoutMode.EXPANDED_TABLET) 16.dp else 8.dp
        val verticalPadding = if (layoutMode == DockLayoutMode.EXPANDED_TABLET) 12.dp else 10.dp
        val cornerRadius = if (layoutMode == DockLayoutMode.EXPANDED_TABLET) 18.dp else 16.dp

        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                .testTag("whiteboard_pen_dock")
        ) {
            Surface(
                shape = RoundedCornerShape(cornerRadius),
                tonalElevation = 2.dp,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .widthIn(max = 960.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(cornerRadius))
                    .testTag("dock_main_bar")
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dockItems()
                }
            }
        }
    }
}
