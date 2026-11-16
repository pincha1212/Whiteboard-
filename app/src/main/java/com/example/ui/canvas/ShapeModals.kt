package com.example.ui.canvas

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.LineCapStyle
import com.example.model.isLineLike
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokeStyle

@Composable
fun ShapePickerBottomSheet(
    initialColorHex: String = "#2563EB",
    initialStrokeStyle: StrokeStyle = StrokeStyle.SOLID,
    initialCapStyle: LineCapStyle = LineCapStyle.NONE,
    initialBrushType: StrokeBrushType = StrokeBrushType.FOUNTAIN_PEN,
    onSelectBrushType: (StrokeBrushType) -> Unit = {},
    onSelectShapeType: (ShapeType, isFilled: Boolean, strokeColor: String, fillColor: String, style: StrokeStyle, capStyle: LineCapStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = listOf(
        "#0F172A" to "#E2E8F0",
        "#2563EB" to "#DBEAFE",
        "#DC2626" to "#FEE2E2",
        "#16A34A" to "#DCFCE7",
        "#7C3AED" to "#EDE9FE",
        "#EA580C" to "#FFEDD5"
    )
    var selectedColorPair by remember(initialColorHex) {
        mutableStateOf(
            palette.firstOrNull { it.first.equals(initialColorHex, ignoreCase = true) } ?: palette[1]
        )
    }
    var isFilled by remember { mutableStateOf(false) }
    var selectedBrushType by remember(initialBrushType) { mutableStateOf(initialBrushType) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .testTag("shape_picker_modal")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Category, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Formas Preestablecidas",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Elige el trazo y arrastra en el lienzo para darle tamaño",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quick initial color & fill style before placement
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        palette.forEach { pair ->
                            val swatch = parseColor(pair.first)
                            val selected = selectedColorPair.first == pair.first
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColorPair = pair }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isFilled) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                width = 1.dp,
                                color = if (isFilled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { isFilled = !isFilled }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isFilled) "Con relleno" else "Solo línea",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isFilled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Brush Type Selector Row
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Brush,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Tipo de trazo",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StrokeBrushType.values().forEach { brush ->
                            val isSelected = selectedBrushType == brush
                            val shortLabel = when (brush) {
                                StrokeBrushType.FOUNTAIN_PEN -> "Pluma"
                                StrokeBrushType.FINE_MARKER -> "Marcador"
                                StrokeBrushType.HIGHLIGHTER -> "Resaltador"
                                StrokeBrushType.SOFT_BRUSH -> "Cerdas"
                                StrokeBrushType.CALLIGRAPHY -> "Caligrafía"
                                StrokeBrushType.AIRBRUSH -> "Aerógrafo"
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        selectedBrushType = brush
                                        onSelectBrushType(brush)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                                    .testTag("shape_picker_brush_${brush.name}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = shortLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ShapeType.values().toList().chunked(3).forEach { rowShapes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowShapes.forEach { shapeType ->
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                        .clickable {
                                            onSelectBrushType(selectedBrushType)
                                            onSelectShapeType(
                                                shapeType,
                                                isFilled && !shapeType.isLineLike,
                                                selectedColorPair.first,
                                                selectedColorPair.second,
                                                initialStrokeStyle,
                                                if (shapeType.isLineLike) {
                                                    when (shapeType) {
                                                        ShapeType.ARROW -> LineCapStyle.END_ARROW
                                                        ShapeType.DOUBLE_ARROW -> LineCapStyle.BOTH_ARROWS
                                                        else -> initialCapStyle
                                                    }
                                                } else LineCapStyle.NONE
                                            )
                                            onDismiss()
                                        }
                                        .testTag("preset_shape_${shapeType.name}")
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        PresetShapeGlyph(
                                            shapeType = shapeType,
                                            strokeHex = selectedColorPair.first,
                                            fillHex = selectedColorPair.second,
                                            isFilled = isFilled && !shapeType.isLineLike,
                                            strokeStyle = initialStrokeStyle,
                                            capStyle = if (shapeType == ShapeType.LINE) initialCapStyle else LineCapStyle.NONE,
                                            brushType = selectedBrushType,
                                            modifier = Modifier.size(34.dp)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = shapeType.displayName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            repeat(3 - rowShapes.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetShapeGlyph(
    shapeType: ShapeType,
    strokeHex: String,
    fillHex: String,
    isFilled: Boolean,
    strokeStyle: StrokeStyle = StrokeStyle.SOLID,
    capStyle: LineCapStyle = LineCapStyle.NONE,
    brushType: StrokeBrushType = StrokeBrushType.FOUNTAIN_PEN,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val pad = size.minDimension * 0.14f
        val w = (size.width - pad * 2f).coerceAtLeast(8f)
        val h = (size.height - pad * 2f).coerceAtLeast(8f)
        val item = ShapeItem(
            shapeType = shapeType,
            x = pad,
            y = pad,
            width = w,
            height = h,
            strokeColorHex = strokeHex,
            fillColorHex = fillHex,
            strokeWidth = 2.6f,
            isFilled = isFilled,
            strokeStyle = strokeStyle,
            capStyle = capStyle,
            brushType = brushType
        )
        drawShapeVector(
            shape = item,
            panX = 0f,
            panY = 0f,
            scale = 1f,
            isSelected = false,
            isOpaqueCanvasBackground = false,
            handDrawnFactor = 0.14f
        )
    }
}
