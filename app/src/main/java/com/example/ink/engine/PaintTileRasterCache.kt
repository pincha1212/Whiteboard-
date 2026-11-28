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
import java.util.ArrayDeque
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Unified Chunked World-Space Raster Cache (v4.0.1):
 * - Single byte-bounded [LruCache] capped at [MemoryBudgetProfile.rasterCacheBytes] (6 MB strict 1 GB / 12 MB standard).
 * - Single-layer fast path eliminates duplicate Level A + Level B copies when 1 NORMAL opaque layer is active.
 * - Adaptive display tile resolution (`512`, `384`, `256`, `192`, `128`) prevents LRU cache thrashing across full viewports.
 * - O(1) incremental append baking draws ONLY newly added tail strokes onto already-baked tile bitmaps.
 */
class PaintTileRasterCache(
    val mutableRasterStore: MutableRasterTileStore = MutableRasterTileStore(),
    private val budget: MemoryBudgetProfile = MemoryBudgetProfile.standard(),
    val bitmapPool: TileBitmapPool = TileBitmapPool(budget.bitmapPoolMaxCount)
) {

    data class TileBucket(
        val boardId: String,
        val tx: Int,
        val ty: Int,
        val signature: Long,
        val strokes: List<WhiteboardStroke>,
        val shapes: List<ShapeItem>,
        val layers: List<DrawingLayer> = listOf(DrawingLayer()),
        val hasMutableRaster: Boolean = false,
        val envSignature: Long = 0L,
        val shapesSignature: Long = 0L,
        val strokeHashes: LongArray = EMPTY_LONG_ARRAY
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
        var envSignature: Long,
        var shapesSignature: Long,
        var hasMutableRaster: Boolean,
        var bakedStrokeHashes: LongArray,
        var isSingleLayerDirect: Boolean,
        val pixelSize: Int,
        val bitmap: Bitmap
    )

    // Telemetry counters for performance verification and tests
    var cacheHits: Long = 0L
        private set
    var incrementalAppends: Long = 0L
        private set
    var fullRebakes: Long = 0L
        private set
    var cacheEvictions: Long = 0L
        private set

    fun resetMetrics() {
        cacheHits = 0L
        incrementalAppends = 0L
        fullRebakes = 0L
        cacheEvictions = 0L
    }

    fun currentCacheBytes(): Int = tileCache.size()
    fun currentTileCount(): Int = tileCache.snapshot().size

    // Secondary pool for adaptive LOD display tiles (384, 256, 192, 128)
    private val secondaryPools = HashMap<Int, ArrayDeque<Bitmap>>()
    private var secondaryPoolBytes: Int = 0
    private val maxSecondaryPoolBytes: Int = (budget.bitmapPoolMaxCount.coerceAtLeast(2) * 262_144)

    // Single unified byte-bounded cache so single-layer boards use 100% of rasterCacheBytes
    private val tileCache = object : LruCache<String, CachedTile>(
        budget.rasterCacheBytes.coerceAtLeast(2 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: CachedTile): Int =
            (value.pixelSize * value.pixelSize * 4).coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: CachedTile, newValue: CachedTile?) {
            if (evicted) cacheEvictions++
            if (oldValue.bitmap !== newValue?.bitmap) {
                reusableCompositeCanvas.setBitmap(null)
                reusableLayerCanvas.setBitmap(null)
                releaseDisplayBitmap(oldValue.bitmap)
            }
        }
    }

    private val tileSrcRect = Rect()
    private val layerSrcRect = Rect()
    private val layerDstRect = Rect()
    private val tileWorldRect = RectF(0f, 0f, TILE_WORLD_SIZE, TILE_WORLD_SIZE)
    private val tileDstRectF = RectF()
    private val reusableCompositeCanvas = Canvas()
    private val reusableLayerCanvas = Canvas()
    private val reusableSampleCanvas = Canvas()
    private val visibleBucketsScratch = ArrayList<TileBucket>(32)
    private val activeLayersScratch = ArrayList<ActiveLayerBucket>(4)

    private data class ActiveLayerBucket(
        val layer: DrawingLayer,
        val strokes: List<WhiteboardStroke>,
        val strokeHashes: LongArray,
        val shapes: List<ShapeItem>,
        val mutableTile: Bitmap?,
        val opacityAlpha: Int
    )

    private val rasterPaint = Paint().apply {
        isDither = false
    }
    private val normalBlitPaint = Paint()
    private val layerBlendPaint = Paint()
    private val alphaLockedOverlayPaint = Paint().apply {
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    private val mutableTilePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    @Synchronized
    private fun acquireDisplayBitmap(pixelSize: Int): Bitmap {
        if (pixelSize == TILE_PIXEL_SIZE) {
            return bitmapPool.acquireClean()
        }
        val queue = secondaryPools[pixelSize]
        while (queue != null && queue.isNotEmpty()) {
            val candidate = queue.pollFirst() ?: break
            secondaryPoolBytes = (secondaryPoolBytes - pixelSize * pixelSize * 4).coerceAtLeast(0)
            if (!candidate.isRecycled &&
                candidate.isMutable &&
                candidate.width == pixelSize &&
                candidate.height == pixelSize
            ) {
                candidate.eraseColor(Color.TRANSPARENT)
                return candidate
            }
        }
        return Bitmap.createBitmap(pixelSize, pixelSize, Bitmap.Config.ARGB_8888)
    }

    @Synchronized
    private fun releaseDisplayBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        val w = bitmap.width
        if (w == TILE_PIXEL_SIZE && bitmap.height == TILE_PIXEL_SIZE) {
            bitmapPool.release(bitmap)
            return
        }
        val byteSize = w * bitmap.height * 4
        if (!bitmap.isMutable || w != bitmap.height || secondaryPoolBytes + byteSize > maxSecondaryPoolBytes) {
            bitmap.recycle()
            return
        }
        bitmap.eraseColor(Color.TRANSPARENT)
        secondaryPools.getOrPut(w) { ArrayDeque() }.addFirst(bitmap)
        secondaryPoolBytes += byteSize
    }

    @Synchronized
    private fun clearSecondaryPools() {
        for (queue in secondaryPools.values) {
            while (queue.isNotEmpty()) {
                val bmp = queue.pollFirst()
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
            }
        }
        secondaryPools.clear()
        secondaryPoolBytes = 0
    }

    fun clear() {
        reusableCompositeCanvas.setBitmap(null)
        reusableLayerCanvas.setBitmap(null)
        reusableSampleCanvas.setBitmap(null)
        tileCache.evictAll()
    }

    fun clearAndReleasePool() {
        clear()
        bitmapPool.clear()
        clearSecondaryPools()
    }

    /** Trim caches proportionally when Android reports memory pressure. */
    fun trimToFraction(fraction: Float) {
        reusableCompositeCanvas.setBitmap(null)
        reusableLayerCanvas.setBitmap(null)
        reusableSampleCanvas.setBitmap(null)
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped <= 0.01f) {
            clearAndReleasePool()
            return
        }
        tileCache.trimToSize((tileCache.maxSize() * clamped).toInt())
        if (clamped <= 0.25f) {
            bitmapPool.clear()
            clearSecondaryPools()
        }
    }

    /** Release half of the disposable raster cache when Android reports memory pressure. */
    fun trimMemory() = trimToFraction(0.5f)

    fun releaseTemporaryTile(bitmap: Bitmap?) {
        bitmapPool.release(bitmap)
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
            val hasRaster = rasterTilesByKey.contains(key)

            val strokeHashes = if (tileStrokes.isEmpty()) {
                EMPTY_LONG_ARRAY
            } else {
                LongArray(tileStrokes.size) { idx -> tileStrokes[idx].renderSignature }
            }

            var strokesAggSig = tileStrokes.size.toLong()
            for (sh in strokeHashes) {
                strokesAggSig = mixHash(strokesAggSig, sh)
            }

            var shapesSig = tileShapes.size.toLong()
            for (sh in tileShapes) {
                shapesSig = mixHash(shapesSig, sh.id.hashCode().toLong())
                shapesSig = mixHash(shapesSig, sh.shapeType.ordinal.toLong())
                shapesSig = mixHash(shapesSig, sh.x.toBits().toLong())
                shapesSig = mixHash(shapesSig, sh.y.toBits().toLong())
                shapesSig = mixHash(shapesSig, sh.width.toBits().toLong())
                shapesSig = mixHash(shapesSig, sh.height.toBits().toLong())
                shapesSig = mixHash(shapesSig, sh.rotation.toBits().toLong())
                shapesSig = mixHash(shapesSig, sh.relativeVerticesHash.toLong())
                shapesSig = mixHash(shapesSig, sh.strokeColorHex.hashCode().toLong())
                shapesSig = mixHash(shapesSig, sh.fillColorHex.hashCode().toLong())
                shapesSig = mixHash(shapesSig, sh.strokeWidth.toBits().toLong())
                shapesSig = mixHash(shapesSig, sh.label.hashCode().toLong())
                shapesSig = mixHash(shapesSig, sh.opacity.toBits().toLong())
                shapesSig = mixHash(
                    shapesSig,
                    ((sh.brushType.ordinal shl 8) or
                        (sh.capStyle.ordinal shl 4) or
                        (if (sh.isFilled) 2 else 0) or
                        (if (sh.isRainbow) 1 else 0)).toLong()
                )
            }

            var sig = mixHash(baseSig, strokesAggSig)
            sig = mixHash(sig, shapesSig)
            if (hasRaster) sig = mixHash(sig, 0x5E3779B97F4A7C15L)

            buckets[key] = TileBucket(
                boardId = boardId,
                tx = tx,
                ty = ty,
                signature = sig,
                strokes = tileStrokes,
                shapes = tileShapes,
                layers = resolvedLayers,
                hasMutableRaster = hasRaster,
                envSignature = baseSig,
                shapesSignature = shapesSig,
                strokeHashes = strokeHashes
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

        visibleBucketsScratch.clear()
        if (spatialIndex.buckets.size <= 96 || gridCellCount > spatialIndex.buckets.size.toLong()) {
            for ((_, bucket) in spatialIndex.buckets) {
                if (bucket.tx in visTxMin..visTxMax && bucket.ty in visTyMin..visTyMax) {
                    visibleBucketsScratch.add(bucket)
                }
            }
        } else {
            for (tx in visTxMin..visTxMax) {
                for (ty in visTyMin..visTyMax) {
                    val bucket = spatialIndex.bucketAt(tx, ty) ?: continue
                    visibleBucketsScratch.add(bucket)
                }
            }
        }

        val visibleCount = visibleBucketsScratch.size
        if (visibleCount == 0) return

        val hasMultiVisibleLayers = visibleBucketsScratch[0].layers.count { it.isVisible && it.opacity > 0.01f } > 1
        val targetTilePx = computeAdaptiveTilePixelSize(
            visibleTileCount = visibleCount,
            scale = safeScale,
            hasMultipleVisibleLayers = hasMultiVisibleLayers
        )

        for (i in 0 until visibleCount) {
            drawSingleTile(
                targetCanvas = canvas,
                bucket = visibleBucketsScratch[i],
                targetTilePx = targetTilePx,
                panX = panX,
                panY = panY,
                scale = safeScale,
                isDarkBoard = isDarkBoard,
                handDrawnFactor = handDrawnFactor
            )
        }
    }

    private fun computeAdaptiveTilePixelSize(
        visibleTileCount: Int,
        scale: Float,
        hasMultipleVisibleLayers: Boolean
    ): Int {
        val idealForScale = when {
            scale <= 0.30f -> 128
            scale <= 0.55f -> 256
            scale <= 0.85f -> 384
            else -> 512
        }
        val layerMultiplier = if (hasMultipleVisibleLayers) 1.75f else 1.0f
        val usableBytes = budget.rasterCacheBytes * 0.85f
        val maxBytesPerTile = usableBytes / (visibleTileCount.coerceAtLeast(1) * layerMultiplier)
        val budgetCapPx = when {
            maxBytesPerTile >= 512f * 512f * 4f -> 512
            maxBytesPerTile >= 384f * 384f * 4f -> 384
            maxBytesPerTile >= 256f * 256f * 4f -> 256
            maxBytesPerTile >= 192f * 192f * 4f -> 192
            else -> 128
        }
        return min(idealForScale, budgetCapPx).coerceAtLeast(128)
    }

    /**
     * Bakes a single layer's vectors + mutable raster tile at (tx, ty) for use by [PigmentMixEngine]
     * or [ScanlineFloodFillEngine] Reference Layer sampling (always full 512x512 world resolution).
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

        val bmp = bitmapPool.acquireClean()
        reusableSampleCanvas.setBitmap(bmp)
        try {
            renderLayerContentToTileCanvas(
                canvas = reusableSampleCanvas,
                tilePixelSize = TILE_PIXEL_SIZE,
                layer = layer,
                layerStrokes = localStrokes,
                layerShapes = localShapes,
                mutableTile = mutableTile,
                tilePanX = -tileLeft,
                tilePanY = -tileTop,
                isDarkBoard = isDarkBoard,
                handDrawnFactor = handDrawnFactor
            )
        } finally {
            reusableSampleCanvas.setBitmap(null)
        }
        return bmp
    }

    private fun drawSingleTile(
        targetCanvas: Canvas,
        bucket: TileBucket,
        targetTilePx: Int,
        panX: Float,
        panY: Float,
        scale: Float,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ) {
        val bmp = getOrBakeCompositeTile(bucket, targetTilePx, isDarkBoard, handDrawnFactor)
        val worldLeft = bucket.tx * TILE_WORLD_SIZE
        val worldTop = bucket.ty * TILE_WORLD_SIZE
        val worldRight = worldLeft + TILE_WORLD_SIZE
        val worldBottom = worldTop + TILE_WORLD_SIZE

        tileSrcRect.set(0, 0, bmp.width, bmp.height)
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
        targetTilePx: Int,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ): Bitmap {
        val compositeKey = "C:${bucket.boardId}:${bucket.tx}:${bucket.ty}"
        val existing = tileCache.get(compositeKey)
        if (existing != null &&
            existing.boardId == bucket.boardId &&
            existing.signature == bucket.signature &&
            !existing.bitmap.isRecycled &&
            !(existing.pixelSize <= 256 && targetTilePx >= existing.pixelSize * 2)
        ) {
            cacheHits++
            return existing.bitmap
        }

        val resolvedLayers = bucket.layers.ifEmpty { listOf(DrawingLayer()) }
        val knownLayerIds = resolvedLayers.map { it.id }.toSet()
        val firstLayerId = resolvedLayers.first().id

        activeLayersScratch.clear()
        for (layer in resolvedLayers) {
            if (!layer.isVisible) continue
            val layerOpacityAlpha = (layer.opacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            if (layerOpacityAlpha == 0) continue

            val layerStrokes: List<WhiteboardStroke>
            val layerStrokeHashes: LongArray
            if (resolvedLayers.size == 1) {
                layerStrokes = bucket.strokes
                layerStrokeHashes = bucket.strokeHashes
            } else {
                val filtered = bucket.strokes.filter { s ->
                    val resolvedId = if (knownLayerIds.contains(s.layerId)) s.layerId else firstLayerId
                    resolvedId == layer.id
                }
                layerStrokes = filtered
                layerStrokeHashes = if (filtered.isEmpty()) {
                    EMPTY_LONG_ARRAY
                } else {
                    LongArray(filtered.size) { idx -> filtered[idx].renderSignature }
                }
            }
            val layerShapes = if (resolvedLayers.size == 1) {
                bucket.shapes
            } else {
                bucket.shapes.filter { sh ->
                    val resolvedId = if (knownLayerIds.contains(sh.layerId)) sh.layerId else firstLayerId
                    resolvedId == layer.id
                }
            }
            val mutableTile = mutableRasterStore.getTile(bucket.boardId, layer.id, bucket.tx, bucket.ty)
            if (layerStrokes.isEmpty() && layerShapes.isEmpty() && mutableTile == null) continue
            activeLayersScratch.add(
                ActiveLayerBucket(
                    layer = layer,
                    strokes = layerStrokes,
                    strokeHashes = layerStrokeHashes,
                    shapes = layerShapes,
                    mutableTile = mutableTile,
                    opacityAlpha = layerOpacityAlpha
                )
            )
        }

        val tilePanX = -(bucket.tx * TILE_WORLD_SIZE)
        val tilePanY = -(bucket.ty * TILE_WORLD_SIZE)

        // Single-Layer Fast Path: bake directly into composite tile without duplicating a Level-A layer tile!
        if (activeLayersScratch.size == 1) {
            val single = activeLayersScratch[0]
            if (single.layer.blendMode == LayerBlendMode.NORMAL && single.opacityAlpha >= 254) {
                if (existing != null &&
                    !existing.bitmap.isRecycled &&
                    existing.isSingleLayerDirect &&
                    existing.boardId == bucket.boardId &&
                    existing.envSignature == bucket.envSignature &&
                    existing.shapesSignature == bucket.shapesSignature &&
                    bucket.shapes.isEmpty() &&
                    !existing.hasMutableRaster &&
                    !bucket.hasMutableRaster &&
                    isPrefixMatch(existing.bakedStrokeHashes, bucket.strokeHashes)
                ) {
                    val startIdx = existing.bakedStrokeHashes.size
                    val bmp = existing.bitmap
                    val tileScale = existing.pixelSize / TILE_WORLD_SIZE
                    reusableCompositeCanvas.setBitmap(bmp)
                    reusableCompositeCanvas.save()
                    if (tileScale != 1f) reusableCompositeCanvas.scale(tileScale, tileScale)
                    try {
                        for (i in startIdx until bucket.strokes.size) {
                            renderStrokeIntoTile(
                                canvas = reusableCompositeCanvas,
                                stroke = bucket.strokes[i],
                                tilePanX = tilePanX,
                                tilePanY = tilePanY,
                                isDarkBoard = isDarkBoard,
                                handDrawnFactor = handDrawnFactor
                            )
                        }
                    } finally {
                        reusableCompositeCanvas.restore()
                        reusableCompositeCanvas.setBitmap(null)
                    }
                    existing.signature = bucket.signature
                    existing.bakedStrokeHashes = bucket.strokeHashes
                    incrementalAppends++
                    return bmp
                }

                val bitmap = acquireOrReuseCompositeBitmap(
                    compositeKey = compositeKey,
                    existing = existing,
                    targetTilePx = targetTilePx,
                    bucket = bucket,
                    isSingleLayerDirect = true
                )
                reusableCompositeCanvas.setBitmap(bitmap)
                try {
                    renderLayerContentToTileCanvas(
                        canvas = reusableCompositeCanvas,
                        tilePixelSize = bitmap.width,
                        layer = single.layer,
                        layerStrokes = single.strokes,
                        layerShapes = single.shapes,
                        mutableTile = single.mutableTile,
                        tilePanX = tilePanX,
                        tilePanY = tilePanY,
                        isDarkBoard = isDarkBoard,
                        handDrawnFactor = handDrawnFactor
                    )
                } finally {
                    reusableCompositeCanvas.setBitmap(null)
                }
                fullRebakes++
                return bitmap
            }
        }

        // Multi-Layer / Blend / Translucent Path
        val bitmap = acquireOrReuseCompositeBitmap(
            compositeKey = compositeKey,
            existing = existing,
            targetTilePx = targetTilePx,
            bucket = bucket,
            isSingleLayerDirect = false
        )
        reusableCompositeCanvas.setBitmap(bitmap)
        try {
            val compSize = bitmap.width
            for (i in 0 until activeLayersScratch.size) {
                val active = activeLayersScratch[i]
                val layerBmp = getOrBakeLayerTile(
                    boardId = bucket.boardId,
                    layer = active.layer,
                    tx = bucket.tx,
                    ty = bucket.ty,
                    targetTilePx = compSize,
                    layerStrokes = active.strokes,
                    layerStrokeHashes = active.strokeHashes,
                    layerShapes = active.shapes,
                    mutableTile = active.mutableTile,
                    tilePanX = tilePanX,
                    tilePanY = tilePanY,
                    isDarkBoard = isDarkBoard,
                    handDrawnFactor = handDrawnFactor
                )

                layerSrcRect.set(0, 0, layerBmp.width, layerBmp.height)
                layerDstRect.set(0, 0, compSize, compSize)

                if (active.layer.blendMode == LayerBlendMode.NORMAL) {
                    normalBlitPaint.alpha = active.opacityAlpha
                    normalBlitPaint.isFilterBitmap = layerBmp.width != compSize
                    reusableCompositeCanvas.drawBitmap(layerBmp, layerSrcRect, layerDstRect, normalBlitPaint)
                } else {
                    layerBlendPaint.reset()
                    layerBlendPaint.alpha = active.opacityAlpha
                    layerBlendPaint.isFilterBitmap = layerBmp.width != compSize
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        layerBlendPaint.blendMode = active.layer.blendMode.toAndroidBlendMode()
                    } else {
                        active.layer.blendMode.toLegacyPorterDuff()?.let {
                            layerBlendPaint.xfermode = PorterDuffXfermode(it)
                        }
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && active.layer.blendMode == LayerBlendMode.MULTIPLY) {
                        val save = reusableCompositeCanvas.saveLayer(0f, 0f, compSize.toFloat(), compSize.toFloat(), layerBlendPaint)
                        try {
                            reusableCompositeCanvas.drawColor(Color.WHITE)
                            normalBlitPaint.alpha = active.opacityAlpha
                            reusableCompositeCanvas.drawBitmap(layerBmp, layerSrcRect, layerDstRect, normalBlitPaint)
                        } finally {
                            reusableCompositeCanvas.restoreToCount(save)
                        }
                    } else {
                        reusableCompositeCanvas.drawBitmap(layerBmp, layerSrcRect, layerDstRect, layerBlendPaint)
                    }
                }
            }
        } finally {
            reusableCompositeCanvas.setBitmap(null)
        }
        return bitmap
    }

    private fun acquireOrReuseCompositeBitmap(
        compositeKey: String,
        existing: CachedTile?,
        targetTilePx: Int,
        bucket: TileBucket,
        isSingleLayerDirect: Boolean
    ): Bitmap {
        if (existing != null && !existing.bitmap.isRecycled && existing.pixelSize == targetTilePx) {
            existing.bitmap.eraseColor(Color.TRANSPARENT)
            existing.boardId = bucket.boardId
            existing.signature = bucket.signature
            existing.envSignature = bucket.envSignature
            existing.shapesSignature = bucket.shapesSignature
            existing.hasMutableRaster = bucket.hasMutableRaster
            existing.bakedStrokeHashes = if (isSingleLayerDirect) bucket.strokeHashes else EMPTY_LONG_ARRAY
            existing.isSingleLayerDirect = isSingleLayerDirect
            return existing.bitmap
        }
        val bitmap = acquireDisplayBitmap(targetTilePx)
        tileCache.put(
            compositeKey,
            CachedTile(
                boardId = bucket.boardId,
                signature = bucket.signature,
                envSignature = bucket.envSignature,
                shapesSignature = bucket.shapesSignature,
                hasMutableRaster = bucket.hasMutableRaster,
                bakedStrokeHashes = if (isSingleLayerDirect) bucket.strokeHashes else EMPTY_LONG_ARRAY,
                isSingleLayerDirect = isSingleLayerDirect,
                pixelSize = targetTilePx,
                bitmap = bitmap
            )
        )
        return bitmap
    }

    private fun getOrBakeLayerTile(
        boardId: String,
        layer: DrawingLayer,
        tx: Int,
        ty: Int,
        targetTilePx: Int,
        layerStrokes: List<WhiteboardStroke>,
        layerStrokeHashes: LongArray,
        layerShapes: List<ShapeItem>,
        mutableTile: Bitmap?,
        tilePanX: Float,
        tilePanY: Float,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ): Bitmap {
        var envSig = mixHash(boardId.hashCode().toLong(), layer.id.hashCode().toLong())
        envSig = mixHash(envSig, if (isDarkBoard) 1L else 0L)
        envSig = mixHash(envSig, handDrawnFactor.toBits().toLong())
        envSig = mixHash(envSig, if (layer.isAlphaLocked) 1L else 0L)
        envSig = mixHash(envSig, layer.rasterRevision xor mutableRasterStore.getLayerRevision(boardId, layer.id))

        var shapesSig = layerShapes.size.toLong()
        for (sh in layerShapes) {
            shapesSig = mixHash(shapesSig, sh.id.hashCode().toLong())
            shapesSig = mixHash(shapesSig, sh.x.toBits().toLong())
            shapesSig = mixHash(shapesSig, sh.y.toBits().toLong())
            shapesSig = mixHash(shapesSig, sh.width.toBits().toLong())
            shapesSig = mixHash(shapesSig, sh.height.toBits().toLong())
            shapesSig = mixHash(shapesSig, sh.rotation.toBits().toLong())
            shapesSig = mixHash(shapesSig, sh.strokeColorHex.hashCode().toLong())
        }

        var sig = mixHash(envSig, layerStrokeHashes.size.toLong())
        for (sh in layerStrokeHashes) sig = mixHash(sig, sh)
        sig = mixHash(sig, shapesSig)

        val cacheKey = "L:$boardId:${layer.id}:$tx:$ty"
        val existing = tileCache.get(cacheKey)
        if (existing != null &&
            existing.signature == sig &&
            !existing.bitmap.isRecycled &&
            !(existing.pixelSize <= 256 && targetTilePx >= existing.pixelSize * 2)
        ) {
            cacheHits++
            return existing.bitmap
        }

        if (existing != null &&
            !existing.bitmap.isRecycled &&
            existing.envSignature == envSig &&
            existing.shapesSignature == shapesSig &&
            layerShapes.isEmpty() &&
            mutableTile == null &&
            !existing.hasMutableRaster &&
            !layer.isAlphaLocked &&
            isPrefixMatch(existing.bakedStrokeHashes, layerStrokeHashes)
        ) {
            val startIdx = existing.bakedStrokeHashes.size
            val bmp = existing.bitmap
            val tileScale = existing.pixelSize / TILE_WORLD_SIZE
            reusableLayerCanvas.setBitmap(bmp)
            reusableLayerCanvas.save()
            if (tileScale != 1f) reusableLayerCanvas.scale(tileScale, tileScale)
            try {
                for (i in startIdx until layerStrokes.size) {
                    renderStrokeIntoTile(
                        canvas = reusableLayerCanvas,
                        stroke = layerStrokes[i],
                        tilePanX = tilePanX,
                        tilePanY = tilePanY,
                        isDarkBoard = isDarkBoard,
                        handDrawnFactor = handDrawnFactor
                    )
                }
            } finally {
                reusableLayerCanvas.restore()
                reusableLayerCanvas.setBitmap(null)
            }
            existing.signature = sig
            existing.bakedStrokeHashes = layerStrokeHashes
            incrementalAppends++
            return bmp
        }

        val bitmap: Bitmap
        if (existing != null && !existing.bitmap.isRecycled && existing.pixelSize == targetTilePx) {
            bitmap = existing.bitmap
            bitmap.eraseColor(Color.TRANSPARENT)
            existing.signature = sig
            existing.envSignature = envSig
            existing.shapesSignature = shapesSig
            existing.hasMutableRaster = mutableTile != null
            existing.bakedStrokeHashes = layerStrokeHashes
        } else {
            bitmap = acquireDisplayBitmap(targetTilePx)
            tileCache.put(
                cacheKey,
                CachedTile(
                    boardId = boardId,
                    signature = sig,
                    envSignature = envSig,
                    shapesSignature = shapesSig,
                    hasMutableRaster = mutableTile != null,
                    bakedStrokeHashes = layerStrokeHashes,
                    isSingleLayerDirect = false,
                    pixelSize = targetTilePx,
                    bitmap = bitmap
                )
            )
        }

        reusableLayerCanvas.setBitmap(bitmap)
        try {
            renderLayerContentToTileCanvas(
                canvas = reusableLayerCanvas,
                tilePixelSize = bitmap.width,
                layer = layer,
                layerStrokes = layerStrokes,
                layerShapes = layerShapes,
                mutableTile = mutableTile,
                tilePanX = tilePanX,
                tilePanY = tilePanY,
                isDarkBoard = isDarkBoard,
                handDrawnFactor = handDrawnFactor
            )
        } finally {
            reusableLayerCanvas.setBitmap(null)
        }
        fullRebakes++
        return bitmap
    }

    private fun renderStrokeIntoTile(
        canvas: Canvas,
        stroke: WhiteboardStroke,
        tilePanX: Float,
        tilePanY: Float,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ) {
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

    private fun renderLayerContentToTileCanvas(
        canvas: Canvas,
        tilePixelSize: Int,
        layer: DrawingLayer,
        layerStrokes: List<WhiteboardStroke>,
        layerShapes: List<ShapeItem>,
        mutableTile: Bitmap?,
        tilePanX: Float,
        tilePanY: Float,
        isDarkBoard: Boolean,
        handDrawnFactor: Float
    ) {
        val tileScale = tilePixelSize / TILE_WORLD_SIZE
        canvas.save()
        if (tileScale != 1f) {
            canvas.scale(tileScale, tileScale)
        }
        try {
            for (i in layerStrokes.indices) {
                renderStrokeIntoTile(
                    canvas = canvas,
                    stroke = layerStrokes[i],
                    tilePanX = tilePanX,
                    tilePanY = tilePanY,
                    isDarkBoard = isDarkBoard,
                    handDrawnFactor = handDrawnFactor
                )
            }
            for (i in layerShapes.indices) {
                UnifiedBrushPipeline.renderShape(
                    canvas = canvas,
                    shape = layerShapes[i],
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
                val rasterOverlayPaint = if (layer.isAlphaLocked) alphaLockedOverlayPaint else mutableTilePaint
                canvas.drawBitmap(mutableTile, null, tileWorldRect, rasterOverlayPaint)
            }
        } finally {
            canvas.restore()
        }
    }

    private fun isPrefixMatch(prefix: LongArray, full: LongArray): Boolean {
        if (prefix.isEmpty() || full.size <= prefix.size) return false
        for (i in prefix.indices) {
            if (prefix[i] != full[i]) return false
        }
        return true
    }

    companion object {
        private val EMPTY_LONG_ARRAY = LongArray(0)
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
