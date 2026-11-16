package com.example.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.notes.model.NoteItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class KeepColorOption(
    val hex: String,
    val label: String
)

val KeepNotePalette = listOf(
    KeepColorOption("#FFFFFF", "Neutro"),
    KeepColorOption("#FFF8B8", "Amarillo"),
    KeepColorOption("#D3E3FD", "Azul"),
    KeepColorOption("#D7F9E9", "Menta"),
    KeepColorOption("#FCE8E6", "Coral"),
    KeepColorOption("#E9DDF8", "Lavanda"),
    KeepColorOption("#E2E8F0", "Grafito")
)

@Composable
fun resolveKeepSurfaceColor(colorHex: String, baseColor: Color, darkFraction: Float = 0.14f, lightFraction: Float = 0.45f): Color {
    if (colorHex.equals("#FFFFFF", ignoreCase = true) || colorHex.isBlank()) {
        return baseColor
    }
    val isDark = isSystemInDarkTheme()
    val rawAccent = try {
        Color(android.graphics.Color.parseColor(colorHex))
    } catch (_: Exception) {
        return baseColor
    }
    return lerp(
        start = baseColor,
        stop = rawAccent,
        fraction = if (isDark) darkFraction else lightFraction
    )
}

@Composable
fun NoteCardView(
    note: NoteItem,
    isUnlocked: Boolean,
    isSelected: Boolean = false,
    originBoardName: String? = null,
    isTrashMode: Boolean = false,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onOpenInBoard: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
    onDeletePermanently: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showCardMenu by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    val cardBg = resolveKeepSurfaceColor(
        colorHex = note.colorHex,
        baseColor = MaterialTheme.colorScheme.surface,
        darkFraction = 0.16f,
        lightFraction = 0.50f
    )

    val isActuallyLocked = note.isLocked && !isUnlocked

    val sdf = SimpleDateFormat("d MMM", Locale.getDefault())
    val dateFormatted = sdf.format(Date(note.updatedAt))

    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        note.isPinned -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (isSelected) 1.5.dp else 1.dp

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBg,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("note_card_${note.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Keep Top Row: Title + Compact Pin & Delete actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                val displayTitle = when {
                    isActuallyLocked -> "Nota protegida"
                    note.title.isNotBlank() -> note.title
                    else -> ""
                }

                if (displayTitle.isNotEmpty()) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(end = 6.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (note.isLocked) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Nota protegida",
                            tint = if (isActuallyLocked) MaterialTheme.colorScheme.error else Color(0xFF16A34A),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    if (!isTrashMode) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable(onClick = onTogglePin),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (note.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                contentDescription = "Fijar nota",
                                tint = if (note.isPinned) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(onClick = {
                                if (isTrashMode) {
                                    onDeletePermanently?.invoke() ?: onDelete()
                                } else {
                                    onDelete()
                                }
                            })
                            .testTag("btn_delete_note_${note.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isTrashMode) Icons.Default.DeleteForever else Icons.Default.Delete,
                            contentDescription = if (isTrashMode) "Eliminar definitivamente" else "Mover a papelera",
                            tint = if (isTrashMode) MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    Box {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable { showCardMenu = true }
                                .testTag("btn_note_menu_${note.id}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Opciones de nota",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showCardMenu,
                            onDismissRequest = { showCardMenu = false },
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            if (isTrashMode) {
                                DropdownMenuItem(
                                    text = { Text("Restaurar nota") },
                                    leadingIcon = {
                                        Icon(Icons.Default.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(17.dp))
                                    },
                                    onClick = {
                                        showCardMenu = false
                                        onRestore?.invoke()
                                    },
                                    modifier = Modifier.testTag("menu_restore_note_${note.id}")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text("Eliminar definitivamente", color = MaterialTheme.colorScheme.error)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.DeleteForever,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    },
                                    onClick = {
                                        showCardMenu = false
                                        onDeletePermanently?.invoke() ?: onDelete()
                                    },
                                    modifier = Modifier.testTag("menu_purge_note_${note.id}")
                                )
                            } else {
                                if (onOpenInBoard != null) {
                                    DropdownMenuItem(
                                        text = { Text("Abrir en pizarra") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Draw,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        },
                                        onClick = {
                                            showCardMenu = false
                                            onOpenInBoard()
                                        },
                                        modifier = Modifier.testTag("menu_open_in_board_${note.id}")
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (note.isPinned) "Desfijar" else "Fijar") },
                                    leadingIcon = {
                                        Icon(
                                            if (note.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    },
                                    onClick = {
                                        showCardMenu = false
                                        onTogglePin()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Mover a papelera", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    },
                                    onClick = {
                                        showCardMenu = false
                                        onDelete()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Body: Keep-style content or locked mask
            if (isActuallyLocked) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Toca para desbloquear con PIN",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2
                )
            } else {
                if (note.title.isNotBlank() && (note.content.isNotBlank() || note.checklists.isNotEmpty())) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 5.dp)
                    )
                }
                if (note.content.isNotBlank()) {
                    val codeBg = if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)
                    val codeRed = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
                    val richPreview = RichNoteFormatter.buildDisplayAnnotatedString(
                        content = RichNoteFormatter.stripEmptyWrappers(note.content).trim(),
                        colors = RichEditorThemeColors(
                            onSurface = MaterialTheme.colorScheme.onSurfaceVariant,
                            onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
                            primary = MaterialTheme.colorScheme.primary,
                            codeBackground = codeBg,
                            codeText = codeRed,
                            quoteBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    )
                    Text(
                        text = richPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (note.title.isBlank() && note.checklists.isEmpty() && !note.isVoiceNote) {
                    Text(
                        text = "Nota vacía",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }

                // Google Keep style checklist preview lines
                if (note.checklists.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        note.checklists.take(4).forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (item.isDone) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = if (item.isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = item.text,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textDecoration = if (item.isDone) TextDecoration.LineThrough else TextDecoration.None,
                                    color = if (item.isDone) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        if (note.checklists.size > 4) {
                            Text(
                                text = "+${note.checklists.size - 4} más",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp)
                            )
                        }
                    }
                }

                // Compact Audio pill if voice note
                if (note.isVoiceNote) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Nota de voz",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Audio · ${note.voiceDurationSec}s",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Footer: origin board badge + custom folder / tags + date
            val showOriginBoard = !originBoardName.isNullOrBlank()
            val showCustomFolder = !showOriginBoard && note.folder.isNotBlank() && note.folder != "Bandeja de Entrada"
            if (showOriginBoard || showCustomFolder || note.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showOriginBoard) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                    .border(
                                        0.75.dp,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .semantics(mergeDescendants = true) {}
                                    .testTag("note_board_badge_${note.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Draw,
                                    contentDescription = "Pizarra de origen",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = originBoardName!!,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else if (showCustomFolder) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = note.folder,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        note.tags.take(2).forEach { tag ->
                            val tagColor = try {
                                Color(android.graphics.Color.parseColor(tag.colorHex))
                            } catch (_: Exception) {
                                MaterialTheme.colorScheme.primary
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(tagColor.copy(alpha = 0.12f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "#${tag.name}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isDark) tagColor.copy(alpha = 0.95f) else tagColor,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Text(
                        text = dateFormatted,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
