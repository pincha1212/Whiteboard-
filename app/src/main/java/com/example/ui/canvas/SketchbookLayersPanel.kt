package com.example.ui.canvas

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.zIndex
import com.example.model.DrawingLayer
import com.example.model.LayerBlendMode
import com.example.model.ShapeItem
import com.example.model.WhiteboardStroke
import com.example.ui.theme.GuideCyan
import com.example.ui.theme.StudioMonoTextStyle
import kotlin.math.roundToInt

/**
 * Floating Sketchbook-style Layers Drawer allowing users to create, reorder, duplicate,
 * merge, toggle visibility/lock, blend mode, Alpha Lock, Reference Layer, and per-layer opacity.
 */
@Composable
fun SketchbookLayersPanel(
    layers: List<DrawingLayer>,
    activeLayerId: String,
    strokes: List<WhiteboardStroke>,
    shapes: List<ShapeItem>,
    onSelectLayer: (String) -> Unit,
    onAddLayer: () -> Unit,
    onToggleVisibility: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onChangeOpacity: (String, Float) -> Unit,
    onOpacityChangeFinished: () -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onDuplicateLayer: (String) -> Unit,
    onMergeDown: (String) -> Unit,
    onDeleteLayer: (String) -> Unit,
    onDismiss: () -> Unit,
    onChangeBlendMode: (String, LayerBlendMode) -> Unit = { _, _ -> },
    onToggleAlphaLock: (String) -> Unit = {},
    onToggleReference: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeLayer = layers.find { it.id == activeLayerId } ?: layers.lastOrNull()
    val displayLayers = layers.asReversed()

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
        modifier = modifier
            .width(248.dp)
            .heightIn(max = 460.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .zIndex(38f)
            .testTag("sketchbook_layers_panel")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Capas",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable(onClick = onAddLayer)
                            .testTag("layers_add_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Nueva capa",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(onClick = onDismiss)
                            .testTag("layers_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar capas",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (activeLayer != null) {
                var showBlendMenu by remember(activeLayer.id) { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Opacidad · ${activeLayer.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(activeLayer.opacity * 100f).roundToInt()}%",
                        style = StudioMonoTextStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = activeLayer.opacity.coerceIn(0f, 1f),
                    onValueChange = { onChangeOpacity(activeLayer.id, it) },
                    onValueChangeFinished = onOpacityChangeFinished,
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .testTag("layers_active_opacity_slider")
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Blend Mode Selector Chip
                    Box {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                .clickable { showBlendMenu = true }
                                .testTag("layers_blend_mode_button")
                        ) {
                            Text(
                                text = activeLayer.blendMode.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showBlendMenu,
                            onDismissRequest = { showBlendMenu = false },
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            LayerBlendMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text("${mode.shortCode} · ${mode.displayName}") },
                                    onClick = {
                                        showBlendMenu = false
                                        onChangeBlendMode(activeLayer.id, mode)
                                    },
                                    modifier = Modifier.testTag("layer_blend_${mode.name}")
                                )
                            }
                        }
                    }

                    // Alpha Lock Toggle Chip
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (activeLayer.isAlphaLocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (activeLayer.isAlphaLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onToggleAlphaLock(activeLayer.id) }
                            .testTag("layers_alpha_lock_btn")
                    ) {
                        Text(
                            text = "Alfa",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (activeLayer.isAlphaLocked) FontWeight.Bold else FontWeight.Medium,
                            color = if (activeLayer.isAlphaLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }

                    // Reference Layer Toggle Chip
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (activeLayer.isReference) GuideCyan.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (activeLayer.isReference) GuideCyan else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onToggleReference(activeLayer.id) }
                            .testTag("layers_reference_btn")
                    ) {
                        Text(
                            text = "Ref",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (activeLayer.isReference) FontWeight.Bold else FontWeight.Medium,
                            color = if (activeLayer.isReference) GuideCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // Stack of layers (topmost at top)
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                displayLayers.forEach { layer ->
                    val isActive = layer.id == activeLayerId
                    val layerIdx = layers.indexOfFirst { it.id == layer.id }
                    val itemCount = strokes.count { it.layerId == layer.id } + shapes.count { it.layerId == layer.id }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            )
                            .border(
                                width = if (isActive) 1.5.dp else 1.dp,
                                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectLayer(layer.id) }
                            .padding(horizontal = 8.dp, vertical = 7.dp)
                            .testTag("layer_card_${layer.id}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (layer.isVisible) MaterialTheme.colorScheme.surface
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            ) {
                                Text(
                                    text = "${layerIdx + 1}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = layer.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = buildString {
                                        append("$itemCount elem · ${(layer.opacity * 100f).roundToInt()}%")
                                        if (layer.blendMode != LayerBlendMode.NORMAL) append(" · ${layer.blendMode.shortCode}")
                                        if (layer.isAlphaLocked) append(" · α")
                                        if (layer.isReference) append(" · REF")
                                    },
                                    fontSize = 10.sp,
                                    color = if (layer.isReference) GuideCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            LayerMiniIcon(
                                icon = if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                description = if (layer.isVisible) "Ocultar capa" else "Mostrar capa",
                                tint = if (layer.isVisible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                onClick = { onToggleVisibility(layer.id) }
                            )
                            LayerMiniIcon(
                                icon = if (layer.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                description = if (layer.isLocked) "Desbloquear capa" else "Bloquear capa",
                                tint = if (layer.isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = { onToggleLock(layer.id) }
                            )
                        }

                        if (isActive) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                LayerMiniIcon(
                                    icon = Icons.Default.ArrowUpward,
                                    description = "Subir capa",
                                    enabled = layerIdx < layers.lastIndex,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    onClick = { onMoveUp(layer.id) }
                                )
                                LayerMiniIcon(
                                    icon = Icons.Default.ArrowDownward,
                                    description = "Bajar capa",
                                    enabled = layerIdx > 0,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    onClick = { onMoveDown(layer.id) }
                                )
                                LayerMiniIcon(
                                    icon = Icons.Default.ContentCopy,
                                    description = "Duplicar capa",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    onClick = { onDuplicateLayer(layer.id) }
                                )
                                LayerMiniIcon(
                                    icon = Icons.AutoMirrored.Filled.MergeType,
                                    description = "Combinar hacia abajo",
                                    enabled = layerIdx > 0,
                                    tint = MaterialTheme.colorScheme.primary,
                                    onClick = { onMergeDown(layer.id) }
                                )
                                LayerMiniIcon(
                                    icon = Icons.Default.Delete,
                                    description = "Eliminar capa",
                                    enabled = layers.size > 1,
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = { onDeleteLayer(layer.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerMiniIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = 0.28f),
            modifier = Modifier.size(16.dp)
        )
    }
}
