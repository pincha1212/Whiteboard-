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
import com.example.model.ShapeType
import com.example.model.PenToolSlot
import com.example.model.ToolType

@Composable
fun WhiteboardPenDock(
    currentTool: ToolType,
    currentPenColor: String,
    currentPenWidth: Float,
    penToolSlots: List<PenToolSlot>,
    activePenSlotIndex: Int,
    onConfigurePenSlot: (Int, ToolType) -> Unit,
    onClearPenSlot: (Int) -> Unit,
    onSelectPenSlot: (Int) -> Unit,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingShapeType: ShapeType? = null,
    pendingStickyNoteColor: String? = null,
    layoutMode: DockLayoutMode = DockLayoutMode.COMPACT_PORTRAIT,
    onSelectTool: (ToolType) -> Unit,
    onUpdatePenColor: (String, isRainbow: Boolean) -> Unit,
    onUpdatePenWidth: (Float) -> Unit,
    onToggleAutoShape: () -> Unit,
    onToggleRuler: () -> Unit,
    onAddStickyNote: (String) -> Unit = {},
    onRequestPlaceStickyNote: (String) -> Unit = {},
    onAddText: () -> Unit = {},
    onOpenShapePicker: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showStyleMenu by remember { mutableStateOf(false) }
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
    val parsedPenColor = remember(currentPenColor, fallbackColor) {
        parseDockColorSafe(currentPenColor, fallbackColor)
    }

    val hasPendingPlacement = pendingShapeType != null || pendingStickyNoteColor != null
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
                currentPenColor = currentPenColor,
                parsedPenColor = parsedPenColor,
                currentPenWidth = currentPenWidth,
                penToolSlots = penToolSlots,
                activePenSlotIndex = activePenSlotIndex,
                onConfigurePenSlot = onConfigurePenSlot,
                onClearPenSlot = onClearPenSlot,
                onSelectPenSlot = onSelectPenSlot,
                isRainbowPen = isRainbowPen,
                autoShapeRecognition = autoShapeRecognition,
                isRulerActive = isRulerActive,
                pendingShapeType = pendingShapeType,
                pendingStickyNoteColor = pendingStickyNoteColor,
                isDrawingActive = isDrawingActive,
                isSelectActive = isSelectActive,
                isHandActive = isHandActive,
                lastInkingTool = lastInkingTool,
                showStyleMenu = showStyleMenu,
                onToggleStyleMenu = { showStyleMenu = !showStyleMenu },
                onDismissStyleMenu = { showStyleMenu = false },
                showStickyPalette = showStickyPalette,
                onToggleStickyPalette = { showStickyPalette = !showStickyPalette },
                onDismissStickyPalette = { showStickyPalette = false },
                onClickDrawingMode = {
                    if (!isDrawingActive) onSelectTool(lastInkingTool)
                },
                onSelectTrayTool = onSelectTrayTool,
                onSelectLasso = onSelectLasso,
                onSelectHand = onSelectHand,
                onUpdatePenColor = onUpdatePenColor,
                onUpdatePenWidth = onUpdatePenWidth,
                onToggleAutoShape = onToggleAutoShape,
                onToggleRuler = onToggleRuler,
                onAddStickyNote = onAddStickyNote,
                onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                onAddText = onAddText,
                onOpenShapePicker = onOpenShapePicker,
                modifier = modifier
            )
        }

        DockLayoutMode.COMPACT_LANDSCAPE_RAIL -> {
            CompactLandscapeRailDock(
                currentTool = currentTool,
                currentPenColor = currentPenColor,
                parsedPenColor = parsedPenColor,
                currentPenWidth = currentPenWidth,
                penToolSlots = penToolSlots,
                activePenSlotIndex = activePenSlotIndex,
                onConfigurePenSlot = onConfigurePenSlot,
                onClearPenSlot = onClearPenSlot,
                onSelectPenSlot = onSelectPenSlot,
                isRainbowPen = isRainbowPen,
                autoShapeRecognition = autoShapeRecognition,
                isRulerActive = isRulerActive,
                pendingShapeType = pendingShapeType,
                pendingStickyNoteColor = pendingStickyNoteColor,
                isDrawingActive = isDrawingActive,
                isSelectActive = isSelectActive,
                isHandActive = isHandActive,
                isInkingTrayOpen = isInkingTrayOpen,
                lastInkingTool = lastInkingTool,
                showStyleMenu = showStyleMenu,
                onToggleStyleMenu = { showStyleMenu = !showStyleMenu },
                onDismissStyleMenu = { showStyleMenu = false },
                showStickyPalette = showStickyPalette,
                onToggleStickyPalette = { showStickyPalette = !showStickyPalette },
                onDismissStickyPalette = { showStickyPalette = false },
                onClickDrawingMode = onClickDrawingMode,
                onCloseInkingTray = { isInkingTrayOpen = false },
                onSelectTrayTool = onSelectTrayTool,
                onSelectLasso = onSelectLasso,
                onSelectHand = onSelectHand,
                onUpdatePenColor = onUpdatePenColor,
                onUpdatePenWidth = onUpdatePenWidth,
                onToggleAutoShape = onToggleAutoShape,
                onToggleRuler = onToggleRuler,
                onAddStickyNote = onAddStickyNote,
                onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                onAddText = onAddText,
                onOpenShapePicker = onOpenShapePicker,
                modifier = modifier
            )
        }

        DockLayoutMode.COMPACT_PORTRAIT -> {
            CompactPortraitDock(
                currentTool = currentTool,
                currentPenColor = currentPenColor,
                parsedPenColor = parsedPenColor,
                currentPenWidth = currentPenWidth,
                penToolSlots = penToolSlots,
                activePenSlotIndex = activePenSlotIndex,
                onConfigurePenSlot = onConfigurePenSlot,
                onClearPenSlot = onClearPenSlot,
                onSelectPenSlot = onSelectPenSlot,
                isRainbowPen = isRainbowPen,
                autoShapeRecognition = autoShapeRecognition,
                isRulerActive = isRulerActive,
                pendingShapeType = pendingShapeType,
                pendingStickyNoteColor = pendingStickyNoteColor,
                isDrawingActive = isDrawingActive,
                isSelectActive = isSelectActive,
                isHandActive = isHandActive,
                isInkingTrayOpen = isInkingTrayOpen,
                lastInkingTool = lastInkingTool,
                showStyleMenu = showStyleMenu,
                onToggleStyleMenu = { showStyleMenu = !showStyleMenu },
                onDismissStyleMenu = { showStyleMenu = false },
                showStickyPalette = showStickyPalette,
                onToggleStickyPalette = { showStickyPalette = !showStickyPalette },
                onDismissStickyPalette = { showStickyPalette = false },
                onClickDrawingMode = onClickDrawingMode,
                onCloseInkingTray = { isInkingTrayOpen = false },
                onSelectTrayTool = onSelectTrayTool,
                onSelectLasso = onSelectLasso,
                onSelectHand = onSelectHand,
                onUpdatePenColor = onUpdatePenColor,
                onUpdatePenWidth = onUpdatePenWidth,
                onToggleAutoShape = onToggleAutoShape,
                onToggleRuler = onToggleRuler,
                onAddStickyNote = onAddStickyNote,
                onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                onAddText = onAddText,
                onOpenShapePicker = onOpenShapePicker,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun CompactPortraitDock(
    currentTool: ToolType,
    currentPenColor: String,
    parsedPenColor: Color,
    currentPenWidth: Float,
    penToolSlots: List<PenToolSlot>,
    activePenSlotIndex: Int,
    onConfigurePenSlot: (Int, ToolType) -> Unit,
    onClearPenSlot: (Int) -> Unit,
    onSelectPenSlot: (Int) -> Unit,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingShapeType: ShapeType?,
    pendingStickyNoteColor: String?,
    isDrawingActive: Boolean,
    isSelectActive: Boolean,
    isHandActive: Boolean,
    isInkingTrayOpen: Boolean,
    lastInkingTool: ToolType,
    showStyleMenu: Boolean,
    onToggleStyleMenu: () -> Unit,
    onDismissStyleMenu: () -> Unit,
    showStickyPalette: Boolean,
    onToggleStickyPalette: () -> Unit,
    onDismissStickyPalette: () -> Unit,
    onClickDrawingMode: () -> Unit,
    onCloseInkingTray: () -> Unit,
    onSelectTrayTool: (ToolType) -> Unit,
    onSelectLasso: () -> Unit,
    onSelectHand: () -> Unit,
    onUpdatePenColor: (String, Boolean) -> Unit,
    onUpdatePenWidth: (Float) -> Unit,
    onToggleAutoShape: () -> Unit,
    onToggleRuler: () -> Unit,
    onAddStickyNote: (String) -> Unit,
    onRequestPlaceStickyNote: (String) -> Unit,
    onAddText: () -> Unit,
    onOpenShapePicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag("whiteboard_pen_dock")
    ) {
        // Upper contextual tray: Drawing, Presentation, Erasers & Ruler (no duplicate Lasso)
        AnimatedVisibility(
            visible = isInkingTrayOpen,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            Surface(
                shape = RoundedCornerShape(WhiteboardUiMetrics.dockCorner),
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(WhiteboardUiMetrics.dockCorner))
                    .testTag("inking_tray_card")
            ) {
                InkingToolsRow(
                    currentTool = currentTool,
                    penColor = parsedPenColor,
                    penToolSlots = penToolSlots,
                    activePenSlotIndex = activePenSlotIndex,
                    onConfigurePenSlot = onConfigurePenSlot,
                    onClearPenSlot = onClearPenSlot,
                    onSelectPenSlot = onSelectPenSlot,
                    onUpdatePenColor = { onUpdatePenColor(it, false) },
                    onUpdatePenWidth = onUpdatePenWidth,
                    isRulerActive = isRulerActive,
                    pendingShapeType = pendingShapeType,
                    pendingStickyNoteColor = pendingStickyNoteColor,
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

        // Unified Main Bar: [Mode: Draw | Lasso | Hand] | [Insert: Note | Text | Shape] | [Style: Color+Width]
        Surface(
            shape = RoundedCornerShape(WhiteboardUiMetrics.dockCorner),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(WhiteboardUiMetrics.dockCorner))
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
                    pendingShapeType = pendingShapeType,
                    pendingStickyNoteColor = pendingStickyNoteColor,
                    showStickyPalette = showStickyPalette,
                    onToggleStickyPalette = onToggleStickyPalette,
                    onDismissStickyPalette = onDismissStickyPalette,
                    onAddStickyNote = onAddStickyNote,
                    onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                    onAddText = onAddText,
                    onOpenShapePicker = onOpenShapePicker,
                    isVertical = false
                )

                DockHairlineDivider(vertical = true)


            }
        }
    }
}

