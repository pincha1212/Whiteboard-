package com.example.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.ink.StickyNoteGeometry
import com.example.model.CanvasGridType
import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.WhiteboardBoard
import com.example.model.WhiteboardStroke
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object BoardExportService {

    fun renderBoardToBitmap(
        board: WhiteboardBoard,
        includeBackground: Boolean = true,
        includeGrid: Boolean = true,
        maxDimensionPx: Int = 1800
    ): Bitmap {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var hasContent = false

        board.strokes.forEach { s ->
            val strokePad = max(s.width * 2.2f, 24f)
            s.points.forEach { p ->
                hasContent = true
                minX = min(minX, p.x - strokePad)
                maxX = max(maxX, p.x + strokePad)
                minY = min(minY, p.y - strokePad)
                maxY = max(maxY, p.y + strokePad)
            }
        }
        board.shapes.forEach { sh ->
            hasContent = true
            val shapePad = max(sh.strokeWidth * 2.2f, 18f)
            minX = min(minX, sh.x - shapePad)
            maxX = max(maxX, sh.x + sh.width + shapePad)
            minY = min(minY, sh.y - shapePad)
            maxY = max(maxY, sh.y + sh.height + shapePad)
        }
        board.stickyNotes.forEach { n ->
            hasContent = true
            minX = min(minX, n.x)
            maxX = max(maxX, n.x + n.width)
            minY = min(minY, n.y)
            maxY = max(maxY, n.y + n.height)
        }
        board.textItems.forEach { t ->
            hasContent = true
            minX = min(minX, t.x)
            maxX = max(maxX, t.x + 240f)
            minY = min(minY, t.y)
            maxY = max(maxY, t.y + 60f)
        }

        if (!hasContent || minX == Float.MAX_VALUE) {
            minX = 0f
            minY = 0f
            maxX = 1080f
            maxY = 720f
        } else {
            val pad = 72f
            minX -= pad
            minY -= pad
            maxX += pad
            maxY += pad
        }

        val worldW = (maxX - minX).coerceAtLeast(360f)
        val worldH = (maxY - minY).coerceAtLeast(240f)
        val longest = max(worldW, worldH)
        val scale = (maxDimensionPx.toFloat() / longest).coerceIn(0.6f, 2.2f)
        val bmpW = (worldW * scale).roundToInt().coerceIn(480, maxDimensionPx)
        val bmpH = (worldH * scale).roundToInt().coerceIn(320, maxDimensionPx)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Background
        val bgColor = if (includeBackground) safeParseColor(board.colorTheme.hex, Color.WHITE) else Color.WHITE
        canvas.drawColor(bgColor)

        // 2. Grid
        if (includeBackground && includeGrid && board.gridType != CanvasGridType.BLANK) {
            drawExportGrid(canvas, board, scale, bmpW.toFloat(), bmpH.toFloat())
        }

        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-minX, -minY)

        val isDarkBoard = board.colorTheme.isDark

        // 3. Strokes
        board.strokes.forEach { stroke ->
            if (stroke.points.size >= 2) {
                drawExportAdvancedStroke(canvas, stroke, isDarkBoard)
            }
        }

        // 4. Shapes
        board.shapes.forEach { shape ->
            if (!shape.isHiddenByCollapse) {
                drawExportShape(canvas, shape, isDarkBoard)
            }
        }

        // 5. Sticky Notes
        val noteFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val noteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(45, 15, 23, 42)
        }
        val noteTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
        }

        board.stickyNotes.forEach { note ->
            if (note.isHiddenByCollapse) return@forEach
            canvas.save()
            val cx = note.x + note.width / 2f
            val cy = note.y + note.height / 2f
            if (note.rotation != 0f) {
                canvas.rotate(note.rotation, cx, cy)
            }
            val bg = safeParseColor(note.colorHex, Color.parseColor("#FFF275"))
            val rect = RectF(note.x, note.y, note.x + note.width, note.y + note.height)
            noteFillPaint.color = bg
            canvas.drawRoundRect(rect, 18f, 18f, noteFillPaint)
            canvas.drawRoundRect(rect, 18f, 18f, noteBorderPaint)

            val isDarkNote = note.colorHex.equals("#2D3139", ignoreCase = true)
            noteTextPaint.color = if (isDarkNote) Color.WHITE else Color.parseColor("#0F172A")
            val textBody = note.text.ifBlank { "Nota adhesiva" }
            val pad = StickyNoteGeometry.innerPaddingWorld(note.width, note.height)
            val (innerW, innerH) = StickyNoteGeometry.innerTextBoxWorld(note.width, note.height)
            noteTextPaint.textSize = StickyNoteGeometry.estimateAutoFitFontSizeWorld(
                text = textBody,
                innerWidthWorld = innerW,
                innerHeightWorld = innerH
            )
            val textWidth = innerW.roundToInt().coerceAtLeast(24)
            @Suppress("DEPRECATION")
            val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(textBody, 0, textBody.length, noteTextPaint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
            } else {
                StaticLayout(textBody, noteTextPaint, textWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
            }
            canvas.translate(note.x + pad, note.y + StickyNoteGeometry.headerHeightWorld(note.height) + pad)
            layout.draw(canvas)
            canvas.restore()
        }

        // 6. Text Items & Editable Template Cause Boxes
        board.textItems.forEach { t ->
            if (t.isHiddenByCollapse) return@forEach
            canvas.save()
            if (t.isEditableCard) {
                val cardBg = safeParseColor(t.backgroundColorHex ?: "#FFFFFF", Color.WHITE)
                val cardRect = RectF(t.x, t.y, t.x + t.width, t.y + 44f)
                noteFillPaint.color = cardBg
                canvas.drawRoundRect(cardRect, 10f, 10f, noteFillPaint)
                noteBorderPaint.color = Color.parseColor("#475569")
                canvas.drawRoundRect(cardRect, 10f, 10f, noteBorderPaint)
            }
            val tPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = t.fontSize.coerceIn(12f, 40f)
                color = safeParseColor(t.colorHex, Color.parseColor("#0F172A"))
                isFakeBoldText = t.isBold
            }
            val tw = t.width.roundToInt().coerceAtLeast(60)
            @Suppress("DEPRECATION")
            val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(t.text, 0, t.text.length, tPaint, tw)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
            } else {
                StaticLayout(t.text, tPaint, tw, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
            }
            canvas.translate(t.x + if (t.isEditableCard) 10f else 0f, t.y + if (t.isEditableCard) 10f else 0f)
            layout.draw(canvas)
            canvas.restore()
        }

        canvas.restore()
        return bitmap
    }

    private fun drawExportGrid(
        canvas: Canvas,
        board: WhiteboardBoard,
        scale: Float,
        w: Float,
        h: Float
    ) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (board.colorTheme.isDark) Color.argb(30, 255, 255, 255) else Color.argb(22, 15, 23, 42)
            strokeWidth = 1.2f
        }
        val step = (40f * scale).coerceAtLeast(16f)
        when (board.gridType) {
            CanvasGridType.GRID -> {
                var x = 0f
                while (x < w) {
                    canvas.drawLine(x, 0f, x, h, gridPaint)
                    x += step
                }
                var y = 0f
                while (y < h) {
                    canvas.drawLine(0f, y, w, y, gridPaint)
                    y += step
                }
            }
            CanvasGridType.DOTS -> {
                var x = step * 0.5f
                while (x < w) {
                    var y = step * 0.5f
                    while (y < h) {
                        canvas.drawCircle(x, y, 2f, gridPaint)
                        y += step
                    }
                    x += step
                }
            }
            CanvasGridType.LINES -> {
                var y = step
                while (y < h) {
                    canvas.drawLine(0f, y, w, y, gridPaint)
                    y += step
                }
            }
            CanvasGridType.DIAMONDS -> {
                var x = step * 0.5f
                while (x < w) {
                    var y = step * 0.5f
                    while (y < h) {
                        canvas.drawCircle(x, y, step * 0.35f, gridPaint.apply { style = Paint.Style.STROKE })
                        y += step
                    }
                    x += step
                }
            }
            CanvasGridType.BLANK -> {}
        }
    }

    private fun drawExportShape(canvas: Canvas, shape: ShapeItem, isDarkBoard: Boolean) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = safeParseColor(shape.strokeColorHex, Color.parseColor("#2563EB"))
            strokeWidth = shape.strokeWidth.coerceAtLeast(1.5f)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            pathEffect = buildAndroidDashEffect(shape.strokeStyle, strokeWidth)
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = safeParseColor(shape.fillColorHex, Color.parseColor("#DBEAFE"))
        }

        val x = shape.x
        val y = shape.y
        val w = shape.width
        val h = shape.height

        canvas.save()
        if (shape.rotation != 0f && shape.shapeType != ShapeType.LINE) {
            canvas.rotate(shape.rotation, x + w / 2f, y + h / 2f)
        }

        when (shape.shapeType) {
            ShapeType.RECTANGLE -> {
                val path = Path().apply { addRect(x, y, x + w, y + h, Path.Direction.CW) }
                if (shape.isFilled) canvas.drawPath(path, fillPaint)
                drawExportStyledPath(canvas, path, shape.strokeColorHex, shape.strokeWidth, shape.strokeStyle, isDarkBoard, shape.opacity)
            }
            ShapeType.ROUNDED_RECT -> {
                val path = Path().apply { addRoundRect(RectF(x, y, x + w, y + h), 16f, 16f, Path.Direction.CW) }
                if (shape.isFilled) canvas.drawPath(path, fillPaint)
                drawExportStyledPath(canvas, path, shape.strokeColorHex, shape.strokeWidth, shape.strokeStyle, isDarkBoard, shape.opacity)
            }
            ShapeType.CIRCLE -> {
                val path = Path().apply { addOval(RectF(x, y, x + w, y + h), Path.Direction.CW) }
                if (shape.isFilled) canvas.drawPath(path, fillPaint)
                drawExportStyledPath(canvas, path, shape.strokeColorHex, shape.strokeWidth, shape.strokeStyle, isDarkBoard, shape.opacity)
            }
            ShapeType.LINE -> {
                val pts = if (shape.vertices.size >= 2) {
                    shape.vertices
                } else {
                    listOf(StrokePoint(x, y), StrokePoint(x + w, y + h))
                }
                drawExportAdvancedStroke(
                    canvas,
                    WhiteboardStroke(
                        points = pts,
                        colorHex = shape.strokeColorHex,
                        width = shape.strokeWidth,
                        strokeStyle = shape.strokeStyle,
                        capStyle = shape.capStyle,
                        isRainbow = shape.isRainbow,
                        opacity = shape.opacity
                    ),
                    isDarkBoard = isDarkBoard
                )
            }
            ShapeType.ARROW -> {
                val midY = y + h / 2f
                val head = min(24f, min(w * 0.35f, h * 0.45f))
                val shaftEnd = x + w - head * 0.4f
                if (shape.strokeStyle == StrokeStyle.SOLID || shape.strokeStyle == StrokeStyle.DASHED ||
                    shape.strokeStyle == StrokeStyle.DOTTED || shape.strokeStyle == StrokeStyle.DASH_DOT
                ) {
                    canvas.drawLine(x, midY, shaftEnd, midY, strokePaint)
                } else {
                    drawExportAdvancedStroke(
                        canvas,
                        WhiteboardStroke(
                            points = listOf(StrokePoint(x, midY), StrokePoint(shaftEnd, midY)),
                            colorHex = shape.strokeColorHex,
                            width = shape.strokeWidth,
                            strokeStyle = shape.strokeStyle
                        ),
                        isDarkBoard = isDarkBoard
                    )
                }
                val headPath = Path().apply {
                    moveTo(x + w, midY)
                    lineTo(x + w - head, midY - head * 0.65f)
                    lineTo(x + w - head, midY + head * 0.65f)
                    close()
                }
                drawExportShapeArrowHead(canvas, headPath, strokePaint, shape.strokeStyle)
            }
            ShapeType.DOUBLE_ARROW -> {
                val midY = y + h / 2f
                val head = min(22f, min(w * 0.25f, h * 0.45f))
                val shaftStart = x + head * 0.4f
                val shaftEnd = x + w - head * 0.4f
                if (shape.strokeStyle == StrokeStyle.SOLID || shape.strokeStyle == StrokeStyle.DASHED ||
                    shape.strokeStyle == StrokeStyle.DOTTED || shape.strokeStyle == StrokeStyle.DASH_DOT
                ) {
                    canvas.drawLine(shaftStart, midY, shaftEnd, midY, strokePaint)
                } else {
                    drawExportAdvancedStroke(
                        canvas,
                        WhiteboardStroke(
                            points = listOf(StrokePoint(shaftStart, midY), StrokePoint(shaftEnd, midY)),
                            colorHex = shape.strokeColorHex,
                            width = shape.strokeWidth,
                            strokeStyle = shape.strokeStyle
                        ),
                        isDarkBoard = isDarkBoard
                    )
                }
                val rightHead = Path().apply {
                    moveTo(x + w, midY)
                    lineTo(x + w - head, midY - head * 0.65f)
                    lineTo(x + w - head, midY + head * 0.65f)
                    close()
                }
                val leftHead = Path().apply {
                    moveTo(x, midY)
                    lineTo(x + head, midY - head * 0.65f)
                    lineTo(x + head, midY + head * 0.65f)
                    close()
                }
                drawExportShapeArrowHead(canvas, rightHead, strokePaint, shape.strokeStyle)
                drawExportShapeArrowHead(canvas, leftHead, strokePaint, shape.strokeStyle)
            }
            else -> {
                val path = buildAndroidShapePath(shape)
                if (shape.isFilled) canvas.drawPath(path, fillPaint)
                drawExportStyledPath(canvas, path, shape.strokeColorHex, shape.strokeWidth, shape.strokeStyle, isDarkBoard, shape.opacity)
            }
        }
        if (shape.label.isNotBlank() && shape.shapeType != ShapeType.LINE) {
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (shape.isFilled) Color.parseColor("#0F172A") else safeParseColor(shape.strokeColorHex, Color.BLACK)
                textSize = 18f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }
            val fm = labelPaint.fontMetrics
            val textY = y + h / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(shape.label, x + w / 2f, textY, labelPaint)
        }
        canvas.restore()
    }

    private data class ExportPathContour(val points: List<StrokePoint>, val isClosed: Boolean)

    private fun extractExportPathContours(path: Path, stepHint: Float): List<ExportPathContour> {
        val contours = mutableListOf<ExportPathContour>()
        val pm = PathMeasure(path, false)
        val pos = FloatArray(2)
        val safeStep = stepHint.coerceIn(2.5f, 8f)
        do {
            val len = pm.length
            if (len >= 1f) {
                val isClosed = pm.isClosed
                val targetSamples = ceil((len / safeStep).toDouble()).toInt().coerceIn(8, 360)
                val pts = ArrayList<StrokePoint>(targetSamples + 1)
                val endIndex = if (isClosed) targetSamples - 1 else targetSamples
                for (i in 0..endIndex) {
                    val d = ((i.toFloat() / targetSamples.toFloat()) * len).coerceIn(0f, len)
                    if (pm.getPosTan(d, pos, null)) {
                        pts.add(StrokePoint(pos[0], pos[1]))
                    }
                }
                if (pts.size >= 2) {
                    contours.add(ExportPathContour(pts, isClosed))
                }
            }
        } while (pm.nextContour())
        return contours
    }

    private fun buildClosedSmoothAndroidPath(points: List<StrokePoint>): Path {
        val path = Path()
        val n = points.size
        if (n < 3) return buildSmoothAndroidPath(points)
        path.moveTo((points[n - 1].x + points[0].x) * 0.5f, (points[n - 1].y + points[0].y) * 0.5f)
        for (i in 0 until n) {
            val cur = points[i]
            val next = points[(i + 1) % n]
            path.quadTo(cur.x, cur.y, (cur.x + next.x) * 0.5f, (cur.y + next.y) * 0.5f)
        }
        path.close()
        return path
    }

    private fun buildClosedParallelExportPaths(points: List<StrokePoint>, offset: Float): Pair<Path, Path> {
        val n = points.size
        if (n < 3) {
            val ptsA = mutableListOf<StrokePoint>()
            val ptsB = mutableListOf<StrokePoint>()
            for (i in points.indices) {
                val dx = if (i < points.lastIndex) points[i + 1].x - points[i].x else points[i].x - points[i - 1].x
                val dy = if (i < points.lastIndex) points[i + 1].y - points[i].y else points[i].y - points[i - 1].y
                val l = hypot(dx, dy).coerceAtLeast(0.001f)
                val nx = -dy / l
                val ny = dx / l
                ptsA.add(StrokePoint(points[i].x + nx * offset, points[i].y + ny * offset))
                ptsB.add(StrokePoint(points[i].x - nx * offset, points[i].y - ny * offset))
            }
            return buildSmoothAndroidPath(ptsA) to buildSmoothAndroidPath(ptsB)
        }
        val ptsA = ArrayList<StrokePoint>(n)
        val ptsB = ArrayList<StrokePoint>(n)
        for (i in 0 until n) {
            val prev = points[(i - 1 + n) % n]
            val next = points[(i + 1) % n]
            val dx = next.x - prev.x
            val dy = next.y - prev.y
            val l = hypot(dx, dy).coerceAtLeast(0.001f)
            val nx = -dy / l
            val ny = dx / l
            ptsA.add(StrokePoint(points[i].x + nx * offset, points[i].y + ny * offset))
            ptsB.add(StrokePoint(points[i].x - nx * offset, points[i].y - ny * offset))
        }
        return buildClosedSmoothAndroidPath(ptsA) to buildClosedSmoothAndroidPath(ptsB)
    }

    private fun exportPolylineLength(points: List<StrokePoint>): Float {
        var len = 0f
        for (i in 0 until points.lastIndex) {
            len += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
        }
        return len
    }

    private fun drawExportStyledPath(
        canvas: Canvas,
        path: Path,
        strokeColorHex: String,
        strokeWidth: Float,
        strokeStyle: StrokeStyle,
        isDarkBoard: Boolean,
        opacity: Float = 1.0f
    ) {
        val baseColor = safeParseColor(strokeColorHex, Color.parseColor("#2563EB"))
        val w = strokeWidth.coerceAtLeast(1.5f)
        val effectiveAlpha = (opacity.coerceIn(0.15f, 1.0f) * 255).roundToInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = baseColor
            alpha = effectiveAlpha
            this.strokeWidth = w
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        when (strokeStyle) {
            StrokeStyle.SOLID,
            StrokeStyle.DASHED,
            StrokeStyle.DOTTED,
            StrokeStyle.DASH_DOT -> {
                paint.pathEffect = buildAndroidDashEffect(strokeStyle, w)
                canvas.drawPath(path, paint)
            }
            StrokeStyle.ANIMATED_PULSE -> {
                paint.alpha = (0.24f * 255).roundToInt()
                paint.strokeWidth = w * 1.75f
                canvas.drawPath(path, paint)
                paint.alpha = 255
                paint.strokeWidth = w
                paint.pathEffect = buildAndroidDashEffect(strokeStyle, w)
                canvas.drawPath(path, paint)
            }
            StrokeStyle.NEON_GLOW -> {
                paint.color = baseColor
                paint.alpha = ((if (isDarkBoard) 0.22f else 0.16f) * 255).roundToInt()
                paint.strokeWidth = w * 3.4f
                canvas.drawPath(path, paint)
                paint.alpha = ((if (isDarkBoard) 0.45f else 0.32f) * 255).roundToInt()
                paint.strokeWidth = w * 1.9f
                canvas.drawPath(path, paint)
                paint.alpha = (0.88f * 255).roundToInt()
                paint.strokeWidth = w
                canvas.drawPath(path, paint)
                paint.color = Color.WHITE
                paint.alpha = (0.95f * 255).roundToInt()
                paint.strokeWidth = (w * 0.32f).coerceAtLeast(1.2f)
                canvas.drawPath(path, paint)
            }
            StrokeStyle.DYNAMIC_GRADIENT -> {
                val bounds = RectF()
                path.computeBounds(bounds, true)
                val endX = if (hypot(bounds.width(), bounds.height()) < 2f) bounds.left + 4f else bounds.right
                val endY = if (hypot(bounds.width(), bounds.height()) < 2f) bounds.top + 4f else bounds.bottom
                paint.shader = LinearGradient(
                    bounds.left,
                    bounds.top,
                    endX,
                    endY,
                    intArrayOf(
                        baseColor,
                        Color.parseColor("#38BDF8"),
                        Color.parseColor("#A855F7"),
                        Color.parseColor("#FB7185"),
                        Color.parseColor("#FACC15")
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(path, paint)
            }
            StrokeStyle.SKETCH -> {
                paint.strokeWidth = (w * 0.45f).coerceAtLeast(1f)
                paint.alpha = (0.75f * 255).roundToInt()
                canvas.drawPath(path, paint)
                val contours = extractExportPathContours(path, (w * 0.8f).coerceIn(3f, 8f))
                contours.forEach { contour ->
                    val j1 = contour.points.mapIndexed { idx, p ->
                        val j = if (idx % 2 == 0) 1.5f else -1.2f
                        StrokePoint(p.x + j, p.y - j * 0.8f)
                    }
                    val j2 = contour.points.mapIndexed { idx, p ->
                        val j = if (idx % 2 == 1) 1.3f else -1.5f
                        StrokePoint(p.x - j * 0.8f, p.y + j)
                    }
                    paint.strokeWidth = (w * 0.35f).coerceAtLeast(0.9f)
                    paint.alpha = (0.50f * 255).roundToInt()
                    canvas.drawPath(if (contour.isClosed) buildClosedSmoothAndroidPath(j1) else buildSmoothAndroidPath(j1), paint)
                    paint.strokeWidth = (w * 0.30f).coerceAtLeast(0.8f)
                    paint.alpha = (0.45f * 255).roundToInt()
                    canvas.drawPath(if (contour.isClosed) buildClosedSmoothAndroidPath(j2) else buildSmoothAndroidPath(j2), paint)
                }
            }
            StrokeStyle.CHALK_TEXTURE -> {
                paint.alpha = (0.24f * 255).roundToInt()
                paint.strokeWidth = w * 1.7f
                canvas.drawPath(path, paint)
                paint.alpha = (0.45f * 255).roundToInt()
                paint.strokeWidth = w * 0.88f
                canvas.drawPath(path, paint)
                paint.alpha = (0.55f * 255).roundToInt()
                paint.strokeWidth = w * 0.65f
                paint.pathEffect = DashPathEffect(floatArrayOf(max(2.5f, w * 0.55f), max(1.8f, w * 0.35f)), 0f)
                canvas.drawPath(path, paint)
                paint.pathEffect = null

                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = baseColor
                }
                val contours = extractExportPathContours(path, (w * 0.9f).coerceIn(4f, 12f))
                contours.forEach { contour ->
                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val samples = resampleExportPoints(loopPts, (w * 0.9f).coerceIn(4f, 12f))
                    samples.forEachIndexed { idx, s ->
                        val shift = when (idx % 3) {
                            0 -> w * 0.36f
                            1 -> -w * 0.34f
                            else -> w * 0.14f
                        }
                        dotPaint.alpha = (if (idx % 2 == 0) 0.44f else 0.28f).times(255).roundToInt()
                        canvas.drawCircle(s.x + s.nx * shift, s.y + s.ny * shift, (w * 0.24f).coerceAtLeast(1f), dotPaint)
                    }
                }
            }
            StrokeStyle.ARROW_PATTERN -> {
                paint.strokeWidth = (w * 0.45f).coerceAtLeast(1.3f)
                paint.alpha = (0.65f * 255).roundToInt()
                canvas.drawPath(path, paint)
                paint.alpha = 255
                val contours = extractExportPathContours(path, (w * 0.65f).coerceIn(2.5f, 7f))
                contours.forEach { contour ->
                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val len = exportPolylineLength(loopPts)
                    if (len < 4f) return@forEach
                    val spacing = min((w * 2.6f).coerceIn(14f, 44f), (len / 6f).coerceAtLeast(6f))
                    val samples = resampleExportPoints(loopPts, spacing)
                    val wingLen = min((w * 0.95f).coerceIn(6f, 26f), (len / 16f).coerceAtLeast(3.2f))
                    for (i in 1 until samples.size) {
                        if (i == samples.lastIndex) {
                            val distPrev = hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
                            if (distPrev < spacing * 0.45f) continue
                        }
                        val s = samples[i]
                        val chev = Path().apply {
                            moveTo(s.x - s.tx * wingLen + s.nx * wingLen * 0.7f, s.y - s.ty * wingLen + s.ny * wingLen * 0.7f)
                            lineTo(s.x, s.y)
                            lineTo(s.x - s.tx * wingLen - s.nx * wingLen * 0.7f, s.y - s.ty * wingLen - s.ny * wingLen * 0.7f)
                        }
                        canvas.drawPath(chev, paint)
                    }
                }
            }
            StrokeStyle.CALLIGRAPHIC -> {
                val hairlinePaint = Paint(paint).apply {
                    this.strokeWidth = (w * 0.22f).coerceAtLeast(1.2f)
                }
                canvas.drawPath(path, hairlinePaint)
                val fillPaint = Paint(paint).apply { style = Paint.Style.FILL }
                val angle = 45f * (PI.toFloat() / 180f)
                val halfW = (w * 0.62f).coerceAtLeast(1.6f)
                val kx = cos(angle) * halfW
                val ky = sin(angle) * halfW
                val contours = extractExportPathContours(path, (w * 0.4f).coerceIn(2.5f, 7f))
                contours.forEach { contour ->
                    val pts = contour.points
                    if (pts.size < 2) return@forEach
                    val count = if (contour.isClosed) pts.size else pts.lastIndex
                    for (i in 0 until count) {
                        val p0 = pts[i]
                        val p1 = pts[(i + 1) % pts.size]
                        val quad = Path().apply {
                            moveTo(p0.x - kx, p0.y - ky)
                            lineTo(p0.x + kx, p0.y + ky)
                            lineTo(p1.x + kx, p1.y + ky)
                            lineTo(p1.x - kx, p1.y - ky)
                            close()
                        }
                        canvas.drawPath(quad, fillPaint)
                    }
                }
            }
            StrokeStyle.DOUBLE -> {
                val contours = extractExportPathContours(path, (w * 0.65f).coerceIn(2.5f, 7f))
                if (contours.isEmpty()) {
                    canvas.drawPath(path, paint)
                    return
                }
                val offset = (w * 0.65f).coerceAtLeast(2.5f)
                paint.strokeWidth = (w * 0.42f).coerceAtLeast(1f)
                contours.forEach { contour ->
                    val (pathA, pathB) = buildClosedParallelExportPaths(contour.points, offset)
                    canvas.drawPath(pathA, paint)
                    canvas.drawPath(pathB, paint)
                }
            }
            StrokeStyle.RAILWAY -> {
                val contours = extractExportPathContours(path, (w * 0.65f).coerceIn(2.5f, 7f))
                if (contours.isEmpty()) {
                    canvas.drawPath(path, paint)
                    return
                }
                val railOff = (w * 0.6f).coerceAtLeast(4f)
                val tieLen = railOff * 1.55f
                contours.forEach { contour ->
                    paint.strokeWidth = (w * 0.32f).coerceAtLeast(1.2f)
                    paint.strokeCap = Paint.Cap.ROUND
                    val (railA, railB) = buildClosedParallelExportPaths(contour.points, railOff)
                    canvas.drawPath(railA, paint)
                    canvas.drawPath(railB, paint)

                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val len = exportPolylineLength(loopPts)
                    if (len < 4f) return@forEach
                    val tieSpacing = min((w * 2.2f).coerceIn(10f, 36f), (len / 7f).coerceAtLeast(5f))
                    val samples = resampleExportPoints(loopPts, tieSpacing)
                    paint.strokeWidth = (w * 0.38f).coerceAtLeast(1.2f)
                    paint.strokeCap = Paint.Cap.SQUARE
                    for (i in samples.indices) {
                        if (i == samples.lastIndex && i > 0) {
                            val distPrev = hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
                            val distStart = if (contour.isClosed) hypot(samples[i].x - samples[0].x, samples[i].y - samples[0].y) else Float.MAX_VALUE
                            if (distPrev < tieSpacing * 0.45f || distStart < tieSpacing * 0.55f) continue
                        }
                        val s = samples[i]
                        canvas.drawLine(s.x - s.nx * tieLen, s.y - s.ny * tieLen, s.x + s.nx * tieLen, s.y + s.ny * tieLen, paint)
                    }
                }
            }
            StrokeStyle.ZIGZAG -> {
                val contours = extractExportPathContours(path, (w * 0.6f).coerceIn(2.5f, 7f))
                contours.forEach { contour ->
                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val len = exportPolylineLength(loopPts)
                    if (len < 4f) return@forEach
                    val step = min((w * 2.2f).coerceIn(10f, 40f), (len / 8f).coerceAtLeast(4f))
                    val amp = min((w * 0.9f).coerceIn(4f, 28f), (len / 20f).coerceAtLeast(2.2f))
                    val samples = resampleExportPoints(loopPts, step * 0.5f)
                    if (samples.size < 2) return@forEach
                    val zig = Path().apply {
                        moveTo(samples[0].x, samples[0].y)
                        for (i in 1 until samples.lastIndex) {
                            val s = samples[i]
                            val sign = if (i % 2 == 1) 1f else -1f
                            lineTo(s.x + s.nx * amp * sign, s.y + s.ny * amp * sign)
                        }
                        if (contour.isClosed) close() else lineTo(samples.last().x, samples.last().y)
                    }
                    paint.strokeWidth = (w * 0.65f).coerceAtLeast(1.3f)
                    canvas.drawPath(zig, paint)
                }
            }
            StrokeStyle.WAVY -> {
                val contours = extractExportPathContours(path, (w * 0.5f).coerceIn(2.5f, 6f))
                contours.forEach { contour ->
                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val len = exportPolylineLength(loopPts)
                    if (len < 4f) return@forEach
                    val wavelength = min((w * 4.2f).coerceIn(18f, 70f), (len / 5f).coerceAtLeast(8f))
                    val step = (wavelength / 5.2f).coerceIn(3f, 16f)
                    val amp = min((w * 0.85f).coerceIn(4f, 26f), (len / 20f).coerceAtLeast(2.2f))
                    val samples = resampleExportPoints(loopPts, step)
                    if (samples.size < 2) return@forEach
                    val totalSampleLen = exportPolylineLength(samples.map { StrokePoint(it.x, it.y) }).coerceAtLeast(1f)
                    val closedCycles = max(2, (totalSampleLen / wavelength).roundToInt())
                    val wavy = Path().apply {
                        moveTo(samples[0].x, samples[0].y)
                        var dist = 0f
                        val endIdx = if (contour.isClosed) samples.lastIndex else samples.size
                        for (i in 1 until endIdx) {
                            dist += hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
                            val phase = if (contour.isClosed) {
                                (dist / totalSampleLen) * (closedCycles * 2f * PI.toFloat())
                            } else {
                                (dist / wavelength) * (2f * PI.toFloat())
                            }
                            val off = sin(phase) * amp
                            lineTo(samples[i].x + samples[i].nx * off, samples[i].y + samples[i].ny * off)
                        }
                        if (contour.isClosed) close()
                    }
                    paint.strokeWidth = (w * 0.72f).coerceAtLeast(1.3f)
                    canvas.drawPath(wavy, paint)
                }
            }
            StrokeStyle.SQUARE_WAVE -> {
                val contours = extractExportPathContours(path, (w * 0.6f).coerceIn(2.5f, 7f))
                contours.forEach { contour ->
                    val loopPts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                    val len = exportPolylineLength(loopPts)
                    if (len < 4f) return@forEach
                    val blockLen = min((w * 2.5f).coerceIn(12f, 44f), (len / 6f).coerceAtLeast(5f))
                    val amp = min((w * 0.85f).coerceIn(4f, 26f), (len / 20f).coerceAtLeast(2.2f))
                    val samples = resampleExportPoints(loopPts, blockLen)
                    if (samples.size < 2) return@forEach
                    val sq = Path().apply {
                        moveTo(samples[0].x, samples[0].y)
                        for (i in 0 until samples.lastIndex) {
                            val cur = samples[i]
                            val next = samples[i + 1]
                            val sign = if (i % 2 == 0) 1f else -1f
                            lineTo(cur.x + cur.nx * amp * sign, cur.y + cur.ny * amp * sign)
                            lineTo(next.x + next.nx * amp * sign, next.y + next.ny * amp * sign)
                            lineTo(next.x, next.y)
                        }
                        if (contour.isClosed) close()
                    }
                    paint.strokeWidth = (w * 0.68f).coerceAtLeast(1.3f)
                    paint.strokeCap = Paint.Cap.SQUARE
                    paint.strokeJoin = Paint.Join.MITER
                    canvas.drawPath(sq, paint)
                }
            }
            StrokeStyle.TAPERED -> {
                val fillRibbonPaint = Paint(paint).apply { style = Paint.Style.FILL }
                val contours = extractExportPathContours(path, (w * 0.65f).coerceIn(2.5f, 7f))
                contours.forEach { contour ->
                    val pts = contour.points
                    val n = pts.size
                    if (n < 3) return@forEach
                    val loopLen = (exportPolylineLength(pts) + hypot(pts.first().x - pts.last().x, pts.first().y - pts.last().y)).coerceAtLeast(1f)
                    var acc = 0f
                    val left = ArrayList<StrokePoint>(n)
                    val right = ArrayList<StrokePoint>(n)
                    for (i in 0 until n) {
                        if (i > 0) acc += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
                        val t = (acc / loopLen).coerceIn(0f, 1f)
                        val pressure = (0.65f + 0.45f * sin(t * 2f * PI.toFloat()) + 0.08f * sin(t * 6f * PI.toFloat())).coerceIn(0.20f, 1.15f)
                        val halfW = (w * pressure * 0.52f).coerceAtLeast(0.8f)
                        val prev = pts[(i - 1 + n) % n]
                        val next = pts[(i + 1) % n]
                        val dx = next.x - prev.x
                        val dy = next.y - prev.y
                        val len = hypot(dx, dy).coerceAtLeast(0.001f)
                        val nx = -dy / len
                        val ny = dx / len
                        left.add(StrokePoint(pts[i].x + nx * halfW, pts[i].y + ny * halfW))
                        right.add(StrokePoint(pts[i].x - nx * halfW, pts[i].y - ny * halfW))
                    }
                    for (i in 0 until n) {
                        val nextIdx = (i + 1) % n
                        val quad = Path().apply {
                            moveTo(left[i].x, left[i].y)
                            lineTo(left[nextIdx].x, left[nextIdx].y)
                            lineTo(right[nextIdx].x, right[nextIdx].y)
                            lineTo(right[i].x, right[i].y)
                            close()
                        }
                        canvas.drawPath(quad, fillRibbonPaint)
                    }
                }
            }
        }
    }

    private fun buildAndroidShapePath(shape: ShapeItem): Path {
        val x = shape.x
        val y = shape.y
        val w = shape.width
        val h = shape.height
        val path = Path()
        when (shape.shapeType) {
            ShapeType.TRIANGLE -> {
                if (shape.vertices.size == 3) {
                    path.moveTo(shape.vertices[0].x, shape.vertices[0].y)
                    path.lineTo(shape.vertices[1].x, shape.vertices[1].y)
                    path.lineTo(shape.vertices[2].x, shape.vertices[2].y)
                } else {
                    path.moveTo(x + w / 2f, y)
                    path.lineTo(x + w, y + h)
                    path.lineTo(x, y + h)
                }
                path.close()
            }
            ShapeType.DIAMOND -> {
                if (shape.vertices.size >= 4) {
                    path.moveTo(shape.vertices[0].x, shape.vertices[0].y)
                    for (i in 1 until shape.vertices.size) {
                        path.lineTo(shape.vertices[i].x, shape.vertices[i].y)
                    }
                } else {
                    path.moveTo(x + w / 2f, y)
                    path.lineTo(x + w, y + h / 2f)
                    path.lineTo(x + w / 2f, y + h)
                    path.lineTo(x, y + h / 2f)
                }
                path.close()
            }
            ShapeType.HEXAGON -> {
                path.moveTo(x + w * 0.25f, y)
                path.lineTo(x + w * 0.75f, y)
                path.lineTo(x + w, y + h * 0.5f)
                path.lineTo(x + w * 0.75f, y + h)
                path.lineTo(x + w * 0.25f, y + h)
                path.lineTo(x, y + h * 0.5f)
                path.close()
            }
            ShapeType.STAR -> {
                val cx = x + w / 2f
                val cy = y + h / 2f
                val outerRx = w / 2f
                val outerRy = h / 2f
                val innerRx = outerRx * 0.42f
                val innerRy = outerRy * 0.42f
                for (i in 0 until 10) {
                    val angle = -PI / 2.0 + i * (PI / 5.0)
                    val rx = if (i % 2 == 0) outerRx else innerRx
                    val ry = if (i % 2 == 0) outerRy else innerRy
                    val px = cx + (rx * cos(angle)).toFloat()
                    val py = cy + (ry * sin(angle)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
            }
            ShapeType.CLOUD_CALLOUT -> {
                path.addRoundRect(RectF(x, y, x + w, y + h * 0.78f), h * 0.35f, h * 0.35f, Path.Direction.CW)
                path.moveTo(x + w * 0.25f, y + h * 0.78f)
                path.lineTo(x + w * 0.16f, y + h)
                path.lineTo(x + w * 0.42f, y + h * 0.78f)
            }
            ShapeType.CYLINDER -> {
                val capH = h * 0.22f
                path.addOval(RectF(x, y, x + w, y + capH), Path.Direction.CW)
                path.moveTo(x, y + capH / 2f)
                path.lineTo(x, y + h - capH / 2f)
                path.arcTo(RectF(x, y + h - capH, x + w, y + h), 180f, -180f, false)
                path.lineTo(x + w, y + capH / 2f)
            }
            else -> {
                path.addRect(x, y, x + w, y + h, Path.Direction.CW)
            }
        }
        return path
    }

    private fun buildAndroidDashEffect(style: StrokeStyle, width: Float): DashPathEffect? {
        return when (style) {
            StrokeStyle.SOLID -> null
            StrokeStyle.DASHED -> DashPathEffect(floatArrayOf(width * 3f, width * 2f), 0f)
            StrokeStyle.DOTTED -> DashPathEffect(floatArrayOf(width, width * 1.5f), 0f)
            StrokeStyle.DASH_DOT -> DashPathEffect(floatArrayOf(width * 4f, width * 1.6f, width * 1.2f, width * 1.6f), 0f)
            StrokeStyle.ANIMATED_PULSE -> DashPathEffect(floatArrayOf(width * 3.5f, width * 2f), 0f)
            else -> null
        }
    }

    private data class ExportSample(val x: Float, val y: Float, val tx: Float, val ty: Float, val nx: Float, val ny: Float)

    private fun buildSmoothAndroidPath(points: List<StrokePoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 2) {
            path.lineTo(points[1].x, points[1].y)
            return path
        }
        for (i in 1 until points.lastIndex) {
            val midX = (points[i].x + points[i + 1].x) * 0.5f
            val midY = (points[i].y + points[i + 1].y) * 0.5f
            path.quadTo(points[i].x, points[i].y, midX, midY)
        }
        path.lineTo(points.last().x, points.last().y)
        return path
    }

    private fun resampleExportPoints(points: List<StrokePoint>, stepDist: Float): List<ExportSample> {
        if (points.size < 2) return emptyList()
        val result = mutableListOf<ExportSample>()
        val safeStep = stepDist.coerceAtLeast(4f)
        val dx0 = points[1].x - points[0].x
        val dy0 = points[1].y - points[0].y
        val len0 = hypot(dx0, dy0).coerceAtLeast(0.001f)
        val initTx = dx0 / len0
        val initTy = dy0 / len0
        result.add(ExportSample(points[0].x, points[0].y, initTx, initTy, -initTy, initTx))
        var currentDist = 0f
        var nextTargetDist = safeStep
        for (i in 0 until points.lastIndex) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val segLen = hypot(p1.x - p0.x, p1.y - p0.y)
            if (segLen < 0.001f) continue
            val tx = (p1.x - p0.x) / segLen
            val ty = (p1.y - p0.y) / segLen
            val nx = -ty
            val ny = tx
            while (currentDist + segLen >= nextTargetDist) {
                val t = ((nextTargetDist - currentDist) / segLen).coerceIn(0f, 1f)
                result.add(ExportSample(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t, tx, ty, nx, ny))
                nextTargetDist += safeStep
            }
            currentDist += segLen
        }
        val last = points.last()
        val lastSample = result.lastOrNull()
        if (lastSample == null || hypot(last.x - lastSample.x, last.y - lastSample.y) > 1.5f) {
            val prev = points[points.lastIndex - 1]
            val dx = last.x - prev.x
            val dy = last.y - prev.y
            val l = hypot(dx, dy).coerceAtLeast(0.001f)
            val tx = if (l > 0.01f) dx / l else initTx
            val ty = if (l > 0.01f) dy / l else initTy
            result.add(ExportSample(last.x, last.y, tx, ty, -ty, tx))
        }
        return result
    }

    private fun deduplicateExportPoints(points: List<StrokePoint>, minStep: Float): List<StrokePoint> {
        if (points.size <= 2) return points
        val out = mutableListOf(points.first())
        for (i in 1 until points.lastIndex) {
            val cur = points[i]
            val last = out.last()
            if (hypot(cur.x - last.x, cur.y - last.y) >= minStep) {
                out.add(cur)
            }
        }
        val endPt = points.last()
        if (hypot(endPt.x - out.last().x, endPt.y - out.last().y) > 0.1f || out.size == 1) {
            out.add(endPt)
        }
        return out
    }

    private fun trimExportPointsForArrowCaps(
        points: List<StrokePoint>,
        capStyle: LineCapStyle,
        inset: Float
    ): List<StrokePoint> {
        if (points.size < 2 || capStyle == LineCapStyle.NONE) return points
        var total = 0f
        for (i in 0 until points.lastIndex) {
            total += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
        }
        val trimStart = capStyle == LineCapStyle.START_ARROW || capStyle == LineCapStyle.BOTH_ARROWS
        val trimEnd = capStyle == LineCapStyle.END_ARROW || capStyle == LineCapStyle.BOTH_ARROWS
        val required = (if (trimStart) inset else 0f) + (if (trimEnd) inset else 0f)
        if (total <= required + 8f) return points

        val startDist = if (trimStart) inset else 0f
        val endDist = if (trimEnd) total - inset else total

        val out = mutableListOf<StrokePoint>()
        var acc = 0f
        var started = !trimStart
        if (started) out.add(points.first())

        for (i in 0 until points.lastIndex) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val seg = hypot(p1.x - p0.x, p1.y - p0.y)
            if (seg < 0.001f) continue

            if (!started && acc + seg >= startDist) {
                val t = ((startDist - acc) / seg).coerceIn(0f, 1f)
                out.add(StrokePoint(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t))
                started = true
            }
            if (started) {
                if (acc + seg >= endDist) {
                    val t = ((endDist - acc) / seg).coerceIn(0f, 1f)
                    val endPt = StrokePoint(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t)
                    if (out.isEmpty() || hypot(endPt.x - out.last().x, endPt.y - out.last().y) > 0.5f) {
                        out.add(endPt)
                    }
                    break
                } else {
                    if (out.isEmpty() || hypot(p1.x - out.last().x, p1.y - out.last().y) > 0.5f) {
                        out.add(p1)
                    }
                }
            }
            acc += seg
        }
        return if (out.size >= 2) out else points
    }

    private fun drawExportShapeArrowHead(
        canvas: Canvas,
        headPath: Path,
        strokePaint: Paint,
        strokeStyle: StrokeStyle
    ) {
        val fillHead = Paint(strokePaint).apply { style = Paint.Style.FILL; pathEffect = null }
        if (strokeStyle == StrokeStyle.NEON_GLOW) {
            val haloPaint = Paint(strokePaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePaint.strokeWidth * 1.4f
                alpha = (0.35f * 255).roundToInt()
                pathEffect = null
            }
            canvas.drawPath(haloPaint.let { headPath }, haloPaint)
            canvas.drawPath(headPath, fillHead)
            val whiteEdge = Paint(strokePaint).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                alpha = (0.85f * 255).roundToInt()
                strokeWidth = 1.5f
                pathEffect = null
            }
            canvas.drawPath(headPath, whiteEdge)
        } else {
            canvas.drawPath(headPath, fillHead)
        }
    }

    private fun drawExportAdvancedStroke(canvas: Canvas, stroke: WhiteboardStroke, isDarkBoard: Boolean = false) {
        val rawPts = deduplicateExportPoints(stroke.points, minStep = 0.75f)
        if (rawPts.size < 2) return
        val baseColor = safeParseColor(stroke.colorHex, Color.BLACK)
        val width = stroke.width.coerceAtLeast(1.5f)
        val effectiveAlpha = (stroke.opacity.coerceIn(0.15f, 1.0f) * 255).roundToInt()
        val arrowSize = (width * 3.0f + 10f).coerceIn(12f, 36f)
        val pts = if (stroke.capStyle != LineCapStyle.NONE) {
            trimExportPointsForArrowCaps(rawPts, stroke.capStyle, arrowSize * 0.62f)
        } else {
            rawPts
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = if (stroke.brushType == StrokeBrushType.HIGHLIGHTER || stroke.isHighlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
            strokeWidth = width
            color = baseColor
            alpha = effectiveAlpha
        }

        if (stroke.isRainbow) {
            paint.shader = LinearGradient(
                pts.first().x, pts.first().y, pts.last().x, pts.last().y,
                intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.MAGENTA),
                null,
                Shader.TileMode.CLAMP
            )
        }

        when (stroke.strokeStyle) {
            StrokeStyle.DOUBLE -> {
                val offset = (width * 0.65f).coerceAtLeast(2.5f)
                val subW = (width * 0.42f).coerceAtLeast(1f)
                paint.strokeWidth = subW
                val ptsA = mutableListOf<StrokePoint>()
                val ptsB = mutableListOf<StrokePoint>()
                for (i in pts.indices) {
                    val dx = if (i < pts.lastIndex) pts[i + 1].x - pts[i].x else pts[i].x - pts[i - 1].x
                    val dy = if (i < pts.lastIndex) pts[i + 1].y - pts[i].y else pts[i].y - pts[i - 1].y
                    val l = hypot(dx, dy).coerceAtLeast(0.001f)
                    val nx = -dy / l
                    val ny = dx / l
                    ptsA.add(StrokePoint(pts[i].x + nx * offset, pts[i].y + ny * offset))
                    ptsB.add(StrokePoint(pts[i].x - nx * offset, pts[i].y - ny * offset))
                }
                canvas.drawPath(buildSmoothAndroidPath(ptsA), paint)
                canvas.drawPath(buildSmoothAndroidPath(ptsB), paint)
            }
            StrokeStyle.ZIGZAG -> {
                val step = (width * 2.2f).coerceIn(12f, 40f)
                val amp = (width * 0.9f).coerceIn(6f, 28f)
                val samples = resampleExportPoints(pts, step * 0.5f)
                if (samples.size >= 2) {
                    val path = Path().apply { moveTo(samples[0].x, samples[0].y) }
                    for (i in 1 until samples.lastIndex) {
                        val s = samples[i]
                        val sign = if (i % 2 == 1) 1f else -1f
                        path.lineTo(s.x + s.nx * amp * sign, s.y + s.ny * amp * sign)
                    }
                    path.lineTo(samples.last().x, samples.last().y)
                    paint.strokeWidth = (width * 0.65f).coerceAtLeast(1.5f)
                    canvas.drawPath(path, paint)
                }
            }
            StrokeStyle.WAVY -> {
                val step = (width * 0.8f).coerceIn(6f, 18f)
                val wavelength = (width * 4.2f).coerceIn(24f, 70f)
                val amp = (width * 0.85f).coerceIn(5f, 26f)
                val samples = resampleExportPoints(pts, step)
                if (samples.size >= 2) {
                    val path = Path().apply { moveTo(samples[0].x, samples[0].y) }
                    var dist = 0f
                    for (i in 1 until samples.size) {
                        dist += hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
                        val off = sin((dist / wavelength) * (2f * PI.toFloat())) * amp
                        path.lineTo(samples[i].x + samples[i].nx * off, samples[i].y + samples[i].ny * off)
                    }
                    paint.strokeWidth = (width * 0.72f).coerceAtLeast(1.5f)
                    canvas.drawPath(path, paint)
                }
            }
            StrokeStyle.SQUARE_WAVE -> {
                val blockLen = (width * 2.5f).coerceIn(16f, 44f)
                val amp = (width * 0.85f).coerceIn(6f, 26f)
                val samples = resampleExportPoints(pts, blockLen)
                if (samples.size >= 2) {
                    val path = Path().apply { moveTo(samples[0].x, samples[0].y) }
                    for (i in 0 until samples.lastIndex) {
                        val cur = samples[i]
                        val next = samples[i + 1]
                        val sign = if (i % 2 == 0) 1f else -1f
                        path.lineTo(cur.x + cur.nx * amp * sign, cur.y + cur.ny * amp * sign)
                        path.lineTo(next.x + next.nx * amp * sign, next.y + next.ny * amp * sign)
                        path.lineTo(next.x, next.y)
                    }
                    paint.strokeWidth = (width * 0.68f).coerceAtLeast(1.5f)
                    paint.strokeCap = Paint.Cap.SQUARE
                    paint.strokeJoin = Paint.Join.MITER
                    canvas.drawPath(path, paint)
                }
            }
            StrokeStyle.RAILWAY -> {
                val railOff = (width * 0.6f).coerceAtLeast(4f)
                val railW = (width * 0.32f).coerceAtLeast(1.5f)
                val denseBase = resampleExportPoints(pts, 10f).map { StrokePoint(it.x, it.y) }
                val railBase = if (denseBase.size >= 2) denseBase else pts
                val ptsA = mutableListOf<StrokePoint>()
                val ptsB = mutableListOf<StrokePoint>()
                for (i in railBase.indices) {
                    val dx = if (i < railBase.lastIndex) railBase[i + 1].x - railBase[i].x else railBase[i].x - railBase[i - 1].x
                    val dy = if (i < railBase.lastIndex) railBase[i + 1].y - railBase[i].y else railBase[i].y - railBase[i - 1].y
                    val l = hypot(dx, dy).coerceAtLeast(0.001f)
                    val nx = -dy / l
                    val ny = dx / l
                    ptsA.add(StrokePoint(railBase[i].x + nx * railOff, railBase[i].y + ny * railOff))
                    ptsB.add(StrokePoint(railBase[i].x - nx * railOff, railBase[i].y - ny * railOff))
                }
                paint.strokeWidth = railW
                canvas.drawPath(buildSmoothAndroidPath(ptsA), paint)
                canvas.drawPath(buildSmoothAndroidPath(ptsB), paint)
                val tieSpacing = (width * 2.2f).coerceIn(14f, 36f)
                val samples = resampleExportPoints(railBase, tieSpacing)
                val tieLen = railOff * 1.55f
                paint.strokeWidth = (width * 0.38f).coerceAtLeast(1.5f)
                paint.strokeCap = Paint.Cap.SQUARE
                for (i in samples.indices) {
                    if (i == samples.lastIndex && i > 0) {
                        val distPrev = hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
                        if (distPrev < tieSpacing * 0.45f) continue
                    }
                    val s = samples[i]
                    canvas.drawLine(s.x - s.nx * tieLen, s.y - s.ny * tieLen, s.x + s.nx * tieLen, s.y + s.ny * tieLen, paint)
                }
            }
            StrokeStyle.ARROW_PATTERN -> {
                paint.strokeWidth = (width * 0.45f).coerceAtLeast(1.5f)
                paint.alpha = (effectiveAlpha * 0.65f).roundToInt()
                canvas.drawPath(buildSmoothAndroidPath(pts), paint)
                paint.alpha = effectiveAlpha
                paint.strokeWidth = (width * 0.45f).coerceAtLeast(1.5f)
                val chevronSpacing = (width * 2.6f).coerceIn(18f, 44f)
                val samples = resampleExportPoints(pts, chevronSpacing)
                val wingLen = (width * 0.95f).coerceIn(8f, 26f)
                for (i in 1 until samples.size) {
                    if (i == samples.lastIndex) {
                        val distPrev = hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
                        if (distPrev < chevronSpacing * 0.45f) continue
                    }
                    val s = samples[i]
                    val chev = Path().apply {
                        moveTo(s.x - s.tx * wingLen + s.nx * wingLen * 0.7f, s.y - s.ty * wingLen + s.ny * wingLen * 0.7f)
                        lineTo(s.x, s.y)
                        lineTo(s.x - s.tx * wingLen - s.nx * wingLen * 0.7f, s.y - s.ty * wingLen - s.ny * wingLen * 0.7f)
                    }
                    canvas.drawPath(chev, paint)
                }
            }
            StrokeStyle.TAPERED -> {
                val samples = resampleExportPoints(pts, 8f)
                if (samples.size >= 2) {
                    var totalLen = 0f
                    for (i in 0 until samples.lastIndex) {
                        totalLen += hypot(samples[i + 1].x - samples[i].x, samples[i + 1].y - samples[i].y)
                    }
                    if (totalLen >= 1f) {
                        var acc = 0f
                        val left = mutableListOf<StrokePoint>()
                        val right = mutableListOf<StrokePoint>()
                        for (i in samples.indices) {
                            if (i > 0) acc += hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
                            val t = (acc / totalLen).coerceIn(0f, 1f)
                            val swell = sin(t * PI.toFloat())
                            val organicRipple = 0.08f * sin(t * 3f * PI.toFloat())
                            val pressure = (0.18f + 0.92f * swell + organicRipple).coerceIn(0.15f, 1.15f)
                            val halfW = (width * pressure * 0.52f).coerceAtLeast(0.8f)
                            left.add(StrokePoint(samples[i].x + samples[i].nx * halfW, samples[i].y + samples[i].ny * halfW))
                            right.add(StrokePoint(samples[i].x - samples[i].nx * halfW, samples[i].y - samples[i].ny * halfW))
                        }
                        val ribbon = Path().apply {
                            moveTo(left.first().x, left.first().y)
                            left.forEach { lineTo(it.x, it.y) }
                            right.reversed().forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        canvas.drawPath(ribbon, Paint(paint).apply { style = Paint.Style.FILL })
                    }
                }
            }
            StrokeStyle.CALLIGRAPHIC -> {
                val angle = 45f * (PI.toFloat() / 180f)
                val halfW = (width * 0.62f).coerceAtLeast(1.8f)
                val kx = cos(angle) * halfW
                val ky = sin(angle) * halfW
                val dense = resampleExportPoints(pts, (width * 0.4f).coerceIn(3f, 8f)).map { StrokePoint(it.x, it.y) }
                val working = if (dense.size >= 2) dense else pts

                val hairlinePaint = Paint(paint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (width * 0.22f).coerceAtLeast(1.4f)
                }
                canvas.drawPath(buildSmoothAndroidPath(working), hairlinePaint)

                val fillPaint = Paint(paint).apply { style = Paint.Style.FILL }
                for (i in 0 until working.lastIndex) {
                    val p0 = working[i]
                    val p1 = working[i + 1]
                    val quad = Path().apply {
                        moveTo(p0.x - kx, p0.y - ky)
                        lineTo(p0.x + kx, p0.y + ky)
                        lineTo(p1.x + kx, p1.y + ky)
                        lineTo(p1.x - kx, p1.y - ky)
                        close()
                    }
                    canvas.drawPath(quad, fillPaint)
                }
            }
            StrokeStyle.SKETCH -> {
                paint.strokeWidth = (width * 0.45f).coerceAtLeast(1f)
                paint.alpha = (effectiveAlpha * 0.75f).roundToInt()
                canvas.drawPath(buildSmoothAndroidPath(pts), paint)
                paint.strokeWidth = (width * 0.35f).coerceAtLeast(0.9f)
                paint.alpha = (effectiveAlpha * 0.50f).roundToInt()
                canvas.drawPath(buildSmoothAndroidPath(pts.mapIndexed { idx, p ->
                    val j = if (idx % 2 == 0) 1.5f else -1.2f
                    StrokePoint(p.x + j, p.y - j * 0.8f)
                }), paint)
                paint.strokeWidth = (width * 0.30f).coerceAtLeast(0.8f)
                paint.alpha = (effectiveAlpha * 0.45f).roundToInt()
                canvas.drawPath(buildSmoothAndroidPath(pts.mapIndexed { idx, p ->
                    val j = if (idx % 2 == 1) 1.3f else -1.5f
                    StrokePoint(p.x - j * 0.8f, p.y + j)
                }), paint)
            }
            StrokeStyle.NEON_GLOW -> {
                val path = buildSmoothAndroidPath(pts)
                paint.color = baseColor
                paint.alpha = ((if (isDarkBoard) 0.22f else 0.16f) * 255).roundToInt()
                paint.strokeWidth = width * 3.4f
                canvas.drawPath(path, paint)
                paint.alpha = ((if (isDarkBoard) 0.45f else 0.32f) * 255).roundToInt()
                paint.strokeWidth = width * 1.9f
                canvas.drawPath(path, paint)
                paint.alpha = (0.88f * 255).roundToInt()
                paint.strokeWidth = width
                canvas.drawPath(path, paint)
                paint.color = Color.WHITE
                paint.alpha = (0.95f * 255).roundToInt()
                paint.strokeWidth = (width * 0.32f).coerceAtLeast(1.2f)
                canvas.drawPath(path, paint)
            }
            StrokeStyle.DYNAMIC_GRADIENT -> {
                val minX = pts.minOf { it.x }
                val maxX = pts.maxOf { it.x }
                val minY = pts.minOf { it.y }
                val maxY = pts.maxOf { it.y }
                val endX = if (hypot(maxX - minX, maxY - minY) < 2f) minX + 4f else maxX
                val endY = if (hypot(maxX - minX, maxY - minY) < 2f) minY + 4f else maxY
                val colors = if (stroke.isRainbow) {
                    intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.MAGENTA)
                } else {
                    intArrayOf(
                        baseColor,
                        Color.parseColor("#38BDF8"),
                        Color.parseColor("#A855F7"),
                        Color.parseColor("#FB7185"),
                        Color.parseColor("#FACC15")
                    )
                }
                paint.shader = LinearGradient(minX, minY, endX, endY, colors, null, Shader.TileMode.CLAMP)
                canvas.drawPath(buildSmoothAndroidPath(pts), paint)
            }
            StrokeStyle.CHALK_TEXTURE -> {
                val path = buildSmoothAndroidPath(pts)
                paint.alpha = (effectiveAlpha * 0.24f).roundToInt()
                paint.strokeWidth = width * 1.7f
                canvas.drawPath(path, paint)
                paint.alpha = (effectiveAlpha * 0.45f).roundToInt()
                paint.strokeWidth = width * 0.88f
                canvas.drawPath(path, paint)
                paint.alpha = (effectiveAlpha * 0.55f).roundToInt()
                paint.strokeWidth = width * 0.65f
                paint.pathEffect = DashPathEffect(floatArrayOf(max(2.5f, width * 0.55f), max(1.8f, width * 0.35f)), 0f)
                canvas.drawPath(path, paint)
                paint.pathEffect = null
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = baseColor
                }
                val samples = resampleExportPoints(pts, (width * 0.9f).coerceIn(4f, 12f))
                samples.forEachIndexed { idx, s ->
                    val shift = when (idx % 3) {
                        0 -> width * 0.36f
                        1 -> -width * 0.34f
                        else -> width * 0.14f
                    }
                    dotPaint.alpha = (effectiveAlpha * if (idx % 2 == 0) 0.44f else 0.28f).roundToInt()
                    canvas.drawCircle(s.x + s.nx * shift, s.y + s.ny * shift, (width * 0.24f).coerceAtLeast(1f), dotPaint)
                }
            }
            else -> {
                paint.pathEffect = buildAndroidDashEffect(stroke.strokeStyle, width)
                canvas.drawPath(buildSmoothAndroidPath(pts), paint)
            }
        }

        if (stroke.capStyle != LineCapStyle.NONE) {
            val lookbackDist = max(8f, width * 1.6f)
            val endDir = if (stroke.capStyle == LineCapStyle.END_ARROW || stroke.capStyle == LineCapStyle.BOTH_ARROWS) {
                val last = rawPts.last()
                var prev = rawPts[rawPts.lastIndex - 1]
                for (i in rawPts.lastIndex - 1 downTo 0) {
                    prev = rawPts[i]
                    if (hypot(last.x - prev.x, last.y - prev.y) >= lookbackDist) break
                }
                StrokePoint(last.x - prev.x, last.y - prev.y)
            } else null

            val startDir = if (stroke.capStyle == LineCapStyle.START_ARROW || stroke.capStyle == LineCapStyle.BOTH_ARROWS) {
                val first = rawPts.first()
                var next = rawPts[1]
                for (i in 1 until rawPts.size) {
                    next = rawPts[i]
                    if (hypot(first.x - next.x, first.y - next.y) >= lookbackDist) break
                }
                StrokePoint(first.x - next.x, first.y - next.y)
            } else null

            if (stroke.strokeStyle == StrokeStyle.NEON_GLOW) {
                val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = baseColor
                    alpha = ((if (isDarkBoard) 0.42f else 0.28f) * 255).roundToInt()
                }
                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = baseColor
                    alpha = (0.94f * 255).roundToInt()
                }
                val hotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                    alpha = (0.88f * 255).roundToInt()
                }
                endDir?.let { d ->
                    val last = rawPts.last()
                    drawExportArrowHead(canvas, last.x, last.y, d.x, d.y, arrowSize * 1.32f, haloPaint)
                    drawExportArrowHead(canvas, last.x, last.y, d.x, d.y, arrowSize, corePaint)
                    drawExportArrowHead(canvas, last.x, last.y, d.x, d.y, arrowSize * 0.52f, hotPaint)
                }
                startDir?.let { d ->
                    val first = rawPts.first()
                    drawExportArrowHead(canvas, first.x, first.y, d.x, d.y, arrowSize * 1.32f, haloPaint)
                    drawExportArrowHead(canvas, first.x, first.y, d.x, d.y, arrowSize, corePaint)
                    drawExportArrowHead(canvas, first.x, first.y, d.x, d.y, arrowSize * 0.52f, hotPaint)
                }
            } else {
                val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = baseColor
                    alpha = effectiveAlpha
                }
                endDir?.let { d ->
                    val last = rawPts.last()
                    drawExportArrowHead(canvas, last.x, last.y, d.x, d.y, arrowSize, arrowPaint)
                }
                startDir?.let { d ->
                    val first = rawPts.first()
                    drawExportArrowHead(canvas, first.x, first.y, d.x, d.y, arrowSize, arrowPaint)
                }
            }
        }
    }

    private fun drawExportArrowHead(
        canvas: Canvas,
        tipX: Float,
        tipY: Float,
        dirX: Float,
        dirY: Float,
        size: Float,
        paint: Paint
    ) {
        val len = hypot(dirX, dirY)
        if (len < 0.001f) return
        val ux = dirX / len
        val uy = dirY / len
        val nx = -uy
        val ny = ux
        val baseX = tipX - ux * size
        val baseY = tipY - uy * size
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(baseX + nx * size * 0.55f, baseY + ny * size * 0.55f)
            lineTo(baseX - ux * size * 0.18f, baseY - uy * size * 0.18f)
            lineTo(baseX - nx * size * 0.55f, baseY - ny * size * 0.55f)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun safeParseColor(hex: String, fallback: Int): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            fallback
        }
    }

    fun saveBitmapToDevice(context: Context, bitmap: Bitmap, boardTitle: String): Result<String> {
        return runCatching {
            val cleanTitle = boardTitle
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .ifBlank { "pizarra" }
                .take(32)
            val fileName = "${cleanTitle}_${System.currentTimeMillis()}.png"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Pizarras")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("No se pudo crear el archivo en la galería")
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: error("No se pudo abrir el flujo de escritura")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Guardada en Galería (Pictures/Pizarras/$fileName)"
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                "Guardada en ${file.name}"
            }
        }
    }

    fun createSharePngIntent(context: Context, bitmap: Bitmap, boardTitle: String): Result<Intent> {
        return runCatching {
            val exportDir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
            val cleanTitle = boardTitle
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .ifBlank { "pizarra" }
                .take(32)
            val file = File(exportDir, "${cleanTitle}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, boardTitle)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(shareIntent, "Exportar pizarra como imagen")
        }
    }

    fun createShareJsonIntent(context: Context, board: WhiteboardBoard): Result<Intent> {
        return runCatching {
            val exportDir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
            val cleanTitle = board.title
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .ifBlank { "pizarra" }
                .take(32)
            val file = File(exportDir, "${cleanTitle}_respaldo.json")
            file.writeText(BoardSnapshotSerializer.serializeBoard(board), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "${board.title} (Respaldo)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(shareIntent, "Exportar respaldo JSON")
        }
    }
}
