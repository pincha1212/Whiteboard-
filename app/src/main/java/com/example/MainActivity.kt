package com.example

import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.LocalFirstWorkspaceStore
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
                if (modelClass.isAssignableFrom(WhiteboardViewModel::class.java)) {
                    return WhiteboardViewModel(store) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }

    private val whiteboardViewModel: WhiteboardViewModel by viewModels { workspaceFactory }

    override fun onPause() {
        super.onPause()
        if (workspaceStore.isReady.value) {
            whiteboardViewModel.commitWorkspaceSync()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (workspaceStore.isReady.value) {
            whiteboardViewModel.handleMemoryTrim(level)
        }
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        if (workspaceStore.isReady.value) {
            whiteboardViewModel.handleMemoryTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
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
                    val isViewingBoardsHome by whiteboardViewModel.uiState
                        .map { it.isViewingBoardsHome }
                        .distinctUntilChanged()
                        .collectAsStateWithLifecycle(
                            initialValue = whiteboardViewModel.uiState.value.isViewingBoardsHome
                        )

                    BackHandler(enabled = !isViewingBoardsHome) {
                        whiteboardViewModel.closeBoardToHome()
                    }

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
                            memoryBudget = whiteboardViewModel.memoryBudget,
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
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        WhiteboardCanvasScreen(
                            viewModel = whiteboardViewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
