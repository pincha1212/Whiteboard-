package com.example.data

import com.example.model.StickyNote
import com.example.model.WhiteboardBoard
import com.example.notes.model.CanvasNoteData
import com.example.notes.model.ChecklistItem
import com.example.notes.model.NoteItem
import com.example.notes.model.NoteTag
import com.example.notes.model.NoteVersion
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