@Composable
private fun CompactLandscapeRailDock(
    currentTool: ToolType,
    currentPenColor: String,
    parsedPenColor: Color,
    currentPenWidth: Float,
    penToolSlots: List<PenToolSlot>,
    activePenSlotIndex: Int,
    onConfigurePenSlot: (Int, ToolType) -> Unit,
    onClearPenSlot: (Int) -> Unit,
    onSelectPenSlot: (Int) -> Unit,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingShapeType: ShapeType?,
    pendingStickyNoteColor: String?,
    isDrawingActive: Boolean,
    isSelectActive: Boolean,
    isHandActive: Boolean,
    isInkingTrayOpen: Boolean,
    lastInkingTool: ToolType,
    showStyleMenu: Boolean,
    onToggleStyleMenu: () -> Unit,
    onDismissStyleMenu: () -> Unit,
    showStickyPalette: Boolean,
    onToggleStickyPalette: () -> Unit,
    onDismissStickyPalette: () -> Unit,
    onClickDrawingMode: () -> Unit,
    onCloseInkingTray: () -> Unit,
    onSelectTrayTool: (ToolType) -> Unit,
    onSelectLasso: () -> Unit,
    onSelectHand: () -> Unit,
    onUpdatePenColor: (String, Boolean) -> Unit,
    onUpdatePenWidth: (Float) -> Unit,
    onToggleAutoShape: () -> Unit,
    onToggleRuler: () -> Unit,
    onAddStickyNote: (String) -> Unit,
    onRequestPlaceStickyNote: (String) -> Unit,
    onAddText: () -> Unit,
    onOpenShapePicker: () -> Unit,
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
            shape = RoundedCornerShape(WhiteboardUiMetrics.dockCorner),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(WhiteboardUiMetrics.dockCorner))
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
                    pendingShapeType = pendingShapeType,
                    pendingStickyNoteColor = pendingStickyNoteColor,
                    showStickyPalette = showStickyPalette,
                    onToggleStickyPalette = onToggleStickyPalette,
                    onDismissStickyPalette = onDismissStickyPalette,
                    onAddStickyNote = onAddStickyNote,
                    onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                    onAddText = onAddText,
                    onOpenShapePicker = onOpenShapePicker,
                    isVertical = true
                )

                DockHairlineDivider(vertical = false)


            }
        }

        // Secondary Vertical Inking Rail popping out to the right of the primary rail
        AnimatedVisibility(
            visible = isInkingTrayOpen,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
        ) {
            Surface(
                shape = RoundedCornerShape(WhiteboardUiMetrics.dockCorner),
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(WhiteboardUiMetrics.dockCorner))
                    .testTag("inking_tray_card")
            ) {
                InkingToolsRow(
                    currentTool = currentTool,
                    penColor = parsedPenColor,
                    penToolSlots = penToolSlots,
                    activePenSlotIndex = activePenSlotIndex,
                    onConfigurePenSlot = onConfigurePenSlot,
                    onClearPenSlot = onClearPenSlot,
                    onSelectPenSlot = onSelectPenSlot,
                    onUpdatePenColor = { onUpdatePenColor(it, false) },
                    onUpdatePenWidth = onUpdatePenWidth,
                    isRulerActive = isRulerActive,
                    pendingShapeType = pendingShapeType,
                    pendingStickyNoteColor = pendingStickyNoteColor,
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
    currentPenColor: String,
    parsedPenColor: Color,
    currentPenWidth: Float,
    penToolSlots: List<PenToolSlot>,
    activePenSlotIndex: Int,
    onConfigurePenSlot: (Int, ToolType) -> Unit,
    onClearPenSlot: (Int) -> Unit,
    onSelectPenSlot: (Int) -> Unit,
    isRainbowPen: Boolean,
    autoShapeRecognition: Boolean,
    isRulerActive: Boolean,
    pendingShapeType: ShapeType?,
    pendingStickyNoteColor: String?,
    isDrawingActive: Boolean,
    isSelectActive: Boolean,
    isHandActive: Boolean,
    lastInkingTool: ToolType,
    showStyleMenu: Boolean,
    onToggleStyleMenu: () -> Unit,
    onDismissStyleMenu: () -> Unit,
    showStickyPalette: Boolean,
    onToggleStickyPalette: () -> Unit,
    onDismissStickyPalette: () -> Unit,
    onClickDrawingMode: () -> Unit,
    onSelectTrayTool: (ToolType) -> Unit,
    onSelectLasso: () -> Unit,
    onSelectHand: () -> Unit,
    onUpdatePenColor: (String, Boolean) -> Unit,
    onUpdatePenWidth: (Float) -> Unit,
    onToggleAutoShape: () -> Unit,
    onToggleRuler: () -> Unit,
    onAddStickyNote: (String) -> Unit,
    onRequestPlaceStickyNote: (String) -> Unit,
    onAddText: () -> Unit,
    onOpenShapePicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Unified single-tier tablet capsule: zero redundant stacking, all tools 1-tap reachable
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
                    penColor = parsedPenColor,
                    penToolSlots = penToolSlots,
                    activePenSlotIndex = activePenSlotIndex,
                    onConfigurePenSlot = onConfigurePenSlot,
                    onClearPenSlot = onClearPenSlot,
                    onSelectPenSlot = onSelectPenSlot,
                    onUpdatePenColor = { onUpdatePenColor(it, false) },
                    onUpdatePenWidth = onUpdatePenWidth,
                    isRulerActive = isRulerActive,
                    pendingShapeType = pendingShapeType,
                    pendingStickyNoteColor = pendingStickyNoteColor,
                    onSelectTool = onSelectTrayTool,
                    onToggleRuler = onToggleRuler,
                    showCollapseButton = false,
                    isVertical = false,
                    modifier = Modifier.testTag("inking_tray_card")
                )

                DockHairlineDivider(vertical = true)

                InsertActionsCluster(
                    pendingShapeType = pendingShapeType,
                    pendingStickyNoteColor = pendingStickyNoteColor,
                    showStickyPalette = showStickyPalette,
                    onToggleStickyPalette = onToggleStickyPalette,
                    onDismissStickyPalette = onDismissStickyPalette,
                    onAddStickyNote = onAddStickyNote,
                    onRequestPlaceStickyNote = onRequestPlaceStickyNote,
                    onAddText = onAddText,
                    onOpenShapePicker = onOpenShapePicker,
                    isVertical = false
                )

                DockHairlineDivider(vertical = true)


            }
        }
    }
}

@Composable
private fun InsertActionsCluster(
    pendingShapeType: ShapeType?,
    pendingStickyNoteColor: String?,
    showStickyPalette: Boolean,
    onToggleStickyPalette: () -> Unit,
    onDismissStickyPalette: () -> Unit,
    onAddStickyNote: (String) -> Unit,
    onRequestPlaceStickyNote: (String) -> Unit,
    onAddText: () -> Unit,
    onOpenShapePicker: () -> Unit,
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
            description = "Crear texto y elegir su formato",
            onClick = onAddText,
            testTag = "dock_action_add_text",
            minWidthDp = 42
        ) {
            Icon(
                imageVector = Icons.Default.TextFields,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
        }

        DockActionButton(
            isSelected = pendingShapeType != null,
            description = "Insertar formas geométricas",
            onClick = onOpenShapePicker,
            testTag = "dock_action_shapes",
            minWidthDp = 42
        ) {
            PhysicalShapesIcon(
                tint = if (pendingShapeType != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
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
