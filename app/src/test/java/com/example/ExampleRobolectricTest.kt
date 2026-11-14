package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ink.ShapeRecognizer
import com.example.model.CanvasGridType
import com.example.model.ShapeType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.ToolType
import com.example.model.WhiteboardStroke
import com.example.viewmodel.WhiteboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
}
