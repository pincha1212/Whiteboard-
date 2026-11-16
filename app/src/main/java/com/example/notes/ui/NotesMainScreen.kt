package com.example.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.notes.model.NoteItem
import com.example.notes.viewmodel.NotesViewModel

@Composable
fun NotesMainScreen(
    notesViewModel: NotesViewModel,
    onOpenWhiteboard: (() -> Unit)? = null,
    onOpenNoteInBoard: (boardId: String, noteId: String) -> Unit = { _, _ -> },
    isEmbeddedInSplit: Boolean = false,
    modifier: Modifier = Modifier
) {
    val state by notesViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showVoiceModal by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var showDockFoldersMenu by remember { mutableStateOf(false) }
    var showDockMoreMenu by remember { mutableStateOf(false) }
    var notePendingDelete by remember { mutableStateOf<NoteItem?>(null)
    var showLinkNoteDialog by remember { mutableStateOf(false) } }

    LaunchedEffect(state.statusNotification) {
        state.statusNotification?.let {
            snackbarHostState.showSnackbar(it)
            notesViewModel.clearStatusNotification()
        }
    }

    if (state.isEditingNote && state.activeNote != null && !isEmbeddedInSplit) {
        val active = state.activeNote!!
        val originBoardId = state.boardFilterId ?: active.linkedBoardIds.firstOrNull() ?: active.canvasData?.boardId
        val originBoardName = originBoardId?.let { state.boardTitlesById[it] ?: active.folder }
        NoteEditorScreen(
            note = active,
            folders = state.folders,
            isPreviewMode = state.isMarkdownPreview,
            isVoicePlaying = state.isVoicePlaying,
            initialShowChecklist = state.isChecklistFocusRequested,
            enableBackHandler = true,
            originBoardName = originBoardName,
            onOpenInBoard = originBoardId?.let { boardId -> { onOpenNoteInBoard(boardId, active.id) } },
            onUnlinkFromBoard = if (state.boardFilterId != null && state.boardFilterId in active.linkedBoardIds) {
                { notesViewModel.unlinkNoteFromBoard(active.id, state.boardFilterId) }
            } else null,
            onBack = { notesViewModel.closeActiveNote() },
            onUpdateTitle = { notesViewModel.updateActiveNoteTitle(it) },
            onUpdateContent = { notesViewModel.updateActiveNoteContent(it) },
            onUpdateFolder = { notesViewModel.updateActiveNoteFolder(it) },
            onUpdateColor = { notesViewModel.updateActiveNoteColor(it) },
            onTogglePin = { notesViewModel.toggleActiveNotePin() },
            onToggleLock = { notesViewModel.toggleLockActiveNote() },
            onAddChecklist = { notesViewModel.addChecklistItem(it) },
            onToggleChecklist = { notesViewModel.toggleChecklistItem(it) },
            onDeleteChecklist = { notesViewModel.removeChecklistItem(it) },
            onAddTag = { notesViewModel.addTagToActiveNote(it) },
            onRemoveTag = { notesViewModel.removeTagFromActiveNote(it) },
            onTogglePreview = { notesViewModel.toggleMarkdownPreview() },
            onOpenVersionHistory = { notesViewModel.setShowVersionHistoryModal(true) },
            onOpenExport = { notesViewModel.setShowExportImportModal(true) },
            onToggleVoicePlayback = { notesViewModel.toggleVoicePlayback() },
            onDeleteNote = { notesViewModel.deleteNote(active.id) }
        )
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 76.dp)
                )
            },
            modifier = modifier.fillMaxSize().testTag("notes_main_screen")
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding())
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 1. FLOATING WHITEBOARD-STYLE GOOGLE KEEP TOP HEADER
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
                                .testTag("notes_top_bar")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Left: Segmented Workspace Switcher (matches WhiteboardTopBar)
                                if (onOpenWhiteboard != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                            .padding(3.dp)
                                    ) {
                                        NotesWorkspaceSegmentItem(
                                            icon = Icons.Default.Draw,
                                            label = "Pizarra",
                                            isSelected = false,
                                            onClick = onOpenWhiteboard,
                                            testTag = "btn_switch_to_whiteboard"
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        NotesWorkspaceSegmentItem(
                                            icon = Icons.Default.Description,
                                            label = "Notas",
                                            isSelected = true,
                                            onClick = {},
                                            testTag = "btn_workspace_notes_active"
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.NoteAlt,
                                            contentDescription = "Notas",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }

                                // Center: Integrated Google Keep Search Pill
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                        .padding(horizontal = 9.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    BasicTextField(
                                        value = state.searchQuery,
                                        onValueChange = { notesViewModel.updateSearchQuery(it) },
                                        singleLine = true,
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                                        decorationBox = { inner ->
                                            if (state.searchQuery.isEmpty()) {
                                                Text(
                                                    text = "Buscar en notas",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            inner()
                                        },
                                        modifier = Modifier.weight(1f).testTag("notes_search_input")
                                    )
                                    if (state.searchQuery.isNotEmpty()) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Limpiar búsqueda",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .size(15.dp)
                                                .clickable { notesViewModel.updateSearchQuery("") }
                                        )
                                    }
                                }

                                // Right: Keep Grid/List Layout Toggle + 3-Dots Overflow Menu
                                if (!isEmbeddedInSplit) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable { notesViewModel.toggleNotesLayout() }
                                            .testTag("btn_toggle_keep_layout"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (state.isGridLayout) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                            contentDescription = if (state.isGridLayout) "Vista de lista" else "Vista de cuadrícula",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }

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
                                            .testTag("btn_notes_topbar_more_menu"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Opciones de notas",
                                            tint = if (showHeaderMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    NotesOverflowDropdownMenu(
                                        expanded = showHeaderMenu,
                                        onDismissRequest = { showHeaderMenu = false },
                                        state = state,
                                        notesViewModel = notesViewModel,
                                        onOpenVoiceModal = { showVoiceModal = true }
                                    )
                                }
                            }
                        }
                    }

                    // 2. ACTIVE FILTER PILL ROW / PAPELERA BANNER
                    if (state.isViewingTrash) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                                .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("notes_trash_banner")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Papelera (${state.trashNotes.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (state.trashNotes.isNotEmpty()) {
                                    Text(
                                        text = "Vaciar",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { notesViewModel.emptyTrash() }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                            .testTag("btn_empty_trash")
                                    )
                                }
                                Text(
                                    text = "Volver",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { notesViewModel.setViewingTrash(false) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                        .testTag("btn_exit_trash")
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    } else {
                        val hasFolderFilter = !state.selectedFolder.isNullOrBlank() && state.selectedFolder != "Todas"
                        val hasTagFilter = !state.selectedTag.isNullOrBlank()
                        if (hasFolderFilter || hasTagFilter) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (hasFolderFilter) {
                                    item {
                                        ActiveFilterChip(
                                            icon = Icons.Default.Folder,
                                            label = state.selectedFolder!!,
                                            onClear = { notesViewModel.selectFolder(null) }
                                        )
                                    }
                                }
                                if (hasTagFilter) {
                                    item {
                                        ActiveFilterChip(
                                            icon = Icons.AutoMirrored.Filled.Label,
                                            label = "#${state.selectedTag}",
                                            onClear = { notesViewModel.selectTag(null) }
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    // 3. GOOGLE KEEP STAGGERED MASONRY GRID / LIST
                    val filtered = state.filteredNotes
                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 28.dp, vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(bottom = 72.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (state.isViewingTrash) Icons.Default.DeleteOutline else Icons.Outlined.Lightbulb,
                                        contentDescription = null,
                                        tint = if (state.isViewingTrash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                val emptyHeadline = when {
                                    state.isViewingTrash -> "La papelera está vacía"
                                    state.searchQuery.isNotEmpty() -> "Sin resultados para '${state.searchQuery}'"
                                    state.notes.isEmpty() -> "Tus notas aparecerán aquí"
                                    else -> "No hay notas en este filtro"
                                }
                                Text(
                                    text = emptyHeadline,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (state.isViewingTrash) "Las notas que muevas a la papelera se pueden restaurar desde aquí."
                                    else "Crea una nota, lista de tareas o grabación desde el dock.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        val pinnedNotes = if (state.isViewingTrash) emptyList() else filtered.filter { it.isPinned }
                        val otherNotes = if (state.isViewingTrash) filtered else filtered.filter { !it.isPinned }
                        val showSectionLabels = pinnedNotes.isNotEmpty() && otherNotes.isNotEmpty()
                        val columnCount = if (state.isGridLayout && !isEmbeddedInSplit) 2 else 1

                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(columnCount),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 4.dp,
                                bottom = 96.dp
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalItemSpacing = 8.dp,
                            modifier = Modifier.fillMaxSize().testTag("notes_lazy_list")
                        ) {
                            if (showSectionLabels) {
                                item(span = StaggeredGridItemSpan.FullLine) {
                                    KeepSectionHeader(title = "FIJADAS")
                                }
                            }

                            items(pinnedNotes, key = { it.id }) { note ->
                                val noteBoardId = state.boardFilterId ?: note.linkedBoardIds.firstOrNull() ?: note.canvasData?.boardId
                                val boardName = noteBoardId?.let { state.boardTitlesById[it] ?: note.folder }
                                NoteCardView(
                                    note = note,
                                    isUnlocked = notesViewModel.isNoteUnlocked(note.id),
                                    isSelected = note.id == state.activeNote?.id,
                                    originBoardName = boardName,
                                    isTrashMode = state.isViewingTrash,
                                    onClick = { notesViewModel.openNote(note) },
                                    onTogglePin = { notesViewModel.toggleNotePin(note.id) },
                                    onDelete = { notesViewModel.deleteNote(note.id) },
                                    onOpenInBoard = noteBoardId?.let { boardId -> { onOpenNoteInBoard(boardId, note.id) } },
                                    onRestore = { notesViewModel.restoreNoteFromTrash(note.id) },
                                    onDeletePermanently = { notePendingDelete = note }
                                )
                            }

                            if (showSectionLabels) {
                                item(span = StaggeredGridItemSpan.FullLine) {
                                    KeepSectionHeader(
                                        title = "OTRAS",
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                            }

                            items(otherNotes, key = { it.id }) { note ->
                                val noteBoardId = note.linkedBoardIds.firstOrNull() ?: note.canvasData?.boardId
                                val boardName = noteBoardId?.let { state.boardTitlesById[it] ?: note.folder }
                                NoteCardView(
                                    note = note,
                                    isUnlocked = notesViewModel.isNoteUnlocked(note.id),
                                    isSelected = note.id == state.activeNote?.id,
                                    originBoardName = boardName,
                                    isTrashMode = state.isViewingTrash,
                                    onClick = { notesViewModel.openNote(note) },
                                    onTogglePin = { notesViewModel.toggleNotePin(note.id) },
                                    onDelete = { notesViewModel.deleteNote(note.id) },
                                    onOpenInBoard = noteBoardId?.let { boardId -> { onOpenNoteInBoard(boardId, note.id) } },
                                    onRestore = { notesViewModel.restoreNoteFromTrash(note.id) },
                                    onDeletePermanently = { notePendingDelete = note }
                                )
                            }
                        }
                    }
                }

                // 4. FLOATING WHITEBOARD-STYLE KEEP BOTTOM DOCK
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 0.dp,
                        shadowElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .testTag("notes_bottom_dock")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Nueva lista (Keep checklist note)
                            KeepDockIconButton(
                                icon = Icons.Default.CheckBox,
                                tooltip = "Nueva lista",
                                isActive = false,
                                onClick = {
                                    notesViewModel.createNewChecklistNote(state.selectedFolder ?: "Bandeja de Entrada")
                                },
                                testTag = "dock_new_checklist"
                            )

                            // 2. Nota de voz
                            KeepDockIconButton(
                                icon = Icons.Default.Mic,
                                tooltip = "Grabar nota de voz",
                                isActive = false,
                                onClick = { showVoiceModal = true },
                                testTag = "fab_voice_note"
                            )

                            // 3. Carpetas y etiquetas rápidas
                            Box {
                                val filterActive = (!state.selectedFolder.isNullOrBlank() && state.selectedFolder != "Todas") || !state.selectedTag.isNullOrBlank()
                                KeepDockIconButton(
                                    icon = Icons.Default.Folder,
                                    tooltip = "Carpetas y filtros",
                                    isActive = filterActive || showDockFoldersMenu,
                                    onClick = { showDockFoldersMenu = !showDockFoldersMenu },
                                    testTag = "dock_folders_filter"
                                )

                                NotesFoldersDropdownMenu(
                                    expanded = showDockFoldersMenu,
                                    onDismissRequest = { showDockFoldersMenu = false },
                                    state = state,
                                    notesViewModel = notesViewModel
                                )
                            }

                            // Hairline Divider (matches WhiteboardPenDock)
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 2.dp)
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )

                            // 4. Primary Action Pill: "Nueva" (mirrors WhiteboardPenDock status pill)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable {
                                        if (state.boardFilterId != null) notesViewModel.createNewNoteForBoard(state.boardFilterId) else notesViewModel.createNewNote(state.selectedFolder ?: "Bandeja de Entrada")
                                    }
                                    .padding(horizontal = 11.dp, vertical = 7.dp)
                                    .testTag("fab_new_note")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Nueva nota",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Nota",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    maxLines = 1
                                )
                            }

                            if (state.boardFilterId != null) {
                                TextButton(onClick = { showLinkNoteDialog = true }) {
                                    Text("Vincular existente", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            // 5. Dock 3-dots overflow menu
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (showDockMoreMenu) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .border(
                                            1.dp,
                                            if (showDockMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable { showDockMoreMenu = !showDockMoreMenu }
                                        .testTag("dock_notes_more_menu"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Más acciones de notas",
                                        tint = if (showDockMoreMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                NotesOverflowDropdownMenu(
                                    expanded = showDockMoreMenu,
                                    onDismissRequest = { showDockMoreMenu = false },
                                    state = state,
                                    notesViewModel = notesViewModel,
                                    onOpenVoiceModal = { showVoiceModal = true }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLinkNoteDialog && state.boardFilterId != null) {
        val candidates = state.notes.filter { note ->
            !note.isDeleted && state.boardFilterId !in note.linkedBoardIds &&
                note.canvasData?.boardId != state.boardFilterId
        }
        AlertDialog(
            onDismissRequest = { showLinkNoteDialog = false },
            title = { Text("Vincular una nota existente") },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (candidates.isEmpty()) {
                        Text("No hay notas disponibles para vincular.")
                    } else {
                        candidates.take(30).forEach { note ->
                            TextButton(
                                onClick = {
                                    notesViewModel.linkNoteToBoard(note.id, state.boardFilterId!!)
                                    showLinkNoteDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = note.title.ifBlank { note.content.take(48).ifBlank { "Nota sin título" } },
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLinkNoteDialog = false }) { Text("Cerrar") }
            }
        )
    }

    // --- MODALS ---
    if (state.showSecurityPinModal) {
        SecurityPinModal(
            onConfirmPin = { notesViewModel.unlockNoteWithPin(it) },
            onDismiss = { notesViewModel.dismissPinModal() }
        )
    }

    if (state.showVersionHistoryModal && state.activeNote != null) {
        VersionHistoryModal(
            versions = state.activeNote!!.versionHistory,
            onRestore = { notesViewModel.restoreNoteVersion(it) },
            onDismiss = { notesViewModel.setShowVersionHistoryModal(false) }
        )
    }

    if (state.showExportImportModal) {
        ExportImportModal(
            activeNoteMarkdown = notesViewModel.exportActiveNoteToMarkdown(),
            activeNotePlainText = notesViewModel.exportActiveNoteToPlainText(),
            allNotesJson = notesViewModel.exportAllNotesToJson(),
            onImportJson = { notesViewModel.importNotesFromJson(it) },
            onDismiss = { notesViewModel.setShowExportImportModal(false) }
        )
    }

    if (showVoiceModal) {
        VoiceRecordModal(
            onFinish = { transcript, audioFilePath, durationSec ->
                notesViewModel.finishVoiceRecording(transcript, audioFilePath, durationSec)
                showVoiceModal = false
            },
            onDismiss = { showVoiceModal = false }
        )
    }

    if (state.showNewFolderDialog) {
        NewFolderModal(
            onConfirm = { name, color -> notesViewModel.createNewFolder(name, color) },
            onDismiss = { notesViewModel.setShowNewFolderDialog(false) }
        )
    }

    if (notePendingDelete != null) {
        val target = notePendingDelete!!
        val label = target.title.ifBlank { "Nota sin título" }
        AlertDialog(
            onDismissRequest = { notePendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Eliminar definitivamente",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "¿Seguro que deseas eliminar \"$label\" definitivamente? Esta acción no se puede deshacer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        notesViewModel.permanentlyDeleteNote(target.id)
                        notePendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { notePendingDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun KeepSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun ActiveFilterChip(
    icon: ImageVector,
    label: String,
    onClear: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClear)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(modifier = Modifier.width(5.dp))
        Icon(
            imageVector = Icons.Default.Clear,
            contentDescription = "Quitar filtro",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
private fun KeepDockIconButton(
    icon: ImageVector,
    tooltip: String,
    isActive: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(
                width = if (isActive) 1.5.dp else 0.dp,
                color = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tooltip,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun NotesFoldersDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    state: com.example.notes.viewmodel.NotesUiState,
    notesViewModel: NotesViewModel
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .width(230.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Text(
            text = "CARPETAS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )

        val isAllSelected = !state.isViewingTrash && (state.selectedFolder == null || state.selectedFolder == "Todas")
        DropdownMenuItem(
            text = {
                Text(
                    text = "Todas (${state.notes.size})",
                    fontWeight = if (isAllSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            },
            trailingIcon = if (isAllSelected) {
                { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
            } else null,
            onClick = {
                notesViewModel.selectFolder(null)
                onDismissRequest()
            }
        )

        state.folders.forEach { folder ->
            val selected = !state.isViewingTrash && state.selectedFolder == folder.name
            val count = state.notes.count { it.folder.equals(folder.name, ignoreCase = true) }
            val folderIcon = if (folder.linkedBoardId != null) Icons.Default.Draw else Icons.Default.Folder
            DropdownMenuItem(
                text = {
                    Text(
                        text = "${folder.name} ($count)",
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                },
                leadingIcon = {
                    Icon(folderIcon, contentDescription = null, modifier = Modifier.size(17.dp))
                },
                trailingIcon = if (selected) {
                    { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
                } else null,
                onClick = {
                    notesViewModel.selectFolder(folder.name)
                    onDismissRequest()
                }
            )
        }

        DropdownMenuItem(
            text = {
                Text(
                    text = "Papelera (${state.trashNotes.size})",
                    fontWeight = if (state.isViewingTrash) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (state.isViewingTrash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = if (state.isViewingTrash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            },
            trailingIcon = if (state.isViewingTrash) {
                { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) }
            } else null,
            onClick = {
                notesViewModel.setViewingTrash(true)
                onDismissRequest()
            },
            modifier = Modifier.testTag("menu_open_trash")
        )

        DropdownMenuItem(
            text = { Text("Nueva carpeta...") },
            leadingIcon = {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(17.dp))
            },
            onClick = {
                onDismissRequest()
                notesViewModel.setShowNewFolderDialog(true)
            },
            modifier = Modifier.testTag("btn_add_folder")
        )

        val allUniqueTags = state.notes.flatMap { it.tags }.distinctBy { it.name }
        if (allUniqueTags.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = "ETIQUETAS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
            allUniqueTags.forEach { tag ->
                val isSelected = state.selectedTag.equals(tag.name, ignoreCase = true)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "#${tag.name}",
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
                    } else null,
                    onClick = {
                        notesViewModel.selectTag(tag.name)
                        onDismissRequest()
                    }
                )
            }
        }
    }
}

@Composable
private fun NotesOverflowDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    state: com.example.notes.viewmodel.NotesUiState,
    notesViewModel: NotesViewModel,
    onOpenVoiceModal: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .width(240.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Text(
            text = "CREAR Y ORGANIZAR",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )

        DropdownMenuItem(
            text = { Text("Nueva nota") },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismissRequest()
                notesViewModel.createNewNote(state.selectedFolder ?: "Bandeja de Entrada")
            }
        )

        DropdownMenuItem(
            text = { Text("Nueva lista de tareas") },
            leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismissRequest()
                notesViewModel.createNewChecklistNote(state.selectedFolder ?: "Bandeja de Entrada")
            }
        )

        DropdownMenuItem(
            text = { Text("Nota de voz") },
            leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismissRequest()
                onOpenVoiceModal()
            }
        )

        DropdownMenuItem(
            text = { Text("Nueva carpeta...") },
            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismissRequest()
                notesViewModel.setShowNewFolderDialog(true)
            }
        )

        if (state.folders.isNotEmpty() || !state.selectedFolder.isNullOrBlank()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = "FILTRAR POR CARPETA",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
            val isAllSelected = !state.isViewingTrash && (state.selectedFolder == null || state.selectedFolder == "Todas")
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Todas (${state.notes.size})",
                        fontWeight = if (isAllSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                },
                trailingIcon = if (isAllSelected) {
                    { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
                } else null,
                onClick = {
                    notesViewModel.selectFolder(null)
                    onDismissRequest()
                }
            )
            state.folders.forEach { folder ->
                val selected = !state.isViewingTrash && state.selectedFolder == folder.name
                val folderIcon = if (folder.linkedBoardId != null) Icons.Default.Draw else Icons.Default.Folder
                DropdownMenuItem(
                    text = {
                        Text(
                            text = folder.name,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = { Icon(folderIcon, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
                    } else null,
                    onClick = {
                        notesViewModel.selectFolder(folder.name)
                        onDismissRequest()
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Papelera (${state.trashNotes.size})",
                        fontWeight = if (state.isViewingTrash) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (state.isViewingTrash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = if (state.isViewingTrash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                },
                onClick = {
                    notesViewModel.setViewingTrash(true)
                    onDismissRequest()
                }
            )
        }

        val allUniqueTags = state.notes.flatMap { it.tags }.distinctBy { it.name }
        if (allUniqueTags.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = "ETIQUETAS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
            allUniqueTags.forEach { tag ->
                val isSelected = state.selectedTag.equals(tag.name, ignoreCase = true)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "#${tag.name}",
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
                    } else null,
                    onClick = {
                        notesViewModel.selectTag(tag.name)
                        onDismissRequest()
                    }
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))

        DropdownMenuItem(
            text = { Text("Exportar / Respaldo") },
            leadingIcon = { Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismissRequest()
                notesViewModel.setShowExportImportModal(true)
            },
            modifier = Modifier.testTag("btn_export_backup")
        )
    }
}

@Composable
private fun NotesWorkspaceSegmentItem(
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
