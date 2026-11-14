package com.example.ui.canvas

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LineCapStyle
import com.example.model.StrokeCategory
import com.example.model.StrokeStyle

@Composable
fun LineStyleSelectorPanel(
    selectedStyle: StrokeStyle,
    previewColorHex: String,
    onSelectStyle: (StrokeStyle) -> Unit,
    modifier: Modifier = Modifier,
    availableStyles: List<StrokeStyle> = StrokeStyle.values().toList()
) {
    val availableCategories = remember(availableStyles) {
        StrokeCategory.values().filter { cat -> availableStyles.any { it.category == cat } }
    }
    var activeCategory by remember(selectedStyle, availableCategories) {
        mutableStateOf(
            if (availableCategories.contains(selectedStyle.category)) selectedStyle.category
            else availableCategories.firstOrNull() ?: StrokeCategory.PATTERNS
        )
    }

    val stylesInCategory = remember(activeCategory, availableStyles) {
        availableStyles.filter { it.category == activeCategory }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Estilo de línea",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = selectedStyle.displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Category Tabs: Patrones / Presión / Efectos
        if (availableCategories.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                availableCategories.forEach { category ->
                    val isCatSelected = activeCategory == category
                    val hasActiveStyle = selectedStyle.category == category
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isCatSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                width = 1.dp,
                                color = when {
                                    isCatSelected -> MaterialTheme.colorScheme.primary
                                    hasActiveStyle -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { activeCategory = category }
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                            .testTag("line_category_${category.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = category.displayName,
                            fontSize = 11.sp,
                            fontWeight = if (isCatSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCatSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 2-column grid of real Canvas line previews + label
        val rows = stylesInCategory.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { rowItems ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rowItems.forEach { style ->
                        LineStylePreviewChip(
                            style = style,
                            previewColorHex = previewColorHex,
                            isSelected = selectedStyle == style,
                            onClick = { onSelectStyle(style) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LineStylePreviewChip(
    style: StrokeStyle,
    previewColorHex: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isNeon = style == StrokeStyle.NEON_GLOW
    val previewSurfaceColor = if (isNeon) {
        Color(0xFF0F172A)
    } else if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val effectivePreviewColorHex = if (isNeon && (previewColorHex.equals("#0F172A", true) || previewColorHex.equals("#1E1E1E", true))) {
        "#38BDF8"
    } else {
        previewColorHex
    }

    val animatedPhase = if (style == StrokeStyle.ANIMATED_PULSE) {
        val transition = rememberInfiniteTransition(label = "line_style_preview_pulse")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase"
        )
        value
    } else {
        0.25f
    }

    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("line_style_${style.name}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isNeon) previewSurfaceColor else Color.Transparent)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
            ) {
                AdvancedLineEffects.run {
                    drawStylePreview(
                        style = style,
                        capStyle = LineCapStyle.NONE,
                        colorHex = effectivePreviewColorHex,
                        animPhase = animatedPhase,
                        isDarkBoard = isNeon
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = style.displayName,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LineCapSelectorRow(
    selectedCapStyle: LineCapStyle,
    onSelectCapStyle: (LineCapStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Extremos (Flechas)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = selectedCapStyle.displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            LineCapStyle.values().forEach { cap ->
                val isSelected = selectedCapStyle == cap
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
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
                        .clickable { onSelectCapStyle(cap) }
                        .padding(vertical = 5.dp, horizontal = 4.dp)
                        .testTag("line_cap_${cap.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = cap.symbol,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (cap) {
                            LineCapStyle.NONE -> "Simple"
                            LineCapStyle.END_ARROW -> "Final"
                            LineCapStyle.BOTH_ARROWS -> "Ambas"
                            LineCapStyle.START_ARROW -> "Inicio"
                        },
                        fontSize = 9.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
