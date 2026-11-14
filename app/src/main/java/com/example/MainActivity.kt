package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.notes.ui.NotesMasterDetailScreen
import com.example.notes.viewmodel.AppActiveWorkspace
import com.example.notes.viewmodel.NotesViewModel
import com.example.ui.canvas.BoardsHomeScreen
import com.example.ui.canvas.WhiteboardCanvasScreen
import com.example.ui.theme.WhiteboardTheme
import com.example.viewmodel.WhiteboardViewModel

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
                var currentWorkspace by remember { mutableStateOf(AppActiveWorkspace.WHITEBOARD) }

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
