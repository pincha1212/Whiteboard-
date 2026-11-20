package com.example.ui.toolbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextFields
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.BrushSlot
import com.example.model.StrokeBrushType
import com.example.model.ToolType

@Composable
fun WhiteboardPenDock(
    currentTool: ToolType,
    brushSlots: List<BrushSlot>,
    activeBrushSlotIndex: Int,
    currentPenColor: String,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingStickyNoteColor: String? = null,
    layoutMode: DockLayoutMode = DockLayoutMode.COMPACT_PORTRAIT,
    onSelectTool: (ToolType) -> Unit,
    onSelectBrushSlot: (Int) -> Unit,
    onUpdateBrushSlot: (Int, StrokeBrushType?, String?, Float?, Boolean?) -> Unit,
    onToggleAutoShape: () -> Unit,
    onToggleRuler: () -> Unit,
    onAddStickyNote: (String) -> Unit = {},
    onRequestPlaceStickyNote: (String) -> Unit = {},
    onAddText: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showStickyPalette by remember { mutableStateOf(false) }
    var isInkingTrayOpen by remember { mutableStateOf(true) }
    var lastInkingTool by remember {
        mutableStateOf(if (currentTool.isInkingOrEraseTool()) currentTool else ToolType.PEN)
    }

    LaunchedEffect(currentTool) {
        if (currentTool.isInkingOrEraseTool()) {
            lastInkingTool = currentTool
        } else if (currentTool == ToolType.HAND || currentTool == ToolType.SELECT_LASSO) {
            isInkingTrayOpen = false
        }
    }

    val fallbackColor = MaterialTheme.colorScheme.onSurface
    val activeSlot = brushSlots.getOrNull(activeBrushSlotIndex)
    val resolvedHex = activeSlot?.colorHex ?: currentPenColor
    val resolvedRainbow = activeSlot?.isRainbow ?: isRainbowPen
    val parsedPenColor = remember(resolvedHex, fallbackColor) {
        parseDockColorSafe(resolvedHex, fallbackColor)
    }

    val hasPendingPlacement = pendingStickyNoteColor != null
    val isHandActive = !hasPendingPlacement && currentTool == ToolType.HAND
    val isSelectActive = !hasPendingPlacement && currentTool == ToolType.SELECT_LASSO
    val isDrawingActive = !hasPendingPlacement && currentTool.isInkingOrEraseTool()

    val onClickDrawingMode: () -> Unit = {
        if (!isDrawingActive) {
            onSelectTool(lastInkingTool)
            isInkingTrayOpen = true
        } else {
            isInkingTrayOpen = !isInkingTrayOpen
        }
    }

    val onSelectTrayTool: (ToolType) -> Unit = { tool ->
        lastInkingTool = tool
        onSelectTool(tool)
    }

    val onSelectSlot: (Int) -> Unit = { idx ->
        brushSlots.getOrNull(idx)?.let { lastInkingTool = it.toolType }
        onSelectBrushSlot(idx)
    }

    val onSelectLasso: () -> Unit = {
        isInkingTrayOpen = false
        onSelectTool(ToolType.SELECT_LASSO)
    }

    val onSelectHand: () -> Unit = {
        isInkingTrayOpen = false
        onSelectTool(ToolType.HAND)
    }

    when (layoutMode) {
        DockLayoutMode.EXPANDED_TABLET -> {
            ExpandedTabletDock(
                currentTool = currentTool,
                brushSlots = brushSlots,
                activeBrushSlotIndex = activeBrushSlotIndex,
                parsedPenColor = parsedPenColor,
                isRainbowPen = resolvedRainbow,
                autoShapeRecognition = autoShapeRecognition,
                isRulerActive = isRulerActive,
                pendingStickyNoteColor = pendingStickyNoteColor,
                isDrawingActive = isDrawingActive,
                isSelectActive = isSelectActive,
                isHandActive = isHandActive,
                lastInkingTool = lastInkingTool,
                showStickyPalette = showStickyPalette,
                onToggleStickyPalette = { showStickyPalette = !showStickyPalette },
                onDismissStickyPalette = { showStickyPalette = false },
                onClickDrawingMode = {
                    if (!isDrawingActive) onSelectTool(lastInkingTool)
                },
                onSelectTrayTool = onSelectTrayTool,
                onSelectBrushSlot = onSelectSlot,
                onUpdateBrushSlot = onUpdateBrushSlot,
                onToggleAutoShape = onToggleAutoShape,
                onSelectLasso = onSelectLasso,
                onSelectHand = onSelectHand,
                onToggleRuler = onToggleRuler,
                onAddStickyNote = onAddStickyNote,
                onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                onAddText = onAddText,
                modifier = modifier
            )
        }

        DockLayoutMode.COMPACT_LANDSCAPE_RAIL -> {
            CompactLandscapeRailDock(
                currentTool = currentTool,
                brushSlots = brushSlots,
                activeBrushSlotIndex = activeBrushSlotIndex,
                parsedPenColor = parsedPenColor,
                isRainbowPen = resolvedRainbow,
                autoShapeRecognition = autoShapeRecognition,
                isRulerActive = isRulerActive,
                pendingStickyNoteColor = pendingStickyNoteColor,
                isDrawingActive = isDrawingActive,
                isSelectActive = isSelectActive,
                isHandActive = isHandActive,
                isInkingTrayOpen = isInkingTrayOpen,
                lastInkingTool = lastInkingTool,
                showStickyPalette = showStickyPalette,
                onToggleStickyPalette = { showStickyPalette = !showStickyPalette },
                onDismissStickyPalette = { showStickyPalette = false },
                onClickDrawingMode = onClickDrawingMode,
                onCloseInkingTray = { isInkingTrayOpen = false },
                onSelectTrayTool = onSelectTrayTool,
                onSelectBrushSlot = onSelectSlot,
                onUpdateBrushSlot = onUpdateBrushSlot,
                onToggleAutoShape = onToggleAutoShape,
                onSelectLasso = onSelectLasso,
                onSelectHand = onSelectHand,
                onToggleRuler = onToggleRuler,
                onAddStickyNote = onAddStickyNote,
                onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                onAddText = onAddText,
                modifier = modifier
            )
        }

        DockLayoutMode.COMPACT_PORTRAIT -> {
            CompactPortraitDock(
                currentTool = currentTool,
                brushSlots = brushSlots,
                activeBrushSlotIndex = activeBrushSlotIndex,
                parsedPenColor = parsedPenColor,
                isRainbowPen = resolvedRainbow,
                autoShapeRecognition = autoShapeRecognition,
                isRulerActive = isRulerActive,
                pendingStickyNoteColor = pendingStickyNoteColor,
                isDrawingActive = isDrawingActive,
                isSelectActive = isSelectActive,
                isHandActive = isHandActive,
                isInkingTrayOpen = isInkingTrayOpen,
                lastInkingTool = lastInkingTool,
                showStickyPalette = showStickyPalette,
                onToggleStickyPalette = { showStickyPalette = !showStickyPalette },
                onDismissStickyPalette = { showStickyPalette = false },
                onClickDrawingMode = onClickDrawingMode,
                onCloseInkingTray = { isInkingTrayOpen = false },
                onSelectTrayTool = onSelectTrayTool,
                onSelectBrushSlot = onSelectSlot,
                onUpdateBrushSlot = onUpdateBrushSlot,
                onToggleAutoShape = onToggleAutoShape,
                onSelectLasso = onSelectLasso,
                onSelectHand = onSelectHand,
                onToggleRuler = onToggleRuler,
                onAddStickyNote = onAddStickyNote,
                onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                onAddText = onAddText,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun CompactPortraitDock(
    currentTool: ToolType,
    brushSlots: List<BrushSlot>,
    activeBrushSlotIndex: Int,
    parsedPenColor: Color,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingStickyNoteColor: String?,
    isDrawingActive: Boolean,
    isSelectActive: Boolean,
    isHandActive: Boolean,
    isInkingTrayOpen: Boolean,
    lastInkingTool: ToolType,
    showStickyPalette: Boolean,
    onToggleStickyPalette: () -> Unit,
    onDismissStickyPalette: () -> Unit,
    onClickDrawingMode: () -> Unit,
    onCloseInkingTray: () -> Unit,
    onSelectTrayTool: (ToolType) -> Unit,
    onSelectBrushSlot: (Int) -> Unit,
    onUpdateBrushSlot: (Int, StrokeBrushType?, String?, Float?, Boolean?) -> Unit,
    onToggleAutoShape: () -> Unit,
    onSelectLasso: () -> Unit,
    onSelectHand: () -> Unit,
    onToggleRuler: () -> Unit,
    onAddStickyNote: (String) -> Unit,
    onRequestPlaceStickyNote: (String) -> Unit,
    onAddText: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag("whiteboard_pen_dock")
    ) {
        // Upper contextual tray: 3 Customizable Brush Slots (color+type+width inside each), Laser, Erasers & Ruler
        AnimatedVisibility(
            visible = isInkingTrayOpen,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .testTag("inking_tray_card")
            ) {
                InkingToolsRow(
                    currentTool = currentTool,
                    brushSlots = brushSlots,
                    activeBrushSlotIndex = activeBrushSlotIndex,
                    autoShapeRecognition = autoShapeRecognition,
                    isRulerActive = isRulerActive,
                    pendingStickyNoteColor = pendingStickyNoteColor,
                    onSelectBrushSlot = onSelectBrushSlot,
                    onUpdateBrushSlot = onUpdateBrushSlot,
                    onToggleAutoShape = onToggleAutoShape,
                    onSelectTool = onSelectTrayTool,
                    onToggleRuler = onToggleRuler,
                    showCollapseButton = true,
                    onCollapse = onCloseInkingTray,
                    isVertical = false,
                    modifier = Modifier
                        .padding(start = 6.dp, end = 4.dp, top = 2.dp, bottom = 0.dp)
                        .clipToBounds()
                )
            }
        }

        // Unified Main Bar: [Mode: Draw | Lasso | Hand] | [Insert: Note | Text]
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
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeSwitcherGroup(
                    isDrawingActive = isDrawingActive,
                    isSelectActive = isSelectActive,
                    isHandActive = isHandActive,
                    isInkingTrayOpen = isInkingTrayOpen,
                    activeInkingTool = lastInkingTool,
                    penColor = parsedPenColor,
                    isRainbowPen = isRainbowPen,
                    isVertical = false,
                    onClickDrawingMode = onClickDrawingMode,
                    onSelectLasso = onSelectLasso,
                    onSelectHand = onSelectHand
                )

                DockHairlineDivider(vertical = true)

                InsertActionsCluster(
                    pendingStickyNoteColor = pendingStickyNoteColor,
                    showStickyPalette = showStickyPalette,
                    onToggleStickyPalette = onToggleStickyPalette,
                    onDismissStickyPalette = onDismissStickyPalette,
                    onAddStickyNote = onAddStickyNote,
                    onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                    onAddText = onAddText,
                    isVertical = false
                )
            }
        }
    }
}

