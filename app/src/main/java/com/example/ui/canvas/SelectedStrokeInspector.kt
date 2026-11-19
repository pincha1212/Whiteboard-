package com.example.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StickyNote
import com.example.model.StrokeStyle
import com.example.model.WhiteboardStroke
import com.example.ui.toolbar.WhiteboardUiMetrics
import kotlin.math.hypot
import kotlin.math.roundToInt

private val SelectionStrokeColors = listOf(
    "#0F172A" to "Pizarra",
    "#2563EB" to "Azul",
    "#DC2626" to "Rojo",
    "#16A34A" to "Verde",
    "#7C3AED" to "Violeta",
    "#EA580C" to "Naranja",
    "#0891B2" to "Cian",
    "#FFFFFF" to "Blanco"
)

private val SelectionFillColors = listOf(
    "#DBEAFE" to "Azul claro",
    "#FEE2E2" to "Rojo claro",
    "#DCFCE7" to "Verde claro",
    "#FEF9C3" to "Amarillo claro",
    "#EDE9FE" to "Violeta claro",
    "#F1F5F9" to "Gris claro"
)

private val SelectionNoteColors = listOf(
    "#FFF275" to "Amarillo",
    "#86E3CE" to "Cian",
    "#A1F0A6" to "Menta",
    "#FF9AA2" to "Rosa",
    "#D5AAFF" to "Lavanda",
    "#FFB347" to "Naranja",
    "#2D3139" to "Pizarra"
)

/**
 * Modern Minimal Dock-style Precision Editor for selected shapes, lines, strokes, and sticky notes.
 * Mirrors the WhiteboardPenDock visual language and provides 1-tap controls plus a detailed 3-dots inspector menu.
 */
