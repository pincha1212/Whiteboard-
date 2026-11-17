package com.example.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.example.model.StickyNote
import com.example.model.WhiteboardBoard
import com.example.model.WhiteboardStroke
import com.example.model.StrokePoint
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.ink.engine.UnifiedBrushPipeline
import com.example.viewmodel.WhiteboardViewModel
import com.example.notes.model.CanvasNoteData
import com.example.notes.model.ChecklistItem
import com.example.notes.model.NoteItem
import com.example.notes.model.NoteTag
import com.example.notes.model.NoteVersion
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        assertEquals(80f, merged.canvasData?.x ?: Float.NaN, 0.001f)
        assertEquals(120f, merged.canvasData?.y ?: Float.NaN, 0.001f)
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

            // This round-trip validates the on-disk format, so explicitly await persistence.
            store.flushToDiskBlocking()
            assertTrue("AtomicFile should commit the completed snapshot", file.isFile)
            assertTrue("AtomicFile backup should be cleared after a successful commit", !File(file.path + ".bak").exists())

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
            assertEquals(125f, restoredNote.canvasData?.x ?: Float.NaN, 0.001f)
            assertEquals("Informe de campo", projectedCard.title)
            assertEquals("Observaciones y resultados", projectedCard.text)
            assertEquals(125f, projectedCard.x, 0.001f)
            assertEquals(240f, projectedCard.y, 0.001f)
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

    @Test
    fun bidirectionalSingleSourceOfTruthSyncRenameTrashRestoreAndFocus() {
        val store = LocalFirstWorkspaceStore()
        val whiteboardVm = com.example.viewmodel.WhiteboardViewModel(store)
        val notesVm = com.example.notes.viewmodel.NotesViewModel(store)

        val boardId = whiteboardVm.uiState.value.board.id
        whiteboardVm.openBoard(boardId)

        // 1. Add sticky note on whiteboard -> immediately appears in Notes under linked board folder
        whiteboardVm.addStickyNote(1080f, 1920f, "#FFF275")
        val stickyId = whiteboardVm.uiState.value.board.stickyNotes.single().id
        assertEquals(1, notesVm.uiState.value.notes.size)
        assertEquals(boardId, notesVm.uiState.value.folders.find { it.linkedBoardId == boardId }?.linkedBoardId)

        // 2. Edit sticky title and body on whiteboard -> Notes reflects both independently
        whiteboardVm.updateStickyNoteTitle(stickyId, "Idea central")
        whiteboardVm.updateStickyNoteText(stickyId, "Detalle extendido desde lienzo")
        val noteFromStore = notesVm.uiState.value.notes.single { it.id == stickyId }
        assertEquals("Idea central", noteFromStore.title)
        assertEquals("Detalle extendido desde lienzo", noteFromStore.content)

        // 3. Edit title and body in Notes -> immediately updates whiteboard sticky note
        notesVm.openNote(noteFromStore)
        notesVm.updateActiveNoteTitle("Idea editada en Notas")
        notesVm.updateActiveNoteContent("Contenido sincronizado bidireccionalmente")
        notesVm.closeActiveNote()
        val updatedSticky = whiteboardVm.uiState.value.board.stickyNotes.single { it.id == stickyId }
        assertEquals("Idea editada en Notas", updatedSticky.title)
        assertEquals("Contenido sincronizado bidireccionalmente", updatedSticky.text)

        // 4. Rename board -> folder updates & canvasData stays intact
        whiteboardVm.renameBoardById(boardId, "Diseño Producto")
        assertEquals("Diseño Producto", whiteboardVm.uiState.value.board.title)
        val renamedNote = notesVm.uiState.value.notes.single { it.id == stickyId }
        assertEquals("Diseño Producto", renamedNote.folder)
        assertEquals(boardId, renamedNote.canvasData?.boardId)
        assertEquals("Diseño Producto", notesVm.uiState.value.boardTitlesById[boardId])

        // 5. Duplicate board -> assigns completely independent element IDs
        whiteboardVm.duplicateCurrentBoard(boardId)
        assertEquals(2, whiteboardVm.uiState.value.allBoards.size)
        val originalBoard = whiteboardVm.uiState.value.allBoards.first { it.id == boardId }
        val duplicatedBoard = whiteboardVm.uiState.value.allBoards.first { it.id != boardId }
        org.junit.Assert.assertTrue(
            originalBoard.stickyNotes.map { it.id }.intersect(duplicatedBoard.stickyNotes.map { it.id }.toSet()).isEmpty()
        )

        // 6. openBoardAndFocusNote opens target board, selects note, and centers camera on it
        whiteboardVm.openBoardAndFocusNote(boardId, stickyId)
        val focusedState = whiteboardVm.uiState.value
        assertEquals(boardId, focusedState.board.id)
        assertEquals(false, focusedState.isViewingBoardsHome)
        assertEquals(stickyId, focusedState.selectedNoteId)
        assertEquals(1.0f, focusedState.scale, 0.001f)
        val expectedPanX = focusedState.viewportWidthPx * 0.5f - (updatedSticky.x + updatedSticky.width * 0.5f)
        assertEquals(expectedPanX, focusedState.panX, 0.5f)

        // 7. Soft delete from whiteboard -> moves to Papelera in Notes & removes from board; restoring brings both back
        whiteboardVm.deleteStickyNote(stickyId)
        assertEquals(0, whiteboardVm.uiState.value.board.stickyNotes.size)
        assertNull(notesVm.uiState.value.notes.find { it.id == stickyId })
        assertEquals(stickyId, notesVm.uiState.value.trashNotes.single { it.id == stickyId }.id)

        notesVm.restoreNoteFromTrash(stickyId)
        assertEquals(1, whiteboardVm.uiState.value.board.stickyNotes.size)
        assertNotNull(notesVm.uiState.value.notes.find { it.id == stickyId })
        assertNull(notesVm.uiState.value.trashNotes.find { it.id == stickyId })

        // 8. Detach from board -> removes from whiteboard but keeps active in Notes with null canvasData
        whiteboardVm.detachStickyNoteFromBoard(stickyId)
        assertEquals(0, whiteboardVm.uiState.value.board.stickyNotes.size)
        val detachedNote = notesVm.uiState.value.notes.single { it.id == stickyId }
        assertNull(detachedNote.canvasData)
    }

    @Test
    fun debouncedPersistenceWritesTheLatestWorkspaceSnapshot() {
        val tempDir = Files.createTempDirectory("whiteboard-store-test").toFile()
        try {
            val storageFile = File(tempDir, "workspace_ssot_db.json")
            val store = LocalFirstWorkspaceStore(storageFile)
            val initialBoard = WhiteboardBoard(
                id = "persist-test-board",
                title = "Initial title"
            )

            store.ensureBoardRegistered(initialBoard)
            store.syncBoardFromWhiteboard(
                board = initialBoard.copy(title = "Latest title"),
                softDeleteMissingNotes = false,
                persist = true
            )

            val deadline = System.currentTimeMillis() + 4_000L
            while (!storageFile.isFile && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L)
            }
            assertTrue("The IO persistence queue did not create the workspace file", storageFile.isFile)

            val boards = JSONObject(storageFile.readText()).getJSONArray("boards")
            var savedTitle: String? = null
            for (index in 0 until boards.length()) {
                val item = boards.getJSONObject(index)
                if (item.optString("boardId") == "persist-test-board") {
                    savedTitle = item.optString("title")
                    break
                }
            }
            assertEquals("Latest title", savedTitle)
        } finally {
            tempDir.deleteRecursively()
        }
    }


    @Test
    fun backgroundWorkspaceLoadPublishesRestoredStateBeforeReady() {
        val tempDir = Files.createTempDirectory("whiteboard-startup-test").toFile()
        try {
            val storageFile = File(tempDir, "workspace_ssot_db.json")
            val expectedBoard = WhiteboardBoard(
                id = "startup-board",
                title = "Loaded without blocking startup"
            )
            val seedStore = LocalFirstWorkspaceStore(storageFile)
            seedStore.createBoard(expectedBoard)
            seedStore.flushToDiskBlocking()

            val restoredStore = LocalFirstWorkspaceStore(
                storageFile = storageFile,
                loadFromDiskInBackground = true
            )
            val deadline = System.currentTimeMillis() + 4_000L
            while (!restoredStore.isReady.value && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L)
            }

            assertTrue("The background workspace load did not complete", restoredStore.isReady.value)
            assertEquals("Loaded without blocking startup", restoredStore.currentState.boards.single().title)
        } finally {
            tempDir.deleteRecursively()
        }
    }


    @Test
    fun strokeGeometryMetricsCacheBoundsAndHashForEachImmutableGeometry() {
        val originalPoints = listOf(
            StrokePoint(12f, -4f),
            StrokePoint(38f, 20f),
            StrokePoint(-8f, 7f)
        )
        val stroke = WhiteboardStroke(
            id = "geometry-metrics",
            points = originalPoints,
            colorHex = "#111111",
            width = 4f
        )

        val metrics = stroke.geometryMetrics
        assertEquals(originalPoints.hashCode(), metrics.pointsHash)
        assertEquals(-8f, metrics.minX, 0.001f)
        assertEquals(-4f, metrics.minY, 0.001f)
        assertEquals(38f, metrics.maxX, 0.001f)
        assertEquals(20f, metrics.maxY, 0.001f)
        assertTrue(metrics.hasPoints)

        val moved = stroke.copy(
            points = originalPoints.map { StrokePoint(it.x + 100f, it.y + 50f) }
        )
        val movedMetrics = moved.geometryMetrics
        assertEquals(moved.points.hashCode(), movedMetrics.pointsHash)
        assertEquals(92f, movedMetrics.minX, 0.001f)
        assertEquals(46f, movedMetrics.minY, 0.001f)
        assertEquals(138f, movedMetrics.maxX, 0.001f)
        assertEquals(70f, movedMetrics.maxY, 0.001f)
    }


    @Test
    fun filledShapeKeepsItsFillWhenCameraPanAndScaleChange() {
        val shape = ShapeItem(
            id = "filled-path-cache",
            shapeType = ShapeType.RECTANGLE,
            x = 20f,
            y = 30f,
            width = 80f,
            height = 60f,
            strokeColorHex = "#22C55E",
            fillColorHex = "#22C55E",
            strokeWidth = 2f,
            isFilled = true
        )

        val cameraCases = listOf(
            Triple(15f, 10f, 1.25f),
            Triple(-5f, 20f, 0.8f)
        )
        cameraCases.forEach { (panX, panY, scale) ->
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                UnifiedBrushPipeline.renderShape(
                    canvas = canvas,
                    shape = shape,
                    panX = panX,
                    panY = panY,
                    scale = scale,
                    handDrawnFactor = 0f,
                    viewportW = 240f,
                    viewportH = 240f
                )

                val centerX = ((shape.x + shape.width / 2f) * scale + panX).toInt()
                val centerY = ((shape.y + shape.height / 2f) * scale + panY).toInt()
                val actualColor = bitmap.getPixel(centerX, centerY)
                assertEquals(
                    "Filled shape center mismatch at camera pan=($panX,$panY), scale=$scale",
                    Color.parseColor("#22C55E"),
                    actualColor
                )
            } finally {
                bitmap.recycle()
            }
        }
    }


    @Test
    fun erasersUseStrokeBoundsAsBroadPhaseWithoutChangingExactHitResults() {
        val viewModel = WhiteboardViewModel(LocalFirstWorkspaceStore())
        viewModel.commitCompletedStroke(
            points = listOf(
                StrokePoint(100f, 100f),
                StrokePoint(500f, 100f),
                StrokePoint(900f, 100f)
            ),
            isHighlighter = false
        )
        assertEquals(1, viewModel.uiState.value.board.strokes.size)

        // A point outside the stroke's expanded bounding box must not trigger segment scanning
        // or alter the board. A point on the stroke still goes through the exact geometry check.
        assertFalse(viewModel.eraseObjectAt(500f, 180f, tolerance = 8f, saveSnapshot = false))
        assertEquals(1, viewModel.uiState.value.board.strokes.size)
        assertTrue(viewModel.eraseObjectAt(500f, 101f, tolerance = 8f, saveSnapshot = false))
        assertEquals(0, viewModel.uiState.value.board.strokes.size)

        viewModel.commitCompletedStroke(
            points = listOf(
                StrokePoint(100f, 300f),
                StrokePoint(500f, 300f),
                StrokePoint(900f, 300f)
            ),
            isHighlighter = false
        )
        assertFalse(viewModel.erasePixelAt(500f, 380f, radius = 8f, saveSnapshot = false))
        assertEquals(1, viewModel.uiState.value.board.strokes.size)
        assertTrue(viewModel.erasePixelAt(500f, 301f, radius = 8f, saveSnapshot = false))
    }


    @Test
    fun stickyNoteTransformProjectsToWorkspaceOnlyWhenGestureFinishes() {
        val boardId = "board-transform-deferral"
        val sticky = StickyNote(
            id = "sticky-transform-deferral",
            x = 40f,
            y = 80f
        )
        val store = LocalFirstWorkspaceStore()
        store.ensureBoardRegistered(
            WhiteboardBoard(
                id = boardId,
                title = "Pizarra de prueba",
                stickyNotes = listOf(sticky)
            )
        )
        val viewModel = WhiteboardViewModel(store)
        var storeUpdates = 0
        val listener: (WorkspaceDbState) -> Unit = { storeUpdates++ }
        store.addListener(listener)

        viewModel.beginStickyNoteTransformSnapshot()
        viewModel.moveStickyNote(sticky.id, deltaX = 20f, deltaY = 10f)

        // The displayed object moves immediately, without rebuilding the shared Notes projection.
        assertEquals(0, storeUpdates)
        val visibleNote = viewModel.uiState.value.board.stickyNotes.single { it.id == sticky.id }
        assertEquals(60f, visibleNote.x, 0.001f)
        assertEquals(90f, visibleNote.y, 0.001f)

        val storedBeforeFinish = store.currentState.boards
            .single { it.id == boardId }.stickyNotes.single { it.id == sticky.id }
        assertEquals(40f, storedBeforeFinish.x, 0.001f)

        viewModel.commitWorkspaceSync()

        // One shared-store projection occurs at gesture end and contains the final geometry.
        assertEquals(1, storeUpdates)
        val storedAfterFinish = store.currentState.boards
            .single { it.id == boardId }.stickyNotes.single { it.id == sticky.id }
        assertEquals(60f, storedAfterFinish.x, 0.001f)
        assertEquals(90f, storedAfterFinish.y, 0.001f)
        val noteItem = store.currentState.notes.single { it.id == sticky.id }
        assertEquals(60f, noteItem.canvasData?.x ?: Float.NaN, 0.001f)
        assertEquals(90f, noteItem.canvasData?.y ?: Float.NaN, 0.001f)

        store.removeListener(listener)
    }

    @Test
    fun unreadableWorkspaceSnapshotIsPreservedBeforeStartingEmpty() {
        val tempDir = Files.createTempDirectory("whiteboard-corrupt-store-test").toFile()
        try {
            val storageFile = File(tempDir, "workspace_ssot_db.json")
            val malformedJson = """{"notes": ["""
            storageFile.writeText(malformedJson)

            val store = LocalFirstWorkspaceStore(storageFile)

            assertTrue(store.currentState.boards.isEmpty())
            assertTrue(store.currentState.notes.isEmpty())
            val recoveryCopy = File(tempDir, "workspace_ssot_db.json.corrupt")
            assertTrue("The unreadable workspace snapshot must be preserved", recoveryCopy.isFile)
            assertEquals(malformedJson, recoveryCopy.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

}