@Composable
private fun CompactLandscapeRailDock(
    currentTool: ToolType,
    brushSlots: List<BrushSlot>,
    activeBrushSlotIndex: Int,
    parsedPenColor: Color,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingStickyNoteColor: String?,
    isDrawingActive: Boolean,
    isSelectActive: Boolean,
    isHandActive: Boolean,
    isInkingTrayOpen: Boolean,
    lastInkingTool: ToolType,
    showStickyPalette: Boolean,
    onToggleStickyPalette: () -> Unit,
    onDismissStickyPalette: () -> Unit,
    onClickDrawingMode: () -> Unit,
    onCloseInkingTray: () -> Unit,
    onSelectTrayTool: (ToolType) -> Unit,
    onSelectBrushSlot: (Int) -> Unit,
    onUpdateBrushSlot: (Int, StrokeBrushType?, String?, Float?, Boolean?) -> Unit,
    onToggleAutoShape: () -> Unit,
    onSelectLasso: () -> Unit,
    onSelectHand: () -> Unit,
    onToggleRuler: () -> Unit,
    onAddStickyNote: (String) -> Unit,
    onRequestPlaceStickyNote: (String) -> Unit,
    onAddText: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(start = 10.dp, top = 68.dp, bottom = 10.dp)
            .testTag("whiteboard_pen_dock")
    ) {
        // Primary Vertical Rail
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
                    .padding(horizontal = 5.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ModeSwitcherGroup(
                    isDrawingActive = isDrawingActive,
                    isSelectActive = isSelectActive,
                    isHandActive = isHandActive,
                    isInkingTrayOpen = isInkingTrayOpen,
                    activeInkingTool = lastInkingTool,
                    penColor = parsedPenColor,
                    isRainbowPen = isRainbowPen,
                    isVertical = true,
                    onClickDrawingMode = onClickDrawingMode,
                    onSelectLasso = onSelectLasso,
                    onSelectHand = onSelectHand
                )

                DockHairlineDivider(vertical = false)

                InsertActionsCluster(
                    pendingStickyNoteColor = pendingStickyNoteColor,
                    showStickyPalette = showStickyPalette,
                    onToggleStickyPalette = onToggleStickyPalette,
                    onDismissStickyPalette = onDismissStickyPalette,
                    onAddStickyNote = onAddStickyNote,
                    onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                    onAddText = onAddText,
                    isVertical = true
                )
            }
        }

        // Secondary Vertical Inking Rail popping out to the right of the primary rail
        AnimatedVisibility(
            visible = isInkingTrayOpen,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .testTag("inking_tray_card")
            ) {
                InkingToolsRow(
                    currentTool = currentTool,
                    brushSlots = brushSlots,
                    activeBrushSlotIndex = activeBrushSlotIndex,
                    autoShapeRecognition = autoShapeRecognition,
                    isRulerActive = isRulerActive,
                    pendingStickyNoteColor = pendingStickyNoteColor,
                    onSelectBrushSlot = onSelectBrushSlot,
                    onUpdateBrushSlot = onUpdateBrushSlot,
                    onToggleAutoShape = onToggleAutoShape,
                    onSelectTool = onSelectTrayTool,
                    onToggleRuler = onToggleRuler,
                    showCollapseButton = true,
                    onCollapse = onCloseInkingTray,
                    isVertical = true,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ExpandedTabletDock(
    currentTool: ToolType,
    brushSlots: List<BrushSlot>,
    activeBrushSlotIndex: Int,
    parsedPenColor: Color,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingStickyNoteColor: String?,
    isDrawingActive: Boolean,
    isSelectActive: Boolean,
    isHandActive: Boolean,
    lastInkingTool: ToolType,
    showStickyPalette: Boolean,
    onToggleStickyPalette: () -> Unit,
    onDismissStickyPalette: () -> Unit,
    onClickDrawingMode: () -> Unit,
    onSelectTrayTool: (ToolType) -> Unit,
    onSelectBrushSlot: (Int) -> Unit,
    onUpdateBrushSlot: (Int, StrokeBrushType?, String?, Float?, Boolean?) -> Unit,
    onToggleAutoShape: () -> Unit,
    onSelectLasso: () -> Unit,
    onSelectHand: () -> Unit,
    onToggleRuler: () -> Unit,
    onAddStickyNote: (String) -> Unit,
    onRequestPlaceStickyNote: (String) -> Unit,
    onAddText: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Unified single-tier tablet capsule: 0 separate style button, long-press any brush slot for color+width+type
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("whiteboard_pen_dock")
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 960.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                .testTag("dock_main_bar")
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeSwitcherGroup(
                    isDrawingActive = isDrawingActive,
                    isSelectActive = isSelectActive,
                    isHandActive = isHandActive,
                    isInkingTrayOpen = true,
                    activeInkingTool = lastInkingTool,
                    penColor = parsedPenColor,
                    isRainbowPen = isRainbowPen,
                    isVertical = false,
                    onClickDrawingMode = onClickDrawingMode,
                    onSelectLasso = onSelectLasso,
                    onSelectHand = onSelectHand
                )

                DockHairlineDivider(vertical = true)

                InkingToolsRow(
                    currentTool = currentTool,
                    brushSlots = brushSlots,
                    activeBrushSlotIndex = activeBrushSlotIndex,
                    autoShapeRecognition = autoShapeRecognition,
                    isRulerActive = isRulerActive,
                    pendingStickyNoteColor = pendingStickyNoteColor,
                    onSelectBrushSlot = onSelectBrushSlot,
                    onUpdateBrushSlot = onUpdateBrushSlot,
                    onToggleAutoShape = onToggleAutoShape,
                    onSelectTool = onSelectTrayTool,
                    onToggleRuler = onToggleRuler,
                    showCollapseButton = false,
                    isVertical = false,
                    modifier = Modifier.testTag("inking_tray_card")
                )

                DockHairlineDivider(vertical = true)

                InsertActionsCluster(
                    pendingStickyNoteColor = pendingStickyNoteColor,
                    showStickyPalette = showStickyPalette,
                    onToggleStickyPalette = onToggleStickyPalette,
                    onDismissStickyPalette = onDismissStickyPalette,
                    onAddStickyNote = onAddStickyNote,
                    onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                    onAddText = onAddText,
                    isVertical = false
                )
            }
        }
    }
}

