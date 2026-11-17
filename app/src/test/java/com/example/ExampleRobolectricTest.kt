package com.example

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.example.data.BoardExportService
import com.example.data.BoardSnapshotSerializer
import com.example.ink.ShapeRecognizer
import com.example.ink.StickyNoteBounds
import com.example.ink.StickyNoteGeometry
import com.example.ink.StickyNoteResizeHandle
import com.example.ink.ViewportTransform
import com.example.model.CanvasColorTheme
import com.example.model.CanvasGridType
import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StickyNote
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.TemplateCategory
import com.example.model.TemplateType
import com.example.model.ToolType
import com.example.model.WhiteboardStroke
import com.example.ui.canvas.IdeationTemplatesDialog
import com.example.ui.canvas.StickyNoteComputedFontSpKey
import com.example.ui.canvas.StickyNoteView
import com.example.ui.canvas.WhiteboardCanvasScreen
import com.example.ui.toolbar.WhiteboardPenDock
import com.example.ui.toolbar.WhiteboardTopBar
import com.example.viewmodel.WhiteboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Whiteboard", appName)
  }

  @Test
  fun `test whiteboard viewmodel sticky note add and select`() {
    val viewModel = WhiteboardViewModel()
    val initialNotesCount = viewModel.uiState.value.board.stickyNotes.size
    viewModel.addStickyNote(1080f, 1920f, "#FFF275")
    val state = viewModel.uiState.value
    assertEquals(initialNotesCount + 1, state.board.stickyNotes.size)

    val addedNote = state.board.stickyNotes.last()
    viewModel.selectNote(addedNote.id)
    assertEquals(addedNote.id, viewModel.uiState.value.selectedNoteId)

    viewModel.deleteSelectedNote()
    assertEquals(initialNotesCount, viewModel.uiState.value.board.stickyNotes.size)
  }

  @Test
  fun `test whiteboard dock tool color width style and grid selection`() {
    val viewModel = WhiteboardViewModel()

    viewModel.setTool(ToolType.HIGHLIGHTER)
    assertEquals(ToolType.HIGHLIGHTER, viewModel.uiState.value.currentTool)

    viewModel.setTool(ToolType.HAND)
    assertEquals(ToolType.HAND, viewModel.uiState.value.currentTool)

    viewModel.setPenColor("#2563EB", isRainbow = false)
    assertEquals("#2563EB", viewModel.uiState.value.penColorHex)
    assertEquals(false, viewModel.uiState.value.isRainbowPen)

    viewModel.setPenWidth(12f)
    assertEquals(12f, viewModel.uiState.value.penWidth)

    viewModel.setPenStyle(StrokeStyle.DASHED)
    assertEquals(StrokeStyle.DASHED, viewModel.uiState.value.penStyle)

    viewModel.setGridType(CanvasGridType.DOTS)
    assertEquals(CanvasGridType.DOTS, viewModel.uiState.value.board.gridType)
  }

  @Test
  fun `test smart correction off by default leaves freehand strokes untouched`() {
    val viewModel = WhiteboardViewModel()
    assertFalse(viewModel.uiState.value.autoShapeRecognition)

    val linePoints = (0..20).map { i ->
      StrokePoint(100f + i * 10f, 400f - i * 10f)
    }
    viewModel.commitCompletedStroke(linePoints, isHighlighter = false)

    assertEquals(1, viewModel.uiState.value.board.strokes.size)
    assertEquals(0, viewModel.uiState.value.board.shapes.size)

    // Enable smart correction and commit same line -> converts to LINE preserving exact direction
    viewModel.toggleAutoShapeRecognition()
    assertTrue(viewModel.uiState.value.autoShapeRecognition)
    viewModel.commitCompletedStroke(linePoints, isHighlighter = false)

    assertEquals(1, viewModel.uiState.value.board.shapes.size)
    val shape = viewModel.uiState.value.board.shapes.first()
    assertEquals(ShapeType.LINE, shape.shapeType)
    assertEquals(2, shape.vertices.size)
    // Verify bottom-left to top-right direction is preserved (not inverted!)
    assertTrue(shape.vertices[0].x < shape.vertices[1].x)
    assertTrue(shape.vertices[0].y > shape.vertices[1].y)
  }

  @Test
  fun `test intelligent shape recognizer distinguishes crescent triangle circle and rectangle`() {
    // 1. Crescent / medialuna (120-degree arc) must NOT be converted to a straight line
    val crescentPts = (0..30).map { i ->
      val angle = (-PI / 3.0) + (2.0 * PI / 3.0) * (i / 30.0)
      StrokePoint(300f + 160f * cos(angle).toFloat(), 300f + 160f * sin(angle).toFloat())
    }
    val crescentResult = ShapeRecognizer.recognizeStroke(
      WhiteboardStroke(points = crescentPts, colorHex = "#0F172A", width = 6f)
    )
    assertNull(crescentResult)

    // 2. Triangle drawn starting mid-bottom-edge must be recognized as TRIANGLE (not SQUARE)
    val vTop = StrokePoint(300f, 100f)
    val vRight = StrokePoint(450f, 360f)
    val vLeft = StrokePoint(150f, 360f)
    val triPts = mutableListOf<StrokePoint>()
    fun addEdge(a: StrokePoint, b: StrokePoint) {
      for (i in 0..14) {
        val t = i / 15f
        triPts.add(StrokePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
      }
    }
    val midBottom = StrokePoint(300f, 360f)
    addEdge(midBottom, vRight)
    addEdge(vRight, vTop)
    addEdge(vTop, vLeft)
    addEdge(vLeft, midBottom)
    val triResult = ShapeRecognizer.recognizeStroke(
      WhiteboardStroke(points = triPts, colorHex = "#0F172A", width = 6f)
    )
    assertNotNull(triResult)
    assertEquals(ShapeType.TRIANGLE, triResult!!.shapeType)
    assertEquals(3, triResult.shapeItem.vertices.size)

    // 3. Circle must be recognized as CIRCLE (not ROUNDED_RECT)
    val circlePts = (0..48).map { i ->
      val angle = (2.0 * PI * i) / 48.0
      StrokePoint(300f + 110f * cos(angle).toFloat(), 300f + 110f * sin(angle).toFloat())
    }
    val circleResult = ShapeRecognizer.recognizeStroke(
      WhiteboardStroke(points = circlePts, colorHex = "#0F172A", width = 6f)
    )
    assertNotNull(circleResult)
    assertEquals(ShapeType.CIRCLE, circleResult!!.shapeType)

    // 4. Square must be recognized as RECTANGLE
    val sqPts = mutableListOf<StrokePoint>()
    val p0 = StrokePoint(100f, 100f)
    val p1 = StrokePoint(300f, 100f)
    val p2 = StrokePoint(300f, 300f)
    val p3 = StrokePoint(100f, 300f)
    fun addSqEdge(a: StrokePoint, b: StrokePoint) {
      for (i in 0..14) {
        val t = i / 15f
        sqPts.add(StrokePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
      }
    }
    addSqEdge(p0, p1)
    addSqEdge(p1, p2)
    addSqEdge(p2, p3)
    addSqEdge(p3, p0)
    sqPts.add(p0)
    val sqResult = ShapeRecognizer.recognizeStroke(
      WhiteboardStroke(points = sqPts, colorHex = "#0F172A", width = 6f)
    )
    assertNotNull(sqResult)
    assertEquals(ShapeType.RECTANGLE, sqResult!!.shapeType)
  }

  @Test
  fun `test meticulous line and triangle shape editing size width color style rotation z-order duplicate`() {
    val viewModel = WhiteboardViewModel()
    viewModel.toggleAutoShapeRecognition()

    // 1. Create a diagonal LINE via smart recognition
    val linePts = (0..20).map { i -> StrokePoint(100f + i * 10f, 100f + i * 5f) }
    viewModel.commitCompletedStroke(linePts, isHighlighter = false)
    val line = viewModel.uiState.value.board.shapes.first()
    assertEquals(ShapeType.LINE, line.shapeType)

    viewModel.selectShape(line.id)
    // Edit color, thickness, line style
    viewModel.updateSelectedShape(strokeColor = "#DC2626", width = 14f, style = StrokeStyle.DASHED)
    var updatedLine = viewModel.uiState.value.board.shapes.first()
    assertEquals("#DC2626", updatedLine.strokeColorHex)
    assertEquals(14f, updatedLine.strokeWidth)
    assertEquals(StrokeStyle.DASHED, updatedLine.strokeStyle)

    // Drag endpoint 1 via handle resize
    viewModel.beginTransformSnapshot()
    viewModel.resizeSelectedShapeHandle(1, 500f, 300f)
    updatedLine = viewModel.uiState.value.board.shapes.first()
    assertEquals(500f, updatedLine.vertices[1].x, 0.01f)
    assertEquals(300f, updatedLine.vertices[1].y, 0.01f)

    // Duplicate line and verify vertices also offset by +30f
    viewModel.duplicateSelectedShape()
    assertEquals(2, viewModel.uiState.value.board.shapes.size)
    val dupLine = viewModel.uiState.value.board.shapes.last()
    assertEquals(530f, dupLine.vertices[1].x, 0.01f)
    assertEquals(330f, dupLine.vertices[1].y, 0.01f)

    // 2. Place a TRIANGLE and edit size, corner resize, rotation, fill, z-order
    viewModel.requestAddCustomShape(ShapeType.TRIANGLE, false, "#0F172A", "#DBEAFE", StrokeStyle.SOLID)
    viewModel.placePendingAt(400f, 400f)
    val tri = viewModel.uiState.value.board.shapes.last()
    viewModel.selectShape(tri.id)

    val origW = tri.width
    val origH = tri.height
    viewModel.scaleSelectedShape(1.5f)
    var updatedTri = viewModel.uiState.value.board.shapes.find { it.id == tri.id }!!
    assertEquals(origW * 1.5f, updatedTri.width, 0.5f)
    assertEquals(origH * 1.5f, updatedTri.height, 0.5f)

    viewModel.rotateSelectedShape(30f)
    updatedTri = viewModel.uiState.value.board.shapes.find { it.id == tri.id }!!
    assertEquals(30f, updatedTri.rotation, 0.01f)

    viewModel.resetSelectedShapeRotation()
    updatedTri = viewModel.uiState.value.board.shapes.find { it.id == tri.id }!!
    assertEquals(0f, updatedTri.rotation, 0.01f)

    viewModel.updateSelectedShape(fillColor = "#DCFCE7", isFilled = true)
    updatedTri = viewModel.uiState.value.board.shapes.find { it.id == tri.id }!!
    assertTrue(updatedTri.isFilled)
    assertEquals("#DCFCE7", updatedTri.fillColorHex)

    // Z-order: send back then bring to front
    viewModel.sendSelectedShapeToBack()
    assertEquals(tri.id, viewModel.uiState.value.board.shapes.first().id)
    viewModel.bringSelectedShapeToFront()
    assertEquals(tri.id, viewModel.uiState.value.board.shapes.last().id)
  }

  @Test
  fun `test freehand stroke editing resize move scale color width style and undo`() {
    val viewModel = WhiteboardViewModel()
    val pts = listOf(StrokePoint(100f, 100f), StrokePoint(200f, 100f), StrokePoint(200f, 200f))
    viewModel.commitCompletedStroke(pts, isHighlighter = false)

    val stroke = viewModel.uiState.value.board.strokes.first()
    viewModel.selectStroke(stroke.id)

    viewModel.updateSelectedStrokeColor("#7C3AED")
    viewModel.updateSelectedStrokeWidth(16f)
    viewModel.updateSelectedStrokeStyle(StrokeStyle.DOTTED)

    var edited = viewModel.uiState.value.board.strokes.first()
    assertEquals("#7C3AED", edited.colorHex)
    assertEquals(16f, edited.width)
    assertEquals(StrokeStyle.DOTTED, edited.strokeStyle)

    viewModel.beginTransformSnapshot()
    viewModel.moveSelectedStroke(50f, -25f)
    edited = viewModel.uiState.value.board.strokes.first()
    assertEquals(150f, edited.points.first().x, 0.01f)
    assertEquals(75f, edited.points.first().y, 0.01f)

    viewModel.beginTransformSnapshot()
    viewModel.resizeSelectedStrokeBounds(3, 350f, 375f)
    edited = viewModel.uiState.value.board.strokes.first()
    assertEquals(350f, edited.points.maxOf { it.x }, 0.5f)
    assertEquals(375f, edited.points.maxOf { it.y }, 0.5f)

    viewModel.undo()
    val undone = viewModel.uiState.value.board.strokes.first()
    assertEquals(250f, undone.points.maxOf { it.x }, 0.5f)
  }

  @Test
  fun `test advanced line styles arrow caps serialization and bitmap export`() {
    val viewModel = WhiteboardViewModel()
    viewModel.openBoard(viewModel.uiState.value.allBoards.first().id)

    viewModel.setPenStyle(StrokeStyle.NEON_GLOW)
    viewModel.setPenCapStyle(LineCapStyle.BOTH_ARROWS)
    assertEquals(StrokeStyle.NEON_GLOW, viewModel.uiState.value.penStyle)
    assertEquals(LineCapStyle.BOTH_ARROWS, viewModel.uiState.value.penCapStyle)

    viewModel.commitCompletedStroke(
      listOf(StrokePoint(40f, 80f), StrokePoint(180f, 120f), StrokePoint(280f, 90f)),
      isHighlighter = false
    )
    val stroke = viewModel.uiState.value.board.strokes.last()
    assertEquals(StrokeStyle.NEON_GLOW, stroke.strokeStyle)
    assertEquals(LineCapStyle.BOTH_ARROWS, stroke.capStyle)

    viewModel.selectStroke(stroke.id)
    viewModel.updateSelectedStrokeStyle(StrokeStyle.RAILWAY)
    viewModel.updateSelectedStrokeCapStyle(LineCapStyle.END_ARROW)
    val editedStroke = viewModel.uiState.value.board.strokes.last()
    assertEquals(StrokeStyle.RAILWAY, editedStroke.strokeStyle)
    assertEquals(LineCapStyle.END_ARROW, editedStroke.capStyle)

    viewModel.requestAddCustomShape(
      type = ShapeType.LINE,
      isFilled = false,
      strokeColor = "#2563EB",
      fillColor = "#DBEAFE",
      style = StrokeStyle.WAVY,
      capStyle = LineCapStyle.START_ARROW
    )
    viewModel.placePendingShapeInBounds(StrokePoint(60f, 220f), StrokePoint(320f, 260f))
    val lineShape = viewModel.uiState.value.board.shapes.last()
    assertEquals(StrokeStyle.WAVY, lineShape.strokeStyle)
    assertEquals(LineCapStyle.START_ARROW, lineShape.capStyle)

    viewModel.updateSelectedShape(style = StrokeStyle.TAPERED, capStyle = LineCapStyle.BOTH_ARROWS)
    val updatedLineShape = viewModel.uiState.value.board.shapes.last()
    assertEquals(StrokeStyle.TAPERED, updatedLineShape.strokeStyle)
    assertEquals(LineCapStyle.BOTH_ARROWS, updatedLineShape.capStyle)

    // Verify all 17 styles render cleanly in bitmap export
    StrokeStyle.values().forEachIndexed { idx, s ->
      viewModel.setPenStyle(s)
      viewModel.setPenCapStyle(LineCapStyle.END_ARROW)
      viewModel.commitCompletedStroke(
        listOf(StrokePoint(20f, 300f + idx * 25f), StrokePoint(240f, 320f + idx * 25f)),
        isHighlighter = false
      )
    }

    val json = BoardSnapshotSerializer.serializeBoard(viewModel.uiState.value.board)
    val restored = BoardSnapshotSerializer.deserializeIntoBoard(json, viewModel.uiState.value.board)
    assertNotNull(restored)
    assertEquals(StrokeStyle.RAILWAY, restored!!.strokes.first().strokeStyle)
    assertEquals(LineCapStyle.END_ARROW, restored.strokes.first().capStyle)
    assertEquals(StrokeStyle.TAPERED, restored.shapes.first().strokeStyle)
    assertEquals(LineCapStyle.BOTH_ARROWS, restored.shapes.first().capStyle)

    val bitmap = BoardExportService.renderBoardToBitmap(restored, includeBackground = true, includeGrid = true, maxDimensionPx = 900)
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
  }

  @Test
  fun `test bug fixes for recognizer caps line vertex rotation multiselect reset and arrow exports`() {
    // 1. ShapeRecognizer preserves capStyle when converting freehand straight stroke to LINE
    val straightPts = (0..18).map { i -> StrokePoint(50f + i * 12f, 120f + i * 12f) }
    val recognized = ShapeRecognizer.recognizeStroke(
      WhiteboardStroke(
        points = straightPts,
        colorHex = "#2563EB",
        width = 6f,
        strokeStyle = StrokeStyle.CALLIGRAPHIC,
        capStyle = LineCapStyle.BOTH_ARROWS
      )
    )
    assertNotNull(recognized)
    assertEquals(ShapeType.LINE, recognized!!.shapeType)
    assertEquals(LineCapStyle.BOTH_ARROWS, recognized.shapeItem.capStyle)
    assertEquals(StrokeStyle.CALLIGRAPHIC, recognized.shapeItem.strokeStyle)

    // 2. Rotating a ShapeType.LINE rotates its vertices directly and keeps rotation == 0f
    val viewModel = WhiteboardViewModel()
    viewModel.requestAddCustomShape(
      type = ShapeType.LINE,
      isFilled = false,
      strokeColor = "#0F172A",
      fillColor = "#DBEAFE",
      style = StrokeStyle.SOLID,
      capStyle = LineCapStyle.END_ARROW
    )
    viewModel.placePendingShapeInBounds(StrokePoint(100f, 200f), StrokePoint(300f, 200f))
    val initialLine = viewModel.uiState.value.board.shapes.last()
    viewModel.selectShape(initialLine.id)
    viewModel.rotateSelectedShape(90f)
    val rotatedLine = viewModel.uiState.value.board.shapes.last()
    assertEquals(0f, rotatedLine.rotation, 0.001f)
    assertEquals(200f, rotatedLine.vertices[0].x, 0.5f)
    assertEquals(100f, rotatedLine.vertices[0].y, 0.5f)
    assertEquals(200f, rotatedLine.vertices[1].x, 0.5f)
    assertEquals(300f, rotatedLine.vertices[1].y, 0.5f)

    // 3. Multi-selection resets on undo/redo/clearBoard and batch operations push a single undo snapshot
    viewModel.commitCompletedStroke(
      listOf(StrokePoint(400f, 100f), StrokePoint(480f, 180f)),
      isHighlighter = false
    )
    val selectedViaLasso = viewModel.selectByLasso(
      listOf(
        StrokePoint(50f, 50f),
        StrokePoint(550f, 50f),
        StrokePoint(550f, 350f),
        StrokePoint(50f, 350f)
      )
    )
    assertTrue(selectedViaLasso)
    assertTrue(viewModel.uiState.value.selectedStrokeIds.isNotEmpty())
    assertTrue(viewModel.uiState.value.selectedShapeIds.isNotEmpty())

    viewModel.duplicateCurrentSelection()
    assertEquals(2, viewModel.uiState.value.board.strokes.size)
    assertEquals(2, viewModel.uiState.value.board.shapes.size)
    viewModel.undo()
    assertEquals(1, viewModel.uiState.value.board.strokes.size)
    assertEquals(1, viewModel.uiState.value.board.shapes.size)
    assertTrue(viewModel.uiState.value.selectedStrokeIds.isEmpty())
    assertTrue(viewModel.uiState.value.selectedShapeIds.isEmpty())
    assertTrue(viewModel.uiState.value.selectedNoteIds.isEmpty())

    viewModel.redo()
    assertEquals(2, viewModel.uiState.value.board.strokes.size)
    assertTrue(viewModel.uiState.value.selectedStrokeIds.isEmpty())

    viewModel.selectByLasso(
      listOf(
        StrokePoint(50f, 50f),
        StrokePoint(550f, 50f),
        StrokePoint(550f, 350f),
        StrokePoint(50f, 350f)
      )
    )
    viewModel.clearBoard()
    assertTrue(viewModel.uiState.value.selectedStrokeIds.isEmpty())
    assertTrue(viewModel.uiState.value.selectedShapeIds.isEmpty())

    // 4. Bitmap export handles ShapeType.ARROW / DOUBLE_ARROW, 45-deg CALLIGRAPHIC, and zero-length DYNAMIC_GRADIENT safely
    viewModel.setColorTheme(CanvasColorTheme.CHARCOAL)
    val exportBoard = viewModel.uiState.value.board.copy(
      strokes = listOf(
        WhiteboardStroke(
          points = listOf(StrokePoint(40f, 40f), StrokePoint(140f, 140f)),
          colorHex = "#38BDF8",
          width = 10f,
          strokeStyle = StrokeStyle.CALLIGRAPHIC,
          capStyle = LineCapStyle.END_ARROW
        ),
        WhiteboardStroke(
          points = listOf(StrokePoint(80f, 80f), StrokePoint(80f, 80f)),
          colorHex = "#F43F5E",
          width = 8f,
          strokeStyle = StrokeStyle.DYNAMIC_GRADIENT,
          capStyle = LineCapStyle.START_ARROW
        )
      ),
      shapes = listOf(
        ShapeItem(
          shapeType = ShapeType.ARROW,
          x = 60f,
          y = 200f,
          width = 180f,
          height = 60f,
          strokeColorHex = "#22D3EE",
          strokeWidth = 6f,
          strokeStyle = StrokeStyle.NEON_GLOW
        ),
        ShapeItem(
          shapeType = ShapeType.DOUBLE_ARROW,
          x = 60f,
          y = 290f,
          width = 180f,
          height = 60f,
          strokeColorHex = "#F59E0B",
          strokeWidth = 6f,
          strokeStyle = StrokeStyle.RAILWAY
        )
      )
    )
    val exportBitmap = BoardExportService.renderBoardToBitmap(exportBoard, includeBackground = true, includeGrid = false, maxDimensionPx = 800)
    assertTrue(exportBitmap.width > 0 && exportBitmap.height > 0)
  }

  @Test
  fun `test physical icon dock has no tools dropdown and separates hand pan button`() {
    val viewModel = WhiteboardViewModel()
    composeTestRule.setContent {
      val state by viewModel.uiState.collectAsState()
      MaterialTheme {
        WhiteboardPenDock(
          currentTool = state.currentTool,
          currentPenColor = state.penColorHex,
          currentPenWidth = state.penWidth,
          currentPenStyle = state.penStyle,
          currentPenCapStyle = state.penCapStyle,
          isRainbowPen = state.isRainbowPen,
          autoShapeRecognition = state.autoShapeRecognition,
          isRulerActive = state.isRulerActive,
          pendingShapeType = state.pendingShapeType,
          onSelectTool = { viewModel.setTool(it) },
          onUpdatePenColor = { hex, rainbow -> viewModel.setPenColor(hex, rainbow) },
          onUpdatePenWidth = { viewModel.setPenWidth(it) },
          onUpdatePenStyle = { viewModel.setPenStyle(it) },
          onUpdatePenCapStyle = { viewModel.setPenCapStyle(it) },
          onToggleAutoShape = { viewModel.toggleAutoShapeRecognition() },
          onToggleRuler = { viewModel.toggleRuler() },
          onOpenShapePicker = {},
          onAddStickyNote = {},
          onAddText = {},
          onOpenTemplates = {},
          onClearBoard = {}
        )
      }
    }

    // Old text-based tools dropdown must not exist
    composeTestRule.onNodeWithTag("dock_tools_menu_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("dock_tools_dropdown_menu").assertDoesNotExist()

    // Compact two-tier structure: upper inking tray + lower main bar + separated Hand button
    composeTestRule.onNodeWithTag("inking_tray_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dock_main_bar").assertIsDisplayed()
    val dockBounds = composeTestRule.onNodeWithTag("whiteboard_pen_dock").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val handBounds = composeTestRule.onNodeWithTag("dock_tool_hand").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue("Hand pan button must sit separated next to dock", handBounds.left > dockBounds.right)

    // Clicking physical tool icons directly switches tools without any dropdown
    composeTestRule.onNodeWithTag("dock_menu_tool_fine_marker").performScrollTo().performClick()
    assertEquals(ToolType.FINE_MARKER, viewModel.uiState.value.currentTool)

    composeTestRule.onNodeWithTag("dock_menu_tool_highlighter").performScrollTo().performClick()
    assertEquals(ToolType.HIGHLIGHTER, viewModel.uiState.value.currentTool)

    composeTestRule.onNodeWithTag("dock_menu_tool_laser").performScrollTo().performClick()
    assertEquals(ToolType.LASER_POINTER, viewModel.uiState.value.currentTool)

    composeTestRule.onNodeWithTag("dock_menu_tool_eraser").performScrollTo().performClick()
    assertEquals(ToolType.ERASER, viewModel.uiState.value.currentTool)

    composeTestRule.onNodeWithTag("dock_menu_toggle_ruler").performScrollTo().performClick()
    assertTrue(viewModel.uiState.value.isRulerActive)

    // Closing the upper tray via X hides it, and pen toggle button in lower bar restores it
    composeTestRule.onNodeWithTag("dock_close_inking_tray").performClick()
    composeTestRule.onNodeWithTag("inking_tray_card").assertDoesNotExist()
    composeTestRule.onNodeWithTag("dock_toggle_inking_tray").performClick()
    composeTestRule.onNodeWithTag("inking_tray_card").assertIsDisplayed()

    // Clicking separated Hand pan button switches straight to HAND
    composeTestRule.onNodeWithTag("dock_tool_hand").performClick()
    assertEquals(ToolType.HAND, viewModel.uiState.value.currentTool)
  }

  @Test
  fun `test all preset shapes and auto-shape recognition honor chosen tool and non-solid line style`() {
    // 1. Auto-shape recognition works for ALL drawing tools (including BRUSH, HIGHLIGHTER, FINE_MARKER)
    //    and preserves chosen non-solid StrokeStyles + brush opacity.
    val viewModel = WhiteboardViewModel()
    viewModel.toggleAutoShapeRecognition()
    assertTrue(viewModel.uiState.value.autoShapeRecognition)

    val circlePts = (0..48).map { i ->
      val angle = (2.0 * PI * i) / 48.0
      StrokePoint(300f + 110f * cos(angle).toFloat(), 300f + 110f * sin(angle).toFloat())
    }

    val toolStylePairs = listOf(
      ToolType.BRUSH to StrokeStyle.ZIGZAG,
      ToolType.HIGHLIGHTER to StrokeStyle.NEON_GLOW,
      ToolType.FINE_MARKER to StrokeStyle.DOUBLE,
      ToolType.PEN to StrokeStyle.WAVY
    )

    toolStylePairs.forEach { (tool, style) ->
      val prevCount = viewModel.uiState.value.board.shapes.size
      viewModel.setTool(tool)
      viewModel.setPenStyle(style)
      viewModel.commitCompletedStroke(circlePts, isHighlighter = (tool == ToolType.HIGHLIGHTER))

      assertEquals("Tool $tool with style $style should auto-convert circle", prevCount + 1, viewModel.uiState.value.board.shapes.size)
      val shape = viewModel.uiState.value.board.shapes.last()
      assertEquals(ShapeType.CIRCLE, shape.shapeType)
      assertEquals(style, shape.strokeStyle)
    }

    // Verify sharp-corner square drawn with FINE_MARKER (high stabilization) + SQUARE_WAVE still recognizes cleanly
    val sqPts = mutableListOf<StrokePoint>()
    val corners = listOf(
      StrokePoint(100f, 100f),
      StrokePoint(300f, 100f),
      StrokePoint(300f, 300f),
      StrokePoint(100f, 300f)
    )
    for (edge in 0 until 4) {
      val a = corners[edge]
      val b = corners[(edge + 1) % 4]
      for (step in 0..14) {
        val t = step / 15f
        sqPts.add(StrokePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
      }
    }
    sqPts.add(corners[0])
    viewModel.setTool(ToolType.FINE_MARKER)
    viewModel.setPenStyle(StrokeStyle.SQUARE_WAVE)
    viewModel.commitCompletedStroke(sqPts, isHighlighter = false)
    val sqShape = viewModel.uiState.value.board.shapes.last()
    assertEquals(ShapeType.RECTANGLE, sqShape.shapeType)
    assertEquals(StrokeStyle.SQUARE_WAVE, sqShape.strokeStyle)

    // 2. ShapePickerBottomSheet forwards chosen StrokeStyle to ALL 12 preset ShapeTypes (closed + open)
    var pickedShapeType: ShapeType? = null
    var pickedStyle: StrokeStyle? = null
    composeTestRule.setContent {
      MaterialTheme {
        com.example.ui.canvas.ShapePickerBottomSheet(
          initialColorHex = "#2563EB",
          initialStrokeStyle = StrokeStyle.NEON_GLOW,
          initialCapStyle = LineCapStyle.END_ARROW,
          onSelectShapeType = { type, _, _, _, style, _ ->
            pickedShapeType = type
            pickedStyle = style
          },
          onDismiss = {}
        )
      }
    }
    composeTestRule.onNodeWithTag("preset_shape_${ShapeType.STAR.name}").performScrollTo().performClick()
    assertEquals(ShapeType.STAR, pickedShapeType)
    assertEquals(StrokeStyle.NEON_GLOW, pickedStyle)

    // 3. All 12 ShapeTypes x all 17 StrokeStyles serialize & render cleanly to Bitmap
    val styles = StrokeStyle.values()
    val allPresetShapes = ShapeType.values().flatMapIndexed { sIdx, shapeType ->
      styles.mapIndexed { stIdx, style ->
        ShapeItem(
          shapeType = shapeType,
          x = 40f + stIdx * 90f,
          y = 40f + sIdx * 90f,
          width = 72f,
          height = 56f,
          strokeColorHex = "#2563EB",
          fillColorHex = "#DBEAFE",
          strokeWidth = 4f,
          isFilled = stIdx % 2 == 0,
          strokeStyle = style
        )
      }
    }
    val matrixBoard = viewModel.uiState.value.board.copy(shapes = allPresetShapes)
    val bitmap = BoardExportService.renderBoardToBitmap(matrixBoard, includeBackground = true, includeGrid = false, maxDimensionPx = 1200)
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
  }

  @Test
  fun `test pixel eraser erases auto-created shapes and splits when completely separated`() {
    val viewModel = WhiteboardViewModel()
    viewModel.toggleAutoShapeRecognition()
    viewModel.setTool(ToolType.PEN)
    viewModel.setPenStyle(StrokeStyle.NEON_GLOW)
    viewModel.setPenWidth(6f)

    // 1. Auto-create a CIRCLE centered around (300, 300) with radius ~110
    val circlePts = (0..48).map { i ->
      val angle = (2.0 * PI * i) / 48.0
      StrokePoint(300f + 110f * cos(angle).toFloat(), 300f + 110f * sin(angle).toFloat())
    }
    viewModel.commitCompletedStroke(circlePts, isHighlighter = false)
    assertEquals(1, viewModel.uiState.value.board.shapes.size)
    assertEquals(0, viewModel.uiState.value.board.strokes.size)
    val circle = viewModel.uiState.value.board.shapes.single()
    assertEquals(ShapeType.CIRCLE, circle.shapeType)
    assertEquals(StrokeStyle.NEON_GLOW, circle.strokeStyle)

    // First cut at the left side (vertex index n/2, NOT vertex 0) should open 1 continuous C-arc (not split yet!)
    val leftEdgeX = circle.x
    val midY = circle.y + circle.height / 2f
    assertTrue(viewModel.erasePixelAt(leftEdgeX, midY, radius = 22f, saveSnapshot = true))
    assertEquals(0, viewModel.uiState.value.board.shapes.size)
    assertEquals(
      "Opening a closed auto-shape in one place must keep 1 continuous stroke",
      1,
      viewModel.uiState.value.board.strokes.size
    )
    assertEquals(StrokeStyle.NEON_GLOW, viewModel.uiState.value.board.strokes.single().strokeStyle)

    // Second cut across the opposite right edge must completely separate it into 2 distinct strokes
    val rightEdgeX = circle.x + circle.width
    assertTrue(viewModel.erasePixelAt(rightEdgeX, midY, radius = 22f, saveSnapshot = false))
    val dividedCircleParts = viewModel.uiState.value.board.strokes
    assertEquals(
      "Erasing opposite edge must split the auto-shape into 2 independent strokes",
      2,
      dividedCircleParts.size
    )
    assertTrue(dividedCircleParts[0].id != dividedCircleParts[1].id)
    assertTrue(dividedCircleParts.all { it.strokeStyle == StrokeStyle.NEON_GLOW })

    // Undo restores the original closed ShapeItem
    viewModel.undo()
    assertEquals(1, viewModel.uiState.value.board.shapes.size)
    assertEquals(0, viewModel.uiState.value.board.strokes.size)

    // 2. Auto-create a straight LINE with BOTH_ARROWS & DOUBLE style -> erase middle -> splits cleanly into 2 lines
    viewModel.clearBoard()
    viewModel.setPenStyle(StrokeStyle.DOUBLE)
    viewModel.setPenCapStyle(LineCapStyle.BOTH_ARROWS)
    val linePts = (0..12).map { i -> StrokePoint(100f + i * 25f, 200f) }
    viewModel.commitCompletedStroke(linePts, isHighlighter = false)
    assertEquals(1, viewModel.uiState.value.board.shapes.size)
    val autoLine = viewModel.uiState.value.board.shapes.single()
    assertEquals(ShapeType.LINE, autoLine.shapeType)
    assertEquals(LineCapStyle.BOTH_ARROWS, autoLine.capStyle)

    assertTrue(viewModel.erasePixelAt(250f, 200f, radius = 24f, saveSnapshot = true))
    assertEquals(0, viewModel.uiState.value.board.shapes.size)
    val splitLineParts = viewModel.uiState.value.board.strokes
    assertEquals(2, splitLineParts.size)
    assertEquals(LineCapStyle.START_ARROW, splitLineParts[0].capStyle)
    assertEquals(LineCapStyle.END_ARROW, splitLineParts[1].capStyle)
    assertTrue(splitLineParts.all { it.strokeStyle == StrokeStyle.DOUBLE })

    // 3. Auto-create a RECTANGLE -> fast swept drag erasePixelSegment top-to-bottom splits it into 2 halves
    viewModel.clearBoard()
    viewModel.setPenStyle(StrokeStyle.ZIGZAG)
    viewModel.setPenCapStyle(LineCapStyle.NONE)
    val rectCorners = listOf(
      StrokePoint(100f, 100f),
      StrokePoint(300f, 100f),
      StrokePoint(300f, 260f),
      StrokePoint(100f, 260f)
    )
    val rectPts = mutableListOf<StrokePoint>()
    for (edge in 0 until 4) {
      val a = rectCorners[edge]
      val b = rectCorners[(edge + 1) % 4]
      for (step in 0..14) {
        val t = step / 15f
        rectPts.add(StrokePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
      }
    }
    rectPts.add(rectCorners[0])
    viewModel.commitCompletedStroke(rectPts, isHighlighter = false)
    assertEquals(1, viewModel.uiState.value.board.shapes.size)

    assertTrue(
      viewModel.erasePixelSegment(
        from = StrokePoint(200f, 70f),
        to = StrokePoint(200f, 290f),
        radius = 20f,
        saveSnapshot = true
      )
    )
    assertEquals(0, viewModel.uiState.value.board.shapes.size)
    val rectHalves = viewModel.uiState.value.board.strokes
    assertEquals(2, rectHalves.size)
    assertTrue(rectHalves.all { it.strokeStyle == StrokeStyle.ZIGZAG })
  }

  @Test
  fun `test ideation templates modal search categories blank canvas and new board toggle`() {
    var showOnNewBoard = false
    var startedBlank = false
    var pickedTemplate: TemplateType? = null
    var dismissed = false

    composeTestRule.setContent {
      MaterialTheme {
        IdeationTemplatesDialog(
          showOnNewBoard = showOnNewBoard,
          onToggleShowOnNewBoard = { showOnNewBoard = it },
          onStartBlankCanvas = { startedBlank = true },
          onSelectTemplate = { pickedTemplate = it },
          onDismiss = { dismissed = true }
        )
      }
    }

    composeTestRule.onNodeWithTag("template_picker_modal").assertIsDisplayed()
    composeTestRule.onNodeWithTag("template_card_${TemplateType.BRAINSTORM_CLUSTER.name}").assertIsDisplayed()

    // Filter by search input
    composeTestRule.onNodeWithTag("template_search_input").performTextInput("Ishikawa")
    composeTestRule.onNodeWithTag("template_card_${TemplateType.ISHIKAWA.name}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("template_card_${TemplateType.BRAINSTORM_CLUSTER.name}").assertDoesNotExist()

    // Clear search by selecting a category directly
    composeTestRule.onNodeWithTag("template_cat_${TemplateCategory.STRATEGY.name}").performClick()
    composeTestRule.onNodeWithTag("template_card_${TemplateType.IMPACT_EFFORT.name}").assertIsDisplayed()

    // Toggle "Mostrar para nuevas pizarras"
    composeTestRule.onNodeWithTag("checkbox_show_templates_new_board").performClick()
    assertTrue(showOnNewBoard)

    // Pick Impact/Effort template
    composeTestRule.onNodeWithTag("template_card_${TemplateType.IMPACT_EFFORT.name}").performClick()
    assertEquals(TemplateType.IMPACT_EFFORT, pickedTemplate)
    assertTrue(dismissed)

    // Blank canvas trigger
    composeTestRule.onNodeWithTag("start_blank_canvas_button").performClick()
    assertTrue(startedBlank)
  }

  @Test
  fun `test all ideation templates generate 8 to 12 unlocked native elements and export cleanly`() {
    val viewModel = WhiteboardViewModel()

    // Every built-in template must create strictly 8..12 independent unlocked native elements
    TemplateType.values().forEach { tmpl ->
      viewModel.clearBoard()
      viewModel.applyTemplate(tmpl, 1080f, 1920f)
      val board = viewModel.uiState.value.board
      val totalElements = board.shapes.size + board.stickyNotes.size + board.textItems.size
      assertTrue(
        "Template $tmpl must generate 8..12 native elements, got $totalElements",
        totalElements in 8..12
      )
      assertTrue("Template $tmpl must not create TextItem overlays", board.textItems.isEmpty())
      assertTrue("All shapes in $tmpl must be unlocked", board.shapes.none { it.isLocked })
      assertTrue("All sticky notes in $tmpl must be unlocked", board.stickyNotes.none { it.isLocked })
      assertTrue("No rigid templateRole should remain in $tmpl", board.shapes.all { it.templateRole == null })
    }

    // Verify full 100% editability and erasability on an applied template (e.g. IMPACT_EFFORT)
    viewModel.clearBoard()
    viewModel.applyTemplate(TemplateType.IMPACT_EFFORT, 1080f, 1920f)
    val matrixBoard = viewModel.uiState.value.board
    assertEquals(2, matrixBoard.shapes.count { it.shapeType == ShapeType.LINE })
    assertEquals(4, matrixBoard.shapes.count { it.shapeType == ShapeType.ROUNDED_RECT })
    assertEquals(4, matrixBoard.stickyNotes.size)

    // Individual node label editing, color change, move, and object-eraser deletion must work cleanly
    val headerShape = matrixBoard.shapes.first { it.shapeType == ShapeType.ROUNDED_RECT }
    viewModel.updateShapeLabel(headerShape.id, "Prioridad Alta")
    assertEquals("Prioridad Alta", viewModel.uiState.value.board.shapes.first { it.id == headerShape.id }.label)

    viewModel.selectShape(headerShape.id)
    viewModel.updateSelectedShape(strokeColor = "#DC2626")
    assertEquals("#DC2626", viewModel.uiState.value.board.shapes.first { it.id == headerShape.id }.strokeColorHex)

    assertTrue(
      "Unlocked template shapes must be erasable via normal object eraser",
      viewModel.eraseObjectAt(headerShape.x + 20f, headerShape.y + 20f, 30f)
    )
    assertFalse(viewModel.uiState.value.board.shapes.any { it.id == headerShape.id })

    // Verify sticky note editing and snapshot serialization + PNG bitmap export on ISHIKAWA
    viewModel.clearBoard()
    viewModel.applyTemplate(TemplateType.ISHIKAWA, 1080f, 1920f)
    val firstNote = viewModel.uiState.value.board.stickyNotes.first()
    viewModel.updateStickyNoteTitle(firstNote.id, "Método actualizado")
    assertEquals("Método actualizado", viewModel.uiState.value.board.stickyNotes.first { it.id == firstNote.id }.title)

    val json = BoardSnapshotSerializer.serializeBoard(viewModel.uiState.value.board)
    val restored = BoardSnapshotSerializer.deserializeIntoBoard(json, viewModel.uiState.value.board)
    assertNotNull(restored)
    assertEquals(4, restored!!.stickyNotes.size)
    assertEquals(6, restored.shapes.size)
    val bmp = BoardExportService.renderBoardToBitmap(restored, includeBackground = true, includeGrid = false, maxDimensionPx = 900)
    assertTrue(bmp.width > 0 && bmp.height > 0)
  }

  @Test
  fun `test mis plantillas tab sits below recomendado saves applies and deletes custom templates`() {
    // 1. Verify category ordering: MY_TEMPLATES is immediately below RECOMMENDED and before BRAINSTORMING
    val categories = TemplateCategory.values().toList()
    assertEquals(TemplateCategory.RECOMMENDED, categories[0])
    assertEquals(TemplateCategory.MY_TEMPLATES, categories[1])
    assertEquals("Mis plantillas", TemplateCategory.MY_TEMPLATES.title)
    assertEquals(TemplateCategory.BRAINSTORMING, categories[2])

    val viewModel = WhiteboardViewModel()
    viewModel.openBoard(viewModel.uiState.value.allBoards.first().id)
    viewModel.applyTemplate(TemplateType.IMPACT_EFFORT, 1080f, 1920f)
    val sourceShapeIds = viewModel.uiState.value.board.shapes.map { it.id }.toSet()

    composeTestRule.setContent {
      val state by viewModel.uiState.collectAsState()
      MaterialTheme {
        IdeationTemplatesDialog(
          showOnNewBoard = state.showTemplatesOnNewBoard,
          onToggleShowOnNewBoard = { viewModel.toggleShowTemplatesOnNewBoard(it) },
          onStartBlankCanvas = {},
          onSelectTemplate = {},
          onDismiss = {},
          customTemplates = state.customTemplates,
          currentBoardTitle = state.board.title,
          onSaveCurrentAsTemplate = { title, desc ->
            viewModel.saveCurrentBoardAsTemplate(title, desc)
          },
          onSelectCustomTemplate = { viewModel.applyCustomTemplate(it) },
          onDeleteCustomTemplate = { viewModel.deleteCustomTemplate(it) }
        )
      }
    }

    // Verify visual ordering in compact single-pane layout (horizontal LazyRow immediately after Recomendado)
    val recBounds = composeTestRule.onNodeWithTag("template_cat_${TemplateCategory.RECOMMENDED.name}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val myBounds = composeTestRule.onNodeWithTag("template_cat_${TemplateCategory.MY_TEMPLATES.name}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val brainBounds = composeTestRule.onNodeWithTag("template_cat_${TemplateCategory.BRAINSTORMING.name}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue(
      "Mis plantillas must appear immediately after Recomendado",
      (myBounds.left >= recBounds.right - 1f && brainBounds.left >= myBounds.right - 1f) ||
        (myBounds.top >= recBounds.bottom - 1f && brainBounds.top >= myBounds.bottom - 1f)
    )

    // Switch to "Mis plantillas" -> initially shows empty state with save CTA
    composeTestRule.onNodeWithTag("template_cat_${TemplateCategory.MY_TEMPLATES.name}").performClick()
    composeTestRule.onNodeWithTag("my_templates_empty_state").assertIsDisplayed()

    // Save current board as a custom template
    composeTestRule.onNodeWithTag("btn_empty_save_custom_template").performClick()
    composeTestRule.onNodeWithTag("save_custom_template_form").assertIsDisplayed()
    composeTestRule.onNodeWithTag("input_custom_template_name").performTextReplacement("Mi Sprint Base")
    composeTestRule.onNodeWithTag("input_custom_template_desc").performTextInput("Matriz lista para el equipo")
    composeTestRule.onNodeWithTag("btn_confirm_save_custom_template").performClick()

    assertEquals(1, viewModel.uiState.value.customTemplates.size)
    val savedTemplate = viewModel.uiState.value.customTemplates.first()
    assertEquals("Mi Sprint Base", savedTemplate.title)
    composeTestRule.onNodeWithTag("custom_template_card_${savedTemplate.id}").assertIsDisplayed()

    // Create a brand new blank board and apply the saved custom template
    viewModel.createNewBoard("Pizarra Nueva", openImmediately = true)
    assertTrue(viewModel.uiState.value.board.shapes.isEmpty())

    composeTestRule.onNodeWithTag("custom_template_card_${savedTemplate.id}").performClick()
    assertEquals(sourceShapeIds.size, viewModel.uiState.value.board.shapes.size)
    val appliedShapeIds = viewModel.uiState.value.board.shapes.map { it.id }.toSet()
    assertTrue("Applied template elements must receive fresh independent IDs", (sourceShapeIds intersect appliedShapeIds).isEmpty())

    // Undo restores the blank board
    viewModel.undo()
    assertTrue(viewModel.uiState.value.board.shapes.isEmpty())

    // Delete the custom template via UI button
    composeTestRule.onNodeWithTag("btn_delete_custom_template_${savedTemplate.id}").performClick()
    assertTrue(viewModel.uiState.value.customTemplates.isEmpty())
    composeTestRule.onNodeWithTag("my_templates_empty_state").assertIsDisplayed()
  }

  @Test
  fun `test topbar overflow menu removes duplicated snapshots entry`() {
    composeTestRule.setContent {
      MaterialTheme {
        WhiteboardTopBar(
          boardTitle = "Pizarra Principal",
          scale = 1.0f,
          canUndo = false,
          canRedo = false,
          isSnapToGrid = false,
          onUndo = {},
          onRedo = {},
          onZoomIn = {},
          onZoomOut = {},
          onResetZoom = {},
          onFitToContent = {},
          onSetZoomPreset = {},
          onToggleSnapToGrid = {},
          onEnterZenMode = {},
          onOpenBoardsManager = {},
          onOpenBackgroundSettings = {},
          onClearBoard = {},
          onExportBoard = {},
          onRenameBoard = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("btn_topbar_more_menu").performClick()
    composeTestRule.onNodeWithTag("topbar_dropdown_menu").assertIsDisplayed()
    composeTestRule.onNodeWithText("Exportar pizarra").assertIsDisplayed()
    composeTestRule.onNodeWithText("Snapshots / Versiones").assertDoesNotExist()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w800dp-h600dp")
  fun `test two pane templates sidebar places mis plantillas directly below recomendado`() {
    composeTestRule.setContent {
      MaterialTheme {
        IdeationTemplatesDialog(
          showOnNewBoard = false,
          onToggleShowOnNewBoard = {},
          onStartBlankCanvas = {},
          onSelectTemplate = {},
          onDismiss = {},
          onSaveCurrentAsTemplate = { _, _ -> }
        )
      }
    }

    val recBounds = composeTestRule.onNodeWithTag("template_cat_${TemplateCategory.RECOMMENDED.name}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val myBounds = composeTestRule.onNodeWithTag("template_cat_${TemplateCategory.MY_TEMPLATES.name}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val brainBounds = composeTestRule.onNodeWithTag("template_cat_${TemplateCategory.BRAINSTORMING.name}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertTrue(
      "Mis plantillas must appear directly below Recomendado in two-pane sidebar",
      myBounds.top >= recBounds.bottom - 1f && brainBounds.top >= myBounds.bottom - 1f
    )
  }

  @Test
  fun `test sticky note world coordinates and viewport resize preserve aspect ratio and distances`() {
    val cam = ViewportTransform(
      scale = 1.5f,
      panX = 120f,
      panY = 80f,
      viewportWidthPx = 1080f,
      viewportHeightPx = 1920f
    )
    val noteA = StickyNote(id = "a", text = "A", x = 100f, y = 200f, width = 220f, height = 160f)
    val noteB = StickyNote(id = "b", text = "B", x = 420f, y = 200f, width = 220f, height = 160f)

    // World aspect ratio & screen aspect ratio must stay identical
    val screenW = cam.worldLengthToScreenPx(noteA.width)
    val screenH = cam.worldLengthToScreenPx(noteA.height)
    assertEquals(noteA.width / noteA.height, screenW / screenH, 0.0001f)

    val distWorld = noteB.x - noteA.x
    val distScreenBefore = cam.worldToScreenX(noteB.x) - cam.worldToScreenX(noteA.x)
    assertEquals(distWorld * cam.scale, distScreenBefore, 0.001f)

    // Viewport resize (e.g., portrait -> landscape) preserves world center, scale, aspect ratio, and relative distances
    val resizedCam = cam.adaptToViewportResize(newWidthPx = 1920f, newHeightPx = 1080f)
    assertEquals(cam.scale, resizedCam.scale, 0.0001f)
    val worldCenterBeforeX = cam.screenToWorldX(cam.viewportWidthPx / 2f)
    val worldCenterBeforeY = cam.screenToWorldY(cam.viewportHeightPx / 2f)
    val worldCenterAfterX = resizedCam.screenToWorldX(resizedCam.viewportWidthPx / 2f)
    val worldCenterAfterY = resizedCam.screenToWorldY(resizedCam.viewportHeightPx / 2f)
    assertEquals(worldCenterBeforeX, worldCenterAfterX, 0.01f)
    assertEquals(worldCenterBeforeY, worldCenterAfterY, 0.01f)

    val distScreenAfter = resizedCam.worldToScreenX(noteB.x) - resizedCam.worldToScreenX(noteA.x)
    assertEquals(distScreenBefore, distScreenAfter, 0.001f)
  }

  @Test
  fun `test sticky note scale compensated handle resizing free and square aspect lock`() {
    val vm = WhiteboardViewModel()
    vm.openBoard(vm.uiState.value.allBoards.first().id)
    vm.addStickyNote(1080f, 1920f, "#FFF275")
    val note = vm.uiState.value.board.stickyNotes.first()
    vm.selectNote(note.id)

    // Zoom to 2.0x -> a +100px screen drag must resize world width/height by exactly +50 world units
    vm.onPanZoom(0f, 0f, zoomChange = 2.0f)
    assertEquals(2.0f, vm.uiState.value.scale, 0.001f)

    vm.beginTransformSnapshot()
    vm.resizeStickyNoteHandle(note.id, StickyNoteResizeHandle.BOTTOM_RIGHT, deltaScreenX = 100f, deltaScreenY = 60f)
    var updated = vm.uiState.value.board.stickyNotes.first { it.id == note.id }
    assertEquals(note.x, updated.x, 0.01f)
    assertEquals(note.y, updated.y, 0.01f)
    assertEquals(note.width + 50f, updated.width, 0.05f)
    assertEquals(note.height + 30f, updated.height, 0.05f)

    // Drag TOP_LEFT inward by (+40px, +20px) at scale=2.0 -> moves x/y by (+20, +10) while keeping bottom-right anchored
    val anchorRight = updated.x + updated.width
    val anchorBottom = updated.y + updated.height
    vm.resizeStickyNoteHandle(note.id, StickyNoteResizeHandle.TOP_LEFT, deltaScreenX = 40f, deltaScreenY = 20f)
    updated = vm.uiState.value.board.stickyNotes.first { it.id == note.id }
    assertEquals(anchorRight, updated.x + updated.width, 0.05f)
    assertEquals(anchorBottom, updated.y + updated.height, 0.05f)

    // Toggle square (1:1) aspect-ratio lock ON -> becomes square and edge/corner drags preserve 1:1
    vm.toggleStickyNoteAspectRatioLock(note.id)
    updated = vm.uiState.value.board.stickyNotes.first { it.id == note.id }
    assertTrue(updated.isAspectRatioLocked)
    assertEquals(updated.width, updated.height, 0.01f)

    vm.resizeStickyNoteHandle(note.id, StickyNoteResizeHandle.RIGHT, deltaScreenX = 120f, deltaScreenY = 0f)
    updated = vm.uiState.value.board.stickyNotes.first { it.id == note.id }
    assertEquals(updated.width, updated.height, 0.01f)
    assertTrue(updated.width > 200f)

    // Verify serializer round-trip preserves custom dimensions & aspect lock
    val json = BoardSnapshotSerializer.serializeBoard(vm.uiState.value.board)
    val restored = BoardSnapshotSerializer.deserializeIntoBoard(json, vm.uiState.value.board)!!
    val restoredNote = restored.stickyNotes.first { it.id == note.id }
    assertEquals(updated.width, restoredNote.width, 0.01f)
    assertEquals(updated.height, restoredNote.height, 0.01f)
    assertTrue(restoredNote.isAspectRatioLocked)

    // Verify scale-compensated math directly for TOP edge anchor preservation
    val topEdgeResized = StickyNoteGeometry.resizeByHandle(
      bounds = StickyNoteBounds(100f, 100f, 200f, 200f),
      handle = StickyNoteResizeHandle.TOP,
      deltaScreenX = 0f,
      deltaScreenY = -90f,
      scale = 3.0f,
      lockAspectRatio = false
    )
    assertEquals(70f, topEdgeResized.y, 0.01f)
    assertEquals(230f, topEdgeResized.height, 0.01f)
    assertEquals(300f, topEdgeResized.y + topEdgeResized.height, 0.01f)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w480dp-h800dp")
  fun `test sticky note ui renders 48dp resize handles and autofits font size proportionally`() {
    val noteState = androidx.compose.runtime.mutableStateOf(
      StickyNote(
        id = "note-autofit",
        text = "Idea corta",
        x = 40f,
        y = 40f,
        width = 160f,
        height = 140f
      )
    )

    composeTestRule.setContent {
      MaterialTheme {
        androidx.compose.foundation.layout.Box(
          modifier = androidx.compose.ui.Modifier.fillMaxSize()
        ) {
          StickyNoteView(
            note = noteState.value,
            scale = 1.0f,
            panX = 0f,
            panY = 0f,
            isSelected = true,
            onUpdateText = { noteState.value = noteState.value.copy(text = it) },
            onUpdateColor = {},
            onTogglePin = {},
            onDelete = {},
            onDragDelta = { _, _ -> }
          )
        }
      }
    }

    composeTestRule.waitForIdle()

    // All 8 resize handles must exist and meet >= 48.dp touch target (exceeding 44px mobile requirement)
    StickyNoteResizeHandle.values().forEach { handle ->
      val node = composeTestRule.onNodeWithTag("sticky_note_handle_${handle.name}_note-autofit")
      node.assertIsDisplayed()
      node.assertWidthIsAtLeast(48.dp)
      node.assertHeightIsAtLeast(48.dp)
    }

    val smallFontSp = composeTestRule
      .onNodeWithTag("sticky_note_input_note-autofit")
      .fetchSemanticsNode()
      .config[StickyNoteComputedFontSpKey]

    // Enlarge sticky note -> auto-fit fontSize must scale up proportionally
    noteState.value = noteState.value.copy(width = 360f, height = 320f)
    composeTestRule.waitForIdle()
    val enlargedFontSp = composeTestRule
      .onNodeWithTag("sticky_note_input_note-autofit")
      .fetchSemanticsNode()
      .config[StickyNoteComputedFontSpKey]
    assertTrue("Expected font size to scale up when note grows ($enlargedFontSp > $smallFontSp)", enlargedFontSp > smallFontSp)

    // Add long multi-line text -> auto-fit fontSize must shrink so text fits without overflow or scroll
    noteState.value = noteState.value.copy(
      text = "Este es un bloque de texto mucho más largo diseñado para comprobar que la fuente se reduce automáticamente dentro de la nota sin desbordarse ni requerir scroll."
    )
    composeTestRule.waitForIdle()
    val longTextFontSp = composeTestRule
      .onNodeWithTag("sticky_note_input_note-autofit")
      .fetchSemanticsNode()
      .config[StickyNoteComputedFontSpKey]
    assertTrue("Expected font size to shrink for long text ($longTextFontSp < $enlargedFontSp)", longTextFontSp < enlargedFontSp)
  }

  @Test
  fun `test hand pan tool disables object selection and pans without moving sticky notes`() {
    val vm = WhiteboardViewModel()
    vm.openBoard(vm.uiState.value.allBoards.first().id)
    vm.addStickyNote(1080f, 1920f, "#FFF275")
    val note = vm.uiState.value.board.stickyNotes.first()
    vm.selectNote(note.id)
    assertEquals(note.id, vm.uiState.value.selectedNoteId)

    // Activating standalone Hand tool immediately clears existing selection
    vm.setTool(ToolType.HAND)
    assertNull(vm.uiState.value.selectedNoteId)
    assertEquals(ToolType.HAND, vm.uiState.value.currentTool)

    composeTestRule.setContent {
      MaterialTheme {
        WhiteboardCanvasScreen(viewModel = vm)
      }
    }
    composeTestRule.waitForIdle()

    // Pan-only shield must be active
    composeTestRule.onNodeWithTag("pan_only_overlay").assertIsDisplayed()

    val initialPanX = vm.uiState.value.panX
    val initialNoteX = vm.uiState.value.board.stickyNotes.first().x

    // Dragging directly across the screen (including over note bounds) only pans the board, never selects or moves the note
    composeTestRule.onNodeWithTag("pan_only_overlay").performTouchInput {
      swipeRight(startX = centerX - 100f, endX = centerX + 120f)
    }
    composeTestRule.waitForIdle()

    assertNull("Hand pan tool must never select sticky notes", vm.uiState.value.selectedNoteId)
    assertNull("Hand pan tool must never select shapes", vm.uiState.value.selectedShapeId)
    assertNull("Hand pan tool must never select strokes", vm.uiState.value.selectedStrokeId)
    assertEquals("Note world X must stay untouched while panning", initialNoteX, vm.uiState.value.board.stickyNotes.first().x, 0.01f)
    assertTrue("Viewport panX must advance when dragging in Hand mode", vm.uiState.value.panX > initialPanX + 50f)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w480dp-h800dp")
  fun `test sticky note editable title rotation handle drag and zoomed line coordinates`() {
    val noteState = androidx.compose.runtime.mutableStateOf(
      StickyNote(
        id = "note-rot",
        title = "",
        text = "Cuerpo inicial",
        x = 80f,
        y = 140f,
        width = 220f,
        height = 180f,
        rotation = 0f
      )
    )
    var rotatedCallbackAngle: Float? = null

    composeTestRule.setContent {
      MaterialTheme {
        androidx.compose.foundation.layout.Box(
          modifier = androidx.compose.ui.Modifier.fillMaxSize()
        ) {
          StickyNoteView(
            note = noteState.value,
            scale = 1.0f,
            panX = 0f,
            panY = 0f,
            isSelected = true,
            onUpdateTitle = { noteState.value = noteState.value.copy(title = it) },
            onUpdateText = { noteState.value = noteState.value.copy(text = it) },
            onUpdateColor = {},
            onTogglePin = {},
            onDelete = {},
            onDragDelta = { _, _ -> },
            onRotate = { deg ->
              rotatedCallbackAngle = deg
              noteState.value = noteState.value.copy(rotation = deg)
            }
          )
        }
      }
    }

    composeTestRule.waitForIdle()

    // 1. Title input edits title cleanly without changing body text
    val titleInput = composeTestRule.onNodeWithTag("sticky_note_title_input_note-rot")
    titleInput.assertIsDisplayed()
    titleInput.performTextReplacement("Título separado")
    assertEquals("Título separado", noteState.value.title)
    assertEquals("Cuerpo inicial", noteState.value.text)

    // 2. Protruding rotation handle is visible, >= 48dp, unclipped, and dragging it rotates the note
    val rotHandle = composeTestRule.onNodeWithTag("sticky_note_rotation_handle_note-rot")
    rotHandle.assertIsDisplayed()
    rotHandle.assertWidthIsAtLeast(48.dp)
    rotHandle.assertHeightIsAtLeast(48.dp)
    rotHandle.performTouchInput {
      swipeRight(startX = centerX, endX = centerX + 120f)
    }
    composeTestRule.waitForIdle()
    assertNotNull("Dragging unclipped rotation handle must invoke onRotate", rotatedCallbackAngle)
    assertTrue("Rotation angle should change when dragging right", noteState.value.rotation > 1f)

    // 3. Adaptive height grows when title + long content require more vertical space
    val adaptedHeight = StickyNoteGeometry.calculateAdaptiveHeightWorld(
      text = "Línea 1\nLínea 2\nLínea 3\nLínea 4\nLínea 5\nLínea 6\nLínea 7\nLínea 8",
      noteWidthWorld = 200f,
      currentHeightWorld = 140f,
      isAspectRatioLocked = false,
      title = "Título largo de tarjeta"
    )
    assertTrue("Adaptive height should expand above 140f", adaptedHeight > 140f)

    // 4. ShapeCanvasCoordinates skips rotation for ARROW with 2 vertices and scales line handle offset with zoom
    val arrowShape = ShapeItem(
      shapeType = ShapeType.ARROW,
      x = 100f,
      y = 200f,
      width = 160f,
      height = 40f,
      rotation = 45f,
      vertices = listOf(StrokePoint(100f, 220f), StrokePoint(260f, 220f))
    )
    val (localX, localY) = com.example.ui.canvas.ShapeCanvasCoordinates.toLocalUnrotated(arrowShape, 260f, 220f)
    assertEquals(260f, localX, 0.001f)
    assertEquals(220f, localY, 0.001f)
    assertEquals(16f, com.example.ui.canvas.ShapeCanvasCoordinates.lineRotationHandleOffsetWorld(2.0f), 0.001f)
  }

  @Test
  fun `test note card displays origin board badge and open in board context menu`() {
    var openedBoardId: String? = null
    val sampleNote = com.example.notes.model.NoteItem(
      id = "card-board-1",
      title = "Título claro",
      content = "Contenido bien diferenciado",
      folder = "Pizarra Arquitectura",
      canvasData = com.example.notes.model.CanvasNoteData(boardId = "board-arch", x = 10f, y = 20f)
    )

    composeTestRule.setContent {
      MaterialTheme {
        com.example.notes.ui.NoteCardView(
          note = sampleNote,
          isUnlocked = true,
          originBoardName = "Pizarra Arquitectura",
          onClick = {},
          onTogglePin = {},
          onDelete = {},
          onOpenInBoard = { openedBoardId = "board-arch" }
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("note_board_badge_card-board-1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_note_menu_card-board-1").performClick()
    composeTestRule.onNodeWithTag("menu_open_in_board_card-board-1").performClick()
    assertEquals("board-arch", openedBoardId)
  }

  @Test
  fun `test shapes combine brush type with line style and serialize cleanly`() {
    val viewModel = WhiteboardViewModel()
    viewModel.openBoard(viewModel.uiState.value.allBoards.first().id)

    // 1. ToolType.HIGHLIGHTER + StrokeStyle.DASHED creates shape with both properties
    viewModel.setTool(ToolType.HIGHLIGHTER)
    viewModel.setPenStyle(StrokeStyle.DASHED)
    viewModel.requestAddCustomShape(
      type = ShapeType.RECTANGLE,
      isFilled = true,
      strokeColor = "#EAB308",
      fillColor = "#FEF08A",
      style = StrokeStyle.DASHED,
      capStyle = LineCapStyle.NONE
    )
    viewModel.placePendingShapeInBounds(
      start = StrokePoint(50f, 50f),
      end = StrokePoint(250f, 200f)
    )

    val createdShape = viewModel.uiState.value.board.shapes.last()
    assertEquals(ShapeType.RECTANGLE, createdShape.shapeType)
    assertEquals(StrokeBrushType.HIGHLIGHTER, createdShape.brushType)
    assertEquals(StrokeStyle.DASHED, createdShape.strokeStyle)
    assertTrue(createdShape.isFilled)

    // 2. Select shape and update brushType and strokeStyle independently or together
    viewModel.selectShape(createdShape.id)
    viewModel.updateSelectedShapeBrushType(StrokeBrushType.FINE_MARKER)
    var updated = viewModel.uiState.value.board.shapes.last()
    assertEquals(StrokeBrushType.FINE_MARKER, updated.brushType)
    assertEquals(StrokeStyle.DASHED, updated.strokeStyle)

    viewModel.updateSelectedShape(
      brushType = StrokeBrushType.SOFT_BRUSH,
      style = StrokeStyle.NEON_GLOW
    )
    updated = viewModel.uiState.value.board.shapes.last()
    assertEquals(StrokeBrushType.SOFT_BRUSH, updated.brushType)
    assertEquals(StrokeStyle.NEON_GLOW, updated.strokeStyle)

    // 3. ShapeRecognizer also inherits stroke.brushType
    val linePts = (0..16).map { i -> StrokePoint(100f + i * 15f, 300f) }
    val recognized = ShapeRecognizer.recognizeStroke(
      WhiteboardStroke(
        points = linePts,
        colorHex = "#3B82F6",
        width = 8f,
        strokeStyle = StrokeStyle.WAVY,
        brushType = StrokeBrushType.FINE_MARKER
      )
    )
    assertNotNull(recognized)
    assertEquals(ShapeType.LINE, recognized!!.shapeType)
    assertEquals(StrokeBrushType.FINE_MARKER, recognized.shapeItem.brushType)
    assertEquals(StrokeStyle.WAVY, recognized.shapeItem.strokeStyle)

    // 4. Board snapshot serializer round-trip preserves brushType
    val json = BoardSnapshotSerializer.serializeBoard(viewModel.uiState.value.board)
    val restored = BoardSnapshotSerializer.deserializeIntoBoard(json, viewModel.uiState.value.board)
    assertNotNull(restored)
    val restoredShape = restored!!.shapes.last()
    assertEquals(StrokeBrushType.SOFT_BRUSH, restoredShape.brushType)
    assertEquals(StrokeStyle.NEON_GLOW, restoredShape.strokeStyle)

    // 5. Bitmap export renders without error
    val bitmap = BoardExportService.renderBoardToBitmap(restored, includeBackground = true, includeGrid = true, maxDimensionPx = 600)
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
  }

  @Test
  fun `test brush type selectable in shape picker`() {
    var pickedBrush: StrokeBrushType? = null
    var pickedShape: ShapeType? = null

    composeTestRule.setContent {
      MaterialTheme {
        com.example.ui.canvas.ShapePickerBottomSheet(
          initialColorHex = "#2563EB",
          initialBrushType = StrokeBrushType.FOUNTAIN_PEN,
          onSelectBrushType = { pickedBrush = it },
          onSelectShapeType = { type, _, _, _, _, _ -> pickedShape = type },
          onDismiss = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("shape_picker_brush_${StrokeBrushType.FINE_MARKER.name}")
      .performScrollTo()
      .performClick()
    composeTestRule.onNodeWithTag("preset_shape_${ShapeType.HEXAGON.name}")
      .performScrollTo()
      .performClick()

    assertEquals(StrokeBrushType.FINE_MARKER, pickedBrush)
    assertEquals(ShapeType.HEXAGON, pickedShape)
  }

  @Test
  fun `test brush type selectable in selection edit dock for shape and stroke`() {
    val viewModel = WhiteboardViewModel()
    viewModel.openBoard(viewModel.uiState.value.allBoards.first().id)
    viewModel.requestAddCustomShape(
      type = ShapeType.RECTANGLE,
      isFilled = false,
      strokeColor = "#2563EB",
      fillColor = "#DBEAFE",
      style = StrokeStyle.SOLID,
      capStyle = LineCapStyle.NONE,
      brushType = StrokeBrushType.FOUNTAIN_PEN
    )
    viewModel.placePendingShapeInBounds(StrokePoint(80f, 80f), StrokePoint(240f, 180f))
    val shapeId = viewModel.uiState.value.board.shapes.last().id
    viewModel.selectShape(shapeId)

    var updatedShapeBrush: StrokeBrushType? = null
    composeTestRule.setContent {
      MaterialTheme {
        val st by viewModel.uiState.collectAsState()
        val selShape = st.board.shapes.find { it.id == st.selectedShapeId }
        val selStroke = st.board.strokes.find { it.id == st.selectedStrokeId }
        com.example.ui.canvas.SelectionEditDock(
          selectedShape = selShape,
          selectedStroke = selStroke,
          selectedNote = null,
          onUpdateShapeColor = {},
          onUpdateShapeFillColor = {},
          onToggleShapeFilled = {},
          onUpdateShapeWidth = {},
          onUpdateShapeStyle = {},
          onUpdateShapeBrushType = {
            updatedShapeBrush = it
            viewModel.updateSelectedShapeBrushType(it)
          },
          onScaleShape = {},
          onRotateShape = {},
          onResetShapeRotation = {},
          onBringShapeToFront = {},
          onSendShapeToBack = {},
          onDuplicateShape = {},
          onDeleteShape = {},
          onUpdateStrokeColor = {},
          onUpdateStrokeWidth = {},
          onUpdateStrokeStyle = {},
          onUpdateStrokeBrushType = { viewModel.updateSelectedStrokeBrushType(it) },
          onScaleStroke = {},
          onConvertStrokeToShape = {},
          onDuplicateStroke = {},
          onDeleteStroke = {},
          onUpdateNoteColor = {},
          onToggleNotePin = {},
          onDeleteNote = {},
          onDone = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("edit_dock_shape_brush_btn").performClick()
    composeTestRule.onNodeWithTag("quick_shape_brush_${StrokeBrushType.SOFT_BRUSH.name}").performClick()
    assertEquals(StrokeBrushType.SOFT_BRUSH, updatedShapeBrush)
    assertEquals(StrokeBrushType.SOFT_BRUSH, viewModel.uiState.value.board.shapes.last().brushType)

    // Switch selection to a freehand stroke within the same Composition and change its brush type
    viewModel.clearSelection()
    viewModel.setTool(ToolType.PEN)
    viewModel.commitCompletedStroke(
      listOf(StrokePoint(50f, 50f), StrokePoint(120f, 80f), StrokePoint(180f, 60f)),
      isHighlighter = false
    )
    val strokeId = viewModel.uiState.value.board.strokes.last().id
    viewModel.selectStroke(strokeId)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("edit_dock_stroke_brush_btn").performClick()
    composeTestRule.onNodeWithTag("quick_stroke_brush_${StrokeBrushType.HIGHLIGHTER.name}").performClick()
    assertEquals(StrokeBrushType.HIGHLIGHTER, viewModel.uiState.value.board.strokes.last().brushType)
  }

  @Test
  fun `test direct canvas rotation of shapes and strokes with snapping and single undo`() {
    val viewModel = WhiteboardViewModel()
    viewModel.openBoard(viewModel.uiState.value.allBoards.first().id)

    // 1. Shape rotation to point with 90-degree snap and arbitrary angle
    viewModel.requestAddCustomShape(
      type = ShapeType.RECTANGLE,
      isFilled = false,
      strokeColor = "#2563EB",
      fillColor = "#DBEAFE",
      style = StrokeStyle.SOLID,
      capStyle = LineCapStyle.NONE
    )
    viewModel.placePendingShapeInBounds(StrokePoint(100f, 100f), StrokePoint(300f, 200f))
    val shape = viewModel.uiState.value.board.shapes.last()
    viewModel.selectShape(shape.id)

    viewModel.beginTransformSnapshot()
    // Center is (200, 150). Point to the right (320, 152) is ~91 deg -> snaps cleanly to 90f
    viewModel.rotateSelectedShapeToPoint(320f, 152f)
    assertEquals(90f, viewModel.uiState.value.board.shapes.last().rotation, 0.01f)

    // Diagonal point (200 + 100, 150 - 100) is 45 deg
    viewModel.rotateSelectedShapeToPoint(300f, 50f)
    assertEquals(45f, viewModel.uiState.value.board.shapes.last().rotation, 0.5f)

    viewModel.undo()
    assertEquals(0f, viewModel.uiState.value.board.shapes.last().rotation, 0.01f)

    // 2. Stroke rotation around fixed pivot over multiple move steps preserves center and undoes in 1 step
    viewModel.clearSelection()
    viewModel.commitCompletedStroke(
      listOf(StrokePoint(100f, 100f), StrokePoint(200f, 100f), StrokePoint(200f, 140f)),
      isHighlighter = false
    )
    val stroke = viewModel.uiState.value.board.strokes.last()
    val origPts = stroke.points
    val origSegLen = kotlin.math.hypot(origPts[1].x - origPts[0].x, origPts[1].y - origPts[0].y)
    viewModel.selectStroke(stroke.id)

    viewModel.resetStrokeDragAngle()
    viewModel.beginTransformSnapshot()
    // Center is (150, 120). Drag from top (150, 40) -> right (230, 120) in two steps
    viewModel.rotateSelectedStrokeToPoint(150f, 40f)
    viewModel.rotateSelectedStrokeToPoint(230f, 120f)
    val rotatedPts = viewModel.uiState.value.board.strokes.last().points
    assertTrue(rotatedPts != origPts)
    // Length of first segment is preserved under rigid rotation (no drift/distortion)
    val segLen = kotlin.math.hypot(rotatedPts[1].x - rotatedPts[0].x, rotatedPts[1].y - rotatedPts[0].y)
    assertEquals(origSegLen, segLen, 0.5f)

    // Undo restores exact original points in one call
    viewModel.undo()
    val undonePts = viewModel.uiState.value.board.strokes.last().points
    assertEquals(origPts.size, undonePts.size)
    assertEquals(origPts[0].x, undonePts[0].x, 0.01f)
    assertEquals(origPts[0].y, undonePts[0].y, 0.01f)
  }
}


