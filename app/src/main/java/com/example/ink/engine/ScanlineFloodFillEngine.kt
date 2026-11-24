package com.example.ink.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class FloodFillResult(
    val undoPatch: TileRasterUndoPatch?,
    val filledPixelCount: Int,
    val didHitRegionEdge: Boolean
)

/**
 * Iterative non-recursive Scanline Flood Fill operating across [MutableRasterTileStore] tiles.
 * Reads watertight boundary information from a [boundaryTileSampler] (e.g. a Reference Layer,
 * active layer, or merged visible layers) inside a bounded world [regionWorld] (capped to prevent OOM
 * on infinite canvases), and writes filled pixels onto [targetLayerId] obeying [isAlphaLocked].
 */
object ScanlineFloodFillEngine {

    private const val TILE_SIZE = PaintTileRasterCache.TILE_PIXEL_SIZE
    private const val MAX_REGION_DIMENSION = 2400

    fun floodFill(
        boardId: String,
        targetLayerId: String,
        seedWorldX: Int,
        seedWorldY: Int,
        fillColorInt: Int,
        fillOpacity: Float,
        tolerance: Int,
        gapClosingPx: Int = 1,
        isAlphaLocked: Boolean = false,
        regionWorld: Rect,
        rasterStore: MutableRasterTileStore,
        boundaryTileSampler: (tx: Int, ty: Int) -> Bitmap?,
        targetExistingTileSampler: ((tx: Int, ty: Int) -> Bitmap?)? = null
    ): FloodFillResult {
        val clampedW = regionWorld.width().coerceIn(64, MAX_REGION_DIMENSION)
        val clampedH = regionWorld.height().coerceIn(64, MAX_REGION_DIMENSION)
        val left = if (regionWorld.width() > MAX_REGION_DIMENSION) {
            seedWorldX - clampedW / 2
        } else regionWorld.left
        val top = if (regionWorld.height() > MAX_REGION_DIMENSION) {
            seedWorldY - clampedH / 2
        } else regionWorld.top
        val right = left + clampedW
        val bottom = top + clampedH

        if (seedWorldX !in left until right || seedWorldY !in top until bottom) {
            return FloodFillResult(null, 0, didHitRegionEdge = false)
        }

        val width = clampedW
        val height = clampedH
        val boundaryPixels = IntArray(width * height)

        val txMin = floorDiv(left, TILE_SIZE)
        val txMax = floorDiv(right - 1, TILE_SIZE)
        val tyMin = floorDiv(top, TILE_SIZE)
        val tyMax = floorDiv(bottom - 1, TILE_SIZE)

        val tileRowBuf = IntArray(TILE_SIZE)
        for (ty in tyMin..tyMax) {
            val tileTopWorld = ty * TILE_SIZE
            for (tx in txMin..txMax) {
                val tileLeftWorld = tx * TILE_SIZE
                val bBmp = boundaryTileSampler(tx, ty) ?: continue
                val copyXStart = max(left, tileLeftWorld)
                val copyXEnd = min(right, tileLeftWorld + TILE_SIZE)
                val copyYStart = max(top, tileTopWorld)
                val copyYEnd = min(bottom, tileTopWorld + TILE_SIZE)
                val copyW = copyXEnd - copyXStart
                if (copyW <= 0 || copyYStart >= copyYEnd) continue

                for (wy in copyYStart until copyYEnd) {
                    val ly = wy - tileTopWorld
                    val lx = copyXStart - tileLeftWorld
                    bBmp.getPixels(tileRowBuf, 0, TILE_SIZE, lx, ly, copyW, 1)
                    val dstOffset = (wy - top) * width + (copyXStart - left)
                    System.arraycopy(tileRowBuf, 0, boundaryPixels, dstOffset, copyW)
                }
            }
        }

        val seedIdx = (seedWorldY - top) * width + (seedWorldX - left)
        val seedColor = boundaryPixels[seedIdx]
        val finalAlpha = (Color.alpha(fillColorInt) / 255f * fillOpacity.coerceIn(0.05f, 1f) * 255f)
            .roundToInt()
            .coerceIn(1, 255)
        val effectiveFillArgb = (finalAlpha shl 24) or (fillColorInt and 0x00FFFFFF)
        if (colorDistance(seedColor, effectiveFillArgb) <= 6 && Color.alpha(seedColor) > 20) {
            return FloodFillResult(null, 0, didHitRegionEdge = false)
        }

        val tol = tolerance.coerceIn(4, 110)
        val visited = BooleanArray(width * height)

        // Optional simple gap closing barrier on boundary alpha ridges
        val isBoundary = BooleanArray(width * height)
        for (i in boundaryPixels.indices) {
            isBoundary[i] = colorDistance(boundaryPixels[i], seedColor) > tol
        }
        if (gapClosingPx > 0 && Color.alpha(seedColor) < 40) {
            val dilated = isBoundary.copyOf()
            for (y in 1 until height - 1) {
                val row = y * width
                for (x in 1 until width - 1) {
                    val idx = row + x
                    if (!isBoundary[idx]) {
                        val hBlocked = isBoundary[idx - 1] && isBoundary[idx + 1]
                        val vBlocked = isBoundary[idx - width] && isBoundary[idx + width]
                        if (hBlocked || vBlocked) dilated[idx] = true
                    }
                }
            }
            System.arraycopy(dilated, 0, isBoundary, 0, isBoundary.size)
        }

        if (isBoundary[seedIdx]) {
            return FloodFillResult(null, 0, didHitRegionEdge = false)
        }

        // Non-recursive scanline stack using packed coordinates
        var stack = IntArray(4096)
        var sp = 0
        fun push(x: Int, y: Int) {
            if (sp >= stack.size) {
                stack = stack.copyOf(stack.size * 2)
            }
            stack[sp++] = (y shl 16) or (x and 0xFFFF)
        }

        push(seedWorldX - left, seedWorldY - top)
        var filledCount = 0
        var hitEdge = false

        while (sp > 0) {
            val packed = stack[--sp]
            val sx = packed and 0xFFFF
            val sy = packed ushr 16
            var lx = sx
            val row = sy * width

            while (lx > 0 && !visited[row + lx - 1] && !isBoundary[row + lx - 1]) {
                lx--
            }
            var rx = sx
            while (rx < width - 1 && !visited[row + rx + 1] && !isBoundary[row + rx + 1]) {
                rx++
            }

            if (lx == 0 || rx == width - 1 || sy == 0 || sy == height - 1) {
                hitEdge = true
            }

            var spanAbove = false
            var spanBelow = false
            for (x in lx..rx) {
                val idx = row + x
                visited[idx] = true
                filledCount++

                if (sy > 0) {
                    val upIdx = idx - width
                    val upValid = !visited[upIdx] && !isBoundary[upIdx]
                    if (!spanAbove && upValid) {
                        push(x, sy - 1)
                        spanAbove = true
                    } else if (spanAbove && !upValid) {
                        spanAbove = false
                    }
                }
                if (sy < height - 1) {
                    val dnIdx = idx + width
                    val dnValid = !visited[dnIdx] && !isBoundary[dnIdx]
                    if (!spanBelow && dnValid) {
                        push(x, sy + 1)
                        spanBelow = true
                    } else if (spanBelow && !dnValid) {
                        spanBelow = false
                    }
                }
            }
        }

        if (filledCount == 0) {
            return FloodFillResult(null, 0, hitEdge)
        }

        val beforeSnapshots = HashMap<RasterTileCoord, ByteArray?>()
        val afterSnapshots = HashMap<RasterTileCoord, ByteArray?>()

        for (ty in tyMin..tyMax) {
            val tileTopWorld = ty * TILE_SIZE
            for (tx in txMin..txMax) {
                val tileLeftWorld = tx * TILE_SIZE
                val copyXStart = max(left, tileLeftWorld)
                val copyXEnd = min(right, tileLeftWorld + TILE_SIZE)
                val copyYStart = max(top, tileTopWorld)
                val copyYEnd = min(bottom, tileTopWorld + TILE_SIZE)
                if (copyXStart >= copyXEnd || copyYStart >= copyYEnd) continue

                var anyInTile = false
                checkLoop@ for (wy in copyYStart until copyYEnd) {
                    val rowOff = (wy - top) * width
                    for (wx in copyXStart until copyXEnd) {
                        if (visited[rowOff + (wx - left)]) {
                            anyInTile = true
                            break@checkLoop
                        }
                    }
                }
                if (!anyInTile) continue

                val coord = RasterTileCoord(tx, ty)
                val beforeBytes = rasterStore.snapshotTileBytes(boardId, targetLayerId, tx, ty)
                val dstBmp = rasterStore.getOrCreateTile(boardId, targetLayerId, tx, ty)
                val targetRefBmp = if (isAlphaLocked) targetExistingTileSampler?.invoke(tx, ty) else null

                val w = copyXEnd - copyXStart
                val h = copyYEnd - copyYStart
                val lx = copyXStart - tileLeftWorld
                val ly = copyYStart - tileTopWorld
                val tileBuf = IntArray(w * h)
                dstBmp.getPixels(tileBuf, 0, w, lx, ly, w, h)
                val refBuf = if (targetRefBmp != null) {
                    IntArray(w * h).also { targetRefBmp.getPixels(it, 0, w, lx, ly, w, h) }
                } else null

                var tileMutated = false
                for (dy in 0 until h) {
                    val wy = copyYStart + dy
                    val vRow = (wy - top) * width
                    val tRow = dy * w
                    for (dx in 0 until w) {
                        val wx = copyXStart + dx
                        if (visited[vRow + (wx - left)]) {
                            if (isAlphaLocked) {
                                val existingAlpha = if (refBuf != null) {
                                    Color.alpha(refBuf[tRow + dx])
                                } else {
                                    Color.alpha(tileBuf[tRow + dx])
                                }
                                if (existingAlpha == 0) continue
                                val maskedAlpha = ((existingAlpha * finalAlpha) / 255).coerceIn(1, 255)
                                tileBuf[tRow + dx] = (maskedAlpha shl 24) or (fillColorInt and 0x00FFFFFF)
                                tileMutated = true
                            } else {
                                tileBuf[tRow + dx] = effectiveFillArgb
                                tileMutated = true
                            }
                        }
                    }
                }
                if (tileMutated) {
                    beforeSnapshots[coord] = beforeBytes
                    dstBmp.setPixels(tileBuf, 0, w, lx, ly, w, h)
                    afterSnapshots[coord] = rasterStore.snapshotTileBytes(boardId, targetLayerId, tx, ty)
                }
            }
        }

        if (afterSnapshots.isEmpty()) {
            return FloodFillResult(null, 0, hitEdge)
        }
        rasterStore.bumpLayerRevision(boardId, targetLayerId)
        val patch = TileRasterUndoPatch(boardId, targetLayerId, beforeSnapshots, afterSnapshots)

        return FloodFillResult(patch, filledCount, hitEdge)
    }

    private fun colorDistance(c1: Int, c2: Int): Int {
        val a1 = Color.alpha(c1)
        val a2 = Color.alpha(c2)
        if (a1 < 12 && a2 < 12) return 0
        val da = abs(a1 - a2)
        val dr = abs(Color.red(c1) - Color.red(c2))
        val dg = abs(Color.green(c1) - Color.green(c2))
        val db = abs(Color.blue(c1) - Color.blue(c2))
        return max(da, max(dr, max(dg, db)))
    }

    private fun floorDiv(a: Int, b: Int): Int = floor(a.toDouble() / b.toDouble()).toInt()
}
