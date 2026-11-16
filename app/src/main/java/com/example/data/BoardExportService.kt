package com.example.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.ink.StickyNoteGeometry
import com.example.ink.engine.UnifiedBrushPipeline
import com.example.model.CanvasGridType
import com.example.model.WhiteboardBoard
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

        // 3. Strokes rendered through the Unified 4-Stage Brush Pipeline
        board.strokes.forEach { stroke ->
            if (stroke.points.size >= 2) {
                UnifiedBrushPipeline.renderStroke(
                    canvas = canvas,
                    stroke = stroke,
                    isDarkBoard = isDarkBoard,
                    isOpaqueCanvasBackground = true
                )
            }
        }

        // 4. Shapes rendered through the Unified 4-Stage Brush Pipeline
        board.shapes.forEach { shape ->
            if (!shape.isHiddenByCollapse) {
                UnifiedBrushPipeline.renderShape(
                    canvas = canvas,
                    shape = shape,
                    isDarkBoard = isDarkBoard,
                    isOpaqueCanvasBackground = true,
                    drawLabel = true
                )
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
            val inkColor = if (isDarkNote) Color.WHITE else Color.parseColor("#0F172A")
            noteTextPaint.color = inkColor
            val cleanTitle = note.title?.trim().orEmpty()
            val textBody = note.text.ifBlank { if (cleanTitle.isEmpty()) "Nota adhesiva" else "" }
            val pad = StickyNoteGeometry.innerPaddingWorld(note.width, note.height)
            val (innerW, innerH) = StickyNoteGeometry.innerTextBoxWorld(note.width, note.height)
            val baseFont = StickyNoteGeometry.estimateAutoFitFontSizeWorld(
                text = textBody,
                innerWidthWorld = innerW,
                innerHeightWorld = innerH,
                title = note.title
            )
            val textWidth = innerW.roundToInt().coerceAtLeast(24)
            canvas.translate(note.x + pad, note.y + StickyNoteGeometry.headerHeightWorld(note.height) + pad)

            if (cleanTitle.isNotEmpty()) {
                val titlePaint = TextPaint(noteTextPaint).apply {
                    textSize = baseFont * 1.15f
                    isFakeBoldText = true
                }
                @Suppress("DEPRECATION")
                val titleLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    StaticLayout.Builder.obtain(cleanTitle, 0, cleanTitle.length, titlePaint, textWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .build()
                } else {
                    StaticLayout(cleanTitle, titlePaint, textWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
                }
                titleLayout.draw(canvas)
                val dividerY = titleLayout.height + 4f
                val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(42, Color.red(inkColor), Color.green(inkColor), Color.blue(inkColor))
                    strokeWidth = 1.2f
                }
                canvas.drawLine(0f, dividerY, textWidth.toFloat(), dividerY, dividerPaint)
                canvas.translate(0f, dividerY + 5f)
            }

            if (textBody.isNotEmpty()) {
                noteTextPaint.textSize = baseFont
                @Suppress("DEPRECATION")
                val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    StaticLayout.Builder.obtain(textBody, 0, textBody.length, noteTextPaint, textWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .build()
                } else {
                    StaticLayout(textBody, noteTextPaint, textWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
                }
                layout.draw(canvas)
            }
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
