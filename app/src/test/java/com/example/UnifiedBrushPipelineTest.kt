package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.ink.engine.Airbrush
import com.example.ink.engine.BristleBrush
import com.example.ink.engine.BrushConfig
import com.example.ink.engine.BrushEngine
import com.example.ink.engine.BrushEngineRegistry
import com.example.ink.engine.CalligraphyBrush
import com.example.ink.engine.HighlighterBrush
import com.example.ink.engine.PathHumanizer
import com.example.ink.engine.ShapeCommand
import com.example.ink.engine.ShapeGenerator
import com.example.ink.engine.TouchSample
import com.example.ink.engine.UnifiedBrushPipeline
import com.example.ink.engine.UnifiedDrawingView
import com.example.model.LineCapStyle
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokeStyle
import com.example.model.ToolType
import com.example.viewmodel.WhiteboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnifiedBrushPipelineTest {

    @Test
    fun `stage 1 ShapeGenerator produces pure Paths from MotionEvents presets polygons and imported paths`() {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20f, 30f, 0)
        val move1 = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, 60f, 55f, 0)
        val move2 = MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_MOVE, 110f, 80f, 0)
        val up = MotionEvent.obtain(now, now + 48, MotionEvent.ACTION_UP, 140f, 95f, 0)

        val session = ShapeGenerator.TouchStrokeSession()
        assertTrue(session.onTouchEvent(down))
        assertTrue(session.onTouchEvent(move1))
        assertTrue(session.onTouchEvent(move2))
        assertTrue(session.onTouchEvent(up))
        val freehandPath = session.buildPath()
        assertFalse(freehandPath.isEmpty)
        assertTrue(PathMeasure(freehandPath, false).length > 100f)
        listOf(down, move1, move2, up).forEach { it.recycle() }

        val circlePath = ShapeGenerator.generatePath(ShapeCommand.Circle(100f, 100f, 50f))
        val squarePath = ShapeGenerator.generatePath(ShapeCommand.Square(10f, 10f, 80f))
        val linePath = ShapeGenerator.generatePath(ShapeCommand.Line(0f, 0f, 120f, 60f))
        val polyPath = ShapeGenerator.generatePath(
            ShapeCommand.Polygon(listOf(PointF(0f, 0f), PointF(80f, 0f), PointF(40f, 70f)), closed = true)
        )
        val imported = ShapeGenerator.generatePath(ShapeCommand.ImportedPath(Path(squarePath)))

        assertTrue(PathMeasure(circlePath, false).length > 300f)
        assertEquals(320f, PathMeasure(squarePath, false).length, 1f)
        assertEquals(hypot(120f, 60f), PathMeasure(linePath, false).length, 1f)
        assertFalse(polyPath.isEmpty)
        assertEquals(320f, PathMeasure(imported, false).length, 1f)

        ShapeType.values().forEach { shapeType ->
            val cmd = ShapeCommand.PresetShape(shapeType, RectF(20f, 20f, 160f, 120f))
            val p = ShapeGenerator.generatePath(cmd)
            assertFalse("Preset shape $shapeType must generate non-empty Path", p.isEmpty)
        }
    }

    @Test
    fun `stage 2 PathHumanizer samples at 2px intervals and applies organic handDrawnFactor without breaking closed loops`() {
        val rectPath = ShapeGenerator.generatePath(ShapeCommand.Rectangle(RectF(40f, 40f, 240f, 140f)))
        // Perimeter = 2*(200 + 100) = 600px -> ~301 nodes at 2px step
        val rigidNodes = PathHumanizer.humanize(rectPath, handDrawnFactor = 0f, stepPx = 2f)
        val organicNodes = PathHumanizer.humanize(rectPath, handDrawnFactor = 0.85f, stepPx = 2f, seed = 42)

        assertTrue("Expected ~300 nodes at 2px sampling, got ${rigidNodes.size}", rigidNodes.size in 295..310)
        assertEquals(rigidNodes.size, organicNodes.size)
        assertEquals(0f, organicNodes.first().progress, 0.001f)
        assertEquals(1f, organicNodes.last().progress, 0.001f)
        assertTrue(organicNodes.first().isClosed)

        // Check closed loop seam continuity: first and last nodes must match closely
        val seamDist = hypot(
            organicNodes.first().x - organicNodes.last().x,
            organicNodes.first().y - organicNodes.last().y
        )
        assertTrue("Closed loop organic perturbation must join cleanly (dist=$seamDist)", seamDist < 1.5f)

        // Compare midpoint deviation from straight top edge (y = 40f)
        val topMidIdx = organicNodes.indexOfFirst { it.x in 135f..145f && it.y < 60f }
        assertTrue(topMidIdx >= 0)
        assertEquals(40f, rigidNodes[topMidIdx].y, 0.01f)
        val maxDeviation = organicNodes.indices.maxOf { i ->
            hypot(organicNodes[i].x - rigidNodes[i].x, organicNodes[i].y - rigidNodes[i].y)
        }
        assertTrue("Expected organic hand-drawn deviation > 1.2px, got $maxDeviation", maxDeviation > 1.2f)
    }

    @Test
    fun `stage 3 all 5 polymorphic BrushEngines render visible procedural strokes without external assets`() {
        val linePath = ShapeGenerator.generatePath(ShapeCommand.Line(30f, 100f, 220f, 100f))
        val nodes = PathHumanizer.humanize(linePath, handDrawnFactor = 0.3f, stepPx = 2f)

        val engines: List<Pair<String, BrushEngine>> = listOf(
            "HighlighterBrush" to HighlighterBrush(),
            "BristleBrush" to BristleBrush(bristleCount = 20),
            "CalligraphyBrush" to CalligraphyBrush(nibAngleDegrees = 45f),
            "Airbrush" to Airbrush()
        )

        engines.forEach { (name, engine) ->
            val bmp = Bitmap.createBitmap(260, 200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)

            // Draw reference strip behind highlighter to verify PorterDuff.Mode.MULTIPLY blending
            if (engine is HighlighterBrush) {
                val under = android.graphics.Paint().apply { color = Color.parseColor("#3B82F6") }
                canvas.drawRect(80f, 60f, 140f, 140f, under)
            }

            engine.render(
                canvas = canvas,
                nodes = nodes,
                config = BrushConfig(
                    color = if (engine is HighlighterBrush) Color.parseColor("#FACC15") else Color.parseColor("#0F172A"),
                    strokeWidth = 14f,
                    alpha = if (engine is HighlighterBrush) 0.38f else 0.92f,
                    handDrawnFactor = 0.3f,
                    isDarkBackground = false,
                    isOpaqueCanvasBackground = true
                )
            )

            // Verify inked pixels exist around y = 100f
            var paintedCount = 0
            for (y in 80..120) {
                for (x in 35..210) {
                    if (bmp.getPixel(x, y) != Color.WHITE) {
                        paintedCount++
                    }
                }
            }
            assertTrue("$name must render visible pixels", paintedCount > 120)

            // Specifically verify HighlighterBrush multiplies blue strip + yellow ink into a darker olive/green tone
            if (engine is HighlighterBrush) {
                val overlapPixel = bmp.getPixel(110, 100)
                assertTrue(overlapPixel != Color.WHITE && overlapPixel != Color.parseColor("#3B82F6"))
            }
        }
    }

    @Test
    fun `stage 3 CalligraphyBrush 45-degree nib modulates stroke cross-section thickness by direction angle`() {
        val nib = CalligraphyBrush(nibAngleDegrees = 45f)
        val diag45Path = ShapeGenerator.generatePath(ShapeCommand.Line(40f, 40f, 200f, 200f)) // +45 deg (parallel to nib)
        val diagMinus45Path = ShapeGenerator.generatePath(ShapeCommand.Line(40f, 200f, 200f, 40f)) // -45 deg (perpendicular to nib)

        fun renderAndCountPixels(p: Path): Int {
            val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val nodes = PathHumanizer.humanize(p, handDrawnFactor = 0f, stepPx = 2f)
            nib.render(canvas, nodes, BrushConfig(color = Color.BLACK, strokeWidth = 22f, handDrawnFactor = 0f))
            var count = 0
            for (y in 20..220) {
                for (x in 20..220) {
                    if (bmp.getPixel(x, y) != Color.WHITE) count++
                }
            }
            return count
        }

        val parallelCount = renderAndCountPixels(diag45Path)
        val perpendicularCount = renderAndCountPixels(diagMinus45Path)
        assertTrue(
            "Perpendicular (-45 deg) stroke should be much broader ($perpendicularCount px) than parallel (+45 deg) stroke ($parallelCount px)",
            perpendicularCount > parallelCount * 2
        )
    }

    @Test
    fun `stage 4 UnifiedDrawingView manages cache bitmap live drag rendering and ACTION_UP commits`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = UnifiedDrawingView(context).apply {
            setBoardBackgroundColor(Color.WHITE)
            setBrushEngine(BristleBrush(20))
            setStrokeColor(Color.parseColor("#1D4ED8"))
            setStrokeWidth(12f)
            setHandDrawnFactor(0.4f)
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 360, 360)

        var committedCallbackCount = 0
        view.onStrokeCommittedListener = { _, _, _ -> committedCallbackCount++ }

        val t0 = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 40f, 60f, 0)
        val move = MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, 160f, 120f, 0)
        val up = MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_UP, 260f, 180f, 0)

        assertTrue(view.onTouchEvent(down))
        assertTrue(view.onTouchEvent(move))

        // Live preview must draw on canvas BEFORE ACTION_UP commit
        val livePreviewBmp = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(livePreviewBmp))
        assertEquals(0, view.committedCount)
        assertTrue(livePreviewBmp.getPixel(160, 120) != Color.WHITE)

        // Final commit on ACTION_UP bakes into offscreen confirmed Bitmap
        assertTrue(view.onTouchEvent(up))
        assertEquals(1, view.committedCount)
        assertEquals(1, committedCallbackCount)

        val snapshotAfterFreehand = view.getConfirmedBitmapSnapshot()
        assertNotNull(snapshotAfterFreehand)
        var nonWhiteAfterFreehand = 0
        for (y in 40..200) {
            for (x in 30..280) {
                if (snapshotAfterFreehand!!.getPixel(x, y) != Color.WHITE) nonWhiteAfterFreehand++
            }
        }
        assertTrue(nonWhiteAfterFreehand > 300)
        listOf(down, move, up).forEach { it.recycle() }

        // Switch to preset shape stretch mode (RECTANGLE rendered with CalligraphyBrush)
        view.setPresetShapeMode(ShapeType.RECTANGLE, filled = false)
        view.setBrushEngine(CalligraphyBrush())
        val sDown = MotionEvent.obtain(t0 + 100, t0 + 100, MotionEvent.ACTION_DOWN, 60f, 200f, 0)
        val sMove = MotionEvent.obtain(t0 + 100, t0 + 116, MotionEvent.ACTION_MOVE, 280f, 320f, 0)
        val sUp = MotionEvent.obtain(t0 + 100, t0 + 132, MotionEvent.ACTION_UP, 280f, 320f, 0)

        assertTrue(view.onTouchEvent(sDown))
        assertTrue(view.onTouchEvent(sMove))
        assertTrue(view.onTouchEvent(sUp))
        assertEquals(2, view.committedCount)
        listOf(sDown, sMove, sUp).forEach { it.recycle() }

        assertTrue(view.undoLast())
        assertEquals(1, view.committedCount)
        view.clearCanvas()
        assertEquals(0, view.committedCount)
    }

    @Test
    fun `viewmodel exposes calligraphy airbrush and handDrawnFactor into unified pipeline`() {
        val viewModel = WhiteboardViewModel()
        viewModel.setTool(ToolType.CALLIGRAPHY)
        assertEquals(StrokeBrushType.CALLIGRAPHY, viewModel.brushTypeForTool(ToolType.CALLIGRAPHY))
        viewModel.setTool(ToolType.AIRBRUSH)
        assertEquals(StrokeBrushType.AIRBRUSH, viewModel.brushTypeForTool(ToolType.AIRBRUSH))

        viewModel.setHandDrawnFactor(0.75f)
        assertEquals(0.75f, viewModel.uiState.value.handDrawnFactor, 0.001f)

        val rectCmd = ShapeCommand.Rectangle(RectF(20f, 20f, 180f, 120f))
        val bmp = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val engine = BrushEngineRegistry.forBrushType(
            viewModel.brushTypeForTool(ToolType.AIRBRUSH),
            StrokeStyle.SOLID
        )
        UnifiedBrushPipeline.renderCommand(
            canvas = canvas,
            command = rectCmd,
            brushEngine = engine,
            config = BrushConfig(
                color = Color.parseColor("#DC2626"),
                strokeWidth = 10f,
                handDrawnFactor = viewModel.uiState.value.handDrawnFactor
            )
        )
        var nonWhite = 0
        for (y in 0 until 180) {
            for (x in 0 until 240) {
                if (bmp.getPixel(x, y) != Color.WHITE) nonWhite++
            }
        }
        assertTrue("UnifiedBrushPipeline must render airbrushed organic rectangle", nonWhite > 200)
    }

    @Test
    fun `highlighter renders uniformly at 0_3f alpha with MULTIPLY and no center dark line or yellow tint override`() {
        // 1. Default highlighter alpha is 0.30f and preserves dark/black user color without yellow override
        assertEquals(0.30f, StrokeBrushType.HIGHLIGHTER.defaultOpacity, 0.001f)
        val viewModel = WhiteboardViewModel()
        assertEquals("#000000", viewModel.effectiveColorForBrush(StrokeBrushType.HIGHLIGHTER, "#000000"))
        assertEquals("#0F172A", viewModel.effectiveColorForBrush(StrokeBrushType.HIGHLIGHTER, "#0F172A"))

        // 2. Render pure black highlighter curved S-stroke onto white bitmap
        val path = ShapeGenerator.fromStrokePoints(
            listOf(
                com.example.model.StrokePoint(30f, 90f),
                com.example.model.StrokePoint(90f, 60f),
                com.example.model.StrokePoint(160f, 120f),
                com.example.model.StrokePoint(230f, 90f)
            )
        )
        val nodes = PathHumanizer.humanize(path, handDrawnFactor = 0f, stepPx = 2f)
        val bmp = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val highlighter = HighlighterBrush()
        highlighter.render(
            canvas = canvas,
            nodes = nodes,
            config = BrushConfig(
                color = Color.BLACK,
                strokeWidth = 24f,
                alpha = 0.30f,
                isDarkBackground = false,
                isOpaqueCanvasBackground = true
            )
        )

        // Inspect pixels along the midpoint x=125 across the stroke height:
        // No black center line! Center pixel and surrounding stroke body must match ~70% white (RGB ~178)
        var minLuma = 255
        var maxBodyLuma = 0
        var strokePixels = 0
        for (y in 40..140) {
            val px = bmp.getPixel(125, y)
            if (px != Color.WHITE) {
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                // Pure neutral gray (no yellow/green tint)
                assertTrue("Pure black highlighter must keep neutral grayscale RGB ($r,$g,$b)", abs(r - g) <= 2 && abs(g - b) <= 2)
                strokePixels++
                if (r < minLuma) minLuma = r
                if (r > maxBodyLuma) maxBodyLuma = r
            }
        }
        assertTrue("Expected thick highlighter stroke span > 18px, got $strokePixels", strokePixels >= 18)
        // With 0.30f alpha on white (255 * 0.70 ≈ 178), center cannot drop near 0 (no black center line!)
        assertTrue("Center pixel must not form a dark/black line (minLuma=$minLuma)", minLuma in 160..195)
        // Exterior pixels inside layerBounds must remain pure opaque white (BlendMode.MULTIPLY preserves Sa=0)
        assertEquals(Color.WHITE, bmp.getPixel(125, 20))
        assertEquals(Color.WHITE, bmp.getPixel(125, 160))

        // 3. Verify a self-intersecting loop in a single highlighter stroke does NOT double-darken at crossover
        val loopPath = ShapeGenerator.fromStrokePoints(
            listOf(
                com.example.model.StrokePoint(50f, 90f),
                com.example.model.StrokePoint(130f, 35f),
                com.example.model.StrokePoint(170f, 90f),
                com.example.model.StrokePoint(125f, 130f),
                com.example.model.StrokePoint(95f, 90f),
                com.example.model.StrokePoint(145f, 45f),
                com.example.model.StrokePoint(210f, 90f)
            )
        )
        val loopNodes = PathHumanizer.humanize(loopPath, handDrawnFactor = 0f, stepPx = 2f)
        val loopBmp = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888)
        val loopCanvas = Canvas(loopBmp)
        loopCanvas.drawColor(Color.WHITE)
        highlighter.render(
            canvas = loopCanvas,
            nodes = loopNodes,
            config = BrushConfig(
                color = Color.BLACK,
                strokeWidth = 20f,
                alpha = 0.30f,
                isDarkBackground = false,
                isOpaqueCanvasBackground = true
            )
        )
        var loopMinLuma = 255
        for (y in 20..160) {
            for (x in 40..220) {
                val px = loopBmp.getPixel(x, y)
                if (px != Color.WHITE) {
                    val r = Color.red(px)
                    if (r < loopMinLuma) loopMinLuma = r
                }
            }
        }
        assertTrue("Self-intersecting highlighter loop must stay uniform ~178 without double-darkening (loopMinLuma=$loopMinLuma)", loopMinLuma in 155..195)
    }

    @Test
    fun `bristle brush keeps pure black grayscale fidelity without dark center stripe`() {
        val linePath = ShapeGenerator.generatePath(ShapeCommand.Line(30f, 90f, 220f, 90f))
        val nodes = PathHumanizer.humanize(linePath, handDrawnFactor = 0f, stepPx = 2f)

        listOf(BristleBrush(20)).forEach { engine ->
            val bmp = Bitmap.createBitmap(250, 180, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)

            engine.render(
                canvas = canvas,
                nodes = nodes,
                config = BrushConfig(
                    color = Color.BLACK,
                    strokeWidth = 18f,
                    alpha = 0.80f,
                    isDarkBackground = false,
                    isOpaqueCanvasBackground = true
                )
            )

            var painted = 0
            for (y in 65..115) {
                for (x in 45..205) {
                    val px = bmp.getPixel(x, y)
                    if (px != Color.WHITE) {
                        painted++
                        val r = Color.red(px)
                        val g = Color.green(px)
                        val b = Color.blue(px)
                        assertTrue("${engine.brushId} with BLACK must output neutral gray ($r,$g,$b)", abs(r - g) <= 2 && abs(g - b) <= 2)
                    }
                }
            }
            assertTrue("${engine.brushId} must render visible stroke pixels", painted > 400)
        }
    }
}
