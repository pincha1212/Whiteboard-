package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.data.LocalFirstWorkspaceStore
import com.example.notes.ui.NotesMasterDetailScreen
import com.example.notes.viewmodel.AppActiveWorkspace
import com.example.notes.viewmodel.NotesViewModel
import com.example.ui.canvas.BoardsHomeScreen
import com.example.ui.canvas.WhiteboardCanvasScreen
import com.example.ui.theme.WhiteboardTheme
import com.example.viewmodel.WhiteboardViewModel
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {

    private val whiteboardViewModel: WhiteboardViewModel by viewModels()
    private val notesViewModel: NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhiteboardTheme {
                val whiteboardState by whiteboardViewModel.uiState.collectAsState()
                val notesState by notesViewModel.uiState.collectAsState()
                val workspaceStore = remember {
                    LocalFirstWorkspaceStore.getInstance(applicationContext)
                }
                var workspaceReady by remember { mutableStateOf(false) }
                var knownBoardIds by remember { mutableStateOf<Set<String>>(emptySet()) }
                var knownNoteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
                var currentWorkspace by remember { mutableStateOf(AppActiveWorkspace.WHITEBOARD) }

                // Hydrate both modules from the same local-first source of truth.
                LaunchedEffect(workspaceStore) {
                    if (workspaceStore.currentState.boards.isEmpty()) {
                        workspaceStore.createBoard(whiteboardViewModel.uiState.value.board)
                    }
                    val initial = workspaceStore.currentState
                    notesViewModel.syncWorkspaceData(initial.notes, initial.folders)
                    whiteboardViewModel.syncWorkspaceBoards(initial.boards)
                    knownBoardIds = initial.boards.map { it.id }.toSet()
                    knownNoteIds = initial.notes.filter { !it.isDeleted }.map { it.id }.toSet()
                    workspaceReady = true

                    workspaceStore.dbState.collect { db ->
                        notesViewModel.syncWorkspaceData(db.notes, db.folders)
                        whiteboardViewModel.syncWorkspaceBoards(db.boards)
                    }
                }

                // Board edits (including sticky-note creation, movement and deletion)
                // are projected into Notes through LocalFirstWorkspaceStore.
                LaunchedEffect(workspaceReady, whiteboardState.allBoards) {
                    if (!workspaceReady) return@LaunchedEffect
                    val currentIds = whiteboardState.allBoards.map { it.id }.toSet()
                    (knownBoardIds - currentIds).forEach { removedId ->
                        workspaceStore.deleteBoard(removedId, whiteboardState.allBoards.firstOrNull())
                    }
                    whiteboardState.allBoards.forEach { board ->
                        workspaceStore.syncBoardFromWhiteboard(board)
                    }
                    knownBoardIds = currentIds
                }

                // Notes are persisted centrally. Choosing a board-linked folder attaches
                // the note to that board; moving it to another folder detaches it.
                LaunchedEffect(workspaceReady, notesState.notes, notesState.folders) {
                    if (!workspaceReady) return@LaunchedEffect
                    val currentNotes = notesState.notes
                    val currentIds = currentNotes.map { it.id }.toSet()
                    (knownNoteIds - currentIds).forEach { removedId ->
                        workspaceStore.softDeleteNotes(setOf(removedId))
                    }
                    currentNotes.forEach { workspaceStore.upsertNote(it) }
                    notesState.folders.forEach { workspaceStore.addFolder(it) }
                    knownNoteIds = currentIds
                }

                BackHandler(
                    enabled = notesState.isEditingNote ||
                        currentWorkspace == AppActiveWorkspace.NOTES ||
                        !whiteboardState.isViewingBoardsHome
                ) {
                    if (notesState.isEditingNote) {
                        notesViewModel.closeActiveNote()
                    } else if (currentWorkspace == AppActiveWorkspace.NOTES) {
                        currentWorkspace = AppActiveWorkspace.WHITEBOARD
                    } else if (!whiteboardState.isViewingBoardsHome) {
                        whiteboardViewModel.closeBoardToHome()
                    }
                }

                when (currentWorkspace) {
                    AppActiveWorkspace.WHITEBOARD -> {
                        if (whiteboardState.isViewingBoardsHome) {
                            BoardsHomeScreen(
                                boards = whiteboardState.allBoards,
                                currentBoardId = whiteboardState.board.id,
                                onOpenBoard = { whiteboardViewModel.openBoard(it) },
                                onCreateBoard = { title, grid, theme ->
                                    whiteboardViewModel.createNewBoard(
                                        title = title,
                                        gridType = grid,
                                        colorTheme = theme,
                                        openImmediately = true
                                    )
                                },
                                onRenameBoard = { id, title -> whiteboardViewModel.renameBoardById(id, title) },
                                onDuplicateBoard = { whiteboardViewModel.duplicateCurrentBoard(it) },
                                onDeleteBoard = { whiteboardViewModel.deleteBoard(it) },
                                onSwitchToNotes = { currentWorkspace = AppActiveWorkspace.NOTES },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            WhiteboardCanvasScreen(
                                viewModel = whiteboardViewModel,
                                onSwitchToNotes = { currentWorkspace = AppActiveWorkspace.NOTES },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    AppActiveWorkspace.NOTES -> {
                        NotesMasterDetailScreen(
                            notesViewModel = notesViewModel,
                            onOpenWhiteboard = { currentWorkspace = AppActiveWorkspace.WHITEBOARD },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