@Composable
private fun InsertActionsCluster(
    pendingStickyNoteColor: String?,
    showStickyPalette: Boolean,
    onToggleStickyPalette: () -> Unit,
    onDismissStickyPalette: () -> Unit,
    onAddStickyNote: (String) -> Unit,
    onRequestPlaceStickyNote: (String) -> Unit,
    onAddText: () -> Unit,
    isVertical: Boolean
) {
    val content: @Composable () -> Unit = {
        Box {
            DockActionButton(
                isSelected = showStickyPalette || pendingStickyNoteColor != null,
                description = "Agregar nota adhesiva",
                onClick = onToggleStickyPalette,
                testTag = "dock_action_sticky_note",
                minWidthDp = 42
            ) {
                PhysicalStickyNoteIcon(modifier = Modifier.size(22.dp))
            }

            StickyNoteDropdownMenu(
                expanded = showStickyPalette,
                onDismiss = onDismissStickyPalette,
                onAddStickyNoteCenter = onAddStickyNote,
                onRequestPlaceStickyNote = onRequestPlaceStickyNote
            )
        }

        DockActionButton(
            isSelected = false,
            description = "Insertar texto en lienzo",
            onClick = onAddText,
            testTag = "dock_action_add_text",
            minWidthDp = 42
        ) {
            Icon(
                imageVector = Icons.Default.TextFields,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (isVertical) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content()
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}
