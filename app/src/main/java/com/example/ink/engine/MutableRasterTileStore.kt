package com.example.ink.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import com.example.model.LayerBlendMode
import java.io.ByteArrayOutputStream
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.min
import kotlin.math.roundToInt

data class RasterTileCoord(
    val tx: Int,
    val ty: Int
)

data class RasterTileKey(
    val boardId: String,
    val layerId: String,
    val tx: Int,
    val ty: Int
)

/**
 * Compressed lossless ARGB_8888 undo/redo delta for mutated raster tiles.
 */
data class TileRasterUndoPatch(
    val boardId: String,
    val layerId: String,
    val beforeTiles: Map<RasterTileCoord, ByteArray?>,
    val afterTiles: Map<RasterTileCoord, ByteArray?>
)

/**
 * Sparse mutable 512x512 world-space ARGB_8888 raster tile store keyed by (boardId, layerId, tx, ty).
 * Uses chunked 16-row Deflate/Inflate compression (zero ~3 MB full-tile allocation bursts),
 * optional [TileBitmapPool] reuse, and inactive-board eviction for strict 1 GB RAM devices.
 */
class MutableRasterTileStore(
    private val bitmapPool: TileBitmapPool? = null
) {

    private val tiles = ConcurrentHashMap<RasterTileKey, Bitmap>()
    private val layerRevisions = ConcurrentHashMap<Pair<String, String>, Long>()
    private val loadedBoards = HashSet<String>()

    @Synchronized
    fun markBoardLoaded(boardId: String) {
        loadedBoards.add(boardId)
    }

    @Synchronized
    fun isBoardLoaded(boardId: String): Boolean = loadedBoards.contains(boardId)

    @Synchronized
    fun retainedBitmapBytes(): Long {
        var total = 0L
        for (bmp in tiles.values) {
            if (!bmp.isRecycled) {
                total += bmp.allocationByteCount.toLong()
            }
        }
        return total
    }

    @Synchronized
    fun getTile(boardId: String, layerId: String, tx: Int, ty: Int): Bitmap? {
        val bmp = tiles[RasterTileKey(boardId, layerId, tx, ty)]
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    @Synchronized
    fun hasAnyTilesForLayer(boardId: String, layerId: String): Boolean {
        return tiles.keys.any { it.boardId == boardId && it.layerId == layerId }
    }

    @Synchronized
    fun getTilesForLayer(boardId: String, layerId: String): Map<RasterTileCoord, Bitmap> {
        val out = HashMap<RasterTileCoord, Bitmap>()
        for ((key, bmp) in tiles) {
            if (key.boardId == boardId && key.layerId == layerId && !bmp.isRecycled) {
                out[RasterTileCoord(key.tx, key.ty)] = bmp
            }
        }
        return out
    }

    @Synchronized
    fun getAllBoardLayerIds(boardId: String): Set<String> {
        return tiles.keys.asSequence()
            .filter { it.boardId == boardId }
            .map { it.layerId }
            .toSet()
    }

    @Synchronized
    fun getAllTileKeys(): Set<RasterTileKey> = tiles.keys.toSet()

    @Synchronized
    fun getLayerRevision(boardId: String, layerId: String): Long {
        return layerRevisions[boardId to layerId] ?: 0L
    }

    @Synchronized
    fun bumpLayerRevision(boardId: String, layerId: String): Long {
        val k = boardId to layerId
        val next = (layerRevisions[k] ?: 0L) + 1L
        layerRevisions[k] = next
        return next
    }

    @Synchronized
    fun getOrCreateTile(boardId: String, layerId: String, tx: Int, ty: Int): Bitmap {
        loadedBoards.add(boardId)
        val key = RasterTileKey(boardId, layerId, tx, ty)
        val existing = tiles[key]
        if (existing != null && !existing.isRecycled) {
            return existing
        }
        val created = bitmapPool?.acquireClean() ?: Bitmap.createBitmap(
            PaintTileRasterCache.TILE_PIXEL_SIZE,
            PaintTileRasterCache.TILE_PIXEL_SIZE,
            Bitmap.Config.ARGB_8888
        )
        tiles[key] = created
        return created
    }

    @Synchronized
    fun putDecodedTile(boardId: String, layerId: String, tx: Int, ty: Int, bitmap: Bitmap) {
        loadedBoards.add(boardId)
        val key = RasterTileKey(boardId, layerId, tx, ty)
        val old = tiles.put(key, bitmap)
        if (old != null && old !== bitmap) {
            releaseBitmap(old)
        }
        bumpLayerRevision(boardId, layerId)
    }

    @Synchronized
    fun snapshotTileBytes(boardId: String, layerId: String, tx: Int, ty: Int): ByteArray? {
        val bmp = getTile(boardId, layerId, tx, ty) ?: return null
        return compressBitmap(bmp)
    }

    @Synchronized
    fun snapshotLayerTileBytes(boardId: String, layerId: String): Map<RasterTileCoord, ByteArray> {
        val out = HashMap<RasterTileCoord, ByteArray>()
        for ((key, bmp) in tiles) {
            if (key.boardId == boardId && key.layerId == layerId && !bmp.isRecycled && !isBitmapTransparent(bmp)) {
                out[RasterTileCoord(key.tx, key.ty)] = compressBitmap(bmp)
            }
        }
        return out
    }

    @Synchronized
    fun applyPatch(patch: TileRasterUndoPatch, applyAfter: Boolean) {
        loadedBoards.add(patch.boardId)
        val targetMap = if (applyAfter) patch.afterTiles else patch.beforeTiles
        for ((coord, bytes) in targetMap) {
            val key = RasterTileKey(patch.boardId, patch.layerId, coord.tx, coord.ty)
            if (bytes == null) {
                val removed = tiles.remove(key)
                if (removed != null) releaseBitmap(removed)
            } else {
                val bmp = getOrCreateTile(patch.boardId, patch.layerId, coord.tx, coord.ty)
                decompressIntoBitmap(bytes, bmp)
            }
        }
        bumpLayerRevision(patch.boardId, patch.layerId)
    }

    @Synchronized
    fun duplicateLayerTiles(boardId: String, sourceLayerId: String, targetLayerId: String) {
        loadedBoards.add(boardId)
        val entries = tiles.entries.filter { it.key.boardId == boardId && it.key.layerId == sourceLayerId }
        for ((key, srcBmp) in entries) {
            if (srcBmp.isRecycled) continue
            val copy = srcBmp.copy(Bitmap.Config.ARGB_8888, true) ?: continue
            tiles[RasterTileKey(boardId, targetLayerId, key.tx, key.ty)] = copy
        }
        if (entries.isNotEmpty()) {
            bumpLayerRevision(boardId, targetLayerId)
        }
    }

    @Synchronized
    fun mergeLayerTilesDown(
        boardId: String,
        topLayerId: String,
        topOpacity: Float,
        topBlendMode: LayerBlendMode,
        bottomLayerId: String
    ) {
        loadedBoards.add(boardId)
        val topEntries = tiles.entries.filter { it.key.boardId == boardId && it.key.layerId == topLayerId }
        if (topEntries.isEmpty()) return

        val alphaInt = (topOpacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        val canvas = Canvas()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = alphaInt
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                blendMode = topBlendMode.toAndroidBlendMode()
            } else {
                topBlendMode.toLegacyPorterDuff()?.let { xfermode = PorterDuffXfermode(it) }
            }
        }

        for ((key, topBmp) in topEntries) {
            if (!topBmp.isRecycled && alphaInt > 0) {
                val dst = getOrCreateTile(boardId, bottomLayerId, key.tx, key.ty)
                canvas.setBitmap(dst)
                canvas.drawBitmap(topBmp, 0f, 0f, paint)
                canvas.setBitmap(null)
            }
            tiles.remove(key)
            releaseBitmap(topBmp)
        }
        bumpLayerRevision(boardId, bottomLayerId)
        bumpLayerRevision(boardId, topLayerId)
    }

    @Synchronized
    fun removeLayer(boardId: String, layerId: String) {
        val iter = tiles.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key.boardId == boardId && entry.key.layerId == layerId) {
                releaseBitmap(entry.value)
                iter.remove()
            }
        }
        layerRevisions.remove(boardId to layerId)
    }

    @Synchronized
    fun removeBoard(boardId: String) {
        val iter = tiles.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key.boardId == boardId) {
                releaseBitmap(entry.value)
                iter.remove()
            }
        }
        layerRevisions.keys.removeAll { it.first == boardId }
        loadedBoards.remove(boardId)
    }

    /**
     * Evicts all in-memory mutable bitmaps belonging to boards other than [activeBoardId]
     * without erasing their on-disk sidecar files.
     */
    @Synchronized
    fun evictInactiveBoardTiles(activeBoardId: String) {
        val iter = tiles.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key.boardId != activeBoardId) {
                releaseBitmap(entry.value)
                iter.remove()
            }
        }
        loadedBoards.removeAll { it != activeBoardId }
    }

    @Synchronized
    fun clearAll() {
        for (bmp in tiles.values) {
            releaseBitmap(bmp)
        }
        tiles.clear()
        layerRevisions.clear()
        loadedBoards.clear()
    }

    private fun releaseBitmap(bmp: Bitmap) {
        if (bmp.isRecycled) return
        if (bitmapPool != null) {
            bitmapPool.release(bmp)
        } else {
            bmp.recycle()
        }
    }

    companion object {
        private const val CHUNK_ROWS = 16
        private const val STD_TILE_W = PaintTileRasterCache.TILE_PIXEL_SIZE
        private const val STD_INT_LEN = STD_TILE_W * CHUNK_ROWS
        private const val STD_BYTE_LEN = STD_INT_LEN * 4

        private val intChunkThreadLocal = ThreadLocal.withInitial { IntArray(STD_INT_LEN) }
        private val byteChunkThreadLocal = ThreadLocal.withInitial { ByteArray(STD_BYTE_LEN) }
        private val deflateBufThreadLocal = ThreadLocal.withInitial { ByteArray(8192) }

        fun compressBitmap(bitmap: Bitmap): ByteArray {
            val w = bitmap.width
            val h = bitmap.height
            val intScratch = if (w <= STD_TILE_W) intChunkThreadLocal.get()!! else IntArray(w * CHUNK_ROWS)
            val byteScratch = if (w <= STD_TILE_W) byteChunkThreadLocal.get()!! else ByteArray(w * CHUNK_ROWS * 4)
            val outBuf = deflateBufThreadLocal.get()!!

            val deflater = Deflater(Deflater.BEST_SPEED)
            val out = ByteArrayOutputStream(8192)
            try {
                var y = 0
                while (y < h) {
                    val rows = min(CHUNK_ROWS, h - y)
                    bitmap.getPixels(intScratch, 0, w, 0, y, w, rows)
                    val pxCount = w * rows
                    var bi = 0
                    for (i in 0 until pxCount) {
                        val px = intScratch[i]
                        byteScratch[bi++] = (px ushr 24).toByte()
                        byteScratch[bi++] = (px ushr 16).toByte()
                        byteScratch[bi++] = (px ushr 8).toByte()
                        byteScratch[bi++] = px.toByte()
                    }
                    deflater.setInput(byteScratch, 0, pxCount * 4)
                    while (!deflater.needsInput()) {
                        val count = deflater.deflate(outBuf)
                        if (count > 0) out.write(outBuf, 0, count) else break
                    }
                    y += rows
                }
                deflater.finish()
                while (!deflater.finished()) {
                    val count = deflater.deflate(outBuf)
                    if (count > 0) out.write(outBuf, 0, count) else break
                }
            } finally {
                deflater.end()
            }
            return out.toByteArray()
        }

        fun decompressIntoBitmap(compressed: ByteArray, target: Bitmap) {
            val w = target.width
            val h = target.height
            val intScratch = if (w <= STD_TILE_W) intChunkThreadLocal.get()!! else IntArray(w * CHUNK_ROWS)
            val byteScratch = if (w <= STD_TILE_W) byteChunkThreadLocal.get()!! else ByteArray(w * CHUNK_ROWS * 4)

            val inflater = Inflater()
            inflater.setInput(compressed)
            try {
                var y = 0
                while (y < h && !inflater.finished()) {
                    val rows = min(CHUNK_ROWS, h - y)
                    val needBytes = w * rows * 4
                    var filled = 0
                    while (filled < needBytes && !inflater.finished()) {
                        val read = inflater.inflate(byteScratch, filled, needBytes - filled)
                        if (read == 0) break
                        filled += read
                    }
                    if (filled < needBytes) {
                        Arrays.fill(byteScratch, filled, needBytes, 0.toByte())
                    }
                    val pxCount = w * rows
                    var bi = 0
                    for (i in 0 until pxCount) {
                        val b0 = byteScratch[bi++].toInt() and 0xFF
                        val b1 = byteScratch[bi++].toInt() and 0xFF
                        val b2 = byteScratch[bi++].toInt() and 0xFF
                        val b3 = byteScratch[bi++].toInt() and 0xFF
                        intScratch[i] = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                    }
                    target.setPixels(intScratch, 0, w, 0, y, w, rows)
                    y += rows
                }
            } finally {
                inflater.end()
            }
        }

        fun isBitmapTransparent(bitmap: Bitmap): Boolean {
            val w = bitmap.width
            val h = bitmap.height
            val row = if (w <= STD_TILE_W) intChunkThreadLocal.get()!! else IntArray(w)
            for (y in 0 until h step 4) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    if ((row[x] ushr 24) != 0) return false
                }
            }
            return true
        }
    }
}

internal fun LayerBlendMode.toAndroidBlendMode(): android.graphics.BlendMode = when (this) {
    LayerBlendMode.NORMAL -> android.graphics.BlendMode.SRC_OVER
    LayerBlendMode.MULTIPLY -> android.graphics.BlendMode.MULTIPLY
    LayerBlendMode.SCREEN -> android.graphics.BlendMode.SCREEN
    LayerBlendMode.OVERLAY -> android.graphics.BlendMode.OVERLAY
}

internal fun LayerBlendMode.toLegacyPorterDuff(): PorterDuff.Mode? = when (this) {
    LayerBlendMode.NORMAL -> null
    LayerBlendMode.MULTIPLY -> PorterDuff.Mode.MULTIPLY
    LayerBlendMode.SCREEN -> PorterDuff.Mode.SCREEN
    LayerBlendMode.OVERLAY -> PorterDuff.Mode.OVERLAY
}
