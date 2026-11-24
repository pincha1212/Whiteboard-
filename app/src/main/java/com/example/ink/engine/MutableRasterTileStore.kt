package com.example.ink.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import com.example.model.LayerBlendMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater
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
 * Only tiles touched by raster tools (Smudge, Flood Fill, Soft Eraser on raster ink, Layer Merge)
 * are allocated in memory. Supports lossless compressed undo/redo patches and sidecar serialization.
 */
class MutableRasterTileStore {

    private val tiles = ConcurrentHashMap<RasterTileKey, Bitmap>()
    private val layerRevisions = ConcurrentHashMap<Pair<String, String>, Long>()

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
        val key = RasterTileKey(boardId, layerId, tx, ty)
        val existing = tiles[key]
        if (existing != null && !existing.isRecycled) {
            return existing
        }
        val created = Bitmap.createBitmap(
            PaintTileRasterCache.TILE_PIXEL_SIZE,
            PaintTileRasterCache.TILE_PIXEL_SIZE,
            Bitmap.Config.ARGB_8888
        )
        tiles[key] = created
        return created
    }

    @Synchronized
    fun putDecodedTile(boardId: String, layerId: String, tx: Int, ty: Int, bitmap: Bitmap) {
        val key = RasterTileKey(boardId, layerId, tx, ty)
        val old = tiles.put(key, bitmap)
        if (old != null && old !== bitmap && !old.isRecycled) {
            old.recycle()
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
        val targetMap = if (applyAfter) patch.afterTiles else patch.beforeTiles
        for ((coord, bytes) in targetMap) {
            val key = RasterTileKey(patch.boardId, patch.layerId, coord.tx, coord.ty)
            if (bytes == null) {
                val removed = tiles.remove(key)
                if (removed != null && !removed.isRecycled) removed.recycle()
            } else {
                val bmp = getOrCreateTile(patch.boardId, patch.layerId, coord.tx, coord.ty)
                decompressIntoBitmap(bytes, bmp)
            }
        }
        bumpLayerRevision(patch.boardId, patch.layerId)
    }

    @Synchronized
    fun duplicateLayerTiles(boardId: String, sourceLayerId: String, targetLayerId: String) {
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
            if (!topBmp.isRecycled) topBmp.recycle()
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
                if (!entry.value.isRecycled) entry.value.recycle()
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
                if (!entry.value.isRecycled) entry.value.recycle()
                iter.remove()
            }
        }
        layerRevisions.keys.removeAll { it.first == boardId }
    }

    @Synchronized
    fun clearAll() {
        for (bmp in tiles.values) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        tiles.clear()
        layerRevisions.clear()
    }

    companion object {
        fun compressBitmap(bitmap: Bitmap): ByteArray {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val raw = ByteBuffer.allocate(pixels.size * 4)
            raw.asIntBuffer().put(pixels)
            val input = raw.array()

            val deflater = Deflater(Deflater.BEST_SPEED)
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size / 4)
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buf)
                if (count > 0) out.write(buf, 0, count) else break
            }
            deflater.end()
            return out.toByteArray()
        }

        fun decompressIntoBitmap(compressed: ByteArray, target: Bitmap) {
            val w = target.width
            val h = target.height
            val expectedBytes = w * h * 4
            val out = ByteArray(expectedBytes)
            val inflater = Inflater()
            inflater.setInput(compressed)
            var offset = 0
            while (!inflater.finished() && offset < expectedBytes) {
                val read = inflater.inflate(out, offset, expectedBytes - offset)
                if (read == 0) break
                offset += read
            }
            inflater.end()
            val pixels = IntArray(w * h)
            ByteBuffer.wrap(out).asIntBuffer().get(pixels)
            target.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        fun isBitmapTransparent(bitmap: Bitmap): Boolean {
            val w = bitmap.width
            val h = bitmap.height
            val row = IntArray(w)
            for (y in 0 until h step 4) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (px in row) {
                    if ((px ushr 24) != 0) return false
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
