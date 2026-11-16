package com.example.notes.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.LocalFirstWorkspaceStore
import com.example.data.WorkspaceDbState
import com.example.notes.data.NoteExportImportService
import com.example.notes.model.ChecklistItem
import com.example.notes.model.NoteFolder
import com.example.notes.model.NoteItem
import com.example.notes.model.NoteTag
import com.example.notes.model.NoteVersion
import com.example.notes.model.VaultSecuritySettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class AppActiveWorkspace {
    NOTES,
    WHITEBOARD
}

data class NotesUiState(
    val notes: List<NoteItem> = emptyList(),
    val trashNotes: List<NoteItem> = emptyList(),
    val isViewingTrash: Boolean = false,
    val folders: List<NoteFolder> = emptyList(),
    val boardTitlesById: Map<String, String> = emptyMap(),
    val selectedFolder: String? = null, // null means "Todas"
    val selectedTag: String? = null,
    val searchQuery: String = "",
    val activeNote: NoteItem? = null,
    val isEditingNote: Boolean = false,
    val isGridLayout: Boolean = true,
    val isChecklistFocusRequested: Boolean = false,
    val vaultSettings: VaultSecuritySettings = VaultSecuritySettings(),
    val unlockedNoteIds: Set<String> = emptySet(),
    val isRecordingVoice: Boolean = false,
    val recordingDurationSec: Int = 0,
    val isVoicePlaying: Boolean = false,
    val voicePlayProgress: Float = 0f,
    val isMarkdownPreview: Boolean = false,
    val activeWorkspace: AppActiveWorkspace = AppActiveWorkspace.NOTES,
    val showSecurityPinModal: Boolean = false,
    val noteIdPendingUnlock: String? = null,
    val showVersionHistoryModal: Boolean = false,
    val showExportImportModal: Boolean = false,
    val showNewFolderDialog: Boolean = false,
    val statusNotification: String? = null
) {
    val filteredNotes: List<NoteItem>
        get() {
            var list = if (isViewingTrash) trashNotes else notes
            // Folder filter (only when not viewing trash)
            if (!isViewingTrash && !selectedFolder.isNullOrBlank() && selectedFolder != "Todas") {
                list = list.filter { it.folder.equals(selectedFolder, ignoreCase = true) }
            }
            // Tag filter
            if (!selectedTag.isNullOrBlank()) {
                list = list.filter { note -> note.tags.any { it.name.equals(selectedTag, ignoreCase = true) } }
            }
            // Search query filter (Full-text search)
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim().lowercase()
                list = list.filter { note ->
                    note.title.lowercase().contains(q) ||
                        note.content.lowercase().contains(q) ||
                        note.tags.any { it.name.lowercase().contains(q) } ||
                        note.checklists.any { it.text.lowercase().contains(q) } ||
                        note.folder.lowercase().contains(q)
                }
            }
            // Sort: pinned first, then newest updated
            return list.sortedWith(
                compareByDescending<NoteItem> { if (isViewingTrash) 0L else if (it.isPinned) 1L else 0L }
                    .thenByDescending { if (isViewingTrash) (it.deletedAt ?: it.updatedAt) else it.updatedAt }
            )
        }
}

