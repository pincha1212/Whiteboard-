package com.example.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.example.ink.engine.MemoryBudgetProfile
import com.example.ink.engine.MutableRasterTileStore
import com.example.model.WhiteboardBoard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class WorkspaceDbState(
    val boards: List<WhiteboardBoard> = emptyList()
)

private data class PersistRequest(
    val revision: Long,
    val state: WorkspaceDbState,
    val immediate: Boolean = false
)

/**
 * Local-First Single Source of Truth store for v4.0.0 Whiteboard Studio.
 * Stores clean board JSON atomically and synchronizes sparse 512x512 PNG raster tile sidecars
 * with hardware-aware debounce and lazy/evictable tile loading for 1 GB RAM devices.
 */
class LocalFirstWorkspaceStore(
    private val storageFile: File? = null,
    private val loadFromDiskInBackground: Boolean = false,
    val memoryBudget: MemoryBudgetProfile = MemoryBudgetProfile.standard()
) {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(WorkspaceDbState) -> Unit>()
    private val persistMutex = Mutex()
    private var stateRevision = 0L
    private var persistedRevision = 0L

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistRequestFlow = MutableSharedFlow<PersistRequest>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val rasterTileStore = MutableRasterTileStore()
    private val rasterSidecarStore: RasterTileSidecarStore? = storageFile?.parentFile?.let {
        RasterTileSidecarStore(File(it, "raster_tiles"))
    }

    private val _dbState = MutableStateFlow(
        if (loadFromDiskInBackground) WorkspaceDbState() else loadFromDiskOrEmpty()
    )
    val dbState: StateFlow<WorkspaceDbState> = _dbState.asStateFlow()

    private val _isReady = MutableStateFlow(!loadFromDiskInBackground)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    val currentState: WorkspaceDbState
        get() = _dbState.value

    init {
        storeScope.launch {
            if (loadFromDiskInBackground) {
                val restored = loadFromDiskOrEmpty()
                synchronized(lock) {
                    _dbState.value = restored
                }
                listeners.forEach { it(restored) }
                _isReady.value = true
            }

            persistRequestFlow.collectLatest { request ->
                if (!request.immediate) delay(memoryBudget.persistDebounceMs)
                persistSnapshot(request)
            }
        }
    }

    fun addListener(listener: (WorkspaceDbState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (WorkspaceDbState) -> Unit) {
        listeners.remove(listener)
    }

    private fun commit(
        persist: Boolean = true,
        transform: (WorkspaceDbState) -> WorkspaceDbState
    ): WorkspaceDbState {
        val next: WorkspaceDbState
        synchronized(lock) {
            next = transform(_dbState.value)
            _dbState.value = next
            stateRevision += 1L
            if (persist) {
                persistRequestFlow.tryEmit(PersistRequest(stateRevision, next))
            }
        }
        listeners.forEach { it(next) }
        return next
    }

    fun flushToDisk() {
        synchronized(lock) {
            persistRequestFlow.tryEmit(
                PersistRequest(stateRevision, _dbState.value, immediate = true)
            )
        }
    }

    fun flushToDiskBlocking() {
        val request = synchronized(lock) {
            PersistRequest(stateRevision, _dbState.value, immediate = true)
        }
        runBlocking(Dispatchers.IO) {
            persistSnapshot(request)
        }
    }

    /**
     * Ensures raster tiles for [boardId] are resident in memory (reading from sidecar PNGs if
     * previously unloaded) and trims inactive-board tiles when operating under strict 1 GB RAM limits.
     */
    fun activateBoardRasterTiles(boardId: String) {
        rasterSidecarStore?.loadBoardInto(boardId, rasterTileStore)
        if (memoryBudget.isLowRamProfile) {
            flushAndTrimInactiveBoards(activeBoardId = boardId)
        }
    }

    fun flushAndTrimInactiveBoards(activeBoardId: String) {
        val snapshot = synchronized(lock) { _dbState.value }
        if (rasterTileStore.retainedBitmapBytes() > memoryBudget.activeMutableTilesBytesCap ||
            memoryBudget.isLowRamProfile
        ) {
            rasterSidecarStore?.syncWorkspace(snapshot.boards, rasterTileStore)
            rasterTileStore.evictInactiveBoardTiles(activeBoardId)
        }
    }

    private suspend fun persistSnapshot(request: PersistRequest) {
        persistMutex.withLock {
            if (request.revision <= persistedRevision) return@withLock
            if (persistToDisk(request.state)) {
                persistedRevision = request.revision
            }
        }
    }

    fun ensureBoardRegistered(board: WhiteboardBoard): WorkspaceDbState {
        synchronized(lock) {
            val cur = _dbState.value
            if (cur.boards.any { it.id == board.id }) return cur
        }
        return commit { cur ->
            val exists = cur.boards.any { it.id == board.id }
            val boards = if (exists) cur.boards else cur.boards + board
            cur.copy(boards = boards)
        }
    }

    fun createBoard(board: WhiteboardBoard, prepend: Boolean = true): WorkspaceDbState {
        return commit { cur ->
            val boards = if (cur.boards.any { it.id == board.id }) {
                cur.boards.map { if (it.id == board.id) board else it }
            } else if (prepend) {
                listOf(board) + cur.boards
            } else {
                cur.boards + board
            }
            cur.copy(boards = boards)
        }
    }

    fun renameBoard(boardId: String, newTitle: String): WorkspaceDbState {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return _dbState.value
        return commit { cur ->
            val updatedBoards = cur.boards.map { b ->
                if (b.id == boardId) b.copy(title = clean, updatedAt = System.currentTimeMillis()) else b
            }
            cur.copy(boards = updatedBoards)
        }
    }

    fun deleteBoard(boardId: String, fallbackBoard: WhiteboardBoard? = null): WorkspaceDbState {
        rasterTileStore.removeBoard(boardId)
        rasterSidecarStore?.deleteBoardDir(boardId)
        return commit { cur ->
            val remainingBoards = cur.boards.filter { it.id != boardId }
            val nextBoards = if (remainingBoards.isEmpty() && fallbackBoard != null) {
                listOf(fallbackBoard)
            } else {
                remainingBoards
            }
            cur.copy(boards = nextBoards)
        }
    }

    fun syncBoardFromWhiteboard(
        board: WhiteboardBoard,
        persist: Boolean = true
    ): WorkspaceDbState {
        return commit(persist = persist) { cur ->
            val boardExists = cur.boards.any { it.id == board.id }
            val updatedBoards = if (boardExists) {
                cur.boards.map { if (it.id == board.id) board else it }
            } else {
                cur.boards + board
            }
            cur.copy(boards = updatedBoards)
        }
    }

    private fun persistToDisk(state: WorkspaceDbState): Boolean {
        val targetFile = storageFile ?: return true
        val atomicFile = AtomicFile(targetFile)
        var outputStream: FileOutputStream? = null
        return try {
            targetFile.parentFile?.mkdirs()
            val root = JSONObject()
            root.put("schemaVersion", 2)

            val boardsArr = JSONArray()
            state.boards.forEach { board ->
                val boardObj = BoardSnapshotSerializer.serializeBoardObject(board)
                boardObj.put("createdAt", board.createdAt)
                boardObj.put("updatedAt", board.updatedAt)
                boardsArr.put(boardObj)
            }
            root.put("boards", boardsArr)

            val stream = atomicFile.startWrite()
            outputStream = stream
            val writer = OutputStreamWriter(stream, Charsets.UTF_8)
            writer.write(root.toString())
            writer.flush()
            atomicFile.finishWrite(stream)
            outputStream = null
            rasterSidecarStore?.syncWorkspace(state.boards, rasterTileStore)
            true
        } catch (error: Exception) {
            outputStream?.let { stream ->
                try {
                    atomicFile.failWrite(stream)
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
            }
            Log.e("LocalFirstWorkspaceStore", "Failed to persist workspace snapshot", error)
            false
        }
    }

    private fun loadFromDiskOrEmpty(): WorkspaceDbState {
        val targetFile = storageFile ?: return WorkspaceDbState()
        val atomicBackup = File(targetFile.path + ".bak")
        if (!targetFile.exists() && !atomicBackup.exists()) return WorkspaceDbState()
        return try {
            val raw = AtomicFile(targetFile).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (raw.isBlank()) return WorkspaceDbState()
            val root = JSONObject(raw)
            val boards = mutableListOf<WhiteboardBoard>()
            val boardsArr = root.optJSONArray("boards")
            if (boardsArr != null) {
                for (i in 0 until boardsArr.length()) {
                    val bo = boardsArr.getJSONObject(i)
                    val baseBoard = WhiteboardBoard(
                        id = bo.optString("boardId", UUID.randomUUID().toString()),
                        title = bo.optString("title", "Pizarra Principal"),
                        createdAt = bo.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = bo.optLong("updatedAt", System.currentTimeMillis())
                    )
                    val restored = BoardSnapshotSerializer.deserializeIntoBoard(bo.toString(), baseBoard) ?: baseBoard
                    boards.add(restored)
                }
            }
            // Under strict 1 GB RAM, only load the active/first board's PNG tiles at startup;
            // remaining boards load on demand when opened.
            if (memoryBudget.isLowRamProfile) {
                boards.firstOrNull()?.let { first ->
                    rasterSidecarStore?.loadBoardInto(first.id, rasterTileStore)
                }
            } else {
                rasterSidecarStore?.loadAllInto(rasterTileStore)
            }
            WorkspaceDbState(boards = boards)
        } catch (error: Exception) {
            val recoveryCopy = try {
                preserveUnreadableSnapshot(targetFile)
            } catch (copyError: Exception) {
                error.addSuppressed(copyError)
                null
            }
            val detail = recoveryCopy?.absolutePath ?: "no recovery copy could be created"
            Log.e(
                "LocalFirstWorkspaceStore",
                "Failed to load workspace snapshot; starting with empty state ($detail)",
                error
            )
            WorkspaceDbState()
        }
    }

    private fun preserveUnreadableSnapshot(targetFile: File): File? {
        if (!targetFile.isFile) return null
        val parent = targetFile.parentFile ?: return null
        var suffix = 0
        var recoveryFile = File(parent, "${targetFile.name}.corrupt")
        while (recoveryFile.exists()) {
            suffix += 1
            recoveryFile = File(parent, "${targetFile.name}.corrupt-$suffix")
        }
        targetFile.copyTo(recoveryFile, overwrite = false)
        return recoveryFile
    }

    companion object {
        @Volatile
        private var instance: LocalFirstWorkspaceStore? = null

        fun getInstance(context: Context): LocalFirstWorkspaceStore {
            return instance ?: synchronized(this) {
                val appCtx = context.applicationContext
                instance ?: LocalFirstWorkspaceStore(
                    storageFile = File(appCtx.filesDir, "workspace_ssot_db.json"),
                    loadFromDiskInBackground = true,
                    memoryBudget = MemoryBudgetProfile.fromContext(appCtx)
                ).also { instance = it }
            }
        }
    }
}
