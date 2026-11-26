package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.data.BoardExportService
import com.example.data.BoardSnapshotSerializer
import com.example.ink.BoxResizeHandle
import com.example.ink.BoxTransformGeometry
import com.example.ink.WorldBoxBounds
import com.example.ink.engine.MemoryBudgetProfile
import com.example.ink.engine.MutableRasterTileStore
import com.example.ink.engine.TileBitmapPool
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokePoint
import com.example.model.TextItem
import com.example.model.WhiteboardBoard
import com.example.model.WhiteboardStroke
import com.example.viewmodel.CanvasHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoryAndCleanupV40Test {

    @Test
    fun `memory budget profile switches cleanly between strict 1GB and standard tiers`() {
        val strictLowFlag = MemoryBudgetProfile.fromSystem(
            isLowRamDevice = true,
            memoryClassMb = 256,
            maxHeapBytes = 512L * 1024L * 1024L
        )
        assertTrue(strictLowFlag.isLowRamProfile)
        assertEquals(6 * 1024 * 1024, strictLowFlag.rasterCacheBytes)
        assertEquals(15, strictLowFlag.maxUndoSteps)
        assertEquals(2_500_000L, strictLowFlag.undoHistoryBudgetBytes)
        assertEquals(2, strictLowFlag.maxExportMultiplier)
        assertEquals(1600, strictLowFlag.maxExportDimensionPx)

        val strictLowHeap = MemoryBudgetProfile.fromSystem(
            isLowRamDevice = false,
            memoryClassMb = 128,
            maxHeapBytes = 128L * 1024L * 1024L
        )
        assertTrue(strictLowHeap.isLowRamProfile)

        val standard = MemoryBudgetProfile.fromSystem(
            isLowRamDevice = false,
            memoryClassMb = 256,
            maxHeapBytes = 256L * 1024L * 1024L
        )
        assertFalse(standard.isLowRamProfile)
        assertEquals(12 * 1024 * 1024, standard.rasterCacheBytes)
        assertEquals(24, standard.maxUndoSteps)
        assertEquals(2, standard.maxExportMultiplier)
        assertEquals(2048, standard.maxExportDimensionPx)
    }

    @Test
    fun `tile bitmap pool reuses cleared bitmaps and respects max count cap`() {
        val pool = TileBitmapPool(maxPoolCount = 2, tileWidth = 64, tileHeight = 64)
        val first = pool.acquireClean()
        first.setPixel(0, 0, Color.RED)
        pool.release(first)
        assertEquals(1, pool.pooledCount())

        val reused = pool.acquireClean()
        assertSame(first, reused)
        assertEquals(Color.TRANSPARENT, reused.getPixel(0, 0))

        val b1 = pool.acquireClean()
        val b2 = pool.acquireClean()
        val b3 = pool.acquireClean()
        pool.release(reused)
        pool.release(b1)
        pool.release(b2)
        pool.release(b3)

        assertEquals(2, pool.pooledCount())
        assertTrue(b3.isRecycled)

        pool.clear()
        assertEquals(0, pool.pooledCount())
    }

    @Test
    fun `chunked 16-row raster tile compression roundtrips lossless pixels`() {
        val src = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(src)
        val paint = Paint().apply { color = Color.argb(210, 37, 99, 235) }
        canvas.drawRect(12f, 18f, 340f, 490f, paint)
        src.setPixel(511, 511, Color.argb(255, 220, 38, 38))

        val compressed = MutableRasterTileStore.compressBitmap(src)
        assertTrue(compressed.isNotEmpty())

        val dst = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        MutableRasterTileStore.decompressIntoBitmap(compressed, dst)

        assertEquals(src.getPixel(120, 220), dst.getPixel(120, 220))
        assertEquals(src.getPixel(511, 511), dst.getPixel(511, 511))
        assertEquals(Color.TRANSPARENT, dst.getPixel(0, 0))

        src.recycle()
        dst.recycle()
    }

    @Test
    fun `canvas history store enforces 15 step and 2_5MB budget and emergency trim`() {
        val store = CanvasHistoryStore(MemoryBudgetProfile.strict1Gb())
        val densePoints = List(2200) { idx -> StrokePoint(idx.toFloat(), idx.toFloat() * 0.5f) }
        val heavyStroke = WhiteboardStroke(
            id = "heavy_stroke",
            points = densePoints,
            colorHex = "#0F172A",
            width = 4f
        )
        val heavyBoard = WhiteboardBoard(
            id = "board_mem",
            title = "Heavy",
            strokes = List(5) { idx -> heavyStroke.copy(id = "s_$idx") }
        )

        repeat(20) {
            store.pushSnapshot(heavyBoard)
        }

        assertTrue("Expected <= 15 undo steps, got ${store.undoCount}", store.undoCount <= 15)
        assertTrue("Expected byte budget trim below 20 steps, got ${store.undoCount}", store.undoCount < 20)
        assertTrue(
            "Expected retained bytes <= 2.5MB, got ${store.retainedBytes()}",
            store.retainedBytes() <= 2_500_000L
        )

        store.trimEmergency(targetFraction = 0.25f)
        assertTrue(store.retainedBytes() <= (2_500_000L * 0.25f).toLong() || store.undoCount <= 1)

        store.trimEmergency(targetFraction = 0f)
        assertEquals(0, store.undoCount)
        assertEquals(0L, store.retainedBytes())
    }

    @Test
    fun `export service enforces hard 2x cap and max dimension ceiling`() {
        val board = WhiteboardBoard(
            id = "export_board",
            title = "Export Cap Test",
            shapes = listOf(
                ShapeItem(
                    id = "rect_1",
                    shapeType = ShapeType.RECTANGLE,
                    x = 0f,
                    y = 0f,
                    width = 1200f,
                    height = 800f
                )
            )
        )

        val strictBmp = BoardExportService.renderBoardToBitmap(
            board = board,
            maxDimensionPx = 800,
            resolutionMultiplier = 4,
            hardMaxDimensionPx = 1600
        )
        assertTrue(maxOf(strictBmp.width, strictBmp.height) <= 1600)
        strictBmp.recycle()

        val standardBmp = BoardExportService.renderBoardToBitmap(
            board = board,
            maxDimensionPx = 1024,
            resolutionMultiplier = 8,
            hardMaxDimensionPx = 2048
        )
        assertTrue(maxOf(standardBmp.width, standardBmp.height) <= 2048)
        standardBmp.recycle()
    }

    @Test
    fun `snapshot serializer cleanly migrates legacy v3 json ignoring stickyNotes and snapshots`() {
        val legacyJson = """
            {
              "schemaVersion": 1,
              "boardId": "legacy_1",
              "title": "Estudio v3",
              "gridType": "GRID",
              "colorTheme": "CHARCOAL_CANVAS",
              "layers": [{"id": "layer_1", "name": "Base", "isVisible": true, "opacity": 0.9}],
              "strokes": [
                {
                  "id": "st_1",
                  "colorHex": "#FFFFFF",
                  "width": 6.0,
                  "brushType": "PENCIL",
                  "points": [{"x": 10.0, "y": 20.0}, {"x": 80.0, "y": 90.0}]
                }
              ],
              "shapes": [],
              "textItems": [
                {"id": "txt_1", "text": "Etiqueta", "x": 40.0, "y": 50.0, "fontSize": 22.0, "colorHex": "#FFFFFF", "rotation": 15.0}
              ],
              "stickyNotes": [
                {"id": "note_legacy", "text": "Debe ignorarse", "x": 100.0, "y": 100.0}
              ],
              "snapshots": [
                {"versionId": "snap_1", "versionName": "v3.0"}
              ]
            }
        """.trimIndent()

        val migrated = BoardSnapshotSerializer.deserializeIntoBoard(
            json = legacyJson,
            currentBoard = WhiteboardBoard(id = "legacy_1", title = "Estudio v3")
        )
        assertNotNull(migrated)
        assertEquals(1, migrated!!.strokes.size)
        assertEquals(1, migrated.textItems.size)
        assertEquals("Etiqueta", migrated.textItems.first().text)
        assertEquals(15f, migrated.textItems.first().rotation, 0.01f)
    }

    @Test
    fun `box transform geometry handles 4-corner resize and top rotation angle math`() {
        val initial = WorldBoxBounds(x = 100f, y = 100f, width = 200f, height = 100f)
        val resized = BoxTransformGeometry.resizeByCornerHandle(
            bounds = initial,
            handle = BoxResizeHandle.BOTTOM_RIGHT,
            deltaScreenX = 80f,
            deltaScreenY = 40f,
            scale = 2f,
            rotationDegrees = 0f,
            minWidthWorld = 96f,
            minHeightWorld = 52f
        )
        assertEquals(240f, resized.width, 0.01f)
        assertEquals(120f, resized.height, 0.01f)

        val degTop = BoxTransformGeometry.calculateRotationDegrees(
            centerX = 200f,
            centerY = 200f,
            pointerX = 200f,
            pointerY = 100f
        )
        assertEquals(0f, degTop, 0.1f)

        val degRight = BoxTransformGeometry.calculateRotationDegrees(
            centerX = 200f,
            centerY = 200f,
            pointerX = 300f,
            pointerY = 200f
        )
        assertEquals(90f, degRight, 0.1f)
    }
}
