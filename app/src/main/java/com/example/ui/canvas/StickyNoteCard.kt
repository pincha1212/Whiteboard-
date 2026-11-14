package com.example.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.StickyNote
import kotlin.math.roundToInt

@Composable
fun StickyNoteView(
    note: StickyNote,
    scale: Float,
    panX: Float,
    panY: Float,
    onUpdateText: (String) -> Unit,
    onUpdateColor: (String) -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val noteBg = try {
        Color(android.graphics.Color.parseColor(note.colorHex))
    } catch (_: Exception) {
        Color(0xFFFFF275)
    }

    val isDarkBg = note.colorHex.equals("#2D3139", ignoreCase = true)
    val textColor = if (isDarkBg) Color.White else Color(0xFF1B1D1F)
    val headerBg = noteBg.copy(alpha = 0.85f)

    val colors = listOf(
        "#FFF275" to "Amarillo",
        "#86E3CE" to "Cian",
        "#A1F0A6" to "Menta",
        "#FF9AA2" to "Rosa",
        "#D5AAFF" to "Lavanda",
        "#FFB347" to "Naranja",
        "#2D3139" to "Pizarra"
    )

    var showPalette by remember { mutableStateOf(false) }

    val screenX = (note.x * scale + panX).roundToInt()
    val screenY = (note.y * scale + panY).roundToInt()
    val screenWidth = (note.width * scale).coerceAtLeast(140f).dp
    val screenHeight = (note.height * scale).coerceAtLeast(130f).dp

    Box(
        modifier = modifier
            .offset { IntOffset(screenX, screenY) }
            .size(screenWidth, screenHeight)
            .rotate(note.rotation)
            .shadow(
                elevation = if (isSelected) 14.dp else 6.dp,
                shape = RoundedCornerShape(8.dp)
            )
            .clip(RoundedCornerShape(8.dp))
            .background(noteBg)
            .border(
                width = if (isSelected) 2.5.dp else 0.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp)
            )
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
            .testTag("sticky_note_${note.id}")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(headerBg)
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(textColor.copy(alpha = 0.25f))
                        .clickable { showPalette = !showPalette }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Fijada",
                            tint = textColor.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onTogglePin() }
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Eliminar nota",
                            tint = textColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Color Palette Flyout
            if (showPalette) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.95f))
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    colors.forEach { (hex, _) ->
                        val c = Color(android.graphics.Color.parseColor(hex))
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(c)
                                .clickable {
                                    onUpdateColor(hex)
                                    showPalette = false
                                }
                        )
                    }
                }
            }

            // Note Content Editor
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                BasicTextField(
                    value = note.text,
                    onValueChange = onUpdateText,
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = (14f * scale).coerceIn(11f, 22f).sp,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(textColor),
                    decorationBox = { inner ->
                        if (note.text.isEmpty()) {
                            Text(
                                text = "Escribe tu idea aquí...",
                                color = textColor.copy(alpha = 0.4f),
                                fontSize = (14f * scale).coerceIn(11f, 22f).sp
                            )
                        }
                        inner()
                    },
                    modifier = Modifier.fillMaxSize().testTag("sticky_note_input_${note.id}")
                )
            }
        }
    }
}