@Composable
fun SelectionEditDock(
    selectedShape: ShapeItem?,
    selectedStroke: WhiteboardStroke?,
    selectedNote: StickyNote?,
    totalSelectedCount: Int = 1,
    onDeleteAll: () -> Unit = {},
    onDuplicateAll: () -> Unit = {},
    onScaleAll: (Float) -> Unit = {},
    onRotateAll: (Float) -> Unit = {},
    onUpdateAllColor: (String) -> Unit = {},
    onUpdateShapeColor: (String) -> Unit,
    onUpdateShapeFillColor: (String) -> Unit,
    onToggleShapeFilled: () -> Unit,
    onUpdateShapeWidth: (Float) -> Unit,
    onUpdateShapeStyle: (StrokeStyle) -> Unit,
    onScaleShape: (Float) -> Unit,
    onRotateShape: (Float) -> Unit,
    onResetShapeRotation: () -> Unit,
    onBringShapeToFront: () -> Unit,
    onSendShapeToBack: () -> Unit,
    onDuplicateShape: () -> Unit,
    onDeleteShape: () -> Unit,
    onUpdateStrokeColor: (String) -> Unit,
    onUpdateStrokeWidth: (Float) -> Unit,
    onUpdateStrokeStyle: (StrokeStyle) -> Unit,
    onScaleStroke: (Float) -> Unit,
    onRotateStroke: (Float) -> Unit = {},
    onConvertStrokeToShape: () -> Unit,
    onDuplicateStroke: () -> Unit,
    onDeleteStroke: () -> Unit,
    onUpdateNoteColor: (String) -> Unit,
    onToggleNotePin: () -> Unit,
    onToggleNoteAspectRatioLock: () -> Unit = {},
    onDeleteNote: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedShape == null && selectedStroke == null && selectedNote == null && totalSelectedCount <= 0) return

    var showMoreMenu by remember(selectedShape?.id, selectedStroke?.id, selectedNote?.id, totalSelectedCount) {
        mutableStateOf(false)
    }

    val strokeColors = SelectionStrokeColors
    val fillColors = SelectionFillColors
    val noteColors = SelectionNoteColors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 760.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .testTag("selection_edit_dock")
        ) {
            when {
                totalSelectedCount > 1 -> {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                        .clickable { showMoreMenu = !showMoreMenu }
                                        .testTag("edit_dock_multi_color_btn"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FormatColorFill,
                                        contentDescription = "Color de la selección",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                    scrollState = rememberScrollState(),
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .width(288.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                        Text(
                                            text = "COLOR PARA $totalSelectedCount OBJETOS",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        ColorSwatchGrid(
                                            colors = strokeColors,
                                            selectedHex = selectedShape?.strokeColorHex ?: selectedStroke?.colorHex ?: "#2563EB",
                                            onSelect = {
                                                onUpdateAllColor(it)
                                                showMoreMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            DockMiniIconButton(
                                icon = Icons.Default.ZoomOut,
                                contentDescription = "Reducir selección",
                                onClick = { onScaleAll(0.86f) },
                                testTag = "edit_dock_multi_scale_down"
                            )
                            DockMiniIconButton(
                                icon = Icons.Default.ZoomIn,
                                contentDescription = "Aumentar selección",
                                onClick = { onScaleAll(1.16f) },
                                testTag = "edit_dock_multi_scale_up"
                            )

                            DockMiniIconButton(
                                icon = Icons.AutoMirrored.Filled.RotateLeft,
                                contentDescription = "Rotar selección -15°",
                                onClick = { onRotateAll(-15f) },
                                testTag = "edit_dock_multi_rotate_left"
                            )
                            DockMiniIconButton(
                                icon = Icons.AutoMirrored.Filled.RotateRight,
                                contentDescription = "Rotar selección +15°",
                                onClick = { onRotateAll(15f) },
                                testTag = "edit_dock_multi_rotate_right"
                            )

                            DockMiniIconButton(
                                icon = Icons.Default.ContentCopy,
                                contentDescription = "Duplicar seleccionados",
                                onClick = onDuplicateAll,
                                testTag = "edit_dock_multi_duplicate"
                            )

                            DockMiniIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = "Eliminar seleccionados",
                                tint = MaterialTheme.colorScheme.error,
                                bgColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                onClick = onDeleteAll,
                                testTag = "edit_dock_multi_delete"
                            )

                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .testTag("edit_dock_multi_status_pill")
                            ) {
                                Text(
                                    text = "Selección ($totalSelectedCount)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = onDone)
                                .testTag("edit_dock_multi_done"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Terminar selección",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                selectedShape != null -> {
                    val currentColor = parseColor(selectedShape.strokeColorHex)
                    val currentWidth = selectedShape.strokeWidth
                    val sizeText = if (selectedShape.shapeType == ShapeType.LINE && selectedShape.vertices.size >= 2) {
                        val len = hypot(
                            selectedShape.vertices[1].x - selectedShape.vertices[0].x,
                            selectedShape.vertices[1].y - selectedShape.vertices[0].y
                        ).roundToInt()
                        "${len}px"
                    } else {
                        "${selectedShape.width.roundToInt()}×${selectedShape.height.roundToInt()}"
                    }

                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Color dot button (opens full menu for palette)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                    .clickable { showMoreMenu = true }
                                    .testTag("edit_dock_color_btn"),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(currentColor)
                                        .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                )
                            }


                            // 3. Quick Thickness Stepper (- / px / +)
                            QuickStepperPill(
                                icon = Icons.Default.LineWeight,
                                label = "${currentWidth.roundToInt()}px",
                                onDecrement = { onUpdateShapeWidth((currentWidth - 2f).coerceAtLeast(1f)) },
                                onIncrement = { onUpdateShapeWidth((currentWidth + 2f).coerceAtMost(28f)) },
                                decTestTag = "edit_dock_width_minus",
                                incTestTag = "edit_dock_width_plus"
                            )

                            // 3. Quick Size Scale (- / +)
                            DockMiniIconButton(
                                icon = Icons.Default.ZoomOut,
                                contentDescription = "Reducir tamaño",
                                onClick = { onScaleShape(0.86f) },
                                testTag = "edit_dock_scale_down"
                            )
                            DockMiniIconButton(
                                icon = Icons.Default.ZoomIn,
                                contentDescription = "Aumentar tamaño",
                                onClick = { onScaleShape(1.16f) },
                                testTag = "edit_dock_scale_up"
                            )

                            // 4. Delete quick button
                            DockMiniIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = "Eliminar figura",
                                tint = MaterialTheme.colorScheme.error,
                                bgColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                onClick = onDeleteShape,
                                testTag = "edit_dock_delete"
                            )

                            // Divider
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )

                            // 5. Status pill next to 3-dots
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                    .clickable { showMoreMenu = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .testTag("edit_dock_status_pill")
                            ) {
                                Text(
                                    text = "${shortShapeLabel(selectedShape.shapeType)} · $sizeText",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // 6. 3-Dots Detailed Inspector Dropdown
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (showMoreMenu) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (showMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { showMoreMenu = !showMoreMenu }
                                    .testTag("edit_dock_more_btn"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Editar propiedades",
                                    tint = if (showMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                                scrollState = rememberScrollState(),
                                containerColor = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .width(316.dp)
                                    .heightIn(max = 480.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                    .testTag("edit_dock_dropdown")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "EDITAR ${selectedShape.shapeType.displayName.uppercase()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Stroke Colors
                                    Text("Color de línea", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    ColorSwatchGrid(
                                        colors = strokeColors,
                                        selectedHex = selectedShape.strokeColorHex,
                                        onSelect = onUpdateShapeColor
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Precise Width Slider + Presets
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Grosor", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${selectedShape.strokeWidth.roundToInt()} px", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                    Slider(
                                        value = selectedShape.strokeWidth.coerceIn(1f, 28f),
                                        onValueChange = onUpdateShapeWidth,
                                        valueRange = 1f..28f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary
                                        ),
                                        modifier = Modifier.height(28.dp)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        listOf(2f, 4f, 8f, 14f, 22f).forEach { w ->
                                            val isSel = kotlin.math.abs(selectedShape.strokeWidth - w) < 1f
                                            ChoiceChipBox(
                                                label = "${w.toInt()}px",
                                                isSelected = isSel,
                                                onClick = { onUpdateShapeWidth(w) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Size & Rotation Controls
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Tamaño ($sizeText)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            ChoiceChipBox(label = "-15%", isSelected = false, onClick = { onScaleShape(0.85f) })
                                            ChoiceChipBox(label = "+15%", isSelected = false, onClick = { onScaleShape(1.15f) })
                                        }
                                    }

                                    if (selectedShape.shapeType != ShapeType.LINE) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Rotación (${selectedShape.rotation.roundToInt()}°)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                DockMiniIconButton(
                                                    icon = Icons.AutoMirrored.Filled.RotateLeft,
                                                    contentDescription = "Rotar -15°",
                                                    onClick = { onRotateShape(-15f) }
                                                )
                                                ChoiceChipBox(label = "0°", isSelected = selectedShape.rotation == 0f, onClick = onResetShapeRotation)
                                                DockMiniIconButton(
                                                    icon = Icons.AutoMirrored.Filled.RotateRight,
                                                    contentDescription = "Rotar +15°",
                                                    onClick = { onRotateShape(15f) }
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Fill Toggle & Colors
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.FormatColorFill, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Relleno", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            ChoiceChipBox(
                                                label = if (selectedShape.isFilled) "Activado" else "Sin relleno",
                                                isSelected = selectedShape.isFilled,
                                                onClick = onToggleShapeFilled
                                            )
                                        }
                                        if (selectedShape.isFilled) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            ColorSwatchGrid(
                                                colors = fillColors,
                                                selectedHex = selectedShape.fillColorHex,
                                                onSelect = onUpdateShapeFillColor
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Layer & Object Actions
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            DockMiniIconButton(
                                                icon = Icons.Default.FlipToFront,
                                                contentDescription = "Traer al frente",
                                                onClick = onBringShapeToFront
                                            )
                                            DockMiniIconButton(
                                                icon = Icons.Default.FlipToBack,
                                                contentDescription = "Enviar atrás",
                                                onClick = onSendShapeToBack
                                            )
                                            DockMiniIconButton(
                                                icon = Icons.Default.ContentCopy,
                                                contentDescription = "Duplicar",
                                                onClick = {
                                                    onDuplicateShape()
                                                    showMoreMenu = false
                                                }
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.errorContainer)
                                                .clickable {
                                                    showMoreMenu = false
                                                    onDeleteShape()
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = "Eliminar",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 7. Done Button
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = onDone)
                                .testTag("edit_dock_done"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Terminar edición",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                selectedStroke != null -> {
                    val currentColor = parseColor(selectedStroke.colorHex)
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                    .clickable { showMoreMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(currentColor)
                                        .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                )
                            }

                            
                            

                            QuickStepperPill(
                                icon = Icons.Default.LineWeight,
                                label = "${selectedStroke.width.roundToInt()}px",
                                onDecrement = { onUpdateStrokeWidth((selectedStroke.width - 2f).coerceAtLeast(1f)) },
                                onIncrement = { onUpdateStrokeWidth((selectedStroke.width + 2f).coerceAtMost(28f)) },
                                decTestTag = "edit_stroke_width_minus",
                                incTestTag = "edit_stroke_width_plus"
                            )

                            DockMiniIconButton(
                                icon = Icons.Default.ZoomOut,
                                contentDescription = "Reducir trazo",
                                onClick = { onScaleStroke(0.86f) }
                            )
                            DockMiniIconButton(
                                icon = Icons.Default.ZoomIn,
                                contentDescription = "Agrandar trazo",
                                onClick = { onScaleStroke(1.16f) }
                            )

                            DockMiniIconButton(
                                icon = Icons.Default.AutoAwesome,
                                contentDescription = "Convertir a forma",
                                tint = MaterialTheme.colorScheme.primary,
                                onClick = onConvertStrokeToShape
                            )

                            DockMiniIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = "Eliminar trazo",
                                tint = MaterialTheme.colorScheme.error,
                                bgColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                onClick = onDeleteStroke
                            )
                        }

                        Box {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (showMoreMenu) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                    .clickable { showMoreMenu = !showMoreMenu },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Más opciones de trazo",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                                scrollState = rememberScrollState(),
                                containerColor = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .width(316.dp)
                                    .heightIn(max = 480.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text("EDITAR TRAZO LIBRE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    ColorSwatchGrid(
                                        colors = strokeColors,
                                        selectedHex = selectedStroke.colorHex,
                                        onSelect = onUpdateStrokeColor
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Grosor", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${selectedStroke.width.roundToInt()} px", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    }
                                    Slider(
                                        value = selectedStroke.width.coerceIn(1f, 28f),
                                        onValueChange = onUpdateStrokeWidth,
                                        valueRange = 1f..28f,
                                        modifier = Modifier.height(28.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        DockMiniIconButton(
                                            icon = Icons.AutoMirrored.Filled.RotateLeft,
                                            contentDescription = "Rotar -15°",
                                            onClick = { onRotateStroke(-15f) },
                                            testTag = "edit_stroke_rotate_left"
                                        )
                                        DockMiniIconButton(
                                            icon = Icons.AutoMirrored.Filled.RotateRight,
                                            contentDescription = "Rotar +15°",
                                            onClick = { onRotateStroke(15f) },
                                            testTag = "edit_stroke_rotate_right"
                                        )
                                        DockMiniIconButton(
                                            icon = Icons.Default.ContentCopy,
                                            contentDescription = "Duplicar",
                                            onClick = {
                                                onDuplicateStroke()
                                                showMoreMenu = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        ChoiceChipBox(
                                            label = "A forma",
                                            isSelected = false,
                                            onClick = {
                                                onConvertStrokeToShape()
                                                showMoreMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = onDone),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Terminar",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                selectedNote != null -> {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            noteColors.forEach { (hex, label) ->
                                val c = parseColor(hex)
                                val isSel = selectedNote.colorHex.equals(hex, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(
                                            width = if (isSel) 2.5.dp else 1.dp,
                                            color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            shape = CircleShape
                                        )
                                        .clickable { onUpdateNoteColor(hex) }
                                        .testTag("edit_note_color_$label")
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )

                            DockMiniIconButton(
                                icon = Icons.Default.AspectRatio,
                                contentDescription = if (selectedNote.isAspectRatioLocked) {
                                    "Proporción cuadrada 1:1 activada (toca para libre)"
                                } else {
                                    "Redimensionamiento libre (toca para proporción cuadrada 1:1)"
                                },
                                tint = if (selectedNote.isAspectRatioLocked) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                bgColor = if (selectedNote.isAspectRatioLocked) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                onClick = onToggleNoteAspectRatioLock,
                                testTag = "note_aspect_lock_button"
                            )

                            DockMiniIconButton(
                                icon = Icons.Default.PushPin,
                                contentDescription = "Fijar nota",
                                tint = if (selectedNote.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                bgColor = if (selectedNote.isPinned) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                onClick = onToggleNotePin
                            )

                            DockMiniIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = "Eliminar nota",
                                tint = MaterialTheme.colorScheme.error,
                                bgColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                onClick = onDeleteNote
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = onDone)
                                .testTag("edit_note_done"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Terminar edición de nota",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickStepperPill(
    icon: ImageVector,
    label: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decTestTag: String,
    incTestTag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 3.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .clickable(onClick = onDecrement)
                .testTag(decTestTag),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Reducir grosor", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(3.dp))
            Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .clickable(onClick = onIncrement)
                .testTag(incTestTag),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = "Aumentar grosor", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun DockMiniIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    bgColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    onClick: () -> Unit,
    testTag: String? = null
) {
    val mod = Modifier
        .size(WhiteboardUiMetrics.compactAction)
        .clip(RoundedCornerShape(WhiteboardUiMetrics.controlCorner))
        .background(bgColor)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(WhiteboardUiMetrics.controlCorner))
        .clickable(onClick = onClick)
        .let { if (testTag != null) it.testTag(testTag) else it }

    Box(modifier = mod, contentAlignment = Alignment.Center) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(WhiteboardUiMetrics.compactIcon))
    }
}

@Composable
private fun ChoiceChipBox(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun ColorSwatchGrid(
    colors: List<Pair<String, String>>,
    selectedHex: String,
    onSelect: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        colors.forEach { (hex, _) ->
            val c = parseColor(hex)
            val isSelected = selectedHex.equals(hex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(
                        width = if (isSelected) 2.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { onSelect(hex) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    val dotColor = if (hex.equals("#FFFFFF", ignoreCase = true) || hex.startsWith("#FE") || hex.startsWith("#DB") || hex.startsWith("#DC") || hex.startsWith("#ED") || hex.startsWith("#F1")) {
                        Color(0xFF0F172A)
                    } else {
                        Color.White
                    }
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
        }
    }
}

private fun shortShapeLabel(type: ShapeType): String = when (type) {
    ShapeType.LINE -> "Línea"
    ShapeType.TRIANGLE -> "Triángulo"
    ShapeType.CIRCLE -> "Círculo"
    ShapeType.RECTANGLE -> "Rectángulo"
    ShapeType.ROUNDED_RECT -> "Rect. Red."
    ShapeType.DIAMOND -> "Rombo"
    ShapeType.ARROW -> "Flecha"
    else -> type.displayName
}




