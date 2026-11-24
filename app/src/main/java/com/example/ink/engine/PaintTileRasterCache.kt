package com.example.ink.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.LruCache
import com.example.model.DrawingLayer
import com.example.model.LayerBlendMode
import com.example.model.ShapeItem
import com.example.model.WhiteboardStroke
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Two-Level Chunked World-Space Raster Cache (v3.2.0):
 * - Level A: Per-layer 512x512 ARGB_8888 cached tiles baking that layer's vector strokes/shapes
 *   and compositing any sparse mutable raster tiles from [MutableRasterTileStore] (honoring `isAlphaLocked`).
 * - Level B: Composite visible-layer stack cached per tile (tx, ty) applying per-layer `opacity`
 *   and [LayerBlendMode] (`NORMAL`, `MULTIPLY`, `SCREEN`, `OVERLAY`) cleanly across API 24-36.
 *
 * Also exposes [bakeSingleLayerTile] so [PigmentMixEngine] and [ScanlineFloodFillEngine] can inspect
 * watertight vector/raster layer contents for accurate smudging and Reference Layer flood fill.
 */
class PaintTileRasterCache(
    val mutableRasterStore: MutableRasterTileStore = MutableRasterTileStore()
) {

    data class TileBucket(
        val boardId: String,
        val tx: Int,
        val ty: Int,
        val signature: Long,
        val strokes: List<WhiteboardStroke>,
        val shapes: List<ShapeItem>,
        val layers: List<DrawingLayer> = listOf(DrawingLayer()),
        val hasMutableRaster: Boolean = false
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

    private val maxCacheBytes: Int =
        calculateCacheBudgetBytes(Runtime.getRuntime().maxMemory())

    // Level B: Final composited tiles (tx, ty)
    private val compositeCache = object : LruCache<Long, CachedTile>((maxCacheBytes * 0.65f).toInt().coerceAtLeast(2 * 1024 * 1024)) {
        override fun sizeOf(key: Long, value: CachedTile): Int = value.bitmap.byteCount.coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: Long, oldValue: CachedTile, newValue: CachedTile?) {
            if (evicted && oldValue.bitmap !== newValue?.bitmap) {
                reusableCompositeCanvas.setBitmap(null)
                if (!oldValue.bitmap.isRecycled) oldValue.bitmap.recycle()
            }
        }
    }

    // Level A: Per-layer baked vector+raster tiles keyed by "$boardId:$layerId:$tx:$ty"
    private val layerTileCache = object : LruCache<String, CachedTile>((maxCacheBytes * 0.35f).toInt().coerceAtLeast(2 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: CachedTile): Int = value.bitmap.byteCount.coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: CachedTile, newValue: CachedTile?) {
            if (evicted && oldValue.bitmap !== newValue?.bitmap) {
                reusableLayerCanvas.setBitmap(null)
                if (!oldValue.bitmap.isRecycled) oldValue.bitmap.recycle()
            }
        }
    }

    private val tileSrcRect = Rect(0, 0, TILE_PIXEL_SIZE, TILE_PIXEL_SIZE)
    private val tileDstRectF = RectF()
    private val reusableCompositeCanvas = Canvas()
    private val reusableLayerCanvas = Canvas()

    private val rasterPaint = Paint().apply {
        isDither = false
    }

    fun clear() {
        reusableCompositeCanvas.setBitmap(null)
        reusableLayerCanvas.setBitmap(null)
        compositeCache.evictAll()
        layerTileCache.evictAll()
    }

    /** Release half of the disposable raster cache when Android reports memory pressure. */
    fun trimMemory() {
        reusableCompositeCanvas.setBitmap(null)
        reusableLayerCanvas.setBitmap(null)
        compositeCache.trimToSize(compositeCache.maxSize() / 2)
        layerTileCache.trimToSize(layerTileCache.maxSize() / 2)
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
        handDrawnFactor: Float,
        layers: List<DrawingLayer> = listOf(DrawingLayer())
    ): SpatialIndex {
        val resolvedLayers = layers.ifEmpty { listOf(DrawingLayer()) }
        val layerById = resolvedLayers.associateBy { it.id }
        val fallbackLayer = resolvedLayers.first()

        val strokeBuckets = HashMap<Long, MutableList<WhiteboardStroke>>()
        val shapeBuckets = HashMap<Long, MutableList<ShapeItem>>()
        val activeKeys = HashSet<Long>()
        val rasterTilesByKey = HashSet<Long>()

        for (layer in resolvedLayers) {
            if (!layer.isVisible) continue
            val rasterTiles = mutableRasterStore.getTilesForLayer(boardId, layer.id)
            for (coord in rasterTiles.keys) {
                val key = packTileKey(coord.tx, coord.ty)
                activeKeys.add(key)
                rasterTilesByKey.add(key)
            }
        }

        if (strokes.isEmpty() && shapes.isEmpty() && activeKeys.isEmpty()) {
            return SpatialIndex(boardId, emptyMap())
        }

        for (stroke in strokes) {
            if (stroke.id == selectedStrokeId || selectedStrokeIds.contains(stroke.id)) continue
            val targetLayer = layerById[stroke.layerId] ?: fallbackLayer
            if (!targetLayer.isVisible) continue
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
            val targetLayer = layerById[shape.layerId] ?: fallbackLayer
            if (!targetLayer.isVisible) continue

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
        var baseSig = mixHash(
            mixHash(boardId.hashCode().toLong(), if (isDarkBoard) 1L else 0L),
            handDrawnFactor.toBits().toLong()
        )
        baseSig = mixHash(baseSig, resolvedLayers.size.toLong())
        for (layer in resolvedLayers) {
            baseSig = mixHash(baseSig, layer.id.hashCode().toLong())
            baseSig = mixHash(baseSig, if (layer.isVisible) 1L else 0L)
            baseSig = mixHash(baseSig, layer.opacity.toBits().toLong())
            baseSig = mixHash(baseSig, layer.blendMode.ordinal.toLong())
            baseSig = mixHash(baseSig, if (layer.isAlphaLocked) 1L else 0L)
            baseSig = mixHash(baseSig, layer.rasterRevision xor mutableRasterStore.getLayerRevision(boardId, layer.id))
        }

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
                sig = mixHash(sig, s.studioSettings.hashCode().toLong())
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
                shapes = tileShapes,
                layers = resolvedLayers,
                hasMutableRaster = rasterTilesByKey.contains(key)
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

    /**
     * Bakes a single layer's vectors + mutable raster tile at (tx, ty) for use by [PigmentMixEngine]
     * or [ScanlineFloodFillEngine] Reference Layer sampling.
     */
    fun bakeSingleLayerTile(
        boardId: String,
        layer: DrawingLayer,
        strokes: List<WhiteboardStroke>,
        shapes: List<ShapeItem>,
        tx: Int,
        ty: Int,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ): Bitmap? {
        val tileLeft = tx * TILE_WORLD_SIZE
        val tileTop = ty * TILE_WORLD_SIZE
        val tileRight = tileLeft + TILE_WORLD_SIZE
        val tileBottom = tileTop + TILE_WORLD_SIZE

        val localStrokes = strokes.filter { s ->
            s.layerId == layer.id &&
                s.geometryMetrics.hasPoints &&
                s.geometryMetrics.maxX + s.width * 2.5f >= tileLeft &&
                s.geometryMetrics.minX - s.width * 2.5f <= tileRight &&
                s.geometryMetrics.maxY + s.width * 2.5f >= tileTop &&
                s.geometryMetrics.minY - s.width * 2.5f <= tileBottom
        }
        val localShapes = shapes.filter { sh ->
            !sh.isHiddenByCollapse && sh.layerId == layer.id &&
                sh.x + sh.width + sh.strokeWidth * 3f >= tileLeft &&
                sh.x - sh.strokeWidth * 3f <= tileRight &&
                sh.y + sh.height + sh.strokeWidth * 3f >= tileTop &&
                sh.y - sh.strokeWidth * 3f <= tileBottom
        }
        val mutableTile = mutableRasterStore.getTile(boardId, layer.id, tx, ty)
        if (localStrokes.isEmpty() && localShapes.isEmpty() && mutableTile == null) return null

        val bmp = Bitmap.createBitmap(TILE_PIXEL_SIZE, TILE_PIXEL_SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        renderLayerContentToTileCanvas(
            canvas = c,
            boardId = boardId,
            layer = layer,
            layerStrokes = localStrokes,
            layerShapes = localShapes,
            mutableTile = mutableTile,
            tilePanX = -tileLeft,
            tilePanY = -tileTop,
            isDarkBoard = isDarkBoard,
            handDrawnFactor = handDrawnFactor
        )
        return bmp
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
        val bmp = getOrBakeCompositeTile(bucket, isDarkBoard, handDrawnFactor)
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

    private fun getOrBakeCompositeTile(
        bucket: TileBucket,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ): Bitmap {
        val key = packTileKey(bucket.tx, bucket.ty)
        val existing = compositeCache.get(key)
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
            compositeCache.put(key, CachedTile(bucket.boardId, bucket.signature, bitmap))
        }

        reusableCompositeCanvas.setBitmap(bitmap)
        val tilePanX = -(bucket.tx * TILE_WORLD_SIZE)
        val tilePanY = -(bucket.ty * TILE_WORLD_SIZE)

        val resolvedLayers = bucket.layers.ifEmpty { listOf(DrawingLayer()) }
        val knownLayerIds = resolvedLayers.map { it.id }.toSet()
        val firstLayerId = resolvedLayers.first().id

        for (layer in resolvedLayers) {
            if (!layer.isVisible) continue
            val layerOpacityAlpha = (layer.opacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            if (layerOpacityAlpha == 0) continue

            val layerStrokes = bucket.strokes.filter { s ->
                val resolvedId = if (knownLayerIds.contains(s.layerId)) s.layerId else firstLayerId
                resolvedId == layer.id
            }
            val layerShapes = bucket.shapes.filter { sh ->
                val resolvedId = if (knownLayerIds.contains(sh.layerId)) sh.layerId else firstLayerId
                resolvedId == layer.id
            }
            val mutableTile = mutableRasterStore.getTile(bucket.boardId, layer.id, bucket.tx, bucket.ty)
            if (layerStrokes.isEmpty() && layerShapes.isEmpty() && mutableTile == null) continue

            // Fast path when single normal opaque layer doesn't need blend isolation
            val needsLayerIsolation = layer.blendMode != LayerBlendMode.NORMAL ||
                (layerOpacityAlpha < 253 && (layerStrokes.size + layerShapes.size + (if (mutableTile != null) 1 else 0) > 1))

            if (layer.blendMode == LayerBlendMode.NORMAL) {
                val layerBmp = getOrBakeLayerTile(
                    boardId = bucket.boardId,
                    layer = layer,
                    tx = bucket.tx,
                    ty = bucket.ty,
                    layerStrokes = layerStrokes,
                    layerShapes = layerShapes,
                    mutableTile = mutableTile,
                    tilePanX = tilePanX,
                    tilePanY = tilePanY,
                    isDarkBoard = isDarkBoard,
                    handDrawnFactor = handDrawnFactor
                )
                val blitPaint = Paint().apply { alpha = layerOpacityAlpha }
                reusableCompositeCanvas.drawBitmap(layerBmp, 0f, 0f, blitPaint)
            } else {
                val layerBmp = getOrBakeLayerTile(
                    boardId = bucket.boardId,
                    layer = layer,
                    tx = bucket.tx,
                    ty = bucket.ty,
                    layerStrokes = layerStrokes,
                    layerShapes = layerShapes,
                    mutableTile = mutableTile,
                    tilePanX = tilePanX,
                    tilePanY = tilePanY,
                    isDarkBoard = isDarkBoard,
                    handDrawnFactor = handDrawnFactor
                )
                val blendPaint = Paint().apply {
                    alpha = layerOpacityAlpha
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        blendMode = layer.blendMode.toAndroidBlendMode()
                    } else {
                        layer.blendMode.toLegacyPorterDuff()?.let { xfermode = PorterDuffXfermode(it) }
                    }
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && layer.blendMode == LayerBlendMode.MULTIPLY) {
                    val save = reusableCompositeCanvas.saveLayer(0f, 0f, TILE_WORLD_SIZE, TILE_WORLD_SIZE, blendPaint)
                    try {
                        reusableCompositeCanvas.drawColor(Color.WHITE)
                        reusableCompositeCanvas.drawBitmap(layerBmp, 0f, 0f, Paint().apply { alpha = layerOpacityAlpha })
                    } finally {
                        reusableCompositeCanvas.restoreToCount(save)
                    }
                } else {
                    reusableCompositeCanvas.drawBitmap(layerBmp, 0f, 0f, blendPaint)
                }
            }
        }

        return bitmap
    }

    private fun getOrBakeLayerTile(
        boardId: String,
        layer: DrawingLayer,
        tx: Int,
        ty: Int,
        layerStrokes: List<WhiteboardStroke>,
        layerShapes: List<ShapeItem>,
        mutableTile: Bitmap?,
        tilePanX: Float,
        tilePanY: Float,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ): Bitmap {
        var sig = mixHash(boardId.hashCode().toLong(), layer.id.hashCode().toLong())
        sig = mixHash(sig, if (isDarkBoard) 1L else 0L)
        sig = mixHash(sig, handDrawnFactor.toBits().toLong())
        sig = mixHash(sig, if (layer.isAlphaLocked) 1L else 0L)
        sig = mixHash(sig, layer.rasterRevision xor mutableRasterStore.getLayerRevision(boardId, layer.id))
        sig = mixHash(sig, layerStrokes.size.toLong())
        for (s in layerStrokes) {
            sig = mixHash(sig, s.id.hashCode().toLong())
            sig = mixHash(sig, s.geometryMetrics.pointsHash.toLong())
            sig = mixHash(sig, s.colorHex.hashCode().toLong())
            sig = mixHash(sig, s.width.toBits().toLong())
            sig = mixHash(sig, s.opacity.toBits().toLong())
            sig = mixHash(sig, s.studioSettings.hashCode().toLong())
        }
        sig = mixHash(sig, layerShapes.size.toLong())
        for (sh in layerShapes) {
            sig = mixHash(sig, sh.id.hashCode().toLong())
            sig = mixHash(sig, sh.x.toBits().toLong())
            sig = mixHash(sig, sh.y.toBits().toLong())
            sig = mixHash(sig, sh.width.toBits().toLong())
            sig = mixHash(sig, sh.height.toBits().toLong())
            sig = mixHash(sig, sh.rotation.toBits().toLong())
            sig = mixHash(sig, sh.strokeColorHex.hashCode().toLong())
        }

        val cacheKey = "$boardId:${layer.id}:$tx:$ty"
        val existing = layerTileCache.get(cacheKey)
        if (existing != null && existing.signature == sig) {
            return existing.bitmap
        }

        val bitmap: Bitmap
        if (existing != null) {
            bitmap = existing.bitmap
            bitmap.eraseColor(Color.TRANSPARENT)
            existing.signature = sig
        } else {
            bitmap = Bitmap.createBitmap(TILE_PIXEL_SIZE, TILE_PIXEL_SIZE, Bitmap.Config.ARGB_8888)
            layerTileCache.put(cacheKey, CachedTile(boardId, sig, bitmap))
        }

        reusableLayerCanvas.setBitmap(bitmap)
        renderLayerContentToTileCanvas(
            canvas = reusableLayerCanvas,
            boardId = boardId,
            layer = layer,
            layerStrokes = layerStrokes,
            layerShapes = layerShapes,
            mutableTile = mutableTile,
            tilePanX = tilePanX,
            tilePanY = tilePanY,
            isDarkBoard = isDarkBoard,
            handDrawnFactor = handDrawnFactor
        )
        return bitmap
    }

    private fun renderLayerContentToTileCanvas(
        canvas: Canvas,
        boardId: String,
        layer: DrawingLayer,
        layerStrokes: List<WhiteboardStroke>,
        layerShapes: List<ShapeItem>,
        mutableTile: Bitmap?,
        tilePanX: Float,
        tilePanY: Float,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ) {
        for (stroke in layerStrokes) {
            UnifiedBrushPipeline.renderStroke(
                canvas = canvas,
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
        for (shape in layerShapes) {
            UnifiedBrushPipeline.renderShape(
                canvas = canvas,
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
        if (mutableTile != null && !mutableTile.isRecycled) {
            val rasterOverlayPaint = if (layer.isAlphaLocked) {
                Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
            } else {
                null
            }
            canvas.drawBitmap(mutableTile, 0f, 0f, rasterOverlayPaint)
        }
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
