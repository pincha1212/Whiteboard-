package com.example.ui.toolbar

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ink.engine.UnifiedBrushPipeline
import com.example.model.BrushSlot
import com.example.model.BrushStudioSettings
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.WhiteboardStroke
import com.example.ui.theme.StudioMonoTextStyle
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrushStudioSheet(
    slot: BrushSlot,
    onUpdateSettings: (BrushStudioSettings) -> Unit,
    onResetSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settings = slot.studioSettings

    val previewBitmap = remember(
        slot.brushType,
        slot.colorHex,
        slot.strokeWidth,
        slot.opacity,
        slot.isRainbow,
        settings
    ) {
        renderBrushStudioPreviewBitmap(slot)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("brush_studio_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Estudio de Pincel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = slot.brushType.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = onResetSettings,
                    modifier = Modifier.testTag("brush_studio_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restablecer")
                }
            }

            // Live bitmap preview rendered directly through UnifiedBrushPipeline
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            ) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "Vista previa del trazo de ${slot.brushType.displayName}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(86.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            }

            StudioParamSlider(
                label = "Flujo",
                value = settings.flow,
                valueRange = 0.1f..1.0f,
                displayText = "${(settings.flow * 100f).roundToInt()}%",
                testTag = "brush_studio_flow_slider",
                onValueChange = { onUpdateSettings(settings.copy(flow = it)) }
            )
            StudioParamSlider(
                label = "Espaciado",
                value = settings.spacing,
                valueRange = 0.08f..0.75f,
                displayText = "${(settings.spacing * 100f).roundToInt()}%",
                testTag = "brush_studio_spacing_slider",
                onValueChange = { onUpdateSettings(settings.copy(spacing = it)) }
            )
            StudioParamSlider(
                label = "Dureza",
                value = settings.hardness,
                valueRange = 0.05f..1.0f,
                displayText = "${(settings.hardness * 100f).roundToInt()}%",
                testTag = "brush_studio_hardness_slider",
                onValueChange = { onUpdateSettings(settings.copy(hardness = it)) }
            )
            StudioParamSlider(
                label = "Grano y textura",
                value = settings.grainStrength,
                valueRange = 0.0f..1.0f,
                displayText = "${(settings.grainStrength * 100f).roundToInt()}%",
                testTag = "brush_studio_grain_slider",
                onValueChange = { onUpdateSettings(settings.copy(grainStrength = it)) }
            )
            StudioParamSlider(
                label = "Afilado inicial",
                value = settings.taperStart,
                valueRange = 0.0f..1.0f,
                displayText = "${(settings.taperStart * 100f).roundToInt()}%",
                testTag = "brush_studio_taper_start_slider",
                onValueChange = { onUpdateSettings(settings.copy(taperStart = it)) }
            )
            StudioParamSlider(
                label = "Afilado final",
                value = settings.taperEnd,
                valueRange = 0.0f..1.0f,
                displayText = "${(settings.taperEnd * 100f).roundToInt()}%",
                testTag = "brush_studio_taper_end_slider",
                onValueChange = { onUpdateSettings(settings.copy(taperEnd = it)) }
            )
            StudioParamSlider(
                label = "Sensibilidad velocidad",
                value = settings.velocitySensitivity,
                valueRange = 0.0f..1.0f,
                displayText = "${(settings.velocitySensitivity * 100f).roundToInt()}%",
                testTag = "brush_studio_velocity_slider",
                onValueChange = { onUpdateSettings(settings.copy(velocitySensitivity = it)) }
            )

            if (slot.brushType == StrokeBrushType.CHISEL_MARKER ||
                slot.brushType == StrokeBrushType.HIGHLIGHTER
            ) {
                StudioParamSlider(
                    label = "Ángulo de bisel",
                    value = settings.chiselAngleDeg,
                    valueRange = 0f..90f,
                    displayText = "${settings.chiselAngleDeg.roundToInt()}°",
                    testTag = "brush_studio_chisel_slider",
                    onValueChange = { onUpdateSettings(settings.copy(chiselAngleDeg = it)) }
                )
            }
        }
    }
}

@Composable
private fun StudioParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayText: String,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = displayText,
                style = StudioMonoTextStyle,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

private fun renderBrushStudioPreviewBitmap(slot: BrushSlot): Bitmap {
    val widthPx = 640
    val heightPx = 152
    val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    canvas.drawColor(AndroidColor.TRANSPARENT)

    val count = 36
    val points = ArrayList<StrokePoint>(count)
    for (i in 0 until count) {
        val t = i / (count - 1).toFloat()
        val x = 36f + t * (widthPx - 72f)
        val y = heightPx * 0.5f - sin(t * 2f * Math.PI.toFloat()) * (heightPx * 0.24f)
        val pressure = 0.45f + 0.55f * sin(t * Math.PI.toFloat())
        points.add(StrokePoint(x = x, y = y, timestampMs = i * 16L, pressure = pressure))
    }

    val previewStroke = WhiteboardStroke(
        id = "studio_preview_${slot.brushType.name}",
        points = points,
        colorHex = slot.colorHex,
        width = slot.strokeWidth.coerceIn(4f, 44f),
        isHighlighter = slot.brushType == StrokeBrushType.HIGHLIGHTER,
        isRainbow = slot.isRainbow,
        brushType = slot.brushType,
        opacity = slot.opacity,
        studioSettings = slot.studioSettings
    )
    UnifiedBrushPipeline.renderStroke(
        canvas = canvas,
        stroke = previewStroke,
        panX = 0f,
        panY = 0f,
        scale = 1f,
        isDarkBoard = true,
        isOpaqueCanvasBackground = false,
        preserveInputPoints = true
    )
    return bmp
}
