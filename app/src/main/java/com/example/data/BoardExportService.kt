package com.example.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.ink.engine.MemoryBudgetProfile
import com.example.ink.engine.MutableRasterTileStore
import com.example.ink.engine.PaintTileRasterCache
import com.example.ink.engine.UnifiedBrushPipeline
import com.example.ink.engine.toAndroidBlendMode
import com.example.ink.engine.toLegacyPorterDuff
import com.example.model.CanvasGridType
import com.example.model.DEFAULT_LAYER_ID
import com.example.model.DrawingLayer
import com.example.model.LayerBlendMode
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
        maxDimensionPx: Int = 1024,
        mutableRasterStore: MutableRasterTileStore? = null,
        onlyLayerId: String? = null,
        resolutionMultiplier: Int = 1,
        hardMaxDimensionPx: Int = 2048
    ): Bitmap {
        val allLayers = board.layers.ifEmpty { listOf(DrawingLayer()) }
        val targetLayers = if (onlyLayerId != null) {
            allLayers.filter { it.id == onlyLayerId }
        } else {
            allLayers.filter { it.isVisible }
        }
        val targetLayerIds = targetLayers.map { it.id }.toSet()

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var hasContent = false

        board.strokes.forEach { s ->
            if (onlyLayerId != null && s.layerId != onlyLayerId) return@forEach
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
            if (onlyLayerId != null && sh.layerId != onlyLayerId) return@forEach
            hasContent = true
            val shapePad = max(sh.strokeWidth * 2.2f, 18f)
            minX = min(minX, sh.x - shapePad)
            maxX = max(maxX, sh.x + sh.width + shapePad)
            minY = min(minY, sh.y - shapePad)
            maxY = max(maxY, sh.y + sh.height + shapePad)
        }
        if (mutableRasterStore != null) {
            for (layerId in targetLayerIds) {
                val tiles = mutableRasterStore.getTilesForLayer(board.id, layerId)
                for (coord in tiles.keys) {
                    hasContent = true
                    val tileLeft = coord.tx * PaintTileRasterCache.TILE_WORLD_SIZE
                    val tileTop = coord.ty * PaintTileRasterCache.TILE_WORLD_SIZE
                    minX = min(minX, tileLeft)
                    maxX = max(maxX, tileLeft + PaintTileRasterCache.TILE_WORLD_SIZE)
                    minY = min(minY, tileTop)
                    maxY = max(maxY, tileTop + PaintTileRasterCache.TILE_WORLD_SIZE)
                }
            }
        }
        if (onlyLayerId == null) {
            board.textItems.forEach { t ->
                hasContent = true
                val tw = max(t.width, 140f)
                val th = max(t.height, 60f)
                minX = min(minX, t.x)
                maxX = max(maxX, t.x + tw)
                minY = min(minY, t.y)
                maxY = max(maxY, t.y + th)
            }
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

        // Hard 2x export cap to prevent 64 MB 4K OOM crashes on 1 GB RAM phones.
        val mult = resolutionMultiplier.coerceIn(1, MemoryBudgetProfile.HARD_MAX_EXPORT_MULTIPLIER)
        val ceiling = hardMaxDimensionPx.coerceIn(480, 2048)
        val effectiveMaxDim = (maxDimensionPx.toLong() * mult).coerceIn(480L, ceiling.toLong()).toInt()
        val worldW = (maxX - minX).coerceAtLeast(360f)
        val worldH = (maxY - minY).coerceAtLeast(240f)
        val longest = max(worldW, worldH)
        val scale = (effectiveMaxDim.toFloat() / longest).coerceIn(0.4f, 2.2f * mult)
        val bmpW = (worldW * scale).roundToInt().coerceIn(320, effectiveMaxDim)
        val bmpH = (worldH * scale).roundToInt().coerceIn(240, effectiveMaxDim)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (includeBackground) {
            val bgColor = safeParseColor(board.colorTheme.hex, Color.WHITE)
            canvas.drawColor(bgColor)
        } else {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }

        if (includeBackground && includeGrid && board.gridType != CanvasGridType.BLANK) {
            drawExportGrid(canvas, board, scale, bmpW.toFloat(), bmpH.toFloat())
        }

        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-minX, -minY)

        val isDarkBoard = board.colorTheme.isDark

        val knownLayerIds = allLayers.map { it.id }.toSet()
        val strokesByLayer = board.strokes.groupBy { s -> if (s.layerId in knownLayerIds) s.layerId else DEFAULT_LAYER_ID }
        val shapesByLayer = board.shapes.groupBy { sh -> if (sh.layerId in knownLayerIds) sh.layerId else DEFAULT_LAYER_ID }

        val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val alphaLockTilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }

        allLayers.forEach { layer ->
            if (onlyLayerId != null && layer.id != onlyLayerId) return@forEach
            if (onlyLayerId == null && !layer.isVisible) return@forEach
            if (layer.opacity <= 0.01f) return@forEach

            val layerAlpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
            val layerBlendPaint = Paint().apply {
                alpha = layerAlpha
                if (layer.blendMode != LayerBlendMode.NORMAL) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        blendMode = layer.blendMode.toAndroidBlendMode()
                    } else {
                        layer.blendMode.toLegacyPorterDuff()?.let { xfermode = PorterDuffXfermode(it) }
                    }
                }
            }
            val needsIsolation = layerAlpha < 252 || layer.blendMode != LayerBlendMode.NORMAL || layer.isAlphaLocked
            val saveCount = if (needsIsolation) {
                canvas.saveLayer(minX, minY, maxX, maxY, layerBlendPaint)
            } else -1

            strokesByLayer[layer.id].orEmpty().forEach { stroke ->
                if (stroke.points.size >= 2) {
                    UnifiedBrushPipeline.renderStroke(
                        canvas = canvas,
                        stroke = stroke,
                        isDarkBoard = isDarkBoard,
                        isOpaqueCanvasBackground = includeBackground
                    )
                }
            }

            shapesByLayer[layer.id].orEmpty().forEach { shape ->
                if (!shape.isHiddenByCollapse) {
                    UnifiedBrushPipeline.renderShape(
                        canvas = canvas,
                        shape = shape,
                        isDarkBoard = isDarkBoard,
                        isOpaqueCanvasBackground = includeBackground,
                        drawLabel = true
                    )
                }
            }

            if (mutableRasterStore != null) {
                val layerTiles = mutableRasterStore.getTilesForLayer(board.id, layer.id)
                val paintToUse = if (layer.isAlphaLocked) alphaLockTilePaint else tilePaint
                for ((coord, tileBmp) in layerTiles) {
                    if (!tileBmp.isRecycled) {
                        val wx = coord.tx * PaintTileRasterCache.TILE_WORLD_SIZE
                        val wy = coord.ty * PaintTileRasterCache.TILE_WORLD_SIZE
                        canvas.drawBitmap(tileBmp, wx, wy, paintToUse)
                    }
                }
            }

            if (saveCount != -1) {
                canvas.restoreToCount(saveCount)
            }
        }

        if (onlyLayerId == null) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.argb(45, 15, 23, 42)
            }
            board.textItems.forEach { t ->
                if (t.isHiddenByCollapse) return@forEach
                canvas.save()
                val tPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = t.fontSize.coerceIn(12f, 72f)
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
                val cardH = max(t.height, layout.height.toFloat() + 20f).coerceAtLeast(48f)
                if (t.rotation != 0f) {
                    canvas.rotate(t.rotation, t.x + t.width * 0.5f, t.y + cardH * 0.5f)
                }
                if (t.isEditableCard && t.backgroundColorHex != "TRANSPARENT") {
                    val cardBg = safeParseColor(t.backgroundColorHex ?: "#FFFFFF", Color.WHITE)
                    val cardRect = RectF(t.x, t.y, t.x + t.width, t.y + cardH)
                    fillPaint.color = cardBg
                    canvas.drawRoundRect(cardRect, 12f, 12f, fillPaint)
                    borderPaint.color = Color.parseColor("#475569")
                    canvas.drawRoundRect(cardRect, 12f, 12f, borderPaint)
                }
                canvas.translate(t.x + if (t.isEditableCard) 10f else 0f, t.y + if (t.isEditableCard) 10f else 0f)
                layout.draw(canvas)
                canvas.restore()
            }
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
                gridPaint.style = Paint.Style.STROKE
                var x = step * 0.5f
                while (x < w) {
                    var y = step * 0.5f
                    while (y < h) {
                        canvas.drawCircle(x, y, step * 0.35f, gridPaint)
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
