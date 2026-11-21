package com.example.ink.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import com.example.model.ShapeItem
import com.example.model.WhiteboardStroke
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MS Paint-style chunked world-space raster cache.
 *
 * Committed strokes and shapes are baked once into sparse 512x512 world-unit ARGB_8888
 * bitmaps (1 world unit = 1 pixel). During pan & zoom, only visible non-empty tiles are
 * blitted directly to the canvas without re-executing vector/brush tessellation.
 *
 * When zooming in past [PIXELATE_SCALE_THRESHOLD] (> 1.02x), bitmap filtering is disabled
 * (nearest-neighbor sampling), producing crisp Paint-like pixelation with O(tiles) frame cost.
 */
class PaintTileRasterCache {

    data class TileBucket(
        val boardId: String,
        val tx: Int,
        val ty: Int,
        val signature: Long,
        val strokes: List<WhiteboardStroke>,
        val shapes: List<ShapeItem>
    )

    class SpatialIndex(
        val boardId: String,
        val buckets: Map<Long, TileBucket>
    ) {
        fun bucketAt(tx: Int, ty: Int): TileBucket? = buckets[packTileKey(tx, ty)]
    }

    private class CachedTile(
        var boardId: String,
        var signature: Long,
        val bitmap: Bitmap
    )

    // Raster tiles are disposable derived data. Keep their budget conservative so
    // low-memory devices do not reserve a large part of the app heap for cached pixels.
    private val maxCacheBytes: Int =
        calculateCacheBudgetBytes(Runtime.getRuntime().maxMemory())

    private val tileCache = object : LruCache<Long, CachedTile>(maxCacheBytes) {
        override fun sizeOf(key: Long, value: CachedTile): Int {
            return value.bitmap.byteCount.coerceAtLeast(1)
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: Long,
            oldValue: CachedTile,
            newValue: CachedTile?
        ) {
            if (evicted && oldValue.bitmap !== newValue?.bitmap) {
                // Canvas retains its last bitmap target; detach before releasing native pixels.
                reusableCanvas.setBitmap(null)
                if (!oldValue.bitmap.isRecycled) oldValue.bitmap.recycle()
            }
        }
    }

    private val tileSrcRect = Rect(0, 0, TILE_PIXEL_SIZE, TILE_PIXEL_SIZE)
    private val tileDstRectF = RectF()
    private val reusableCanvas = Canvas()

    private val rasterPaint = Paint().apply {
        isDither = false
    }

    fun clear() {
        reusableCanvas.setBitmap(null)
        tileCache.evictAll()
    }

    /** Release half of the disposable raster cache when Android reports memory pressure. */
    fun trimMemory() {
        reusableCanvas.setBitmap(null)
        tileCache.trimToSize(maxCacheBytes / 2)
    }

