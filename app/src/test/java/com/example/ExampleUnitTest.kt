package com.example

import com.example.notes.data.NoteExportImportService
import com.example.notes.model.ChecklistItem
import com.example.notes.model.NoteFolder
import com.example.notes.model.NoteItem
import com.example.notes.model.NoteTag
import com.example.notes.model.NoteVersion
import com.example.notes.viewmodel.NotesViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleUnitTest {

    @Test
    fun testMarkdownExportService() {
        val note = NoteItem(
            id = "test-1",
            title = "Arquitectura Offline-First",
            content = "Nota técnica sin dependencias de nube ni suscripciones.",
            folder = "Trabajo",
            tags = listOf(NoteTag("arquitectura", "#0078D4"), NoteTag("offline", "#107C41")),
            checklists = listOf(
                ChecklistItem(text = "Definir esquemas", isDone = true),
                ChecklistItem(text = "Validar exportación", isDone = false)
            )
        )

        val md = NoteExportImportService.exportToMarkdown(note)
        assertTrue(md.contains("# Arquitectura Offline-First"))
        assertTrue(md.contains("**Carpeta:** Trabajo"))
        assertTrue(md.contains("#arquitectura"))
        assertTrue(md.contains("[x] Definir esquemas"))
        assertTrue(md.contains("[ ] Validar exportación"))
    }

    @Test
    fun testJsonExportAndImportCycle() {
        val notes = listOf(
            NoteItem(
                id = "n-1",
                title = "Nota de Prueba JSON",
                content = "Contenido en markdown",
                folder = "Personal",
                tags = listOf(NoteTag("test", "#FF0000")),
                versionHistory = listOf(NoteVersion(title = "Versión Antigua", content = "Contenido antiguo"))
            )
        )
        val folders = listOf(NoteFolder(name = "Personal", colorHex = "#107C41"))

        val exportedJson = NoteExportImportService.exportAllToJson(notes, folders)
        assertTrue(exportedJson.contains("Chronos Notes Offline-First"))
        assertTrue(exportedJson.contains("Nota de Prueba JSON"))

        val (importedNotes, importedFolders) = NoteExportImportService.importFromJson(exportedJson)
        assertEquals(1, importedNotes.size)
        assertEquals(1, importedFolders.size)
        assertEquals("Nota de Prueba JSON", importedNotes.first().title)
        assertEquals("Personal", importedFolders.first().name)
        assertEquals(1, importedNotes.first().versionHistory.size)
    }

    @Test
    fun testNotesViewModelInitialStateAndFiltering() {
        val vm = NotesViewModel()
        val state = vm.uiState.value
        assertTrue(state.notes.isEmpty())
        assertTrue(state.folders.isEmpty())

        // Create notes dynamically and test search filter
        vm.createNewNote("Trabajo")
        vm.updateActiveNoteTitle("Plan de Proyecto")
        vm.closeActiveNote()

        vm.createNewNote("Personal")
        vm.updateActiveNoteTitle("Lista de Compras")
        vm.closeActiveNote()

        assertEquals(2, vm.uiState.value.notes.size)

        vm.updateSearchQuery("Proyecto")
        assertEquals(1, vm.uiState.value.filteredNotes.size)
        assertEquals("Plan de Proyecto", vm.uiState.value.filteredNotes.first().title)

        // Clear search
        vm.updateSearchQuery("")
        assertEquals(2, vm.uiState.value.filteredNotes.size)
    }

    @Test
    fun testNotesViewModelChecklistAddition() {
        val vm = NotesViewModel()
        vm.createNewNote("Trabajo")
        val active = vm.uiState.value.activeNote
        assertNotNull(active)

        vm.addChecklistItem("Tarea A")
        vm.addChecklistItem("Tarea B")
        assertEquals(2, vm.uiState.value.activeNote?.checklists?.size)

        val itemAId = vm.uiState.value.activeNote!!.checklists.first().id
        vm.toggleChecklistItem(itemAId)
        assertTrue(vm.uiState.value.activeNote!!.checklists.first().isDone)
    }

    @Test
    fun testSecurityPinUnlockLogic() {
        val vm = NotesViewModel()
        vm.createNewNote("Personal")
        vm.updateActiveNoteTitle("Nota Privada")
        vm.toggleLockActiveNote()
        vm.closeActiveNote()

        val lockedNote = vm.uiState.value.notes.first { it.isLocked }
        assertFalse(vm.isNoteUnlocked(lockedNote.id))

        // Open note triggers pin modal
        vm.openNote(lockedNote)
        assertTrue(vm.uiState.value.showSecurityPinModal)

        // Wrong pin fails
        val wrongResult = vm.unlockNoteWithPin("9999")
        assertFalse(wrongResult)

        // Correct master/custom pin succeeds
        val rightResult = vm.unlockNoteWithPin("1234")
        assertTrue(rightResult)
        assertTrue(vm.isNoteUnlocked(lockedNote.id))
    }

    @Test
    fun testVoiceNotePersistenceAndDelete() {
        val vm = NotesViewModel()
        vm.finishVoiceRecording(
            transcription = "Resumen rápido",
            audioFilePath = "/tmp/test_voice.m4a",
            durationSec = 12
        )
        assertEquals(1, vm.uiState.value.notes.size)
        val created = vm.uiState.value.notes.first()
        assertTrue(created.isVoiceNote)
        assertEquals(12, created.voiceDurationSec)
        assertEquals("/tmp/test_voice.m4a", created.voiceAudioUri)
        assertEquals("Resumen rápido", created.content)

        vm.deleteNote(created.id)
        assertTrue(vm.uiState.value.notes.isEmpty())
        assertEquals(false, vm.uiState.value.isEditingNote)
    }

    @Test
    fun testWhiteboardBoardsHomeAndSyncFlow() {
        val vm = com.example.viewmodel.WhiteboardViewModel()
        assertTrue(vm.uiState.value.isViewingBoardsHome)
        assertEquals(1, vm.uiState.value.allBoards.size)

        vm.createNewBoard("Arquitectura App")
        assertFalse(vm.uiState.value.isViewingBoardsHome)
        assertEquals(2, vm.uiState.value.allBoards.size)
        val createdId = vm.uiState.value.board.id

        vm.commitCompletedStroke(
            listOf(
                com.example.model.StrokePoint(10f, 10f),
                com.example.model.StrokePoint(120f, 90f)
            ),
            isHighlighter = false
        )
        vm.closeBoardToHome()
        assertTrue(vm.uiState.value.isViewingBoardsHome)
        val synced = vm.uiState.value.allBoards.first { it.id == createdId }
        assertEquals(1, synced.strokes.size)

        vm.renameBoardById(createdId, "Arquitectura v2")
        assertEquals("Arquitectura v2", vm.uiState.value.allBoards.first { it.id == createdId }.title)

        vm.deleteBoard(createdId)
        assertEquals(1, vm.uiState.value.allBoards.size)
    }

    @Test
    fun testPresetShapeDeferredPlacementEntersEditMode() {
        val vm = com.example.viewmodel.WhiteboardViewModel()
        vm.openBoard(vm.uiState.value.allBoards.first().id)

        vm.requestAddCustomShape(
            type = com.example.model.ShapeType.STAR,
            isFilled = true,
            strokeColor = "#2563EB",
            fillColor = "#DBEAFE",
            style = com.example.model.StrokeStyle.SOLID
        )
        // Must NOT place automatically
        assertTrue(vm.uiState.value.board.shapes.isEmpty())
        assertEquals(com.example.model.ShapeType.STAR, vm.uiState.value.pendingShapeType)

        // User drags on canvas to set location and size
        vm.placePendingShapeInBounds(
            com.example.model.StrokePoint(100f, 150f),
            com.example.model.StrokePoint(340f, 390f)
        )
        assertEquals(null, vm.uiState.value.pendingShapeType)
        assertEquals(1, vm.uiState.value.board.shapes.size)
        val placed = vm.uiState.value.board.shapes.first()
        assertEquals(100f, placed.x, 0.1f)
        assertEquals(150f, placed.y, 0.1f)
        assertEquals(240f, placed.width, 0.1f)
        assertEquals(240f, placed.height, 0.1f)
        // Auto-selects shape and enters edit mode
        assertEquals(placed.id, vm.uiState.value.selectedShapeId)
        assertEquals(com.example.model.ToolType.SELECT_LASSO, vm.uiState.value.currentTool)
    }

    @Test
    fun testSnapshotCreateRestoreAndBitmapExport() {
        val vm = com.example.viewmodel.WhiteboardViewModel()
        vm.openBoard(vm.uiState.value.allBoards.first().id)

        vm.requestAddCustomShape(
            type = com.example.model.ShapeType.HEXAGON,
            isFilled = false,
            strokeColor = "#0F172A",
            fillColor = "#E2E8F0",
            style = com.example.model.StrokeStyle.DASHED
        )
        vm.placePendingShapeInBounds(
            com.example.model.StrokePoint(50f, 60f),
            com.example.model.StrokePoint(250f, 210f)
        )
        vm.createSnapshot("v1.0", "Con hexágono")
        assertEquals(1, vm.uiState.value.board.snapshots.size)
        val snap = vm.uiState.value.board.snapshots.first()

        // Clear board, then restore snapshot
        vm.clearBoard()
        assertTrue(vm.uiState.value.board.shapes.isEmpty())

        val restored = vm.restoreSnapshot(snap)
        assertTrue(restored)
        assertEquals(1, vm.uiState.value.board.shapes.size)
        assertEquals(com.example.model.ShapeType.HEXAGON, vm.uiState.value.board.shapes.first().shapeType)

        // Render bitmap via BoardExportService
        val bmp = com.example.data.BoardExportService.renderBoardToBitmap(
            board = vm.uiState.value.board,
            includeBackground = true,
            includeGrid = true,
            maxDimensionPx = 800
        )
        assertTrue(bmp.width > 0 && bmp.height > 0)

        vm.deleteSnapshot(snap.versionId)
        assertTrue(vm.uiState.value.board.snapshots.isEmpty())
    }

    @Test
    fun testNotesKeepLayoutChecklistAndColor() {
        val vm = NotesViewModel()
        assertTrue(vm.uiState.value.isGridLayout)
        vm.toggleNotesLayout()
        assertFalse(vm.uiState.value.isGridLayout)
        vm.toggleNotesLayout()
        assertTrue(vm.uiState.value.isGridLayout)

        vm.createNewChecklistNote("Personal")
        assertTrue(vm.uiState.value.isEditingNote)
        assertTrue(vm.uiState.value.isChecklistFocusRequested)
        vm.updateActiveNoteColor("#D3E3FD")
        assertEquals("#D3E3FD", vm.uiState.value.activeNote?.colorHex)
        vm.addChecklistItem("Comprar café")
        assertEquals(1, vm.uiState.value.activeNote?.checklists?.size)
        vm.closeActiveNote()
        assertFalse(vm.uiState.value.isChecklistFocusRequested)
    }

    @Test
    fun testRichNoteFormatterVisualAndNoGenericWords() {
        val colors = com.example.notes.ui.RichEditorThemeColors(
            onSurface = androidx.compose.ui.graphics.Color.Black,
            onSurfaceVariant = androidx.compose.ui.graphics.Color.Gray,
            primary = androidx.compose.ui.graphics.Color.Blue,
            codeBackground = androidx.compose.ui.graphics.Color(0xFFF1F5F9),
            codeText = androidx.compose.ui.graphics.Color(0xFFDC2626),
            quoteBackground = androidx.compose.ui.graphics.Color(0xFFE2E8F0)
        )

        // 1. H1 toggle on empty text inserts NO generic words, stays active
        val empty = androidx.compose.ui.text.input.TextFieldValue("")
        val h1Empty = com.example.notes.ui.RichNoteFormatter.toggleBlockFormat(
            empty,
            com.example.notes.ui.NoteBlockFormat.H1
        )
        assertEquals("# ", h1Empty.text)
        assertEquals(
            com.example.notes.ui.NoteBlockFormat.H1,
            com.example.notes.ui.RichNoteFormatter.detectBlockFormatAtCursor(h1Empty.text, h1Empty.selection.start)
        )
        val h1Transformed = com.example.notes.ui.RichNoteFormatter.transformRichText(
            h1Empty.text, colors, h1Empty.selection.start, emptySet()
        )
        // Visual output has zero visible letters (only zero-width metric char)
        assertTrue(h1Transformed.text.text.trim('\u200B').isEmpty())

        // 2. Code toggle on empty text inserts NO generic words, shows empty styled code box
        val (stillEmpty, pendingCode) = com.example.notes.ui.RichNoteFormatter.toggleInlineFormat(
            empty,
            com.example.notes.ui.NoteInlineFormat.CODE,
            emptySet()
        )
        assertEquals("", stillEmpty.text)
        assertTrue(com.example.notes.ui.NoteInlineFormat.CODE in pendingCode)
        val codeEmptyTransformed = com.example.notes.ui.RichNoteFormatter.transformRichText(
            stillEmpty.text, colors, stillEmpty.selection.start, pendingCode
        )
        assertTrue(codeEmptyTransformed.text.text.isBlank()) // no generic words!
        assertTrue(codeEmptyTransformed.text.spanStyles.any {
            it.item.background == colors.codeBackground && it.item.color == colors.codeText
        })

        // 3. Typing first character wraps automatically in backticks without generic text
        val typedIncoming = androidx.compose.ui.text.input.TextFieldValue(
            "x",
            androidx.compose.ui.text.TextRange(1)
        )
        val (wrappedCode, nextPending) = com.example.notes.ui.RichNoteFormatter.processTextChange(
            stillEmpty,
            typedIncoming,
            pendingCode
        )
        assertEquals("`x`", wrappedCode.text)
        assertEquals(2, wrappedCode.selection.start)
        assertTrue(nextPending.isEmpty())

        // 4. Multi-format visual rendering hides raw markers & maintains valid OffsetMapping
        val sample = "# Título\n## Sub\n**Negrita** *Cursiva* __Subrayado__ ~~Tachado~~ `fn`\n> Cita\n- Uno"
        val transformed = com.example.notes.ui.RichNoteFormatter.transformRichText(
            sample, colors, sample.length, emptySet()
        )
        val visual = transformed.text.text
        assertFalse(visual.contains("**"))
        assertFalse(visual.contains("__"))
        assertFalse(visual.contains("~~"))
        assertFalse(visual.contains("`"))
        assertTrue(visual.contains("▎ Cita"))
        assertTrue(visual.contains("• Uno"))

        for (i in 0..sample.length) {
            val mapped = transformed.offsetMapping.originalToTransformed(i)
            assertTrue(mapped in 0..visual.length)
        }
        for (j in 0..visual.length) {
            val mapped = transformed.offsetMapping.transformedToOriginal(j)
            assertTrue(mapped in 0..sample.length)
        }
    }

    @Test
    fun testWhiteboardBrushToolsErasersAndManipulation() {
        val vm = com.example.viewmodel.WhiteboardViewModel()
        vm.openBoard(vm.uiState.value.allBoards.first().id)

        // 1. Escritura y Boceto: distinct brush stabilization profiles
        val jitteryPoints = (0..12).map { i ->
            com.example.model.StrokePoint(i * 20f, 100f + if (i % 2 == 0) 14f else -14f)
        }
        val lowStab = com.example.ink.StrokeStabilizer.stabilizePoints(
            jitteryPoints,
            com.example.model.StrokeBrushType.CALLIGRAPHY.stabilization
        )
        val highStab = com.example.ink.StrokeStabilizer.stabilizePoints(
            jitteryPoints,
            com.example.model.StrokeBrushType.FINE_MARKER.stabilization
        )
        val lowVariance = lowStab.map { kotlin.math.abs(it.y - 100f) }.average()
        val highVariance = highStab.map { kotlin.math.abs(it.y - 100f) }.average()
        assertTrue(highVariance < lowVariance)

        // Commit strokes with Calligraphy & Soft Brush and verify brushType + opacity
        vm.setTool(com.example.model.ToolType.CALLIGRAPHY)
        vm.commitCompletedStroke(
            listOf(
                com.example.model.StrokePoint(0f, 100f),
                com.example.model.StrokePoint(50f, 100f),
                com.example.model.StrokePoint(100f, 100f),
                com.example.model.StrokePoint(150f, 100f),
                com.example.model.StrokePoint(200f, 100f)
            ),
            isHighlighter = false
        )
        val calligStroke = vm.uiState.value.board.strokes.last()
        assertEquals(com.example.model.StrokeBrushType.CALLIGRAPHY, calligStroke.brushType)
        assertTrue(calligStroke.opacity < 1.0f)

        vm.setTool(com.example.model.ToolType.BRUSH)
        vm.commitCompletedStroke(
            listOf(
                com.example.model.StrokePoint(10f, 220f),
                com.example.model.StrokePoint(110f, 220f)
            ),
            isHighlighter = false
        )
        val brushStroke = vm.uiState.value.board.strokes.last()
        assertEquals(com.example.model.StrokeBrushType.SOFT_BRUSH, brushStroke.brushType)
        assertTrue(brushStroke.opacity < 0.6f)

        // 2. Pixel Eraser splits the pencil stroke cleanly at x = 100 into 2 fragments
        val pixelErased = vm.erasePixelAt(100f, 100f, radius = 22f, saveSnapshot = true)
        assertTrue(pixelErased)
        val strokesAfterPixel = vm.uiState.value.board.strokes
        // Pencil stroke split into 2 + 1 brush stroke = 3 strokes
        assertEquals(3, strokesAfterPixel.size)

        // 3. Object Eraser removes entire brush stroke on single touch
        val objErased = vm.eraseObjectAt(60f, 220f, tolerance = 20f, saveSnapshot = true)
        assertTrue(objErased)
        assertEquals(2, vm.uiState.value.board.strokes.size)

        // 4. Lasso selection + rotation of selected stroke
        val lasso = listOf(
            com.example.model.StrokePoint(-10f, 70f),
            com.example.model.StrokePoint(90f, 70f),
            com.example.model.StrokePoint(90f, 130f),
            com.example.model.StrokePoint(-10f, 130f)
        )
        assertTrue(vm.selectByLasso(lasso))
        assertNotNull(vm.uiState.value.selectedStrokeId)

        val beforeRotateY = vm.uiState.value.board.strokes.first { it.id == vm.uiState.value.selectedStrokeId }.points.first().y
        vm.rotateSelectedStroke(90f)
        val afterRotateY = vm.uiState.value.board.strokes.first { it.id == vm.uiState.value.selectedStrokeId }.points.first().y
        assertTrue(kotlin.math.abs(afterRotateY - beforeRotateY) > 5f)

        // 5. Snap-to-shape on hold converts rough circle into ShapeType.CIRCLE
        val circlePts = (0..36).map { deg ->
            val rad = deg * 10.0 * Math.PI / 180.0
            com.example.model.StrokePoint(
                (400f + 80f * kotlin.math.cos(rad)).toFloat(),
                (400f + 80f * kotlin.math.sin(rad)).toFloat()
            )
        }
        vm.setTool(com.example.model.ToolType.PEN)
        assertTrue(vm.commitSnapToShapeFromPoints(circlePts))
        assertEquals(1, vm.uiState.value.board.shapes.size)
        assertEquals(com.example.model.ShapeType.CIRCLE, vm.uiState.value.board.shapes.first().shapeType)

        // 6. Ruler projection snaps nearby points to ruler edge
        val ruler = com.example.model.RulerState(
            isVisible = true,
            centerX = 300f,
            centerY = 300f,
            angleDegrees = 0f,
            length = 400f,
            width = 80f
        )
        val nearTopEdge = com.example.ink.StrokeStabilizer.projectPointToRulerIfNear(
            com.example.model.StrokePoint(300f, 255f),
            ruler
        )
        assertEquals(258f, nearTopEdge.y, 0.5f)
    }

    @Test
    fun testLassoMultiObjectSelectionAndGroupActions() {
        val vm = com.example.viewmodel.WhiteboardViewModel()
        vm.openBoard(vm.uiState.value.allBoards.first().id)

        // 1. Create two strokes and one shape inside the region (100..300, 100..300)
        vm.commitCompletedStroke(
            listOf(
                com.example.model.StrokePoint(120f, 130f),
                com.example.model.StrokePoint(180f, 190f)
            ),
            isHighlighter = false
        )
        val stroke1Id = vm.uiState.value.board.strokes.last().id

        vm.commitCompletedStroke(
            listOf(
                com.example.model.StrokePoint(200f, 210f),
                com.example.model.StrokePoint(250f, 260f)
            ),
            isHighlighter = false
        )
        val stroke2Id = vm.uiState.value.board.strokes.last().id

        vm.requestAddCustomShape(
            type = com.example.model.ShapeType.RECTANGLE,
            isFilled = false,
            strokeColor = "#2563EB",
            fillColor = "#FFFFFF",
            style = com.example.model.StrokeStyle.SOLID
        )
        vm.placePendingShapeInBounds(
            com.example.model.StrokePoint(140f, 140f),
            com.example.model.StrokePoint(220f, 220f)
        )
        val shapeId = vm.uiState.value.board.shapes.last().id

        // 2. Create one stroke far outside the lasso region
        vm.commitCompletedStroke(
            listOf(
                com.example.model.StrokePoint(800f, 800f),
                com.example.model.StrokePoint(850f, 850f)
            ),
            isHighlighter = false
        )
        val outsideStrokeId = vm.uiState.value.board.strokes.last().id

        // Clear any placement selection
        vm.clearSelection()
        assertTrue(vm.uiState.value.selectedStrokeIds.isEmpty())
        assertTrue(vm.uiState.value.selectedShapeIds.isEmpty())

        // 3. Lasso loop enclosing (50, 50) to (350, 350)
        val lasso = listOf(
            com.example.model.StrokePoint(50f, 50f),
            com.example.model.StrokePoint(350f, 50f),
            com.example.model.StrokePoint(350f, 350f),
            com.example.model.StrokePoint(50f, 350f)
        )
        val selected = vm.selectByLasso(lasso)
        assertTrue(selected)

        // Verifies all objects inside the lasso polygon are selected simultaneously
        val state = vm.uiState.value
        assertEquals(2, state.selectedStrokeIds.size)
        assertTrue(state.selectedStrokeIds.contains(stroke1Id))
        assertTrue(state.selectedStrokeIds.contains(stroke2Id))
        assertFalse(state.selectedStrokeIds.contains(outsideStrokeId))

        assertEquals(1, state.selectedShapeIds.size)
        assertTrue(state.selectedShapeIds.contains(shapeId))

        // 4. Move selected objects by (+40, +50)
        val s1InitialX = vm.uiState.value.board.strokes.first { it.id == stroke1Id }.points.first().x
        val shapeInitialX = vm.uiState.value.board.shapes.first { it.id == shapeId }.x
        val outsideInitialX = vm.uiState.value.board.strokes.first { it.id == outsideStrokeId }.points.first().x

        vm.moveSelectedObjects(40f, 50f)

        val s1MovedX = vm.uiState.value.board.strokes.first { it.id == stroke1Id }.points.first().x
        val shapeMovedX = vm.uiState.value.board.shapes.first { it.id == shapeId }.x
        val outsideMovedX = vm.uiState.value.board.strokes.first { it.id == outsideStrokeId }.points.first().x

        assertEquals(s1InitialX + 40f, s1MovedX, 0.1f)
        assertEquals(shapeInitialX + 40f, shapeMovedX, 0.1f)
        assertEquals(outsideInitialX, outsideMovedX, 0.1f) // Unselected object stayed untouched

        // 5. Delete multi-selection deletes both strokes and shape
        vm.deleteCurrentSelection()
        val finalStrokes = vm.uiState.value.board.strokes
        val finalShapes = vm.uiState.value.board.shapes

        assertEquals(1, finalStrokes.size)
        assertEquals(outsideStrokeId, finalStrokes.first().id)
        assertTrue(finalShapes.isEmpty())
        assertTrue(vm.uiState.value.selectedStrokeIds.isEmpty())
        assertTrue(vm.uiState.value.selectedShapeIds.isEmpty())
    }
}


