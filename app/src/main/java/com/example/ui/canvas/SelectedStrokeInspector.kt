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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
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
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.TextItem
import com.example.model.WhiteboardStroke
import com.example.ui.theme.StudioIcons
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

/**
 * Floating selection inspector dock with strict zero-duplication layout:
 * - Multi-selection: [Color] [Escala -/+] [Rotar -15/+15] [Duplicar] [Eliminar] [Selección (N)] [Check]
 * - Shape: [Color dot] [Grosor -/+] [Escala -/+] [Eliminar] [Status pill] [⋮: Color/Rotación/Relleno/Orden/Duplicar] [Check]
 * - Stroke: [Color dot] [Grosor -/+] [A forma] [Eliminar] [⋮: Color/Escala/Rotar/Duplicar] [Check]
 * - Text: [Color dot] [Tamaño -/+] [Negrita] [Fondo] [Eliminar] [Check] (rotation/corner handles live on canvas)
 */
@Composable
fun SelectionEditDock(
    selectedShape: ShapeItem?,
    selectedStroke: WhiteboardStroke?,
    selectedText: TextItem? = null,
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
    onScaleShape: (Float) -> Unit,
    onRotateShape: (Float) -> Unit,
    onResetShapeRotation: () -> Unit,
    onBringShapeToFront: () -> Unit,
    onSendShapeToBack: () -> Unit,
    onDuplicateShape: () -> Unit,
    onDeleteShape: () -> Unit,
    onUpdateStrokeColor: (String) -> Unit,
    onUpdateStrokeWidth: (Float) -> Unit,
    onScaleStroke: (Float) -> Unit,
    onRotateStroke: (Float) -> Unit = {},
    onConvertStrokeToShape: () -> Unit,
    onDuplicateStroke: () -> Unit,
    onDeleteStroke: () -> Unit,
    onUpdateTextColor: (String) -> Unit = {},
    onToggleTextBackground: () -> Unit = {},
    onToggleTextBold: () -> Unit = {},
    onUpdateTextFontSize: (Float) -> Unit = {},
    isVoiceDictating: Boolean = false,
    onToggleVoiceDictation: () -> Unit = {},
    onDeleteText: () -> Unit = {},
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedShape == null && selectedStroke == null && selectedText == null && totalSelectedCount <= 0) return

    var showMoreMenu by remember(selectedShape?.id, selectedStroke?.id, selectedText?.id, totalSelectedCount) {
        mutableStateOf(false)
    }

    val strokeColors = SelectionStrokeColors
    val fillColors = SelectionFillColors

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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 40.dp, minHeight = 48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { showMoreMenu = !showMoreMenu }
                                        .testTag("edit_dock_multi_color_btn"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = StudioIcons.FormatColorFill,
                                            contentDescription = "Color de la selección",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
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
                                icon = StudioIcons.ZoomOut,
                                contentDescription = "Reducir selección",
                                onClick = { onScaleAll(0.86f) },
                                testTag = "edit_dock_multi_scale_down"
                            )
                            DockMiniIconButton(
                                icon = StudioIcons.ZoomIn,
                                contentDescription = "Aumentar selección",
                                onClick = { onScaleAll(1.16f) },
                                testTag = "edit_dock_multi_scale_up"
                            )

                            DockMiniIconButton(
                                icon = StudioIcons.RotateLeft,
                                contentDescription = "Rotar selección -15°",
                                onClick = { onRotateAll(-15f) },
                                testTag = "edit_dock_multi_rotate_left"
                            )
                            DockMiniIconButton(
                                icon = StudioIcons.RotateRight,
                                contentDescription = "Rotar selección +15°",
                                onClick = { onRotateAll(15f) },
                                testTag = "edit_dock_multi_rotate_right"
                            )

                            DockMiniIconButton(
                                icon = StudioIcons.ContentCopy,
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

                        DockDoneButton(
                            contentDescription = "Terminar selección",
                            onClick = onDone,
                            testTag = "edit_dock_multi_done"
                        )
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ColorDotTrigger(
                                color = currentColor,
                                contentDescription = "Color y propiedades de figura",
                                onClick = { showMoreMenu = true },
                                testTag = "edit_dock_color_btn"
                            )

                            QuickStepperPill(
                                icon = StudioIcons.LineWeight,
                                label = "${currentWidth.roundToInt()}px",
                                decrementDesc = "Reducir grosor",
                                incrementDesc = "Aumentar grosor",
                                onDecrement = { onUpdateShapeWidth((currentWidth - 2f).coerceAtLeast(1f)) },
                                onIncrement = { onUpdateShapeWidth((currentWidth + 2f).coerceAtMost(28f)) },
                                decTestTag = "edit_dock_width_minus",
                                incTestTag = "edit_dock_width_plus"
                            )

                            DockMiniIconButton(
                                icon = StudioIcons.ZoomOut,
                                contentDescription = "Reducir tamaño",
                                onClick = { onScaleShape(0.86f) },
                                testTag = "edit_dock_scale_down"
                            )
                            DockMiniIconButton(
                                icon = StudioIcons.ZoomIn,
                                contentDescription = "Aumentar tamaño",
                                onClick = { onScaleShape(1.16f) },
                                testTag = "edit_dock_scale_up"
                            )

                            DockMiniIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = "Eliminar figura",
                                tint = MaterialTheme.colorScheme.error,
                                bgColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                onClick = onDeleteShape,
                                testTag = "edit_dock_delete"
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
                                    .defaultMinSize(minHeight = 40.dp)
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

                        // Overflow menu: Color, Rotación, Relleno, Orden de apilado y Duplicar (sin repetir grosor, escala ni eliminar)
                        Box {
                            DockMiniIconButton(
                                icon = Icons.Default.MoreVert,
                                contentDescription = "Más propiedades de figura",
                                tint = if (showMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                bgColor = if (showMoreMenu) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                borderColor = if (showMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                onClick = { showMoreMenu = !showMoreMenu },
                                testTag = "edit_dock_more_btn"
                            )

                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                                scrollState = rememberScrollState(),
                                containerColor = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .width(304.dp)
                                    .heightIn(max = 440.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                    .testTag("edit_dock_dropdown")
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(
                                        text = "PROPIEDADES · ${selectedShape.shapeType.displayName.uppercase()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Color de línea", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    ColorSwatchGrid(
                                        colors = strokeColors,
                                        selectedHex = selectedShape.strokeColorHex,
                                        onSelect = onUpdateShapeColor
                                    )

                                    if (selectedShape.shapeType != ShapeType.LINE) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Rotación (${selectedShape.rotation.roundToInt()}°)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                DockMiniIconButton(
                                                    icon = StudioIcons.RotateLeft,
                                                    contentDescription = "Rotar -15°",
                                                    onClick = { onRotateShape(-15f) }
                                                )
                                                ChoiceChipBox(label = "0°", isSelected = selectedShape.rotation == 0f, onClick = onResetShapeRotation)
                                                DockMiniIconButton(
                                                    icon = StudioIcons.RotateRight,
                                                    contentDescription = "Rotar +15°",
                                                    onClick = { onRotateShape(15f) }
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(StudioIcons.FormatColorFill, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
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

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Orden y copia", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            DockMiniIconButton(
                                                icon = StudioIcons.FlipToFront,
                                                contentDescription = "Traer al frente",
                                                onClick = onBringShapeToFront
                                            )
                                            DockMiniIconButton(
                                                icon = StudioIcons.FlipToBack,
                                                contentDescription = "Enviar atrás",
                                                onClick = onSendShapeToBack
                                            )
                                            DockMiniIconButton(
                                                icon = StudioIcons.ContentCopy,
                                                contentDescription = "Duplicar figura",
                                                onClick = {
                                                    onDuplicateShape()
                                                    showMoreMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        DockDoneButton(
                            contentDescription = "Terminar edición",
                            onClick = onDone,
                            testTag = "edit_dock_done"
                        )
                    }
                }

                selectedStroke != null -> {
                    val currentColor = parseColor(selectedStroke.colorHex)
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ColorDotTrigger(
                                color = currentColor,
                                contentDescription = "Color del trazo",
                                onClick = { showMoreMenu = true },
                                testTag = "edit_stroke_color_btn"
                            )

                            QuickStepperPill(
                                icon = StudioIcons.LineWeight,
                                label = "${selectedStroke.width.roundToInt()}px",
                                decrementDesc = "Reducir grosor",
                                incrementDesc = "Aumentar grosor",
                                onDecrement = { onUpdateStrokeWidth((selectedStroke.width - 2f).coerceAtLeast(1f)) },
                                onIncrement = { onUpdateStrokeWidth((selectedStroke.width + 2f).coerceAtMost(28f)) },
                                decTestTag = "edit_stroke_width_minus",
                                incTestTag = "edit_stroke_width_plus"
                            )

                            DockMiniIconButton(
                                icon = StudioIcons.AutoAwesome,
                                contentDescription = "Convertir a forma",
                                tint = MaterialTheme.colorScheme.primary,
                                onClick = onConvertStrokeToShape,
                                testTag = "edit_stroke_to_shape"
                            )

                            DockMiniIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = "Eliminar trazo",
                                tint = MaterialTheme.colorScheme.error,
                                bgColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                onClick = onDeleteStroke,
                                testTag = "edit_stroke_delete"
                            )
                        }

                        // Overflow menu: Color, Escala (-/+), Rotar (-15°/+15°), Duplicar (sin repetir grosor ni A forma)
                        Box {
                            DockMiniIconButton(
                                icon = Icons.Default.MoreVert,
                                contentDescription = "Más opciones de trazo",
                                tint = if (showMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                bgColor = if (showMoreMenu) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                borderColor = if (showMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                onClick = { showMoreMenu = !showMoreMenu },
                                testTag = "edit_stroke_more_btn"
                            )

                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                                scrollState = rememberScrollState(),
                                containerColor = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .width(304.dp)
                                    .heightIn(max = 360.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(
                                        text = "PROPIEDADES DEL TRAZO",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Color del trazo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    ColorSwatchGrid(
                                        colors = strokeColors,
                                        selectedHex = selectedStroke.colorHex,
                                        onSelect = onUpdateStrokeColor
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Escala", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            DockMiniIconButton(
                                                icon = StudioIcons.ZoomOut,
                                                contentDescription = "Reducir trazo",
                                                onClick = { onScaleStroke(0.86f) }
                                            )
                                            DockMiniIconButton(
                                                icon = StudioIcons.ZoomIn,
                                                contentDescription = "Agrandar trazo",
                                                onClick = { onScaleStroke(1.16f) }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Rotar y duplicar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            DockMiniIconButton(
                                                icon = StudioIcons.RotateLeft,
                                                contentDescription = "Rotar -15°",
                                                onClick = { onRotateStroke(-15f) },
                                                testTag = "edit_stroke_rotate_left"
                                            )
                                            DockMiniIconButton(
                                                icon = StudioIcons.RotateRight,
                                                contentDescription = "Rotar +15°",
                                                onClick = { onRotateStroke(15f) },
                                                testTag = "edit_stroke_rotate_right"
                                            )
                                            DockMiniIconButton(
                                                icon = StudioIcons.ContentCopy,
                                                contentDescription = "Duplicar trazo",
                                                onClick = {
                                                    onDuplicateStroke()
                                                    showMoreMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        DockDoneButton(
                            contentDescription = "Terminar edición de trazo",
                            onClick = onDone,
                            testTag = "edit_stroke_done"
                        )
                    }
                }

                selectedText != null -> {
                    val hasCardBg = selectedText.backgroundColorHex != "TRANSPARENT"
                    val currentTextColor = parseColor(selectedText.colorHex)
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                ColorDotTrigger(
                                    color = currentTextColor,
                                    contentDescription = "Color del texto",
                                    onClick = { showMoreMenu = !showMoreMenu },
                                    testTag = "edit_text_color_btn"
                                )

                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .width(268.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                        Text(
                                            text = "COLOR DEL TEXTO",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        ColorSwatchGrid(
                                            colors = strokeColors,
                                            selectedHex = selectedText.colorHex,
                                            onSelect = { hex ->
                                                onUpdateTextColor(hex)
                                                showMoreMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            QuickStepperPill(
                                icon = StudioIcons.FormatSize,
                                label = "${selectedText.fontSize.roundToInt()}",
                                decrementDesc = "Reducir tamaño de texto",
                                incrementDesc = "Aumentar tamaño de texto",
                                onDecrement = { onUpdateTextFontSize((selectedText.fontSize - 4f).coerceAtLeast(20f)) },
                                onIncrement = { onUpdateTextFontSize((selectedText.fontSize + 4f).coerceAtMost(72f)) },
                                decTestTag = "edit_text_size_minus",
                                incTestTag = "edit_text_size_plus"
                            )

                            DockMiniIconButton(
                                icon = StudioIcons.FormatBold,
                                contentDescription = "Negrita",
                                tint = if (selectedText.isBold) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                bgColor = if (selectedText.isBold) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                onClick = onToggleTextBold,
                                testTag = "edit_text_bold_btn"
                            )

                            DockMiniIconButton(
                                icon = StudioIcons.Layers,
                                contentDescription = if (hasCardBg) "Quitar fondo de tarjeta" else "Mostrar fondo de tarjeta",
                                tint = if (hasCardBg) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                bgColor = if (hasCardBg) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                onClick = onToggleTextBackground,
                                testTag = "edit_text_bg_toggle"
                            )

                            DockMiniIconButton(
                                icon = if (isVoiceDictating) StudioIcons.Stop else StudioIcons.Mic,
                                contentDescription = if (isVoiceDictating) "Detener dictado en vivo" else "Dictar en vivo",
                                tint = if (isVoiceDictating) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary,
                                bgColor = if (isVoiceDictating) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                onClick = onToggleVoiceDictation,
                                testTag = "edit_text_voice_dictate"
                            )

                            DockMiniIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = "Eliminar texto",
                                tint = MaterialTheme.colorScheme.error,
                                bgColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                onClick = onDeleteText,
                                testTag = "edit_text_delete"
                            )
                        }

                        DockDoneButton(
                            contentDescription = "Terminar edición de texto",
                            onClick = onDone,
                            testTag = "edit_text_done"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorDotTrigger(
    color: Color,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
    }
}

@Composable
private fun DockDoneButton(
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun QuickStepperPill(
    icon: ImageVector,
    label: String,
    decrementDesc: String,
    incrementDesc: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decTestTag: String,
    incTestTag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 36.dp, minHeight = 40.dp)
                .clip(RoundedCornerShape(7.dp))
                .clickable(onClick = onDecrement)
                .testTag(decTestTag),
            contentAlignment = Alignment.Center
        ) {
            Icon(StudioIcons.Remove, contentDescription = decrementDesc, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
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
                .defaultMinSize(minWidth = 36.dp, minHeight = 40.dp)
                .clip(RoundedCornerShape(7.dp))
                .clickable(onClick = onIncrement)
                .testTag(incTestTag),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = incrementDesc, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun DockMiniIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    bgColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    onClick: () -> Unit,
    testTag: String? = null
) {
    val mod = Modifier
        .defaultMinSize(minWidth = 40.dp, minHeight = 48.dp)
        .clip(RoundedCornerShape(10.dp))
        .clickable(onClick = onClick)
        .let { if (testTag != null) it.testTag(testTag) else it }

    Box(modifier = mod, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(16.dp))
        }
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
            .defaultMinSize(minHeight = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
                    .size(26.dp)
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