    fun buildSpatialIndex(
        boardId: String,
        strokes: List<WhiteboardStroke>,
        shapes: List<ShapeItem>,
        selectedStrokeId: String?,
        selectedStrokeIds: Set<String>,
        selectedShapeId: String?,
        selectedShapeIds: Set<String>,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ): SpatialIndex {
        if (strokes.isEmpty() && shapes.isEmpty()) {
            return SpatialIndex(boardId, emptyMap())
        }

        val strokeBuckets = HashMap<Long, MutableList<WhiteboardStroke>>()
        val shapeBuckets = HashMap<Long, MutableList<ShapeItem>>()
        val activeKeys = HashSet<Long>()

        for (stroke in strokes) {
            if (stroke.id == selectedStrokeId || selectedStrokeIds.contains(stroke.id)) continue
            val metrics = stroke.geometryMetrics
            if (!metrics.hasPoints) continue
            val pad = stroke.width * 2.2f + 16f
            val txMin = floor((metrics.minX - pad) / TILE_WORLD_SIZE).toInt()
            val txMax = floor((metrics.maxX + pad) / TILE_WORLD_SIZE).toInt()
            val tyMin = floor((metrics.minY - pad) / TILE_WORLD_SIZE).toInt()
            val tyMax = floor((metrics.maxY + pad) / TILE_WORLD_SIZE).toInt()
            for (tx in txMin..txMax) {
                for (ty in tyMin..tyMax) {
                    val key = packTileKey(tx, ty)
                    activeKeys.add(key)
                    strokeBuckets.getOrPut(key) { mutableListOf() }.add(stroke)
                }
            }
        }

        for (shape in shapes) {
            if (shape.isHiddenByCollapse) continue
            if (shape.id == selectedShapeId || selectedShapeIds.contains(shape.id)) continue

            val cx = shape.x + shape.width * 0.5f
            val cy = shape.y + shape.height * 0.5f
            val pad = shape.strokeWidth * 2.5f + 28f
            val halfDiag = hypot(shape.width, shape.height) * 0.5f + pad

            var minX = cx - halfDiag
            var maxX = cx + halfDiag
            var minY = cy - halfDiag
            var maxY = cy + halfDiag
            for (v in shape.vertices) {
                if (v.x - pad < minX) minX = v.x - pad
                if (v.x + pad > maxX) maxX = v.x + pad
                if (v.y - pad < minY) minY = v.y - pad
                if (v.y + pad > maxY) maxY = v.y + pad
            }

            val txMin = floor(minX / TILE_WORLD_SIZE).toInt()
            val txMax = floor(maxX / TILE_WORLD_SIZE).toInt()
            val tyMin = floor(minY / TILE_WORLD_SIZE).toInt()
            val tyMax = floor(maxY / TILE_WORLD_SIZE).toInt()
            for (tx in txMin..txMax) {
                for (ty in tyMin..tyMax) {
                    val key = packTileKey(tx, ty)
                    activeKeys.add(key)
                    shapeBuckets.getOrPut(key) { mutableListOf() }.add(shape)
                }
            }
        }

        if (activeKeys.isEmpty()) {
            return SpatialIndex(boardId, emptyMap())
        }

        val buckets = HashMap<Long, TileBucket>(activeKeys.size)
        val baseSig = mixHash(
            mixHash(boardId.hashCode().toLong(), if (isDarkBoard) 1L else 0L),
            handDrawnFactor.toBits().toLong()
        )

        for (key in activeKeys) {
            val tx = unpackTx(key)
            val ty = unpackTy(key)
            val tileStrokes = strokeBuckets[key] ?: emptyList()
            val tileShapes = shapeBuckets[key] ?: emptyList()

            var sig = baseSig
            sig = mixHash(sig, tileStrokes.size.toLong())
            for (s in tileStrokes) {
                sig = mixHash(sig, s.id.hashCode().toLong())
                sig = mixHash(sig, s.geometryMetrics.pointsHash.toLong())
                sig = mixHash(sig, s.width.toBits().toLong())
                sig = mixHash(sig, s.colorHex.hashCode().toLong())
                sig = mixHash(sig, s.opacity.toBits().toLong())
                sig = mixHash(
                    sig,
                    ((s.brushType.ordinal shl 8) or
                        (s.capStyle.ordinal shl 4) or
                        (if (s.isHighlighter) 2 else 0) or
                        (if (s.isRainbow) 1 else 0)).toLong()
                )
            }
            sig = mixHash(sig, tileShapes.size.toLong())
            for (sh in tileShapes) {
                sig = mixHash(sig, sh.id.hashCode().toLong())
                sig = mixHash(sig, sh.shapeType.ordinal.toLong())
                sig = mixHash(sig, sh.x.toBits().toLong())
                sig = mixHash(sig, sh.y.toBits().toLong())
                sig = mixHash(sig, sh.width.toBits().toLong())
                sig = mixHash(sig, sh.height.toBits().toLong())
                sig = mixHash(sig, sh.rotation.toBits().toLong())
                sig = mixHash(sig, sh.relativeVerticesHash.toLong())
                sig = mixHash(sig, sh.strokeColorHex.hashCode().toLong())
                sig = mixHash(sig, sh.fillColorHex.hashCode().toLong())
                sig = mixHash(sig, sh.strokeWidth.toBits().toLong())
                sig = mixHash(sig, sh.label.hashCode().toLong())
                sig = mixHash(sig, sh.opacity.toBits().toLong())
                sig = mixHash(
                    sig,
                    ((sh.brushType.ordinal shl 8) or
                        (sh.capStyle.ordinal shl 4) or
                        (if (sh.isFilled) 2 else 0) or
                        (if (sh.isRainbow) 1 else 0)).toLong()
                )
            }

            buckets[key] = TileBucket(
                boardId = boardId,
                tx = tx,
                ty = ty,
                signature = sig,
                strokes = tileStrokes,
                shapes = tileShapes
            )
        }

        return SpatialIndex(boardId, buckets)
    }

    fun renderVisibleTiles(
        canvas: Canvas,
        spatialIndex: SpatialIndex,
        panX: Float,
        panY: Float,
        scale: Float,
        screenW: Float,
        screenH: Float,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ) {
        if (spatialIndex.buckets.isEmpty() || screenW <= 0f || screenH <= 0f) return
        val safeScale = scale.coerceAtLeast(0.05f)
        val visWorldLeft = -panX / safeScale
        val visWorldTop = -panY / safeScale
        val visWorldRight = (screenW - panX) / safeScale
        val visWorldBottom = (screenH - panY) / safeScale

        val visTxMin = floor(visWorldLeft / TILE_WORLD_SIZE).toInt()
        val visTxMax = floor(visWorldRight / TILE_WORLD_SIZE).toInt()
        val visTyMin = floor(visWorldTop / TILE_WORLD_SIZE).toInt()
        val visTyMax = floor(visWorldBottom / TILE_WORLD_SIZE).toInt()

        // Pixelated nearest-neighbor sampling when zoomed closer than native 1:1 resolution.
        val shouldPixelate = safeScale > PIXELATE_SCALE_THRESHOLD
        rasterPaint.isFilterBitmap = !shouldPixelate
        rasterPaint.isAntiAlias = !shouldPixelate

        val gridCols = (visTxMax - visTxMin + 1).coerceAtLeast(0)
        val gridRows = (visTyMax - visTyMin + 1).coerceAtLeast(0)
        val gridCellCount = gridCols.toLong() * gridRows.toLong()

        if (spatialIndex.buckets.size <= 96 || gridCellCount > spatialIndex.buckets.size.toLong()) {
            for ((_, bucket) in spatialIndex.buckets) {
                if (bucket.tx in visTxMin..visTxMax && bucket.ty in visTyMin..visTyMax) {
                    drawSingleTile(canvas, bucket, panX, panY, safeScale, isDarkBoard, handDrawnFactor)
                }
            }
        } else {
            for (tx in visTxMin..visTxMax) {
                for (ty in visTyMin..visTyMax) {
                    val bucket = spatialIndex.bucketAt(tx, ty) ?: continue
                    drawSingleTile(canvas, bucket, panX, panY, safeScale, isDarkBoard, handDrawnFactor)
                }
            }
        }
    }

