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
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.model.CanvasGridType
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokeStyle
import com.example.model.WhiteboardBoard
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
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
            s.points.forEach { p ->
                hasContent = true
                minX = min(minX, p.x - s.width)
                maxX = max(maxX, p.x + s.width)
                minY = min(minY, p.y - s.width)
                maxY = max(maxY, p.y + s.width)
            }
        }
        board.shapes.forEach { sh ->
            hasContent = true
            minX = min(minX, sh.x - sh.strokeWidth)
            maxX = max(maxX, sh.x + sh.width + sh.strokeWidth)
            minY = min(minY, sh.y - sh.strokeWidth)
            maxY = max(maxY, sh.y + sh.height + sh.strokeWidth)
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

        // 3. Strokes
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        board.strokes.forEach { stroke ->
            if (stroke.points.size >= 2) {
                strokePaint.shader = null
                strokePaint.pathEffect = buildAndroidDashEffect(stroke.strokeStyle, stroke.width)
                strokePaint.strokeWidth = stroke.width.coerceAtLeast(1.5f)
                strokePaint.strokeCap = if (stroke.brushType == StrokeBrushType.HIGHLIGHTER || stroke.isHighlighter) {
                    Paint.Cap.SQUARE
                } else {
                    Paint.Cap.ROUND
                }
                val effectiveAlpha = (stroke.opacity.coerceIn(0.1f, 1.0f) * 255).roundToInt()
                if (stroke.isRainbow) {
                    val pFirst = stroke.points.first()
                    val pLast = stroke.points.last()
                    strokePaint.shader = LinearGradient(
                        pFirst.x, pFirst.y, pLast.x, pLast.y,
                        intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.MAGENTA),
                        null,
                        Shader.TileMode.CLAMP
                    )
                    strokePaint.alpha = effectiveAlpha
                } else {
                    strokePaint.color = safeParseColor(stroke.colorHex, Color.BLACK)
                    strokePaint.alpha = effectiveAlpha
                }
                val path = Path()
                path.moveTo(stroke.points[0].x, stroke.points[0].y)
                if (stroke.points.size == 2) {
                    path.lineTo(stroke.points[1].x, stroke.points[1].y)
                } else {
                    for (i in 1 until stroke.points.lastIndex) {
                        val pMidX = (stroke.points[i].x + stroke.points[i + 1].x) / 2f
                        val pMidY = (stroke.points[i].y + stroke.points[i + 1].y) / 2f
                        path.quadTo(stroke.points[i].x, stroke.points[i].y, pMidX, pMidY)
                    }
                    val lastPt = stroke.points.last()
                    path.lineTo(lastPt.x, lastPt.y)
                }
                canvas.drawPath(path, strokePaint)
            }
        }

        // 4. Shapes
        board.shapes.forEach { shape ->
            drawExportShape(canvas, shape)
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
            val textWidth = (note.width - 28f).roundToInt().coerceAtLeast(40)
            @Suppress("DEPRECATION")
            val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(textBody, 0, textBody.length, noteTextPaint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
            } else {
                StaticLayout(textBody, noteTextPaint, textWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
            }
            canvas.translate(note.x + 14f, note.y + 14f)
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

    private fun drawExportShape(canvas: Canvas, shape: ShapeItem) {
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
                if (shape.isFilled) canvas.drawRect(x, y, x + w, y + h, fillPaint)
                canvas.drawRect(x, y, x + w, y + h, strokePaint)
            }
            ShapeType.ROUNDED_RECT -> {
                val rect = RectF(x, y, x + w, y + h)
                if (shape.isFilled) canvas.drawRoundRect(rect, 16f, 16f, fillPaint)
                canvas.drawRoundRect(rect, 16f, 16f, strokePaint)
            }
            ShapeType.CIRCLE -> {
                val rect = RectF(x, y, x + w, y + h)
                if (shape.isFilled) canvas.drawOval(rect, fillPaint)
                canvas.drawOval(rect, strokePaint)
            }
            ShapeType.LINE -> {
                val p0 = shape.vertices.getOrNull(0)
                val p1 = shape.vertices.getOrNull(1)
                if (p0 != null && p1 != null) {
                    canvas.drawLine(p0.x, p0.y, p1.x, p1.y, strokePaint)
                } else {
                    canvas.drawLine(x, y, x + w, y + h, strokePaint)
                }
            }
            ShapeType.ARROW -> {
                val midY = y + h / 2f
                val head = min(24f, min(w * 0.35f, h * 0.45f))
                canvas.drawLine(x, midY, x + w - head * 0.4f, midY, strokePaint)
                val headPath = Path().apply {
                    moveTo(x + w, midY)
                    lineTo(x + w - head, midY - head * 0.65f)
                    lineTo(x + w - head, midY + head * 0.65f)
                    close()
                }
                canvas.drawPath(headPath, Paint(strokePaint).apply { style = Paint.Style.FILL; pathEffect = null })
            }
            ShapeType.DOUBLE_ARROW -> {
                val midY = y + h / 2f
                val head = min(22f, min(w * 0.25f, h * 0.45f))
                canvas.drawLine(x + head * 0.4f, midY, x + w - head * 0.4f, midY, strokePaint)
                val fillHead = Paint(strokePaint).apply { style = Paint.Style.FILL; pathEffect = null }
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
                canvas.drawPath(rightHead, fillHead)
                canvas.drawPath(leftHead, fillHead)
            }
            else -> {
                val path = buildAndroidShapePath(shape)
                if (shape.isFilled) canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
            }
        }
        canvas.restore()
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
            StrokeStyle.ANIMATED_PULSE -> DashPathEffect(floatArrayOf(width * 3f, width * 2f), 0f)
            else -> null
        }
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
