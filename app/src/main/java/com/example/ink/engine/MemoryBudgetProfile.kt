package com.example.ink.engine

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque

/**
 * Hardware-aware memory budget governing raster caches, bitmap pooling,
 * undo history, export dimensions, and persistence cadence in v4.0.0.
 *
 * Devices reporting [ActivityManager.isLowRamDevice] or `memoryClass <= 128 MB`
 * (typical 1 GB RAM / Android Go targets) automatically enter [strict1Gb].
 */
data class MemoryBudgetProfile(
    val isLowRamProfile: Boolean,
    val rasterCacheBytes: Int,
    val bitmapPoolMaxCount: Int,
    val activeMutableTilesBytesCap: Long,
    val nodeCacheBytes: Int,
    val fillPathCacheBytes: Int,
    val undoHistoryBudgetBytes: Long,
    val maxUndoSteps: Int,
    val maxExportMultiplier: Int,
    val maxExportDimensionPx: Int,
    val persistDebounceMs: Long
) {
    companion object {
        const val HARD_MAX_EXPORT_MULTIPLIER = 2

        fun strict1Gb(): MemoryBudgetProfile = MemoryBudgetProfile(
            isLowRamProfile = true,
            rasterCacheBytes = 6 * 1024 * 1024,
            bitmapPoolMaxCount = 4,
            activeMutableTilesBytesCap = 8L * 1024L * 1024L,
            nodeCacheBytes = 1536 * 1024,
            fillPathCacheBytes = 512 * 1024,
            undoHistoryBudgetBytes = 2_500_000L,
            maxUndoSteps = 15,
            maxExportMultiplier = HARD_MAX_EXPORT_MULTIPLIER,
            maxExportDimensionPx = 1600,
            persistDebounceMs = 450L
        )

        fun standard(): MemoryBudgetProfile = MemoryBudgetProfile(
            isLowRamProfile = false,
            rasterCacheBytes = 12 * 1024 * 1024,
            bitmapPoolMaxCount = 8,
            activeMutableTilesBytesCap = 20L * 1024L * 1024L,
            nodeCacheBytes = 3 * 1024 * 1024,
            fillPathCacheBytes = 1024 * 1024,
            undoHistoryBudgetBytes = 6_000_000L,
            maxUndoSteps = 24,
            maxExportMultiplier = HARD_MAX_EXPORT_MULTIPLIER,
            maxExportDimensionPx = 2048,
            persistDebounceMs = 300L
        )

        fun fromSystem(
            isLowRamDevice: Boolean,
            memoryClassMb: Int,
            maxHeapBytes: Long = Runtime.getRuntime().maxMemory()
        ): MemoryBudgetProfile {
            return if (isLowRamDevice || memoryClassMb <= 128 || maxHeapBytes <= 134_217_728L) {
                strict1Gb()
            } else {
                standard()
            }
        }

        fun fromContext(context: Context): MemoryBudgetProfile {
            val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val isLowRam = am?.isLowRamDevice ?: false
            val memClassMb = am?.memoryClass ?: 128
            return fromSystem(isLowRamDevice = isLowRam, memoryClassMb = memClassMb)
        }
    }
}

/**
 * Thread-safe pool of reusable 512x512 ARGB_8888 [Bitmap] tiles.
 * Eliminates repeated 1 MB native/pixel allocations during panning, baking, smudge, and flood fill.
 */
class TileBitmapPool(
    private var maxPoolCount: Int = 4,
    val tileWidth: Int = PaintTileRasterCache.TILE_PIXEL_SIZE,
    val tileHeight: Int = PaintTileRasterCache.TILE_PIXEL_SIZE
) {
    private val pool = ArrayDeque<Bitmap>()

    @Synchronized
    fun configureMaxCount(newMax: Int) {
        maxPoolCount = newMax.coerceAtLeast(0)
        while (pool.size > maxPoolCount) {
            val popped = pool.pollLast()
            if (popped != null && !popped.isRecycled) popped.recycle()
        }
    }

    @Synchronized
    fun acquireClean(): Bitmap {
        while (pool.isNotEmpty()) {
            val candidate = pool.pollFirst() ?: break
            if (!candidate.isRecycled &&
                candidate.isMutable &&
                candidate.config == Bitmap.Config.ARGB_8888 &&
                candidate.width == tileWidth &&
                candidate.height == tileHeight
            ) {
                candidate.eraseColor(Color.TRANSPARENT)
                return candidate
            }
        }
        return Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
    }

    @Synchronized
    fun release(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        val canReuse = bitmap.isMutable &&
            bitmap.config == Bitmap.Config.ARGB_8888 &&
            bitmap.width == tileWidth &&
            bitmap.height == tileHeight
        if (!canReuse || pool.size >= maxPoolCount) {
            bitmap.recycle()
            return
        }
        bitmap.eraseColor(Color.TRANSPARENT)
        pool.addFirst(bitmap)
    }

    @Synchronized
    fun clear() {
        while (pool.isNotEmpty()) {
            val bmp = pool.pollFirst()
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
        }
    }

    @Synchronized
    fun pooledCount(): Int = pool.size
}
