package com.example.ui.toolbar

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import com.example.model.GuideType
import com.example.model.GuidesOverlayState
import com.example.model.PerspectiveMode
import com.example.model.SymmetryMode
import com.example.ui.theme.GuideCyan
import com.example.ui.theme.StudioMonoTextStyle
import kotlin.math.roundToInt

@Composable
fun WhiteboardTopBar(
    boardTitle: String,
    scale: Float,
    canUndo: Boolean,
    canRedo: Boolean,
    isSnapToGrid: Boolean,
    symmetryMode: SymmetryMode = SymmetryMode.NONE,
    layerCount: Int = 1,
    isLayersPanelOpen: Boolean = false,
    guidesState: GuidesOverlayState = GuidesOverlayState(),
    isRulerVisible: Boolean = false,
    predictionLevel: Int = 2,
    floodFillTolerance: Int = 36,
    onSelectGuide: (GuideType) -> Unit = {},
    onSelectPerspectiveMode: (PerspectiveMode) -> Unit = {},
    onChangePredictionLevel: (Int) -> Unit = {},
    onChangeFloodFillTolerance: (Int) -> Unit = {},
    onCycleSymmetryMode: () -> Unit = {},
    onToggleLayersPanel: () -> Unit = {},
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onFitToContent: () -> Unit,
    onSetZoomPreset: (Float) -> Unit,
    onToggleSnapToGrid: () -> Unit,
    onEnterZenMode: () -> Unit,
    onOpenBoardsManager: () -> Unit = {},
    onOpenBackgroundSettings: () -> Unit,
    onClearBoard: () -> Unit,
    onExportBoard: () -> Unit,
    onRenameBoard: () -> Unit,
    onOpenSnapshotsAndAudit: () -> Unit = {},
    onBackToHome: (() -> Unit)? = null,
    onSwitchToNotes: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showHeaderMenu by remember { mutableStateOf(false) }
    var showGuidesMenu by remember { mutableStateOf(false) }
    val zoomPercent = (scale * 100f).roundToInt()
    val hasAnyActiveGuide = isRulerVisible ||
        guidesState.ellipse.isVisible ||
        guidesState.frenchCurve.isVisible ||
        guidesState.perspective.mode != PerspectiveMode.OFF

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .testTag("whiteboard_top_bar")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Back to Boards Home + optional Workspace Switcher
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (onBackToHome != null) {
                        TopBarActionIcon(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver a pizarras",
                            enabled = true,
                            onClick = onBackToHome,
                            testTag = "btn_topbar_back_home"
                        )
                    }

                    if (onSwitchToNotes != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                .padding(3.dp)
                        ) {
                            WorkspaceSegmentItem(
                                icon = Icons.Default.Draw,
                                label = "Pizarra",
                                isSelected = true,
                                onClick = {},
                                testTag = "btn_topbar_workspace_whiteboard"
                            )

                            Spacer(modifier = Modifier.width(2.dp))

                            WorkspaceSegmentItem(
                                icon = Icons.Default.Description,
                                label = "Notas",
                                isSelected = false,
                                onClick = onSwitchToNotes,
                                testTag = "btn_topbar_switch_to_notes"
                            )
                        }
                    }
                }

                // Center: Pertinent Info Only (Board Title + Live Zoom/Snap Summary), generous spacing
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onRenameBoard() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("btn_topbar_rename_board"),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = boardTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val snapLabel = if (isSnapToGrid) " · Imán" else ""
                    val predLabel = if (predictionLevel > 0) " · P$predictionLevel" else ""
                    Text(
                        text = "$zoomPercent%$snapLabel$predLabel",
                        style = StudioMonoTextStyle.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Right: Precision Guides, Symmetry, Layers, Undo/Redo & Overflow Menu
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box {
                        TopBarActionIcon(
                            icon = Icons.Default.Straighten,
                            contentDescription = "Guías de precisión y trazo predictivo",
                            enabled = true,
                            isHighlighted = hasAnyActiveGuide || showGuidesMenu,
                            onClick = { showGuidesMenu = !showGuidesMenu },
                            testTag = "btn_topbar_guides"
                        )

                        DropdownMenu(
                            expanded = showGuidesMenu,
                            onDismissRequest = { showGuidesMenu = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .width(256.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                .testTag("topbar_guides_menu")
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "GUÍAS Y PRECISIÓN",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    GuideChipToggle(
                                        label = "Regla",
                                        selected = isRulerVisible,
                                        modifier = Modifier.weight(1f),
                                        testTag = "guide_chip_ruler",
                                        onClick = { onSelectGuide(GuideType.RULER) }
                                    )
                                    GuideChipToggle(
                                        label = "Elipse",
                                        selected = guidesState.ellipse.isVisible,
                                        modifier = Modifier.weight(1f),
                                        testTag = "guide_chip_ellipse",
                                        onClick = { onSelectGuide(GuideType.ELLIPSE) }
                                    )
                                    GuideChipToggle(
                                        label = "Curva",
                                        selected = guidesState.frenchCurve.isVisible,
                                        modifier = Modifier.weight(1f),
                                        testTag = "guide_chip_french",
                                        onClick = { onSelectGuide(GuideType.FRENCH_CURVE) }
                                    )
                                }

                                Text(
                                    text = "PERSPECTIVA",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    PerspectiveMode.entries.forEach { mode ->
                                        GuideChipToggle(
                                            label = mode.shortCode,
                                            selected = guidesState.perspective.mode == mode,
                                            modifier = Modifier.weight(1f),
                                            testTag = "perspective_mode_${mode.name}",
                                            onClick = { onSelectPerspectiveMode(mode) }
                                        )
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Trazo predictivo",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (predictionLevel == 0) "OFF" else "Nv. $predictionLevel",
                                        style = StudioMonoTextStyle,
                                        color = GuideCyan
                                    )
                                }
                                Slider(
                                    value = predictionLevel.toFloat(),
                                    onValueChange = { onChangePredictionLevel(it.roundToInt().coerceIn(0, 5)) },
                                    valueRange = 0f..5f,
                                    steps = 4,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(26.dp)
                                        .testTag("slider_prediction_level")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Tolerancia relleno",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "$floodFillTolerance",
                                        style = StudioMonoTextStyle,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = floodFillTolerance.toFloat(),
                                    onValueChange = { onChangeFloodFillTolerance(it.roundToInt().coerceIn(4, 96)) },
                                    valueRange = 4f..96f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(26.dp)
                                        .testTag("slider_flood_tolerance")
                                )
                            }
                        }
                    }

                    TopBarActionIcon(
                        icon = Icons.Default.Flip,
                        contentDescription = symmetryMode.displayName,
                        enabled = true,
                        isHighlighted = symmetryMode != SymmetryMode.NONE,
                        onClick = onCycleSymmetryMode,
                        testTag = "btn_topbar_symmetry"
                    )

                    TopBarActionIcon(
                        icon = Icons.Default.Layers,
                        contentDescription = "Capas ($layerCount)",
                        enabled = true,
                        isHighlighted = isLayersPanelOpen,
                        onClick = onToggleLayersPanel,
                        testTag = "btn_topbar_layers"
                    )

                    TopBarActionIcon(
                        icon = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Deshacer",
                        enabled = canUndo,
                        onClick = onUndo,
                        testTag = "btn_topbar_undo"
                    )

                    TopBarActionIcon(
                        icon = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Rehacer",
                        enabled = canRedo,
                        onClick = onRedo,
                        testTag = "btn_topbar_redo"
                    )

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .width(1.dp)
                            .height(22.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    Box {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (showHeaderMenu) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (showHeaderMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { showHeaderMenu = !showHeaderMenu }
                                .testTag("btn_topbar_more_menu"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Opciones del tablero",
                                tint = if (showHeaderMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showHeaderMenu,
                            onDismissRequest = { showHeaderMenu = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .width(260.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                .testTag("topbar_dropdown_menu")
                        ) {
                            // Section 1: View & Zoom Controls
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "VISTA Y ZOOM",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "$zoomPercent%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    HeaderMenuChipButton(
                                        icon = Icons.Default.Remove,
                                        label = null,
                                        onClick = onZoomOut,
                                        modifier = Modifier.weight(1f),
                                        testTag = "menu_zoom_out"
                                    )
                                    HeaderMenuChipButton(
                                        icon = null,
                                        label = "100%",
                                        onClick = onResetZoom,
                                        modifier = Modifier.weight(1.2f),
                                        testTag = "menu_zoom_reset"
                                    )
                                    HeaderMenuChipButton(
                                        icon = Icons.Default.Add,
                                        label = null,
                                        onClick = onZoomIn,
                                        modifier = Modifier.weight(1f),
                                        testTag = "menu_zoom_in"
                                    )
                                    HeaderMenuChipButton(
                                        icon = Icons.Default.CenterFocusStrong,
                                        label = null,
                                        onClick = {
                                            onFitToContent()
                                            showHeaderMenu = false
                                        },
                                        modifier = Modifier.weight(1f),
                                        testTag = "menu_zoom_fit"
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(0.5f to "50%", 1.0f to "100%", 1.5f to "150%", 2.0f to "200%").forEach { (preset, label) ->
                                        val isCurrent = kotlin.math.abs(scale - preset) < 0.05f
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                                .border(
                                                    1.dp,
                                                    if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable { onSetZoomPreset(preset) }
                                                .padding(vertical = 5.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Section 2: Canvas Settings & Board Management
                            DropdownMenuItem(
                                text = { Text("Ajustar a cuadrícula", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Grid4x4,
                                        contentDescription = null,
                                        tint = if (isSnapToGrid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (isSnapToGrid) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Activado",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                onClick = { onToggleSnapToGrid() }
                            )

                            DropdownMenuItem(
                                text = { Text("Fondo y cuadrícula", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = {
                                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    onOpenBackgroundSettings()
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = symmetryMode.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (symmetryMode != SymmetryMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Flip,
                                        contentDescription = null,
                                        tint = if (symmetryMode != SymmetryMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = { onCycleSymmetryMode() }
                            )

                            DropdownMenuItem(
                                text = { Text("Modo Enfoque", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = {
                                    Icon(Icons.Default.Fullscreen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    onEnterZenMode()
                                }
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            DropdownMenuItem(
                                text = { Text("Snapshots y Auditoría", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = {
                                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    onOpenSnapshotsAndAudit()
                                },
                                modifier = Modifier.testTag("menu_open_snapshots_audit")
                            )

                            DropdownMenuItem(
                                text = { Text("Renombrar pizarra", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    onRenameBoard()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Exportar pizarra", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = {
                                    Icon(Icons.Default.IosShare, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    onExportBoard()
                                }
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Limpiar pizarra",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    onClearBoard()
                                },
                                modifier = Modifier.testTag("btn_topbar_clear")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderMenuChipButton(
    icon: ImageVector?,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(15.dp)
            )
        } else if (label != null) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun WorkspaceSegmentItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fgColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(enabled = !isSelected, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fgColor,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = fgColor,
            maxLines = 1
        )
    }
}

@Composable
private fun TopBarActionIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    isHighlighted: Boolean = false,
    onClick: () -> Unit,
    testTag: String
) {
    val bgColor = when {
        !enabled -> Color.Transparent
        isHighlighted -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val tintColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        isHighlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .size(31.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .then(
                if (isHighlighted && enabled) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tintColor,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun GuideChipToggle(
    label: String,
    selected: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) GuideCyan.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = if (selected) GuideCyan else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) GuideCyan else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

