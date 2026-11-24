package com.example

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.data.BoardExportService
import com.example.data.BoardSnapshotSerializer
import com.example.data.LocalFirstWorkspaceStore
import com.example.ink.engine.MutableRasterTileStore
import com.example.model.BrushStudioSettings
import com.example.model.DrawingLayer
import com.example.model.LayerBlendMode
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.WhiteboardBoard
import com.example.model.WhiteboardStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersistenceAndExportV35Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `serializer round-trips brush studio settings, pressure points, and layer attributes`() {
        val studio = BrushStudioSettings(
            flow = 0.63f,
            spacing = 0.21f,
            hardness = 0.77f,
            grainStrength = 0.49f,
            taperStart = 0.15f,
            taperEnd = 0.55f,
            velocitySensitivity = 0.88f,
            chiselAngleDeg = 44f
        )
        val board = WhiteboardBoard(
            id = "board_roundtrip",
            title = "Estudio V35",
            activeLayerId = "layer_2",
            layers = listOf(
                DrawingLayer(
                    id = "layer_1",
                    name = "Boceto",
                    blendMode = LayerBlendMode.NORMAL,
                    isReference = true,
                    rasterRevision = 3L
                ),
                DrawingLayer(
                    id = "layer_2",
                    name = "Color",
                    blendMode = LayerBlendMode.MULTIPLY,
                    isAlphaLocked = true,
                    isReference = false,
                    rasterRevision = 7L
                )
            ),
            strokes = listOf(
                WhiteboardStroke(
                    id = "s1",
                    points = listOf(
                        StrokePoint(10f, 20f, timestampMs = 500L, pressure = 0.45f),
                        StrokePoint(80f, 95f, timestampMs = 540L, pressure = 0.92f)
                    ),
                    colorHex = "#FF5722",
                    width = 18f,
                    brushType = StrokeBrushType.WATERCOLOR,
                    layerId = "layer_2",
                    studioSettings = studio
                )
            )
        )

        val json = BoardSnapshotSerializer.serializeBoard(board)
        val restored = BoardSnapshotSerializer.deserializeIntoBoard(
            json = json,
            currentBoard = WhiteboardBoard(id = "board_roundtrip", title = "Estudio V35")
        )
        assertNotNull(restored)
        val nonNull = restored!!

        assertEquals(2, nonNull.layers.size)
        assertTrue(nonNull.layers[0].isReference)
        assertEquals(LayerBlendMode.MULTIPLY, nonNull.layers[1].blendMode)
        assertTrue(nonNull.layers[1].isAlphaLocked)
        assertEquals(7L, nonNull.layers[1].rasterRevision)

        val restoredStroke = nonNull.strokes.single()
        assertEquals(StrokeBrushType.WATERCOLOR, restoredStroke.brushType)
        assertEquals(studio.flow, restoredStroke.studioSettings.flow, 0.001f)
        assertEquals(studio.grainStrength, restoredStroke.studioSettings.grainStrength, 0.001f)
        assertEquals(44f, restoredStroke.studioSettings.chiselAngleDeg, 0.001f)
        assertEquals(0.45f, restoredStroke.points[0].pressure, 0.001f)
        assertEquals(540L, restoredStroke.points[1].timestampMs)
    }

    @Test
    fun `workspace store persists raster png sidecars and reloads bit-identical pixels`() {
        val dir = tempFolder.newFolder("workspace_v35")
        val dbFile = File(dir, "workspace_ssot_db.json")
        val store1 = LocalFirstWorkspaceStore(storageFile = dbFile, loadFromDiskInBackground = false)

        val boardId = "board_raster_io"
        val layerId = "layer_1"
        val board = WhiteboardBoard(
            id = boardId,
            title = "Lienzo Raster",
            layers = listOf(DrawingLayer(id = layerId, name = "Capa 1", rasterRevision = 1L))
        )
        store1.syncBoardFromWhiteboard(board, persist = false)

        val tile = store1.rasterTileStore.getOrCreateTile(boardId, layerId, 0, 0)
        Canvas(tile).drawRect(
            64f, 64f, 160f, 160f,
            Paint().apply {
                color = Color.argb(255, 42, 168, 224)
                style = Paint.Style.FILL
            }
        )
        store1.rasterTileStore.bumpLayerRevision(boardId, layerId)
        store1.flushToDiskBlocking()

        val sidecarDir = File(dir, "raster_tiles")
        assertTrue("Expected raster_tiles sidecar dir to exist", sidecarDir.exists())

        // Reload in a brand new store pointing to the same storageFile
        val store2 = LocalFirstWorkspaceStore(storageFile = dbFile, loadFromDiskInBackground = false)
        val reloadedTile = store2.rasterTileStore.getTile(boardId, layerId, 0, 0)
        assertNotNull(reloadedTile)
        assertEquals(Color.argb(255, 42, 168, 224), reloadedTile!!.getPixel(100, 100))
        assertEquals(0, Color.alpha(reloadedTile.getPixel(10, 10)))

        // Deleting board must clean up its raster tile directory
        store2.deleteBoard(boardId)
        store2.flushToDiskBlocking()
        assertFalse(File(sidecarDir, boardId).exists())
    }

    @Test
    fun `export service supports transparent png, single layer isolation, and resolution multiplier`() {
        val rasterStore = MutableRasterTileStore()
        val board = WhiteboardBoard(
            id = "export_board",
            title = "Export",
            activeLayerId = "layer_1",
            layers = listOf(
                DrawingLayer(id = "layer_1", name = "Capa 1"),
                DrawingLayer(id = "layer_2", name = "Capa 2")
            ),
            shapes = listOf(
                ShapeItem(
                    id = "rect_l1",
                    shapeType = ShapeType.RECTANGLE,
                    x = 100f,
                    y = 100f,
                    width = 200f,
                    height = 200f,
                    strokeColorHex = "#FF0000",
                    fillColorHex = "#FF0000",
                    isFilled = true,
                    layerId = "layer_1"
                ),
                ShapeItem(
                    id = "rect_l2",
                    shapeType = ShapeType.RECTANGLE,
                    x = 400f,
                    y = 100f,
                    width = 200f,
                    height = 200f,
                    strokeColorHex = "#0000FF",
                    fillColorHex = "#0000FF",
                    isFilled = true,
                    layerId = "layer_2"
                )
            )
        )

        val transparentBmp = BoardExportService.renderBoardToBitmap(
            board = board,
            includeBackground = false,
            includeGrid = false,
            maxDimensionPx = 800,
            mutableRasterStore = rasterStore,
            onlyLayerId = "layer_1",
            resolutionMultiplier = 1
        )
        assertEquals(0, Color.alpha(transparentBmp.getPixel(1, 1)))

        val hiresBmp = BoardExportService.renderBoardToBitmap(
            board = board,
            includeBackground = true,
            includeGrid = false,
            maxDimensionPx = 800,
            mutableRasterStore = rasterStore,
            onlyLayerId = "layer_1",
            resolutionMultiplier = 2
        )
        assertTrue(hiresBmp.width > transparentBmp.width)
        assertEquals(1600, maxOf(hiresBmp.width, hiresBmp.height))
        transparentBmp.recycle()
        hiresBmp.recycle()
    }
}
