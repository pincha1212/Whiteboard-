package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.LocalFirstWorkspaceStore
import com.example.notes.ui.NotesMasterDetailScreen
import com.example.notes.viewmodel.AppActiveWorkspace
import com.example.notes.viewmodel.NotesViewModel
import com.example.ui.canvas.BoardsHomeScreen
import com.example.ui.canvas.WhiteboardCanvasScreen
import com.example.ui.theme.WhiteboardTheme
import com.example.viewmodel.WhiteboardViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    private val workspaceStore by lazy {
        LocalFirstWorkspaceStore.getInstance(applicationContext)
    }

    private val workspaceFactory by lazy {
        val store = workspaceStore
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when {
                    modelClass.isAssignableFrom(WhiteboardViewModel::class.java) ->
                        WhiteboardViewModel(store) as T
                    modelClass.isAssignableFrom(NotesViewModel::class.java) ->
                        NotesViewModel(store) as T
                    else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }

    private val whiteboardViewModel: WhiteboardViewModel by viewModels { workspaceFactory }
    private val notesViewModel: NotesViewModel by viewModels { workspaceFactory }

    override fun onPause() {
        super.onPause()
        // Do not instantiate ViewModels while the initial workspace read is still running.
        if (workspaceStore.isReady.value) {
            whiteboardViewModel.commitWorkspaceSync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhiteboardTheme {
                val workspaceReady by workspaceStore.isReady.collectAsStateWithLifecycle()
                if (!workspaceReady) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    // Observe only navigation flags here. Collecting the complete UI states at
                    // this root caused camera/selection updates to recompose the workspace host.
                    var currentWorkspace by remember { mutableStateOf(AppActiveWorkspace.WHITEBOARD) }
                    val isViewingBoardsHome by whiteboardViewModel.uiState
                        .map { it.isViewingBoardsHome }
                        .distinctUntilChanged()
                        .collectAsStateWithLifecycle(
                            initialValue = whiteboardViewModel.uiState.value.isViewingBoardsHome
                        )

                    // Notes are an optional workspace. Do not create its ViewModel or
                    // subscribe to its state while the user is working on the whiteboard.
                    val isEditingNote = if (currentWorkspace == AppActiveWorkspace.NOTES) {
                        val editing by notesViewModel.uiState
                            .map { it.isEditingNote }
                            .distinctUntilChanged()
                            .collectAsStateWithLifecycle(
                                initialValue = notesViewModel.uiState.value.isEditingNote
                            )
                        editing
                    } else {
                        false
                    }

                    BackHandler(
                        enabled = isEditingNote ||
                            currentWorkspace == AppActiveWorkspace.NOTES ||
                            !isViewingBoardsHome
                    ) {
                        if (isEditingNote) {
                            notesViewModel.closeActiveNote()
                        } else if (currentWorkspace == AppActiveWorkspace.NOTES) {
                            currentWorkspace = AppActiveWorkspace.WHITEBOARD
                        } else if (!isViewingBoardsHome) {
                            whiteboardViewModel.closeBoardToHome()
                        }
                    }

                    when (currentWorkspace) {
                        AppActiveWorkspace.WHITEBOARD -> {
                            if (isViewingBoardsHome) {
                                val homeData by whiteboardViewModel.uiState
                                    .map { it.allBoards to it.board.id }
                                    .distinctUntilChanged()
                                    .collectAsStateWithLifecycle(
                                        initialValue = whiteboardViewModel.uiState.value.let {
                                            it.allBoards to it.board.id
                                        }
                                    )
                                BoardsHomeScreen(
                                    boards = homeData.first,
                                    currentBoardId = homeData.second,
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
                                onOpenNoteInBoard = { boardId, noteId ->
                                    notesViewModel.closeActiveNote()
                                    whiteboardViewModel.openBoardAndFocusNote(boardId, noteId)
                                    currentWorkspace = AppActiveWorkspace.WHITEBOARD
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
