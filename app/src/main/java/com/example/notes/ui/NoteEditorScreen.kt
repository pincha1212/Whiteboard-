package com.example.notes.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.notes.model.NoteFolder
import com.example.notes.model.NoteItem
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class EditorDockTray {
    NONE,
    COLORS,
    FORMAT
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditorScreen(
    note: NoteItem,
    folders: List<NoteFolder>,
    isPreviewMode: Boolean,
    isVoicePlaying: Boolean,
    initialShowChecklist: Boolean = false,
    enableBackHandler: Boolean = false,
    originBoardName: String? = null,
    onOpenInBoard: (() -> Unit)? = null,
    onBack: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onUpdateContent: (String) -> Unit,
    onUpdateFolder: (String) -> Unit,
    onUpdateColor: (String) -> Unit = {},
    onTogglePin: () -> Unit,
    onToggleLock: () -> Unit,
    onAddChecklist: (String) -> Unit,
    onToggleChecklist: (String) -> Unit,
    onDeleteChecklist: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onTogglePreview: () -> Unit,
    onOpenVersionHistory: () -> Unit,
    onOpenExport: () -> Unit,
    onToggleVoicePlayback: () -> Unit,
    onDeleteNote: () -> Unit,
    modifier: Modifier = Modifier
) {
    val handleBack: () -> Unit = {
        val cleaned = RichNoteFormatter.stripEmptyWrappers(note.content)
        if (cleaned != note.content) {
            onUpdateContent(cleaned)
        }
        onBack()
    }

    if (enableBackHandler) {
        BackHandler { handleBack() }
    }

    val context = LocalContext.current
    var showTopOverflowMenu by remember { mutableStateOf(false) }
    var showFolderPickerMenu by remember { mutableStateOf(false) }
    var showDockOverflowMenu by remember { mutableStateOf(false) }
    var activeTray by remember { mutableStateOf(EditorDockTray.NONE) }
    var newTagInput by remember { mutableStateOf("") }
    var isAddingTag by remember { mutableStateOf(false) }
    var newChecklistInput by remember { mutableStateOf("") }

    // Rich WYSIWYG editor state
    var contentFieldValue by remember(note.id) {
        mutableStateOf(
            TextFieldValue(
                text = note.content,
                selection = TextRange(note.content.length)
            )
        )
    }
    var pendingInlineFormats by remember(note.id) { mutableStateOf<Set<NoteInlineFormat>>(emptySet()) }

    // Sync external content changes (e.g. restoring a version) without losing cursor while typing normally
    LaunchedEffect(note.content) {
        if (note.content != contentFieldValue.text) {
            contentFieldValue = TextFieldValue(
                text = note.content,
                selection = TextRange(note.content.length)
            )
        }
    }

    var localPlaying by remember(note.id) { mutableStateOf(false) }
    var playbackProgress by remember(note.id) { mutableFloatStateOf(0f) }
    val mediaPlayerHolder = remember(note.id) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(note.id, note.voiceAudioUri) {
        onDispose {
            mediaPlayerHolder.value?.let { mp ->
                try {
                    if (mp.isPlaying) mp.stop()
                } catch (_: Exception) {}
                try {
                    mp.release()
                } catch (_: Exception) {}
            }
            mediaPlayerHolder.value = null
            localPlaying = false
        }
    }

    LaunchedEffect(localPlaying) {
        while (localPlaying) {
            val mp = mediaPlayerHolder.value
            if (mp != null && mp.duration > 0) {
                playbackProgress = (mp.currentPosition.toFloat() / mp.duration.toFloat()).coerceIn(0f, 1f)
            }
            delay(100)
        }
    }

    val isDark = isSystemInDarkTheme()
    val noteBg = resolveKeepSurfaceColor(
        colorHex = note.colorHex,
        baseColor = MaterialTheme.colorScheme.background,
        darkFraction = 0.12f,
        lightFraction = 0.42f
    )
    val selectedColorDot = if (note.colorHex.equals("#FFFFFF", ignoreCase = true) || note.colorHex.isBlank()) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        try {
            Color(android.graphics.Color.parseColor(note.colorHex))
        } catch (_: Exception) {
            MaterialTheme.colorScheme.surfaceVariant
        }
    }
    val editedTimeText = remember(note.updatedAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(note.updatedAt))
    }

    val richThemeColors = RichEditorThemeColors(
        onSurface = MaterialTheme.colorScheme.onSurface,
        onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        primary = MaterialTheme.colorScheme.primary,
        codeBackground = if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9),
        codeText = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626),
        quoteBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    )

    val cursorPos = contentFieldValue.selection.start.coerceIn(0, contentFieldValue.text.length)
    val activeBlockFormat = RichNoteFormatter.detectBlockFormatAtCursor(contentFieldValue.text, cursorPos)
    val detectedInlineFormats = RichNoteFormatter.detectInlineFormatsAtCursor(contentFieldValue.text, cursorPos)
    val activeInlineFormats = pendingInlineFormats + detectedInlineFormats

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(noteBg)
            .imePadding()
            .testTag("note_editor_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. FLOATING WHITEBOARD-STYLE KEEP TOP BAR
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
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
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Back
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable(onClick = handleBack)
                                    .testTag("btn_editor_back"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Volver",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column {
                                Text(
                                    text = if (note.folder.isNotBlank() && note.folder != "Bandeja de Entrada") note.folder else "Nota",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (note.isLocked) "Protegida · $editedTimeText" else "Editado $editedTimeText",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }

                        // Right: Essential Keep Pin + Overflow 3-dots
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (note.isPinned) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(
                                        1.dp,
                                        if (note.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable(onClick = onTogglePin)
                                    .testTag("btn_editor_pin"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (note.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                    contentDescription = if (note.isPinned) "Desfijar nota" else "Fijar nota",
                                    tint = if (note.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (showTopOverflowMenu) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .border(
                                            1.dp,
                                            if (showTopOverflowMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { showTopOverflowMenu = !showTopOverflowMenu }
                                        .testTag("btn_editor_more_menu"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Más opciones de nota",
                                        tint = if (showTopOverflowMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                EditorOptionsDropdownMenu(
                                    expanded = showTopOverflowMenu,
                                    onDismissRequest = { showTopOverflowMenu = false },
                                    note = note,
                                    isPreviewMode = isPreviewMode,
                                    onOpenInBoard = onOpenInBoard?.let { openFn ->
                                        {
                                            showTopOverflowMenu = false
                                            openFn()
                                        }
                                    },
                                    onTogglePreview = {
                                        onTogglePreview()
                                        showTopOverflowMenu = false
                                    },
                                    onOpenFolderPicker = {
                                        showTopOverflowMenu = false
                                        showFolderPickerMenu = true
                                    },
                                    onStartAddTag = {
                                        isAddingTag = true
                                        showTopOverflowMenu = false
                                    },
                                    onToggleLock = {
                                        onToggleLock()
                                        showTopOverflowMenu = false
                                    },
                                    onOpenVersionHistory = {
                                        showTopOverflowMenu = false
                                        onOpenVersionHistory()
                                    },
                                    onOpenExport = {
                                        showTopOverflowMenu = false
                                        onOpenExport()
                                    },
                                    onDeleteNote = {
                                        showTopOverflowMenu = false
                                        onDeleteNote()
                                    },
                                    includeDeleteTestTag = true
                                )

                                // Folder Picker Dropdown
                                DropdownMenu(
                                    expanded = showFolderPickerMenu,
                                    onDismissRequest = { showFolderPickerMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .width(210.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                ) {
                                    Text(
                                        text = "MOVER A CARPETA",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                    val folderNames = (listOf("Bandeja de Entrada") + folders.map { it.name }).distinct()
                                    folderNames.forEach { folderName ->
                                        val selected = note.folder.equals(folderName, ignoreCase = true)
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = folderName,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            trailingIcon = if (selected) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            } else null,
                                            onClick = {
                                                onUpdateFolder(folderName)
                                                showFolderPickerMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. GOOGLE KEEP SCROLLABLE CANVAS
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                // Keep Title Input
                BasicTextField(
                    value = note.title,
                    onValueChange = onUpdateTitle,
                    textStyle = TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        if (note.title.isEmpty()) {
                            Text(
                                text = "Título",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                        innerTextField()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("editor_title_input")
                )

                if (!originBoardName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                RoundedCornerShape(8.dp)
                            )
                            .let { mod -> if (onOpenInBoard != null) mod.clickable(onClick = onOpenInBoard) else mod }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("btn_editor_open_in_board")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Draw,
                            contentDescription = "Pizarra de origen",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (onOpenInBoard != null) "Pizarra: $originBoardName · Abrir en pizarra" else "Pizarra: $originBoardName",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )

                // Voice Note Player Banner (if voice note)
                if (note.isVoiceNote) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    val path = note.voiceAudioUri
                                    if (path.isNullOrBlank() || !File(path).exists()) {
                                        Toast.makeText(context, "Archivo de audio no disponible", Toast.LENGTH_SHORT).show()
                                        return@IconButton
                                    }
                                    if (localPlaying) {
                                        try {
                                            mediaPlayerHolder.value?.pause()
                                        } catch (_: Exception) {}
                                        localPlaying = false
                                    } else {
                                        try {
                                            val existing = mediaPlayerHolder.value
                                            val player = existing ?: MediaPlayer().apply {
                                                setDataSource(path)
                                                prepare()
                                                setOnCompletionListener {
                                                    localPlaying = false
                                                    playbackProgress = 0f
                                                }
                                                mediaPlayerHolder.value = this
                                            }
                                            player.start()
                                            localPlaying = true
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "No se pudo reproducir el audio", Toast.LENGTH_SHORT).show()
                                            localPlaying = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(
                                    imageVector = if (localPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (localPlaying) "Pausar audio" else "Reproducir audio",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).height(28.dp)
                            ) {
                                val heights = listOf(12, 20, 10, 26, 16, 28, 12, 22, 18, 14, 24, 12, 18, 22, 14, 24)
                                heights.forEachIndexed { idx, h ->
                                    val barThreshold = (idx + 1).toFloat() / heights.size.toFloat()
                                    val isPlayed = playbackProgress >= barThreshold
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(h.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                if (isPlayed) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                                            )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Text(
                                text = "${note.voiceDurationSec}s",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Interactive Checklists Section (only shown when opened via "Nueva lista" or note already has checklist items)
                if (initialShowChecklist || note.checklists.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    if (note.checklists.isNotEmpty()) {
                        val done = note.checklists.count { it.isDone }
                        val total = note.checklists.size
                        val progress = if (total > 0) done.toFloat() / total else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        note.checklists.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                IconButton(
                                    onClick = { onToggleChecklist(item.id) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = if (item.isDone) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                        contentDescription = if (item.isDone) "Completada" else "Pendiente",
                                        tint = if (item.isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                Text(
                                    text = item.text,
                                    fontSize = 14.sp,
                                    textDecoration = if (item.isDone) TextDecoration.LineThrough else TextDecoration.None,
                                    color = if (item.isDone) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )

                                IconButton(
                                    onClick = { onDeleteChecklist(item.id) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Quitar elemento",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).padding(start = 4.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = newChecklistInput,
                            onValueChange = { newChecklistInput = it },
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (newChecklistInput.isNotBlank()) {
                                        onAddChecklist(newChecklistInput)
                                        newChecklistInput = ""
                                    }
                                }
                            ),
                            decorationBox = { inner ->
                                if (newChecklistInput.isEmpty()) {
                                    Text(
                                        text = "Elemento de lista...",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                    )
                                }
                                inner()
                            },
                            modifier = Modifier.weight(1f).padding(vertical = 6.dp).testTag("editor_checklist_input")
                        )

                        if (newChecklistInput.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    onAddChecklist(newChecklistInput)
                                    newChecklistInput = ""
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Añadir tarea",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Main Visual Rich-Text Content Area
                if (isPreviewMode) {
                    RenderRichPreview(
                        content = RichNoteFormatter.stripEmptyWrappers(note.content),
                        colors = richThemeColors
                    )
                } else {
                    val hasAnyActiveFormat = activeBlockFormat != NoteBlockFormat.NONE || activeInlineFormats.isNotEmpty()
                    val baseFontSize = when (activeBlockFormat) {
                        NoteBlockFormat.H1 -> 24.sp
                        NoteBlockFormat.H2 -> 19.sp
                        else -> 15.sp
                    }
                    val baseLineHeight = when (activeBlockFormat) {
                        NoteBlockFormat.H1 -> 32.sp
                        NoteBlockFormat.H2 -> 26.sp
                        else -> 23.sp
                    }

                    BasicTextField(
                        value = contentFieldValue,
                        onValueChange = { incoming ->
                            val (processedValue, nextPending) = RichNoteFormatter.processTextChange(
                                previous = contentFieldValue,
                                incoming = incoming,
                                pendingFormats = pendingInlineFormats
                            )
                            contentFieldValue = processedValue
                            pendingInlineFormats = nextPending
                            if (processedValue.text != note.content) {
                                onUpdateContent(processedValue.text)
                            }
                        },
                        textStyle = TextStyle(
                            fontSize = baseFontSize,
                            lineHeight = baseLineHeight,
                            fontFamily = if (NoteInlineFormat.CODE in activeInlineFormats) FontFamily.Monospace else FontFamily.Default,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(
                            if (NoteInlineFormat.CODE in activeInlineFormats) richThemeColors.codeText
                            else MaterialTheme.colorScheme.primary
                        ),
                        visualTransformation = RichNoteVisualTransformation(
                            colors = richThemeColors,
                            cursorOffset = contentFieldValue.selection.start,
                            pendingFormats = pendingInlineFormats
                        ),
                        decorationBox = { innerTextField ->
                            // Show minimal "Nota" hint ONLY when genuinely empty and no formatting style is active
                            if (contentFieldValue.text.isEmpty() && !hasAnyActiveFormat) {
                                Text(
                                    text = "Nota",
                                    fontSize = 15.sp,
                                    lineHeight = 23.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                )
                            }
                            innerTextField()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp)
                            .testTag("editor_content_input")
                    )
                }

                // Subtle Keep Label/Tag & Folder Chips at bottom of content
                if (note.tags.isNotEmpty() || isAddingTag || (note.folder.isNotBlank() && note.folder != "Bandeja de Entrada")) {
                    Spacer(modifier = Modifier.height(20.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (note.folder.isNotBlank() && note.folder != "Bandeja de Entrada") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable { showFolderPickerMenu = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = note.folder,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        note.tags.forEach { tag ->
                            val tagColor = try {
                                Color(android.graphics.Color.parseColor(tag.colorHex))
                            } catch (_: Exception) {
                                MaterialTheme.colorScheme.primary
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(tagColor.copy(alpha = 0.14f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "#${tag.name}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) tagColor.copy(alpha = 0.95f) else tagColor
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Eliminar etiqueta",
                                    tint = if (isDark) tagColor.copy(alpha = 0.95f) else tagColor,
                                    modifier = Modifier.size(12.dp).clickable { onRemoveTag(tag.name) }
                                )
                            }
                        }

                        if (isAddingTag) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                BasicTextField(
                                    value = newTagInput,
                                    onValueChange = { newTagInput = it },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            if (newTagInput.isNotBlank()) {
                                                onAddTag(newTagInput)
                                                newTagInput = ""
                                            }
                                            isAddingTag = false
                                        }
                                    ),
                                    decorationBox = { inner ->
                                        if (newTagInput.isEmpty()) {
                                            Text("etiqueta...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        inner()
                                    },
                                    modifier = Modifier.width(76.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Guardar etiqueta",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp).clickable {
                                        if (newTagInput.isNotBlank()) {
                                            onAddTag(newTagInput)
                                            newTagInput = ""
                                        }
                                        isAddingTag = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(120.dp))
            }
        }

        // 3. FLOATING WHITEBOARD-STYLE KEEP BOTTOM EDITOR DOCK
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Keep Color Palette Tray above dock
            AnimatedVisibility(visible = activeTray == EditorDockTray.COLORS) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        items(KeepNotePalette) { option ->
                            val swatchBg = if (option.hex == "#FFFFFF") MaterialTheme.colorScheme.surfaceVariant
                            else try {
                                Color(android.graphics.Color.parseColor(option.hex))
                            } catch (_: Exception) {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                            val isSelected = note.colorHex.equals(option.hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(swatchBg)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        onUpdateColor(option.hex)
                                        activeTray = EditorDockTray.NONE
                                    }
                                    .testTag("editor_color_${option.label}"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = option.label,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Visual Rich-Text Formatting Tray ('Tr') above dock
            AnimatedVisibility(visible = activeTray == EditorDockTray.FORMAT) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .testTag("editor_format_tray")
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        // H1
                        item {
                            FormatToggleChip(
                                label = "H1",
                                isActive = activeBlockFormat == NoteBlockFormat.H1,
                                fontWeight = FontWeight.Bold,
                                onClick = {
                                    val updated = RichNoteFormatter.toggleBlockFormat(contentFieldValue, NoteBlockFormat.H1)
                                    contentFieldValue = updated
                                    if (updated.text != note.content) onUpdateContent(updated.text)
                                },
                                testTag = "format_h1"
                            )
                        }

                        // H2
                        item {
                            FormatToggleChip(
                                label = "H2",
                                isActive = activeBlockFormat == NoteBlockFormat.H2,
                                fontWeight = FontWeight.SemiBold,
                                onClick = {
                                    val updated = RichNoteFormatter.toggleBlockFormat(contentFieldValue, NoteBlockFormat.H2)
                                    contentFieldValue = updated
                                    if (updated.text != note.content) onUpdateContent(updated.text)
                                },
                                testTag = "format_h2"
                            )
                        }

                        // Negrita (B)
                        item {
                            FormatToggleChip(
                                label = "B",
                                isActive = NoteInlineFormat.BOLD in activeInlineFormats,
                                fontWeight = FontWeight.ExtraBold,
                                onClick = {
                                    val (updated, nextPending) = RichNoteFormatter.toggleInlineFormat(
                                        contentFieldValue, NoteInlineFormat.BOLD, pendingInlineFormats
                                    )
                                    contentFieldValue = updated
                                    pendingInlineFormats = nextPending
                                    if (updated.text != note.content) onUpdateContent(updated.text)
                                },
                                testTag = "format_bold"
                            )
                        }

                        // Cursiva (I)
                        item {
                            FormatToggleChip(
                                label = "I",
                                isActive = NoteInlineFormat.ITALIC in activeInlineFormats,
                                fontStyle = FontStyle.Italic,
                                fontWeight = FontWeight.SemiBold,
                                onClick = {
                                    val (updated, nextPending) = RichNoteFormatter.toggleInlineFormat(
                                        contentFieldValue, NoteInlineFormat.ITALIC, pendingInlineFormats
                                    )
                                    contentFieldValue = updated
                                    pendingInlineFormats = nextPending
                                    if (updated.text != note.content) onUpdateContent(updated.text)
                                },
                                testTag = "format_italic"
                            )
                        }

                        // Subrayado (U)
                        item {
                            FormatToggleChip(
                                label = "U",
                                isActive = NoteInlineFormat.UNDERLINE in activeInlineFormats,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.SemiBold,
                                onClick = {
                                    val (updated, nextPending) = RichNoteFormatter.toggleInlineFormat(
                                        contentFieldValue, NoteInlineFormat.UNDERLINE, pendingInlineFormats
                                    )
                                    contentFieldValue = updated
                                    pendingInlineFormats = nextPending
                                    if (updated.text != note.content) onUpdateContent(updated.text)
                                },
                                testTag = "format_underline"
                            )
                        }

                        // Tachado (S)
                        item {
                            FormatToggleChip(
                                label = "S",
                                isActive = NoteInlineFormat.STRIKE in activeInlineFormats,
                                textDecoration = TextDecoration.LineThrough,
                                fontWeight = FontWeight.SemiBold,
                                onClick = {
                                    val (updated, nextPending) = RichNoteFormatter.toggleInlineFormat(
                                        contentFieldValue, NoteInlineFormat.STRIKE, pendingInlineFormats
                                    )
                                    contentFieldValue = updated
                                    pendingInlineFormats = nextPending
                                    if (updated.text != note.content) onUpdateContent(updated.text)
                                },
                                testTag = "format_strike"
                            )
                        }

                        // Code (Fondo código + texto rojo)
                        item {
                            val isCodeActive = NoteInlineFormat.CODE in activeInlineFormats
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isCodeActive) richThemeColors.codeBackground
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(
                                        width = if (isCodeActive) 1.5.dp else 1.dp,
                                        color = if (isCodeActive) richThemeColors.codeText else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        val (updated, nextPending) = RichNoteFormatter.toggleInlineFormat(
                                            contentFieldValue, NoteInlineFormat.CODE, pendingInlineFormats
                                        )
                                        contentFieldValue = updated
                                        pendingInlineFormats = nextPending
                                        if (updated.text != note.content) onUpdateContent(updated.text)
                                    }
                                    .padding(horizontal = 9.dp, vertical = 6.dp)
                                    .testTag("format_code")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Code,
                                    contentDescription = "Código",
                                    tint = richThemeColors.codeText,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "code",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = richThemeColors.codeText
                                )
                            }
                        }

                        // Cita
                        item {
                            FormatIconChip(
                                icon = Icons.Default.FormatQuote,
                                contentDescription = "Cita",
                                isActive = activeBlockFormat == NoteBlockFormat.QUOTE,
                                onClick = {
                                    val updated = RichNoteFormatter.toggleBlockFormat(contentFieldValue, NoteBlockFormat.QUOTE)
                                    contentFieldValue = updated
                                    if (updated.text != note.content) onUpdateContent(updated.text)
                                },
                                testTag = "format_quote"
                            )
                        }

                        // Lista (Única lista unificada)
                        item {
                            FormatIconChip(
                                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                                contentDescription = "Lista",
                                isActive = activeBlockFormat == NoteBlockFormat.LIST,
                                onClick = {
                                    val updated = RichNoteFormatter.toggleBlockFormat(contentFieldValue, NoteBlockFormat.LIST)
                                    contentFieldValue = updated
                                    if (updated.text != note.content) onUpdateContent(updated.text)
                                },
                                testTag = "format_list"
                            )
                        }

                        // Separador horizontal
                        item {
                            FormatIconChip(
                                icon = Icons.Default.HorizontalRule,
                                contentDescription = "Separador",
                                isActive = false,
                                onClick = {
                                    val updated = RichNoteFormatter.insertDivider(contentFieldValue)
                                    contentFieldValue = updated
                                    onUpdateContent(updated.text)
                                },
                                testTag = "format_divider"
                            )
                        }
                    }
                }
            }

            // Main Floating Editor Dock Pill (matches WhiteboardPenDock)
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .testTag("note_editor_bottom_dock")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Keep Color Palette
                    EditorDockIconButton(
                        icon = Icons.Default.Palette,
                        contentDescription = "Color de fondo",
                        isSelected = activeTray == EditorDockTray.COLORS,
                        badgeColor = selectedColorDot,
                        onClick = {
                            activeTray = if (activeTray == EditorDockTray.COLORS) EditorDockTray.NONE else EditorDockTray.COLORS
                        },
                        testTag = "editor_dock_color"
                    )

                    // 2. Formato visual ('Tr')
                    val anyFormatSelected = activeTray == EditorDockTray.FORMAT ||
                        activeBlockFormat != NoteBlockFormat.NONE ||
                        activeInlineFormats.isNotEmpty()
                    EditorDockIconButton(
                        icon = Icons.Default.TextFields,
                        contentDescription = "Formato de texto",
                        isSelected = anyFormatSelected,
                        onClick = {
                            activeTray = if (activeTray == EditorDockTray.FORMAT) EditorDockTray.NONE else EditorDockTray.FORMAT
                        },
                        testTag = "editor_dock_format"
                    )

                    // 3. Vista de lectura / edición
                    EditorDockIconButton(
                        icon = if (isPreviewMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isPreviewMode) "Volver a editar" else "Vista lectura",
                        isSelected = isPreviewMode,
                        onClick = onTogglePreview,
                        testTag = "editor_dock_preview"
                    )

                    // Hairline Divider
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .width(1.dp)
                            .height(24.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    // Live Active Format / Status Pill
                    val activeStyleLabel = buildActiveStyleSummary(
                        block = activeBlockFormat,
                        inlines = activeInlineFormats,
                        isPreviewMode = isPreviewMode,
                        editedTimeText = editedTimeText
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (activeBlockFormat != NoteBlockFormat.NONE || activeInlineFormats.isNotEmpty()) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                            .border(
                                1.dp,
                                if (activeBlockFormat != NoteBlockFormat.NONE || activeInlineFormats.isNotEmpty()) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                activeTray = if (activeTray == EditorDockTray.FORMAT) EditorDockTray.NONE else EditorDockTray.FORMAT
                            }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        if (note.isLocked) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = activeStyleLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (activeBlockFormat != NoteBlockFormat.NONE || activeInlineFormats.isNotEmpty()) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1
                        )
                    }

                    // 3-dots Dock Overflow
                    Box {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (showDockOverflowMenu) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    1.dp,
                                    if (showDockOverflowMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { showDockOverflowMenu = !showDockOverflowMenu }
                                .testTag("editor_dock_more_menu"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Más acciones",
                                tint = if (showDockOverflowMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        EditorOptionsDropdownMenu(
                            expanded = showDockOverflowMenu,
                            onDismissRequest = { showDockOverflowMenu = false },
                            note = note,
                            isPreviewMode = isPreviewMode,
                            onOpenInBoard = onOpenInBoard?.let { openFn ->
                                {
                                    showDockOverflowMenu = false
                                    openFn()
                                }
                            },
                            onTogglePreview = {
                                onTogglePreview()
                                showDockOverflowMenu = false
                            },
                            onOpenFolderPicker = {
                                showDockOverflowMenu = false
                                showFolderPickerMenu = true
                            },
                            onStartAddTag = {
                                isAddingTag = true
                                showDockOverflowMenu = false
                            },
                            onToggleLock = {
                                onToggleLock()
                                showDockOverflowMenu = false
                            },
                            onOpenVersionHistory = {
                                showDockOverflowMenu = false
                                onOpenVersionHistory()
                            },
                            onOpenExport = {
                                showDockOverflowMenu = false
                                onOpenExport()
                            },
                            onDeleteNote = {
                                showDockOverflowMenu = false
                                onDeleteNote()
                            },
                            includeDeleteTestTag = false
                        )
                    }
                }
            }
        }
    }
}

private fun buildActiveStyleSummary(
    block: NoteBlockFormat,
    inlines: Set<NoteInlineFormat>,
    isPreviewMode: Boolean,
    editedTimeText: String
): String {
    if (isPreviewMode) return "Lectura"
    val parts = mutableListOf<String>()
    when (block) {
        NoteBlockFormat.H1 -> parts.add("H1")
        NoteBlockFormat.H2 -> parts.add("H2")
        NoteBlockFormat.QUOTE -> parts.add("Cita")
        NoteBlockFormat.LIST -> parts.add("Lista")
        NoteBlockFormat.NONE -> {}
    }
    if (NoteInlineFormat.CODE in inlines) parts.add("Code")
    if (NoteInlineFormat.BOLD in inlines) parts.add("B")
    if (NoteInlineFormat.ITALIC in inlines) parts.add("I")
    if (NoteInlineFormat.UNDERLINE in inlines) parts.add("U")
    if (NoteInlineFormat.STRIKE in inlines) parts.add("S")
    return if (parts.isEmpty()) "Editado $editedTimeText" else parts.joinToString(" · ")
}

@Composable
private fun FormatToggleChip(
    label: String,
    isActive: Boolean,
    fontWeight: FontWeight = FontWeight.Medium,
    fontStyle: FontStyle = FontStyle.Normal,
    textDecoration: TextDecoration = TextDecoration.None,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            textDecoration = textDecoration,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FormatIconChip(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun EditorOptionsDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    note: NoteItem,
    isPreviewMode: Boolean,
    onOpenInBoard: (() -> Unit)? = null,
    onTogglePreview: () -> Unit,
    onOpenFolderPicker: () -> Unit,
    onStartAddTag: () -> Unit,
    onToggleLock: () -> Unit,
    onOpenVersionHistory: () -> Unit,
    onOpenExport: () -> Unit,
    onDeleteNote: () -> Unit,
    includeDeleteTestTag: Boolean
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .width(235.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Text(
            text = "HERRAMIENTAS DE NOTA",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )

        if (onOpenInBoard != null) {
            DropdownMenuItem(
                text = { Text("Abrir en pizarra") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Draw,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = onOpenInBoard,
                modifier = Modifier.testTag("menu_editor_open_in_board")
            )
        }

        DropdownMenuItem(
            text = { Text(if (isPreviewMode) "Volver a editar" else "Modo lectura") },
            leadingIcon = {
                Icon(
                    imageVector = if (isPreviewMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onTogglePreview
        )

        DropdownMenuItem(
            text = { Text("Carpeta (${note.folder})") },
            leadingIcon = {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            onClick = onOpenFolderPicker
        )

        DropdownMenuItem(
            text = { Text("Añadir etiqueta") },
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            onClick = onStartAddTag
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        DropdownMenuItem(
            text = { Text(if (note.isLocked) "Quitar bloqueo PIN" else "Bloquear con PIN") },
            leadingIcon = {
                Icon(
                    imageVector = if (note.isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (note.isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onToggleLock
        )

        DropdownMenuItem(
            text = { Text("Historial de versiones (${note.versionHistory.size})") },
            leadingIcon = {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            onClick = onOpenVersionHistory
        )

        DropdownMenuItem(
            text = { Text("Exportar / Compartir") },
            leadingIcon = {
                Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            onClick = onOpenExport
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        DropdownMenuItem(
            text = {
                Text(
                    text = "Mover a papelera",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onDeleteNote,
            modifier = if (includeDeleteTestTag) Modifier.testTag("btn_editor_delete_note") else Modifier
        )
    }
}

@Composable
private fun EditorDockIconButton(
    icon: ImageVector,
    contentDescription: String,
    isSelected: Boolean,
    badgeColor: Color? = null,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Box {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            if (badgeColor != null) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(badgeColor)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun RenderRichPreview(
    content: String,
    colors: RichEditorThemeColors
) {
    if (content.isBlank()) {
        Text(
            text = "Sin contenido",
            fontSize = 14.sp,
            color = colors.onSurfaceVariant.copy(alpha = 0.6f)
        )
        return
    }
    val annotated = remember(content, colors) {
        RichNoteFormatter.buildDisplayAnnotatedString(content, colors)
    }
    Text(
        text = annotated,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        color = colors.onSurface,
        modifier = Modifier.fillMaxWidth()
    )
}
