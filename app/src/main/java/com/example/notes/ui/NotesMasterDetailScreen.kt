package com.example.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.notes.model.NoteItem
import com.example.notes.viewmodel.NotesViewModel

@Composable
fun NotesMasterDetailScreen(
    notesViewModel: NotesViewModel,
    onOpenWhiteboard: (() -> Unit)? = null,
    onOpenNoteInBoard: (boardId: String, noteId: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val state by notesViewModel.uiState.collectAsState()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isTablet = maxWidth >= 720.dp

        if (isTablet) {
            // WhatsApp on tablet style: Left note list, Right note content/editor
            Row(modifier = Modifier.fillMaxSize()) {
                // Left Pane: Note List (Chats)
                Box(modifier = Modifier.width(380.dp).fillMaxHeight()) {
                    NotesMainScreen(
                        notesViewModel = notesViewModel,
                        onOpenWhiteboard = onOpenWhiteboard,
                        onOpenNoteInBoard = onOpenNoteInBoard,
                        isEmbeddedInSplit = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                // Right Pane: Note Content / Editor (Selected Chat)
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (state.activeNote != null && state.isEditingNote) {
                        val active = state.activeNote!!
                        val originBoardName = active.canvasData?.let { state.boardTitlesById[it.boardId] ?: active.folder }
                        NoteEditorScreen(
                            note = active,
                            folders = state.folders,
                            isPreviewMode = state.isMarkdownPreview,
                            isVoicePlaying = state.isVoicePlaying,
                            initialShowChecklist = state.isChecklistFocusRequested,
                            enableBackHandler = false,
                            originBoardName = originBoardName,
                            onOpenInBoard = active.canvasData?.let { cd -> { onOpenNoteInBoard(cd.boardId, active.id) } },
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
                            onDeleteNote = { notesViewModel.deleteNote(active.id) },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Selecciona una nota de la izquierda para ver y editar su contenido",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            // Compact Phone: standard flow
            NotesMainScreen(
                notesViewModel = notesViewModel,
                onOpenWhiteboard = onOpenWhiteboard,
                onOpenNoteInBoard = onOpenNoteInBoard,
                isEmbeddedInSplit = false,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
