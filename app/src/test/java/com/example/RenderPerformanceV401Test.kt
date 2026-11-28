package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.example.ink.StrokeStabilizer
import com.example.ink.engine.MemoryBudgetProfile
import com.example.ink.engine.PaintTileRasterCache
import com.example.ink.engine.UnifiedBrushPipeline
import com.example.model.BrushStudioSettings
import com.example.model.DrawingLayer
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.WhiteboardStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderPerformanceV401Test {

    @Test
    fun `paint tile cache performs O1 incremental append matching full rebake`() {
        val strictBudget = MemoryBudgetProfile.strict1Gb()
        val incrementalCache = PaintTileRasterCache(budget = strictBudget)
        val referenceCache = PaintTileRasterCache(budget = strictBudget)

        val baseStrokes = (0 until 10).map { idx ->
            WhiteboardStroke(
                id = "base_$idx",
                points = listOf(
                    StrokePoint(30f + idx * 12f, 40f, timestampMs = 100L + idx * 20L, pressure = 0.7f),
                    StrokePoint(120f + idx * 12f, 180f, timestampMs = 140L + idx * 20L, pressure = 0.8f),
                    StrokePoint(240f + idx * 10f, 320f, timestampMs = 180L + idx * 20L, pressure = 0.6f)
                ),
                colorHex = "#1E88E5",
                width = 8f,
                brushType = StrokeBrushType.FOUNTAIN_PEN
            )
        }
        val appendedStroke = WhiteboardStroke(
            id = "appended_11",
            points = listOf(
                StrokePoint(50f, 380f, timestampMs = 400L, pressure = 0.75f),
                StrokePoint(200f, 360f, timestampMs = 440L, pressure = 0.9f),
                StrokePoint(360f, 410f, timestampMs = 480L, pressure = 0.7f)
            ),
            colorHex = "#E53935",
            width = 12f,
            brushType = StrokeBrushType.FINE_MARKER
        )
        val allStrokes = baseStrokes + appendedStroke
        val layers = listOf(DrawingLayer())

        // Frame 1: bake first 10 strokes into tile (0, 0)
        val idx1 = incrementalCache.buildSpatialIndex(
            boardId = "perf_board",
            strokes = baseStrokes,
            shapes = emptyList(),
            selectedStrokeId = null,
            selectedStrokeIds = emptySet(),
            selectedShapeId = null,
            selectedShapeIds = emptySet(),
            isDarkBoard = false,
            handDrawnFactor = 0f,
            layers = layers
        )
        val outInc = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val incCanvas = Canvas(outInc)
        incrementalCache.renderVisibleTiles(
            canvas = incCanvas,
            spatialIndex = idx1,
            panX = 0f,
            panY = 0f,
            scale = 1f,
            screenW = 512f,
            screenH = 512f,
            isDarkBoard = false,
            handDrawnFactor = 0f
        )
        val rebakesAfterFirst = incrementalCache.fullRebakes
        assertTrue("Expected initial bake >= 1, got $rebakesAfterFirst", rebakesAfterFirst >= 1L)
        assertEquals(0L, incrementalCache.incrementalAppends)

        // Frame 2: append stroke #11 and render again -> must hit O(1) incremental append
        val idx2 = incrementalCache.buildSpatialIndex(
            boardId = "perf_board",
            strokes = allStrokes,
            shapes = emptyList(),
            selectedStrokeId = null,
            selectedStrokeIds = emptySet(),
            selectedShapeId = null,
            selectedShapeIds = emptySet(),
            isDarkBoard = false,
            handDrawnFactor = 0f,
            layers = layers
        )
        outInc.eraseColor(Color.TRANSPARENT)
        incrementalCache.renderVisibleTiles(
            canvas = incCanvas,
            spatialIndex = idx2,
            panX = 0f,
            panY = 0f,
            scale = 1f,
            screenW = 512f,
            screenH = 512f,
            isDarkBoard = false,
            handDrawnFactor = 0f
        )

        assertEquals(1L, incrementalCache.incrementalAppends)
        assertEquals(rebakesAfterFirst, incrementalCache.fullRebakes)
        assertTrue(incrementalCache.currentCacheBytes() <= strictBudget.rasterCacheBytes)

        // Compare pixels against a fresh full bake
        val refIdx = referenceCache.buildSpatialIndex(
            boardId = "perf_board",
            strokes = allStrokes,
            shapes = emptyList(),
            selectedStrokeId = null,
            selectedStrokeIds = emptySet(),
            selectedShapeId = null,
            selectedShapeIds = emptySet(),
            isDarkBoard = false,
            handDrawnFactor = 0f,
            layers = layers
        )
        val outRef = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        referenceCache.renderVisibleTiles(
            canvas = Canvas(outRef),
            spatialIndex = refIdx,
            panX = 0f,
            panY = 0f,
            scale = 1f,
            screenW = 512f,
            screenH = 512f,
            isDarkBoard = false,
            handDrawnFactor = 0f
        )

        var maxChannelDiff = 0
        var nonEmptyPixels = 0
        for (y in 0 until 512 step 4) {
            for (x in 0 until 512 step 4) {
                val pInc = outInc.getPixel(x, y)
                val pRef = outRef.getPixel(x, y)
                if (Color.alpha(pRef) > 10) nonEmptyPixels++
                maxChannelDiff = maxOf(
                    maxChannelDiff,
                    abs(Color.alpha(pInc) - Color.alpha(pRef)),
                    abs(Color.red(pInc) - Color.red(pRef)),
                    abs(Color.green(pInc) - Color.green(pRef)),
                    abs(Color.blue(pInc) - Color.blue(pRef))
                )
            }
        }
        assertTrue("Expected visible stroke pixels in tile", nonEmptyPixels > 20)
        assertTrue("Expected incremental bake to match full rebake (maxDiff=$maxChannelDiff)", maxChannelDiff <= 4)

        outInc.recycle()
        outRef.recycle()
        incrementalCache.clearAndReleasePool()
        referenceCache.clearAndReleasePool()
    }

    @Test
    fun `full 1080x2400 viewport of 15 active tiles has zero LRU thrashing under 1GB strict profile`() {
        val strictBudget = MemoryBudgetProfile.strict1Gb()
        val cache = PaintTileRasterCache(budget = strictBudget)

        // 3 columns x 5 rows = 15 tiles spanning a 1080x2400 screen at 1x scale (each tile is 512x512 world units)
        val strokes = mutableListOf<WhiteboardStroke>()
        for (ty in 0 until 5) {
            for (tx in 0 until 3) {
                val ox = tx * 512f + 80f
                val oy = ty * 512f + 80f
                strokes += WhiteboardStroke(
                    id = "tile_${tx}_${ty}",
                    points = listOf(
                        StrokePoint(ox, oy, timestampMs = 10L, pressure = 0.7f),
                        StrokePoint(ox + 180f, oy + 140f, timestampMs = 30L, pressure = 0.8f),
                        StrokePoint(ox + 300f, oy + 260f, timestampMs = 50L, pressure = 0.65f)
                    ),
                    colorHex = "#0F172A",
                    width = 6f,
                    brushType = StrokeBrushType.TECHNICAL_INK
                )
            }
        }

        val index = cache.buildSpatialIndex(
            boardId = "viewport_15_tiles",
            strokes = strokes,
            shapes = emptyList(),
            selectedStrokeId = null,
            selectedStrokeIds = emptySet(),
            selectedShapeId = null,
            selectedShapeIds = emptySet(),
            isDarkBoard = false,
            handDrawnFactor = 0f,
            layers = listOf(DrawingLayer())
        )

        val screenBmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val screenCanvas = Canvas(screenBmp)

        // Frame 1 populates all 15 tiles adaptively within 6 MB budget
        cache.renderVisibleTiles(
            canvas = screenCanvas,
            spatialIndex = index,
            panX = 0f,
            panY = 0f,
            scale = 1f,
            screenW = 1080f,
            screenH = 2400f,
            isDarkBoard = false,
            handDrawnFactor = 0f
        )
        assertEquals(0L, cache.cacheEvictions)
        assertEquals(15, cache.currentTileCount())
        assertTrue(cache.currentCacheBytes() <= strictBudget.rasterCacheBytes)

        // Frame 2 & 3: all 15 tiles must hit cache with 0 evictions and 0 rebakes
        cache.resetMetrics()
        repeat(2) {
            cache.renderVisibleTiles(
                canvas = screenCanvas,
                spatialIndex = index,
                panX = 0f,
                panY = 0f,
                scale = 1f,
                screenW = 1080f,
                screenH = 2400f,
                isDarkBoard = false,
                handDrawnFactor = 0f
            )
        }
        assertEquals(30L, cache.cacheHits)
        assertEquals(0L, cache.fullRebakes)
        assertEquals(0L, cache.cacheEvictions)

        screenBmp.recycle()
        cache.clearAndReleasePool()
    }

    @Test
    fun `incremental live preview filter matches batch stabilizeLivePreview across pointer moves`() {
        val rawStream = (0 until 28).map { i ->
            StrokePoint(
                x = 20f + i * 9.5f + if (i % 2 == 0) 2.2f else -1.8f,
                y = 60f + i * 4.2f + if (i % 3 == 0) -2.5f else 1.9f,
                timestampMs = 1000L + i * 16L,
                pressure = 0.5f + (i % 5) * 0.08f
            )
        }

        val incrementalFilter = StrokeStabilizer.IncrementalLivePreviewFilter()
        val accumulated = mutableListOf<StrokePoint>()

        for (i in rawStream.indices) {
            accumulated.add(rawStream[i])
            for (level in listOf(3)) {
                val incResult = incrementalFilter.update(accumulated, predictionLevel = level)
                val batchResult = StrokeStabilizer.stabilizeLivePreview(accumulated, predictionLevel = level)
                assertEquals("Point count mismatch at step $i", batchResult.size, incResult.size)
                for (j in batchResult.indices) {
                    assertEquals("X mismatch at step $i idx $j", batchResult[j].x, incResult[j].x, 0.001f)
                    assertEquals("Y mismatch at step $i idx $j", batchResult[j].y, incResult[j].y, 0.001f)
                }
            }
        }
    }

    @Test
    fun `humanized brush pipeline renders visible pixels across textured and shader brushes`() {
        val points = listOf(
            StrokePoint(24f, 45f, timestampMs = 100L, pressure = 0.55f),
            StrokePoint(80f, 62f, timestampMs = 132L, pressure = 0.85f),
            StrokePoint(145f, 54f, timestampMs = 168L, pressure = 0.78f),
            StrokePoint(210f, 92f, timestampMs = 205L, pressure = 0.62f)
        )

        val brushesToVerify = listOf(
            StrokeBrushType.PENCIL,
            StrokeBrushType.AIRBRUSH,
            StrokeBrushType.WATERCOLOR,
            StrokeBrushType.HIGHLIGHTER,
            StrokeBrushType.FOUNTAIN_PEN,
            StrokeBrushType.PAINT_BRUSH
        )

        brushesToVerify.forEach { brush ->
            val bmp = Bitmap.createBitmap(260, 140, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val stroke = WhiteboardStroke(
                id = "perf_brush_${brush.name}",
                points = points,
                colorHex = "#2563EB",
                width = 16f,
                brushType = brush,
                opacity = brush.defaultOpacity,
                studioSettings = BrushStudioSettings.defaultFor(brush)
            )
            UnifiedBrushPipeline.renderStroke(
                canvas = canvas,
                stroke = stroke,
                isDarkBoard = false,
                isOpaqueCanvasBackground = false
            )

            var visiblePixels = 0
            for (y in 0 until bmp.height) {
                for (x in 0 until bmp.width) {
                    if (Color.alpha(bmp.getPixel(x, y)) > 8) visiblePixels++
                }
            }
            assertTrue("Expected visible pixels for $brush, got $visiblePixels", visiblePixels > 50)
            bmp.recycle()
        }
    }
}