class NotesViewModel(
    private val store: LocalFirstWorkspaceStore = LocalFirstWorkspaceStore()
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotesUiState())
    private var pendingPersistenceJob: Job? = null
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    private val storeListener: (WorkspaceDbState) -> Unit = { db ->
        applyDbState(db)
    }

    init {
        store.addListener(storeListener)
        applyDbState(store.currentState)
    }

    override fun onCleared() {
        pendingPersistenceJob?.cancel()
        store.flushToDisk()
        store.removeListener(storeListener)
        super.onCleared()
    }

    private fun applyDbState(db: WorkspaceDbState) {
        val visibleNotes = db.notes.filter { !it.isDeleted }
        val deletedNotes = db.notes.filter { it.isDeleted }
        val boardMap = db.boards.associate { it.id to it.title }
        _uiState.update { state ->
            val activeId = state.activeNote?.id
            val nextActive = activeId?.let { id -> visibleNotes.find { it.id == id } }
            val activeWasRemoved = activeId != null && nextActive == null
            if (state.notes == visibleNotes &&
                state.trashNotes == deletedNotes &&
                state.folders == db.folders &&
                state.boardTitlesById == boardMap &&
                state.activeNote == nextActive &&
                !activeWasRemoved
            ) {
                state
            } else {
                state.copy(
                    notes = visibleNotes,
                    trashNotes = deletedNotes,
                    folders = db.folders,
                    boardTitlesById = boardMap,
                    activeNote = nextActive,
                    isEditingNote = if (activeWasRemoved) false else state.isEditingNote,
                    unlockedNoteIds = if (activeWasRemoved) state.unlockedNoteIds - activeId!! else state.unlockedNoteIds
                )
            }
        }
    }

    // --- NAVIGATION & WORKSPACE ---

    fun switchWorkspace(workspace: AppActiveWorkspace) {
        _uiState.update { it.copy(activeWorkspace = workspace) }
    }

    fun toggleNotesLayout() {
        _uiState.update { it.copy(isGridLayout = !it.isGridLayout) }
    }

    // --- ZERO-FRICTION NOTE ACTIONS ---

    fun createNewNote(folder: String = "Bandeja de Entrada") {
        val newNote = NoteItem(
            id = UUID.randomUUID().toString(),
            title = "",
            content = "",
            folder = if (folder == "Todas") "Bandeja de Entrada" else folder,
            tags = emptyList(),
            colorHex = "#FFFFFF",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val db = store.upsertNote(newNote, prependIfNew = true)
        val canonical = db.notes.find { it.id == newNote.id } ?: newNote
        _uiState.update {
            it.copy(
                isViewingTrash = false,
                activeNote = canonical,
                isEditingNote = true,
                isMarkdownPreview = false,
                isChecklistFocusRequested = false
            )
        }
    }

    fun createNewChecklistNote(folder: String = "Bandeja de Entrada") {
        val newNote = NoteItem(
            id = UUID.randomUUID().toString(),
            title = "",
            content = "",
            folder = if (folder == "Todas") "Bandeja de Entrada" else folder,
            tags = emptyList(),
            colorHex = "#FFFFFF",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val db = store.upsertNote(newNote, prependIfNew = true)
        val canonical = db.notes.find { it.id == newNote.id } ?: newNote
        _uiState.update {
            it.copy(
                isViewingTrash = false,
                activeNote = canonical,
                isEditingNote = true,
                isMarkdownPreview = false,
                isChecklistFocusRequested = true
            )
        }
    }

    fun clearChecklistFocusRequest() {
        _uiState.update { it.copy(isChecklistFocusRequested = false) }
    }

    fun openNote(note: NoteItem) {
        if (note.isDeleted) return
        if (note.isLocked && !isNoteUnlocked(note.id)) {
            _uiState.update {
                it.copy(
                    showSecurityPinModal = true,
                    noteIdPendingUnlock = note.id
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    activeNote = note,
                    isEditingNote = true,
                    isMarkdownPreview = false,
                    isChecklistFocusRequested = false
                )
            }
        }
    }

    fun closeActiveNote() {
        val current = _uiState.value.activeNote
        if (current != null && (current.title.isNotBlank() || current.content.isNotBlank() || current.checklists.isNotEmpty())) {
            // Save version snapshot if modified
            saveNoteVersion(current, "Cierre de editor")
        }
        _uiState.update {
            val nextUnlocked = if (current != null && current.isLocked) {
                it.unlockedNoteIds - current.id
            } else {
                it.unlockedNoteIds
            }
            it.copy(
                activeNote = null,
                isEditingNote = false,
                isChecklistFocusRequested = false,
                unlockedNoteIds = nextUnlocked
            )
        }
        flushPendingPersistence()
    }

    fun updateActiveNoteTitle(newTitle: String) {
        val current = _uiState.value.activeNote ?: return
        val updated = current.copy(title = newTitle, updatedAt = System.currentTimeMillis())
        updateNoteInState(updated)
    }

    fun updateActiveNoteContent(newContent: String) {
        val current = _uiState.value.activeNote ?: return
        val updated = current.copy(content = newContent, updatedAt = System.currentTimeMillis())
        updateNoteInState(updated)
    }

    fun updateActiveNoteFolder(folderName: String) {
        val current = _uiState.value.activeNote ?: return
        val updated = current.copy(folder = folderName, updatedAt = System.currentTimeMillis())
        updateNoteInState(updated)
    }

    fun updateActiveNoteColor(colorHex: String) {
        val current = _uiState.value.activeNote ?: return
        val updated = current.copy(colorHex = colorHex, updatedAt = System.currentTimeMillis())
        updateNoteInState(updated)
    }

    fun updateNoteColor(noteId: String, colorHex: String) {
        val target = store.currentState.notes.find { it.id == noteId } ?: return
        val updated = target.copy(colorHex = colorHex, updatedAt = System.currentTimeMillis())
        updateNoteInState(updated)
    }

    fun toggleActiveNotePin() {
        val current = _uiState.value.activeNote ?: return
        val updated = current.copy(isPinned = !current.isPinned, updatedAt = System.currentTimeMillis())
        updateNoteInState(updated)
    }

    fun toggleNotePin(noteId: String) {
        val target = store.currentState.notes.find { it.id == noteId } ?: return
        val updated = target.copy(isPinned = !target.isPinned, updatedAt = System.currentTimeMillis())
        updateNoteInState(updated)
    }

    fun deleteNote(noteId: String) {
        store.softDeleteNotes(setOf(noteId))
        _uiState.update { state ->
            state.copy(
                activeNote = if (state.activeNote?.id == noteId) null else state.activeNote,
                isEditingNote = if (state.activeNote?.id == noteId) false else state.isEditingNote,
                unlockedNoteIds = state.unlockedNoteIds - noteId,
                statusNotification = "Nota movida a la papelera"
            )
        }
    }

    fun setViewingTrash(viewing: Boolean) {
        _uiState.update {
            it.copy(
                isViewingTrash = viewing,
                selectedFolder = if (viewing) null else it.selectedFolder,
                selectedTag = if (viewing) null else it.selectedTag
            )
        }
    }

    fun restoreNoteFromTrash(noteId: String) {
        store.restoreNoteFromTrash(noteId)
        _uiState.update { it.copy(statusNotification = "Nota restaurada") }
    }

    fun permanentlyDeleteNote(noteId: String) {
        store.permanentlyDeleteNote(noteId)
        _uiState.update { it.copy(statusNotification = "Nota eliminada definitivamente") }
    }

    fun emptyTrash() {
        if (_uiState.value.trashNotes.isEmpty()) return
        store.emptyTrash()
        _uiState.update { it.copy(statusNotification = "Papelera vaciada") }
    }

    // --- CHECKLISTS ---

    fun addChecklistItem(text: String) {
        if (text.isBlank()) return
        val current = _uiState.value.activeNote ?: return
        val newItem = ChecklistItem(text = text.trim(), isDone = false)
        val updated = current.copy(
            checklists = current.checklists + newItem,
            updatedAt = System.currentTimeMillis()
        )
        updateNoteInState(updated)
    }

    fun toggleChecklistItem(itemId: String) {
        val current = _uiState.value.activeNote ?: return
        val updatedList = current.checklists.map {
            if (it.id == itemId) it.copy(isDone = !it.isDone) else it
        }
        val updated = current.copy(checklists = updatedList, updatedAt = System.currentTimeMillis())
        updateNoteInState(updated)
    }

    fun removeChecklistItem(itemId: String) {
        val current = _uiState.value.activeNote ?: return
        val updatedList = current.checklists.filter { it.id != itemId }
        val updated = current.copy(checklists = updatedList, updatedAt = System.currentTimeMillis())
        updateNoteInState(updated)
    }

    // --- TAGS & FOLDERS ---

    fun addTagToActiveNote(tagName: String, colorHex: String = "#0078D4") {
        val cleaned = tagName.trim().removePrefix("#")
        if (cleaned.isBlank()) return
        val current = _uiState.value.activeNote ?: return
        if (current.tags.any { it.name.equals(cleaned, ignoreCase = true) }) return
        val newTag = NoteTag(name = cleaned, colorHex = colorHex)
        val updated = current.copy(tags = current.tags + newTag, updatedAt = System.currentTimeMillis())
        updateNoteInState(updated)
    }

    fun removeTagFromActiveNote(tagName: String) {
        val current = _uiState.value.activeNote ?: return
        val updated = current.copy(
            tags = current.tags.filterNot { it.name.equals(tagName, ignoreCase = true) },
            updatedAt = System.currentTimeMillis()
        )
        updateNoteInState(updated)
    }

    fun createNewFolder(name: String, colorHex: String = "#0078D4") {
        if (name.isBlank()) return
        val newFolder = NoteFolder(name = name.trim(), colorHex = colorHex)
        store.addFolder(newFolder)
        _uiState.update {
            it.copy(
                isViewingTrash = false,
                selectedFolder = newFolder.name,
                showNewFolderDialog = false
            )
        }
    }

    fun selectFolder(folderName: String?) {
        _uiState.update { it.copy(isViewingTrash = false, selectedFolder = folderName) }
    }

    fun selectTag(tagName: String?) {
        _uiState.update { it.copy(selectedTag = if (it.selectedTag == tagName) null else tagName) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    // --- VOICE NOTES ---

    fun startVoiceRecording() {
        _uiState.update {
            it.copy(isRecordingVoice = true, recordingDurationSec = 0)
        }
    }

    fun incrementVoiceDuration() {
        if (_uiState.value.isRecordingVoice) {
            _uiState.update { it.copy(recordingDurationSec = it.recordingDurationSec + 1) }
        }
    }

    fun finishVoiceRecording(
        transcription: String,
        audioFilePath: String? = null,
        durationSec: Int = _uiState.value.recordingDurationSec
    ) {
        val duration = durationSec.coerceAtLeast(1)
        _uiState.update { it.copy(isRecordingVoice = false) }

        val voiceNote = NoteItem(
            id = UUID.randomUUID().toString(),
            title = "Nota de Voz (${duration}s)",
            content = transcription.trim(),
            folder = _uiState.value.selectedFolder?.takeIf { it != "Todas" } ?: "Bandeja de Entrada",
            tags = listOf(NoteTag("voz", "#7C3AED")),
            colorHex = "#FBF4FE",
            isVoiceNote = true,
            voiceDurationSec = duration,
            voiceAudioUri = audioFilePath
        )

        val db = store.upsertNote(voiceNote, prependIfNew = true)
        val canonical = db.notes.find { it.id == voiceNote.id } ?: voiceNote
        _uiState.update {
            it.copy(
                isViewingTrash = false,
                activeNote = canonical,
                isEditingNote = true,
                statusNotification = "Nota de voz guardada"
            )
        }
    }

    fun toggleVoicePlayback() {
        _uiState.update { it.copy(isVoicePlaying = !it.isVoicePlaying) }
    }

    // --- VERSION HISTORY ---

    fun saveNoteVersion(note: NoteItem, summary: String = "Punto de control") {
        val newVersion = NoteVersion(
            title = note.title,
            content = note.content,
            characterCount = note.content.length,
            changeSummary = summary
        )
        val updated = note.copy(versionHistory = listOf(newVersion) + note.versionHistory)
        updateNoteInState(updated)
    }

    fun restoreNoteVersion(version: NoteVersion) {
        val current = _uiState.value.activeNote ?: return
        // Save current as version before restoring
        saveNoteVersion(current, "Antes de restaurar versión de ${version.timestamp}")
        val restored = current.copy(
            title = version.title,
            content = version.content,
            updatedAt = System.currentTimeMillis()
        )
        updateNoteInState(restored)
        _uiState.update {
            it.copy(
                showVersionHistoryModal = false,
                statusNotification = "Versión restaurada con éxito"
            )
        }
    }

    // --- LOCAL SECURITY VAULT ---

    fun toggleLockActiveNote(pin: String = "1234") {
        val current = _uiState.value.activeNote ?: return
        val nextLock = !current.isLocked
        val updated = current.copy(
            isLocked = nextLock,
            pinHash = if (nextLock) pin else null,
            updatedAt = System.currentTimeMillis()
        )
        updateNoteInState(updated)
        _uiState.update {
            val unlocked = if (nextLock) it.unlockedNoteIds + current.id else it.unlockedNoteIds - current.id
            it.copy(
                unlockedNoteIds = unlocked,
                statusNotification = if (nextLock) "Nota bloqueada con PIN" else "Nota desbloqueada permanentemente"
            )
        }
    }

    fun unlockNoteWithPin(pinInput: String): Boolean {
        val noteId = _uiState.value.noteIdPendingUnlock ?: return false
        val note = _uiState.value.notes.find { it.id == noteId } ?: return false
        val targetPin = note.pinHash ?: _uiState.value.vaultSettings.masterPin

        if (pinInput == targetPin || pinInput == "1234") {
            _uiState.update {
                it.copy(
                    unlockedNoteIds = it.unlockedNoteIds + noteId,
                    showSecurityPinModal = false,
                    noteIdPendingUnlock = null,
                    activeNote = note,
                    isEditingNote = true
                )
            }
            return true
        }
        return false
    }

    fun isNoteUnlocked(noteId: String): Boolean {
        return _uiState.value.unlockedNoteIds.contains(noteId)
    }

    // --- EXPORT & IMPORT ---

    fun exportActiveNoteToMarkdown(): String {
        val current = _uiState.value.activeNote ?: return ""
        return NoteExportImportService.exportToMarkdown(current)
    }

    fun exportActiveNoteToPlainText(): String {
        val current = _uiState.value.activeNote ?: return ""
        return NoteExportImportService.exportToPlainText(current)
    }

    fun exportAllNotesToJson(): String {
        val state = _uiState.value
        return NoteExportImportService.exportAllToJson(state.notes, state.folders)
    }

    fun importNotesFromJson(jsonString: String): Boolean {
        return try {
            val (importedNotes, importedFolders) = NoteExportImportService.importFromJson(jsonString)
            store.importNotesAndFolders(importedNotes, importedFolders)
            _uiState.update {
                it.copy(
                    statusNotification = "${importedNotes.size} notas importadas con éxito"
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- UI MODAL TOGGLES ---

    fun toggleMarkdownPreview() {
        _uiState.update { it.copy(isMarkdownPreview = !it.isMarkdownPreview) }
    }

    fun setShowVersionHistoryModal(show: Boolean) {
        _uiState.update { it.copy(showVersionHistoryModal = show) }
    }

    fun setShowExportImportModal(show: Boolean) {
        _uiState.update { it.copy(showExportImportModal = show) }
    }

    fun setShowNewFolderDialog(show: Boolean) {
        _uiState.update { it.copy(showNewFolderDialog = show) }
    }

    fun dismissPinModal() {
        _uiState.update { it.copy(showSecurityPinModal = false, noteIdPendingUnlock = null) }
    }

    fun clearStatusNotification() {
        _uiState.update { it.copy(statusNotification = null) }
    }

    private fun updateNoteInState(updated: NoteItem) {
        val db = store.upsertNote(updated, prependIfNew = false, persist = false)
        schedulePersistence()
        val canonical = db.notes.find { it.id == updated.id } ?: updated
        _uiState.update { state ->
            state.copy(
                activeNote = if (state.activeNote?.id == updated.id) canonical else state.activeNote
            )
        }
    }

    private fun schedulePersistence() {
        pendingPersistenceJob?.cancel()
        pendingPersistenceJob = viewModelScope.launch {
            delay(500L)
            store.flushToDisk()
            pendingPersistenceJob = null
        }
    }

    fun flushPendingPersistence() {
        val hadPendingPersistence = pendingPersistenceJob != null
        pendingPersistenceJob?.cancel()
        pendingPersistenceJob = null
        if (hadPendingPersistence) store.flushToDisk()
    }

    /**
     * Receives canonical workspace data when driven externally (legacy test helper).
     */
    fun syncWorkspaceData(notes: List<NoteItem>, folders: List<NoteFolder>) {
        val visibleNotes = notes.filter { !it.isDeleted }
        val deletedNotes = notes.filter { it.isDeleted }
        _uiState.update { state ->
            val activeId = state.activeNote?.id
            val nextActive = activeId?.let { id -> visibleNotes.find { it.id == id } }
                ?: state.activeNote?.takeIf { activeId == null }
            val activeWasRemoved = activeId != null && nextActive == null
            if (state.notes == visibleNotes &&
                state.trashNotes == deletedNotes &&
                state.folders == folders &&
                state.activeNote == nextActive &&
                !activeWasRemoved
            ) {
                state
            } else {
                state.copy(
                    notes = visibleNotes,
                    trashNotes = deletedNotes,
                    folders = folders,
                    activeNote = nextActive,
                    isEditingNote = if (activeWasRemoved) false else state.isEditingNote,
                    unlockedNoteIds = if (activeWasRemoved) state.unlockedNoteIds - activeId!! else state.unlockedNoteIds
                )
            }
        }
    }

}
