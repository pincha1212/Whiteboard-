package com.example.data

import com.example.model.StickyNote
import com.example.model.WhiteboardBoard
import com.example.notes.model.CanvasNoteData
import com.example.notes.model.ChecklistItem
import com.example.notes.model.NoteItem
import com.example.notes.model.NoteTag
import com.example.notes.model.NoteVersion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceNoteProjectionTest {

    @Test
    fun noteProjectionKeepsTitleAndBodySeparate() {
        val note = NoteItem(
            id = "note-1",
            title = "Título independiente",
            content = "Cuerpo de la nota",
            canvasData = CanvasNoteData(
                boardId = "board-1",
                x = 100f,
                y = 200f
            )
        )

        val sticky = note.toStickyNote()

        assertNotNull(sticky)
        assertEquals("Título independiente", sticky?.title)
        assertEquals("Cuerpo de la nota", sticky?.text)
    }

    @Test
    fun canvasEditPreservesRichNoteMetadata() {
        val checklist = listOf(
            ChecklistItem(id = "task-1", text = "Revisar", isDone = true),
            ChecklistItem(id = "task-2", text = "Entregar", isDone = false)
        )
        val versions = listOf(
            NoteVersion(
                versionId = "version-1",
                timestamp = 100L,
                title = "Título anterior",
                content = "Contenido anterior",
                changeSummary = "Versión inicial"
            )
        )
        val original = NoteItem(
            id = "note-2",
            title = "Plan de trabajo",
            content = "Contenido original",
            folder = "Proyecto",
            tags = listOf(NoteTag("universidad", "#A855F7")),
            isPinned = true,
            isLocked = true,
            pinHash = "pin-hash",
            isVoiceNote = true,
            voiceDurationSec = 38,
            voiceAudioUri = "/private/voice-note.m4a",
            checklists = checklist,
            versionHistory = versions,
            canvasData = CanvasNoteData(boardId = "board-2", x = 10f, y = 20f)
        )
        val editedCanvasCard = StickyNote(
            id = original.id,
            title = original.title,
            text = "Contenido actualizado desde el lienzo",
            x = 80f,
            y = 120f,
            colorHex = "#FFF275",
            isPinned = original.isPinned,
            isLocked = original.isLocked,
            tags = listOf("universidad")
        )

        val merged = LocalFirstWorkspaceStore.mergeStickyIntoNoteItem(
            existing = original,
            sticky = editedCanvasCard,
            boardId = "board-2",
            folderName = "Proyecto",
            now = 500L
        )

        assertEquals("Plan de trabajo", merged.title)
        assertEquals("Contenido actualizado desde el lienzo", merged.content)
        assertEquals(checklist, merged.checklists)
        assertEquals(versions, merged.versionHistory)
        assertEquals("/private/voice-note.m4a", merged.voiceAudioUri)
        assertEquals(38, merged.voiceDurationSec)
        assertEquals(true, merged.isVoiceNote)
        assertEquals("pin-hash", merged.pinHash)
        assertEquals(true, merged.isLocked)
        assertEquals(listOf(NoteTag("universidad", "#A855F7")), merged.tags)
        assertEquals(80f, merged.canvasData?.x)
        assertEquals(120f, merged.canvasData?.y)
    }

    @Test
    fun legacyCanvasTextDoesNotEraseExistingTitle() {
        val original = NoteItem(
            id = "note-3",
            title = "Título antiguo",
            content = "Cuerpo anterior"
        )
        val legacyCard = StickyNote(
            id = original.id,
            text = "Título antiguo\nCuerpo actualizado",
            x = 0f,
            y = 0f
        )

        val merged = LocalFirstWorkspaceStore.mergeStickyIntoNoteItem(
            existing = original,
            sticky = legacyCard,
            boardId = "board-3",
            folderName = "Pizarra",
            now = 700L
        )

        assertEquals("Título antiguo", merged.title)
        assertEquals("Cuerpo actualizado", merged.content)
    }

    @Test
    fun boardSnapshotRoundTripPreservesIndependentTitle() {
        val sticky = StickyNote(
            id = "note-4",
            title = "Título de la tarjeta",
            text = "Contenido de la tarjeta",
            x = 12f,
            y = 34f
        )
        val board = WhiteboardBoard(
            id = "board-4",
            title = "Pizarra de prueba",
            stickyNotes = listOf(sticky)
        )

        val json = BoardSnapshotSerializer.serializeBoard(board)
        val restored = BoardSnapshotSerializer.deserializeIntoBoard(json, board)

        assertNotNull(restored)
        assertEquals("Título de la tarjeta", restored?.stickyNotes?.singleOrNull()?.title)
        assertEquals("Contenido de la tarjeta", restored?.stickyNotes?.singleOrNull()?.text)
    }

    @Test
    fun workspaceDiskRoundTripPreservesRichNoteAndCanvasProjection() {
        val file = File.createTempFile("workspace-rich-note", ".json")
        file.delete()
        try {
            val store = LocalFirstWorkspaceStore(storageFile = file)
            val board = WhiteboardBoard(id = "persist-board", title = "Pizarra de proyecto")
            store.createBoard(board)

            val note = NoteItem(
                id = "persist-note",
                title = "Informe de campo",
                content = "Observaciones y resultados",
                folder = "Pizarra de proyecto",
                tags = listOf(NoteTag("geotecnologia", "#0F766E")),
                isPinned = true,
                isLocked = true,
                pinHash = "secure-pin-hash",
                isVoiceNote = true,
                voiceDurationSec = 64,
                voiceAudioUri = "/private/voice-persist.m4a",
                checklists = listOf(ChecklistItem(id = "persist-task", text = "Procesar datos", isDone = true)),
                versionHistory = listOf(
                    NoteVersion(
                        versionId = "persist-version",
                        timestamp = 200L,
                        title = "Borrador",
                        content = "Notas preliminares",
                        changeSummary = "Guardado inicial"
                    )
                ),
                canvasData = CanvasNoteData(
                    boardId = "persist-board",
                    x = 125f,
                    y = 240f,
                    width = 280f,
                    height = 210f,
                    rotation = 5f
                )
            )
            store.upsertNote(note)

            val restored = LocalFirstWorkspaceStore(storageFile = file)
            val restoredNote = restored.currentState.notes.single { it.id == note.id }
            val restoredBoard = restored.currentState.boards.single { it.id == board.id }
            val projectedCard = restoredBoard.stickyNotes.single()

            assertEquals(note.title, restoredNote.title)
            assertEquals(note.content, restoredNote.content)
            assertEquals(note.tags, restoredNote.tags)
            assertEquals(note.checklists, restoredNote.checklists)
            assertEquals(note.versionHistory, restoredNote.versionHistory)
            assertEquals(note.voiceAudioUri, restoredNote.voiceAudioUri)
            assertEquals(note.voiceDurationSec, restoredNote.voiceDurationSec)
            assertEquals(note.isVoiceNote, restoredNote.isVoiceNote)
            assertEquals(note.pinHash, restoredNote.pinHash)
            assertEquals(note.canvasData?.boardId, restoredNote.canvasData?.boardId)
            assertEquals(125f, restoredNote.canvasData?.x)
            assertEquals("Informe de campo", projectedCard.title)
            assertEquals("Observaciones y resultados", projectedCard.text)
            assertEquals(125f, projectedCard.x)
            assertEquals(240f, projectedCard.y)
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacyBoardSnapshotWithoutTitleRemainsRecognizable() {
        val board = WhiteboardBoard(id = "board-5", title = "Pizarra antigua")
        val legacyJson = BoardSnapshotSerializer.serializeBoard(
            board.copy(
                stickyNotes = listOf(
                    StickyNote(id = "legacy-note", text = "Texto antiguo", x = 1f, y = 2f)
                )
            )
        ).replace(",\"title\":\"\"", "")

        val restored = BoardSnapshotSerializer.deserializeIntoBoard(legacyJson, board)

        assertNotNull(restored)
        assertNull(restored?.stickyNotes?.singleOrNull()?.title)
    }
}
