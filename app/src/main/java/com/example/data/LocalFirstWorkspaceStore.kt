package com.example.data

import android.content.Context
import com.example.model.StickyNote
import com.example.model.WhiteboardBoard
import com.example.notes.data.NoteExportImportService
import com.example.notes.model.CanvasNoteData
import com.example.notes.model.NoteFolder
import com.example.notes.model.NoteItem
import com.example.notes.model.NoteTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class WorkspaceDbState(
    val notes: List<NoteItem> = emptyList(),
    val folders: List<NoteFolder> = emptyList(),
    val boards: List<WhiteboardBoard> = emptyList()
)

/**
 * Single Source of Truth (local-first) store synchronizing Whiteboard boards/stickyNotes
 * and Notes notes/folders with zero data duplication and atomic local persistence.
 */
class LocalFirstWorkspaceStore(
    private val storageFile: File? = null
) {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(WorkspaceDbState) -> Unit>()

    private val _dbState = MutableStateFlow(loadFromDiskOrEmpty())
    val dbState: StateFlow<WorkspaceDbState> = _dbState.asStateFlow()

    val currentState: WorkspaceDbState
        get() = _dbState.value

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
            next = projectBoardsFromNotes(transform(_dbState.value))
            _dbState.value = next
            if (persist) {
                persistToDisk(next)
            }
        }
        listeners.forEach { it(next) }
        return next
    }

    fun flushToDisk() {
        synchronized(lock) {
            persistToDisk(_dbState.value)
        }
    }

    // --- BOARD <-> FOLDER & STICKY NOTE SSOT OPERATIONS ---

    fun ensureBoardRegistered(board: WhiteboardBoard): WorkspaceDbState {
        synchronized(lock) {
            val cur = _dbState.value
            val alreadyExists = cur.boards.any { it.id == board.id }
            val hasLinkedFolder = cur.folders.any { it.linkedBoardId == board.id }
            if (alreadyExists && hasLinkedFolder) return cur
        }
        return commit { cur ->
            val exists = cur.boards.any { it.id == board.id }
            val boards = if (exists) cur.boards else cur.boards + board
            val (folders, linkedFolder) = ensureLinkedFolderInternal(cur.folders, board.id, board.title)
            val notes = upsertBoardStickyNotesInternal(
                existingNotes = cur.notes,
                boardId = board.id,
                folderName = linkedFolder.name,
                stickyNotes = board.stickyNotes
            )
            cur.copy(notes = notes, folders = folders, boards = boards)
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
            val (folders, linkedFolder) = ensureLinkedFolderInternal(cur.folders, board.id, board.title)
            val notes = upsertBoardStickyNotesInternal(
                existingNotes = cur.notes,
                boardId = board.id,
                folderName = linkedFolder.name,
                stickyNotes = board.stickyNotes
            )
            cur.copy(notes = notes, folders = folders, boards = boards)
        }
    }

    fun renameBoard(boardId: String, newTitle: String): WorkspaceDbState {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return _dbState.value
        return commit { cur ->
            val oldBoardTitle = cur.boards.find { it.id == boardId }?.title
            val oldFolder = cur.folders.find { it.linkedBoardId == boardId }
            val oldFolderName = oldFolder?.name ?: oldBoardTitle

            val updatedBoards = cur.boards.map { b ->
                if (b.id == boardId) b.copy(title = clean, updatedAt = System.currentTimeMillis()) else b
            }
            val (updatedFolders, _) = ensureLinkedFolderInternal(cur.folders, boardId, clean)
            val updatedNotes = cur.notes.map { n ->
                val belongsToBoard = n.canvasData?.boardId == boardId ||
                    (oldFolderName != null && n.folder.equals(oldFolderName, ignoreCase = true))
                if (belongsToBoard) {
                    n.copy(folder = clean, updatedAt = System.currentTimeMillis())
                } else n
            }
            cur.copy(notes = updatedNotes, folders = updatedFolders, boards = updatedBoards)
        }
    }

    fun deleteBoard(boardId: String, fallbackBoard: WhiteboardBoard? = null): WorkspaceDbState {
        return commit { cur ->
            val remainingBoards = cur.boards.filter { it.id != boardId }
            val nextBoards = if (remainingBoards.isEmpty() && fallbackBoard != null) {
                listOf(fallbackBoard)
            } else {
                remainingBoards
            }
            // Preserve notes safely in Notas by detaching canvasData when their board is deleted
            val updatedNotes = cur.notes.map { n ->
                val nextLinks = n.linkedBoardIds - boardId
                if (n.canvasData?.boardId == boardId) {
                    n.copy(canvasData = null, linkedBoardIds = nextLinks, updatedAt = System.currentTimeMillis())
                } else if (nextLinks != n.linkedBoardIds) {
                    n.copy(linkedBoardIds = nextLinks, updatedAt = System.currentTimeMillis())
                } else n
            }
            val updatedFolders = cur.folders.map { f ->
                if (f.linkedBoardId == boardId) f.copy(linkedBoardId = null) else f
            }
            val finalFolders = if (remainingBoards.isEmpty() && fallbackBoard != null) {
                ensureLinkedFolderInternal(updatedFolders, fallbackBoard.id, fallbackBoard.title).first
            } else {
                updatedFolders
            }
            cur.copy(notes = updatedNotes, folders = finalFolders, boards = nextBoards)
        }
    }

    /**
     * Converts legacy canvas sticky notes into regular Notes documents linked by board ID.
     * The note ID and all rich-note fields remain unchanged; only canvas placement metadata is detached.
     */
    fun migrateBoardStickyNotesToDocuments(boardId: String): WorkspaceDbState {
        return commit { cur ->
            val board = cur.boards.find { it.id == boardId } ?: return@commit cur
            val (folders, linkedFolder) = ensureLinkedFolderInternal(cur.folders, board.id, board.title)
            val upserted = upsertBoardStickyNotesInternal(
                existingNotes = cur.notes,
                boardId = board.id,
                folderName = linkedFolder.name,
                stickyNotes = board.stickyNotes
            )
            val now = System.currentTimeMillis()
            val migratedNotes = upserted.map { note ->
                if (note.canvasData?.boardId == boardId) {
                    note.copy(
                        canvasData = null,
                        linkedBoardIds = note.linkedBoardIds + boardId,
                        updatedAt = now
                    )
                } else note
            }
            val migratedBoards = cur.boards.map { current ->
                if (current.id == boardId && current.stickyNotes.isNotEmpty()) {
                    current.copy(stickyNotes = emptyList(), updatedAt = now)
                } else current
            }
            cur.copy(notes = migratedNotes, folders = folders, boards = migratedBoards)
        }
    }

    fun linkNoteToBoard(noteId: String, boardId: String): WorkspaceDbState {
        return commit { cur ->
            if (cur.boards.none { it.id == boardId }) return@commit cur
            cur.copy(notes = cur.notes.map { note ->
                if (note.id == noteId && !note.isDeleted) {
                    note.copy(linkedBoardIds = note.linkedBoardIds + boardId, updatedAt = System.currentTimeMillis())
                } else note
            })
        }
    }

    fun unlinkNoteFromBoard(noteId: String, boardId: String): WorkspaceDbState {
        return commit { cur ->
            cur.copy(notes = cur.notes.map { note ->
                if (note.id == noteId) {
                    note.copy(linkedBoardIds = note.linkedBoardIds - boardId, updatedAt = System.currentTimeMillis())
                } else note
            })
        }
    }

    fun syncBoardFromWhiteboard(
        board: WhiteboardBoard,
        softDeleteMissingNotes: Boolean = true,
        persist: Boolean = true
    ): WorkspaceDbState {
        return commit(persist = persist) { cur ->
            val (folders, linkedFolder) = ensureLinkedFolderInternal(cur.folders, board.id, board.title)
            val updatedNotes = syncBoardStickyNotesInternal(
                existingNotes = cur.notes,
                boardId = board.id,
                folderName = linkedFolder.name,
                stickyNotes = board.stickyNotes,
                softDeleteMissing = softDeleteMissingNotes
            )
            val boardExists = cur.boards.any { it.id == board.id }
            val updatedBoards = if (boardExists) {
                cur.boards.map { if (it.id == board.id) board else it }
            } else {
                cur.boards + board
            }
            cur.copy(notes = updatedNotes, folders = folders, boards = updatedBoards)
        }
    }

    fun upsertStickyNote(
        boardId: String,
        boardTitle: String,
        stickyNote: StickyNote
    ): WorkspaceDbState {
        return commit { cur ->
            val (folders, linkedFolder) = ensureLinkedFolderInternal(cur.folders, boardId, boardTitle)
            val updatedNotes = upsertBoardStickyNotesInternal(
                existingNotes = cur.notes,
                boardId = boardId,
                folderName = linkedFolder.name,
                stickyNotes = listOf(stickyNote)
            )
            cur.copy(notes = updatedNotes, folders = folders)
        }
    }

    fun softDeleteNotes(noteIds: Set<String>): WorkspaceDbState {
        if (noteIds.isEmpty()) return _dbState.value
        val now = System.currentTimeMillis()
        return commit { cur ->
            val updatedNotes = cur.notes.map { n ->
                if (noteIds.contains(n.id)) {
                    n.copy(isDeleted = true, deletedAt = now, updatedAt = now)
                } else n
            }
            cur.copy(notes = updatedNotes)
        }
    }

    fun detachNoteFromCanvas(noteId: String): WorkspaceDbState {
        val now = System.currentTimeMillis()
        return commit { cur ->
            val updatedNotes = cur.notes.map { n ->
                if (n.id == noteId) {
                    n.copy(canvasData = null, isDeleted = false, deletedAt = null, updatedAt = now)
                } else n
            }
            cur.copy(notes = updatedNotes)
        }
    }

    fun restoreNoteFromTrash(noteId: String): WorkspaceDbState {
        val now = System.currentTimeMillis()
        return commit { cur ->
            var folders = cur.folders
            val updatedNotes = cur.notes.map { n ->
                if (n.id == noteId) {
                    val boardId = n.canvasData?.boardId
                    if (boardId != null) {
                        val boardTitle = cur.boards.find { it.id == boardId }?.title ?: n.folder
                        val ensured = ensureLinkedFolderInternal(folders, boardId, boardTitle)
                        folders = ensured.first
                        n.copy(
                            folder = ensured.second.name,
                            isDeleted = false,
                            deletedAt = null,
                            updatedAt = now
                        )
                    } else {
                        n.copy(isDeleted = false, deletedAt = null, updatedAt = now)
                    }
                } else n
            }
            cur.copy(notes = updatedNotes, folders = folders)
        }
    }

    fun permanentlyDeleteNote(noteId: String): WorkspaceDbState {
        val target = _dbState.value.notes.find { it.id == noteId }
        target?.voiceAudioUri?.let { path ->
            try {
                File(path).takeIf { it.exists() }?.delete()
            } catch (_: Exception) {}
        }
        return commit { cur ->
            cur.copy(notes = cur.notes.filter { it.id != noteId })
        }
    }

    fun emptyTrash(): WorkspaceDbState {
        val trashed = _dbState.value.notes.filter { it.isDeleted }
        trashed.forEach { target ->
            target.voiceAudioUri?.let { path ->
                try {
                    File(path).takeIf { it.exists() }?.delete()
                } catch (_: Exception) {}
            }
        }
        return commit { cur ->
            cur.copy(notes = cur.notes.filter { !it.isDeleted })
        }
    }

    // --- NOTES MODULE OPERATIONS ---

    fun upsertNote(note: NoteItem, prependIfNew: Boolean = true): WorkspaceDbState {
        return commit { cur ->
            val linkedFolder = cur.folders.find {
                it.name.equals(note.folder, ignoreCase = true) && it.linkedBoardId != null
            }
            val existingBoardId = note.canvasData?.boardId
            val targetBoardId = linkedFolder?.linkedBoardId
            val normalizedCanvas = when {
                targetBoardId != null && note.canvasData == null -> {
                    val boardNoteCount = cur.notes.count {
                        !it.isDeleted && it.canvasData?.boardId == targetBoardId && it.id != note.id
                    }
                    CanvasNoteData(
                        boardId = targetBoardId,
                        x = 72f + (boardNoteCount % 5) * 28f,
                        y = 72f + (boardNoteCount / 5) * 28f,
                        color = note.colorHex,
                        isLocked = note.isLocked
                    )
                }
                targetBoardId != null && note.canvasData != null ->
                    note.canvasData.copy(boardId = targetBoardId, color = note.colorHex, isLocked = note.isLocked)
                existingBoardId != null && targetBoardId == null -> null
                note.canvasData != null ->
                    note.canvasData.copy(color = note.colorHex, isLocked = note.isLocked)
                else -> null
            }
            val normalizedNote = note.copy(canvasData = normalizedCanvas)
            val exists = cur.notes.any { it.id == note.id }
            val updatedNotes = if (exists) {
                cur.notes.map { if (it.id == note.id) normalizedNote else it }
            } else if (prependIfNew) {
                listOf(normalizedNote) + cur.notes
            } else {
                cur.notes + normalizedNote
            }
            cur.copy(notes = updatedNotes)
        }
    }

    fun addFolder(folder: NoteFolder): WorkspaceDbState {
        return commit { cur ->
            val exists = cur.folders.any { it.name.equals(folder.name, ignoreCase = true) }
            val updatedFolders = if (exists) cur.folders else cur.folders + folder
            cur.copy(folders = updatedFolders)
        }
    }

    fun importNotesAndFolders(
        importedNotes: List<NoteItem>,
        importedFolders: List<NoteFolder>
    ): WorkspaceDbState {
        return commit { cur ->
            val existingIds = cur.notes.map { it.id }.toSet()
            val freshNotes = importedNotes.filter { !existingIds.contains(it.id) }
            val mergedFolders = (cur.folders + importedFolders).distinctBy { it.name.lowercase() }
            cur.copy(
                notes = freshNotes + cur.notes,
                folders = mergedFolders
            )
        }
    }

    // --- INTERNAL MAPPING & PROJECTION HELPERS ---

    private fun ensureLinkedFolderInternal(
        folders: List<NoteFolder>,
        boardId: String,
        boardTitle: String
    ): Pair<List<NoteFolder>, NoteFolder> {
        val cleanTitle = boardTitle.trim().ifBlank { "Pizarra Principal" }
        val existingByBoard = folders.find { it.linkedBoardId == boardId }
        if (existingByBoard != null) {
            val updated = existingByBoard.copy(name = cleanTitle)
            val nextList = folders.map { if (it.id == existingByBoard.id) updated else it }
            return nextList to updated
        }
        val existingByName = folders.find { it.linkedBoardId == null && it.name.equals(cleanTitle, ignoreCase = true) }
        if (existingByName != null) {
            val linked = existingByName.copy(linkedBoardId = boardId, iconName = "draw")
            val nextList = folders.map { if (it.id == existingByName.id) linked else it }
            return nextList to linked
        }
        val created = NoteFolder(
            id = UUID.randomUUID().toString(),
            name = cleanTitle,
            iconName = "draw",
            colorHex = "#0078D4",
            isDefault = false,
            linkedBoardId = boardId
        )
        return (folders + created) to created
    }

    private fun upsertBoardStickyNotesInternal(
        existingNotes: List<NoteItem>,
        boardId: String,
        folderName: String,
        stickyNotes: List<StickyNote>
    ): List<NoteItem> {
        if (stickyNotes.isEmpty()) return existingNotes
        val now = System.currentTimeMillis()
        val byId = existingNotes.associateBy { it.id }.toMutableMap()
        val orderIds = existingNotes.map { it.id }.toMutableList()

        stickyNotes.forEach { sticky ->
            val current = byId[sticky.id]
            val merged = mergeStickyIntoNoteItem(
                existing = current,
                sticky = sticky,
                boardId = boardId,
                folderName = folderName,
                now = now
            )
            if (current == null) {
                orderIds.add(0, merged.id)
            }
            byId[merged.id] = merged
        }
        return orderIds.mapNotNull { byId[it] }
    }

    private fun syncBoardStickyNotesInternal(
        existingNotes: List<NoteItem>,
        boardId: String,
        folderName: String,
        stickyNotes: List<StickyNote>,
        softDeleteMissing: Boolean
    ): List<NoteItem> {
        val now = System.currentTimeMillis()
        val incomingIds = stickyNotes.map { it.id }.toSet()
        val upserted = upsertBoardStickyNotesInternal(existingNotes, boardId, folderName, stickyNotes)
        if (!softDeleteMissing) return upserted

        return upserted.map { n ->
            if (n.canvasData?.boardId == boardId && !n.isDeleted && !incomingIds.contains(n.id)) {
                n.copy(isDeleted = true, deletedAt = now, updatedAt = now)
            } else {
                n
            }
        }
    }

    private fun projectBoardsFromNotes(state: WorkspaceDbState): WorkspaceDbState {
        val activeCanvasNotesByBoard = state.notes
            .filter { !it.isDeleted && it.canvasData != null }
            .groupBy { it.canvasData!!.boardId }

        val syncedBoards = state.boards.map { board ->
            val projectedStickies = (activeCanvasNotesByBoard[board.id] ?: emptyList())
                .mapNotNull { it.toStickyNote() }
            board.copy(stickyNotes = projectedStickies)
        }
        return state.copy(boards = syncedBoards)
    }

    private fun persistToDisk(state: WorkspaceDbState) {
        val targetFile = storageFile ?: return
        try {
            targetFile.parentFile?.mkdirs()
            val root = JSONObject()
            root.put("schemaVersion", 1)

            // Persist unified notes + folders via NoteExportImportService (single source of truth)
            val notesJson = JSONObject(NoteExportImportService.exportAllToJson(state.notes, state.folders))
            root.put("notes", notesJson.getJSONArray("notes"))
            root.put("folders", notesJson.getJSONArray("folders"))

            // Persist boards without duplicating stickyNotes (stickyNotes are derived from notes with canvasData)
            val boardsArr = JSONArray()
            state.boards.forEach { board ->
                val boardWithoutDuplicateNotes = board.copy(stickyNotes = emptyList())
                val boardObj = JSONObject(BoardSnapshotSerializer.serializeBoard(boardWithoutDuplicateNotes))
                boardObj.put("createdAt", board.createdAt)
                boardObj.put("updatedAt", board.updatedAt)
                boardsArr.put(boardObj)
            }
            root.put("boards", boardsArr)

            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            tempFile.writeText(root.toString())
            if (!tempFile.renameTo(targetFile)) {
                targetFile.writeText(root.toString())
                tempFile.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun loadFromDiskOrEmpty(): WorkspaceDbState {
        val targetFile = storageFile ?: return WorkspaceDbState()
        if (!targetFile.exists()) return WorkspaceDbState()
        return try {
            val raw = targetFile.readText()
            if (raw.isBlank()) return WorkspaceDbState()
            val root = JSONObject(raw)
            val (notes, folders) = NoteExportImportService.importFromJson(raw)
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
            projectBoardsFromNotes(WorkspaceDbState(notes = notes, folders = folders, boards = boards))
        } catch (_: Exception) {
            WorkspaceDbState()
        }
    }

    companion object {
        @Volatile
        private var instance: LocalFirstWorkspaceStore? = null

        fun getInstance(context: Context): LocalFirstWorkspaceStore {
            return instance ?: synchronized(this) {
                instance ?: LocalFirstWorkspaceStore(
                    storageFile = File(context.applicationContext.filesDir, "workspace_ssot_db.json")
                ).also { instance = it }
            }
        }

        fun mergeStickyIntoNoteItem(
            existing: NoteItem?,
            sticky: StickyNote,
            boardId: String,
            folderName: String,
            now: Long = System.currentTimeMillis()
        ): NoteItem {
            val canvas = CanvasNoteData(
                boardId = boardId,
                x = sticky.x,
                y = sticky.y,
                width = sticky.width,
                height = sticky.height,
                rotation = sticky.rotation,
                color = sticky.colorHex,
                isLocked = sticky.isLocked,
                isAspectRatioLocked = sticky.isAspectRatioLocked,
                groupId = sticky.groupId,
                parentId = sticky.parentId,
                isHiddenByCollapse = sticky.isHiddenByCollapse
            )
            if (existing == null) {
                return NoteItem(
                    id = sticky.id,
                    title = sticky.title.orEmpty(),
                    content = sticky.text,
                    folder = folderName,
                    tags = sticky.tags.map { NoteTag(name = it, colorHex = "#0078D4") },
                    colorHex = sticky.colorHex,
                    isPinned = sticky.isPinned,
                    isLocked = sticky.isLocked,
                    isVoiceNote = sticky.isVoiceNote,
                    voiceDurationSec = sticky.voiceDurationSec,
                    canvasData = canvas,
                    isDeleted = false,
                    deletedAt = null,
                    createdAt = now,
                    updatedAt = now
                )
            }

            // Legacy canvas payloads had no independent title field and stored
            // "title + body" in StickyNote.text. New payloads always carry title
            // separately, including an explicitly empty title.
            val (nextTitle, nextContent) = if (sticky.title == null) {
                applyLegacyStickyTextEdit(existing.title, existing.content, sticky.text)
            } else {
                sticky.title to sticky.text
            }

            // StickyNote currently exposes tag names only. Preserve each existing
            // tag's color and metadata rather than replacing it with a generic color.
            val nextTags = if (sticky.tags.isEmpty()) {
                existing.tags
            } else {
                sticky.tags.map { tagName ->
                    existing.tags.firstOrNull { it.name.equals(tagName, ignoreCase = true) }
                        ?: NoteTag(name = tagName, colorHex = "#0078D4")
                }
            }

            val changed = nextTitle != existing.title ||
                nextContent != existing.content ||
                existing.colorHex != sticky.colorHex ||
                existing.isPinned != sticky.isPinned ||
                existing.isLocked != sticky.isLocked ||
                existing.tags != nextTags ||
                existing.folder != folderName ||
                existing.canvasData != canvas ||
                existing.isDeleted

            // Do not copy voice, checklist, version-history, pin-hash or other
            // note-only fields from the lightweight canvas projection. They remain
            // owned by the existing NoteItem and survive canvas-only edits.
            return existing.copy(
                title = nextTitle,
                content = nextContent,
                folder = folderName,
                tags = nextTags,
                colorHex = sticky.colorHex,
                isPinned = sticky.isPinned,
                isLocked = sticky.isLocked,
                canvasData = canvas,
                isDeleted = false,
                deletedAt = null,
                updatedAt = if (changed) now else existing.updatedAt
            )
        }

        private fun applyLegacyStickyTextEdit(
            existingTitle: String,
            existingContent: String,
            newStickyText: String
        ): Pair<String, String> {
            val existingCombined = when {
                existingTitle.isNotBlank() && existingContent.isNotBlank() -> "$existingTitle\n$existingContent"
                existingTitle.isNotBlank() -> existingTitle
                else -> existingContent
            }
            if (newStickyText == existingCombined) {
                return existingTitle to existingContent
            }
            if (existingTitle.isNotBlank()) {
                val prefix = "$existingTitle\n"
                if (newStickyText.startsWith(prefix)) {
                    return existingTitle to newStickyText.removePrefix(prefix)
                }
                if (newStickyText == existingTitle) {
                    return existingTitle to ""
                }
                // Old cards treated the entire editable value as body text. Keep
                // the original title rather than silently deleting it on an edit.
                return existingTitle to newStickyText
            }
            return "" to newStickyText
        }
    }
}

fun NoteItem.effectiveStickyText(): String {
    return when {
        title.isNotBlank() && content.isNotBlank() -> "$title\n$content"
        title.isNotBlank() -> title
        else -> content
    }
}

fun NoteItem.toStickyNote(): StickyNote? {
    val cd = canvasData ?: return null
    return StickyNote(
        id = id,
        text = content,
        x = cd.x,
        y = cd.y,
        width = cd.width,
        height = cd.height,
        colorHex = cd.color.ifBlank { colorHex },
        rotation = cd.rotation,
        isPinned = isPinned,
        isVoiceNote = isVoiceNote,
        voiceDurationSec = voiceDurationSec,
        tags = tags.map { it.name },
        isLocked = cd.isLocked || isLocked,
        isAspectRatioLocked = cd.isAspectRatioLocked,
        groupId = cd.groupId,
        parentId = cd.parentId,
        isHiddenByCollapse = cd.isHiddenByCollapse,
        title = title
    )
}