    private fun drawSingleTile(
        targetCanvas: Canvas,
        bucket: TileBucket,
        panX: Float,
        panY: Float,
        scale: Float,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ) {
        val bmp = getOrBakeTile(bucket, isDarkBoard, handDrawnFactor)
        val worldLeft = bucket.tx * TILE_WORLD_SIZE
        val worldTop = bucket.ty * TILE_WORLD_SIZE
        val worldRight = worldLeft + TILE_WORLD_SIZE
        val worldBottom = worldTop + TILE_WORLD_SIZE

        tileDstRectF.set(
            worldLeft * scale + panX,
            worldTop * scale + panY,
            worldRight * scale + panX,
            worldBottom * scale + panY
        )
        targetCanvas.drawBitmap(bmp, tileSrcRect, tileDstRectF, rasterPaint)
    }

    private fun getOrBakeTile(
        bucket: TileBucket,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ): Bitmap {
        val key = packTileKey(bucket.tx, bucket.ty)
        val existing = tileCache.get(key)
        if (existing != null && existing.boardId == bucket.boardId && existing.signature == bucket.signature) {
            return existing.bitmap
        }

        val bitmap: Bitmap
        if (existing != null) {
            bitmap = existing.bitmap
            bitmap.eraseColor(Color.TRANSPARENT)
            existing.boardId = bucket.boardId
            existing.signature = bucket.signature
        } else {
            bitmap = Bitmap.createBitmap(TILE_PIXEL_SIZE, TILE_PIXEL_SIZE, Bitmap.Config.ARGB_8888)
            tileCache.put(key, CachedTile(bucket.boardId, bucket.signature, bitmap))
        }

        reusableCanvas.setBitmap(bitmap)
        val tilePanX = -(bucket.tx * TILE_WORLD_SIZE)
        val tilePanY = -(bucket.ty * TILE_WORLD_SIZE)

        for (stroke in bucket.strokes) {
            UnifiedBrushPipeline.renderStroke(
                canvas = reusableCanvas,
                stroke = stroke,
                panX = tilePanX,
                panY = tilePanY,
                scale = 1.0f,
                animPhase = 0f,
                isSelected = false,
                isDarkBoard = isDarkBoard,
                isOpaqueCanvasBackground = false,
                handDrawnFactor = handDrawnFactor,
                viewportW = 0f,
                viewportH = 0f,
                preserveInputPoints = false
            )
        }

        for (shape in bucket.shapes) {
            UnifiedBrushPipeline.renderShape(
                canvas = reusableCanvas,
                shape = shape,
                panX = tilePanX,
                panY = tilePanY,
                scale = 1.0f,
                animPhase = 0f,
                isDarkBoard = isDarkBoard,
                isOpaqueCanvasBackground = false,
                handDrawnFactor = handDrawnFactor,
                drawLabel = true,
                viewportW = 0f,
                viewportH = 0f
            )
        }

        return bitmap
    }

    companion object {
        private const val MIN_CACHE_BYTES = 4 * 1024 * 1024
        private const val MAX_CACHE_BYTES = 16 * 1024 * 1024

        internal fun calculateCacheBudgetBytes(maxHeapBytes: Long): Int {
            val safeHeapBytes = maxHeapBytes.coerceAtLeast(16L * 1024L * 1024L)
            return (safeHeapBytes / 16L)
                .coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong())
                .toInt()
        }

        const val TILE_WORLD_SIZE = 512f
        const val TILE_PIXEL_SIZE = 512
        const val PIXELATE_SCALE_THRESHOLD = 1.02f

        fun packTileKey(tx: Int, ty: Int): Long {
            return (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
        }

        private fun unpackTx(key: Long): Int = (key shr 32).toInt()
        private fun unpackTy(key: Long): Int = key.toInt()

        private fun mixHash(acc: Long, value: Long): Long {
            var h = acc xor value
            h = (h xor (h ushr 27))
            return h
        }
    }
}
