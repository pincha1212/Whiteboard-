package com.example.ink.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.StrokePoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Real pixel-level Smudge / Pigment Mixing engine operating across [MutableRasterTileStore] tiles.
 * Reads existing layer pigment (vectors + mutable raster tiles via [layerSampler]) and smears/mixes
 * RGB & alpha along the stroke path with radial falloff and optional Alpha Lock preservation.
 */
object PigmentMixEngine {

    private const val TILE_SIZE = PaintTileRasterCache.TILE_PIXEL_SIZE

    fun applySmudgeStroke(
        boardId: String,
        layerId: String,
        points: List<StrokePoint>,
        brushRadiusWorld: Float,
        strength: Float = 0.72f,
        isAlphaLocked: Boolean = false,
        rasterStore: MutableRasterTileStore,
        layerSampler: (tx: Int, ty: Int) -> Bitmap?
    ): TileRasterUndoPatch? {
        if (points.size < 2) return null
        val radius = brushRadiusWorld.coerceIn(4f, 96f)
        val step = max(2.5f, radius * 0.25f)
        val mixStrength = strength.coerceIn(0.15f, 0.96f)

        val sampledPoints = resamplePath(points, step)
        if (sampledPoints.size < 2) return null

        val beforeSnapshots = HashMap<RasterTileCoord, ByteArray?>()
        val sampledSourceCache = HashMap<RasterTileCoord, Bitmap?>()
        val modifiedTiles = HashSet<RasterTileCoord>()

        fun readCombinedPixel(wx: Int, wy: Int): Int {
            val tx = floorDiv(wx, TILE_SIZE)
            val ty = floorDiv(wy, TILE_SIZE)
            val coord = RasterTileCoord(tx, ty)
            val lx = floorMod(wx, TILE_SIZE)
            val ly = floorMod(wy, TILE_SIZE)

            val mutableTile = rasterStore.getTile(boardId, layerId, tx, ty)
            val mutPx = mutableTile?.getPixel(lx, ly) ?: 0
            if ((mutPx ushr 24) >= 245) return mutPx

            val baseTile = sampledSourceCache.getOrPut(coord) { layerSampler(tx, ty) }
            val basePx = baseTile?.getPixel(lx, ly) ?: 0
            if ((mutPx ushr 24) == 0) return basePx
            return alphaOver(mutPx, basePx)
        }

        fun ensureBeforeSnapshot(coord: RasterTileCoord) {
            if (!beforeSnapshots.containsKey(coord)) {
                beforeSnapshots[coord] = rasterStore.snapshotTileBytes(boardId, layerId, coord.tx, coord.ty)
            }
        }

        // Seed pickup pigment from initial touch down
        var carryR = 0f
        var carryG = 0f
        var carryB = 0f
        var carryA = 0f

        run {
            val p0 = sampledPoints[0]
            val samplePx = sampleAverageDisk(p0.x.roundToInt(), p0.y.roundToInt(), (radius * 0.45f).roundToInt().coerceAtLeast(1), ::readCombinedPixel)
            carryA = Color.alpha(samplePx) / 255f
            carryR = Color.red(samplePx).toFloat()
            carryG = Color.green(samplePx).toFloat()
            carryB = Color.blue(samplePx).toFloat()
        }

        val rInt = ceil(radius.toDouble()).toInt()
        val rSq = radius * radius

        for (i in 1 until sampledPoints.size) {
            val pt = sampledPoints[i]
            val cx = pt.x.roundToInt()
            val cy = pt.y.roundToInt()

            // Refresh pickup color slightly at each stamp so pigment gradually blends along the smear
            val underPx = sampleAverageDisk(cx, cy, max(1, (radius * 0.35f).roundToInt()), ::readCombinedPixel)
            val underA = Color.alpha(underPx) / 255f
            if (underA > 0.04f) {
                val pickupBlend = 0.28f
                carryR = carryR * (1f - pickupBlend) + Color.red(underPx) * pickupBlend
                carryG = carryG * (1f - pickupBlend) + Color.green(underPx) * pickupBlend
                carryB = carryB * (1f - pickupBlend) + Color.blue(underPx) * pickupBlend
                carryA = max(carryA * 0.94f, underA * 0.92f)
            } else {
                carryA *= 0.90f
            }

            if (carryA < 0.02f) continue

            val minX = cx - rInt
            val maxX = cx + rInt
            val minY = cy - rInt
            val maxY = cy + rInt

            val txMin = floorDiv(minX, TILE_SIZE)
            val txMax = floorDiv(maxX, TILE_SIZE)
            val tyMin = floorDiv(minY, TILE_SIZE)
            val tyMax = floorDiv(maxY, TILE_SIZE)

            for (tx in txMin..txMax) {
                for (ty in tyMin..tyMax) {
                    val coord = RasterTileCoord(tx, ty)
                    ensureBeforeSnapshot(coord)
                    val tileBmp = rasterStore.getOrCreateTile(boardId, layerId, tx, ty)
                    val tileOriginX = tx * TILE_SIZE
                    val tileOriginY = ty * TILE_SIZE

                    val lxStart = max(0, minX - tileOriginX)
                    val lxEnd = min(TILE_SIZE - 1, maxX - tileOriginX)
                    val lyStart = max(0, minY - tileOriginY)
                    val lyEnd = min(TILE_SIZE - 1, maxY - tileOriginY)
                    if (lxStart > lxEnd || lyStart > lyEnd) continue

                    val w = lxEnd - lxStart + 1
                    val h = lyEnd - lyStart + 1
                    val buf = IntArray(w * h)
                    tileBmp.getPixels(buf, 0, w, lxStart, lyStart, w, h)

                    var tileChanged = false
                    for (dy in 0 until h) {
                        val wy = tileOriginY + lyStart + dy
                        val yDiff = (wy - cy).toFloat()
                        val yDiffSq = yDiff * yDiff
                        val rowOffset = dy * w
                        for (dx in 0 until w) {
                            val wx = tileOriginX + lxStart + dx
                            val xDiff = (wx - cx).toFloat()
                            val distSq = xDiff * xDiff + yDiffSq
                            if (distSq > rSq) continue

                            val distFrac = sqrt(distSq) / radius
                            val falloff = (1f - distFrac)
                            val wBlend = (falloff * falloff * mixStrength * pt.pressure.coerceIn(0.3f, 1.2f)).coerceIn(0f, 0.95f)
                            if (wBlend <= 0.01f) continue

                            val existingPx = buf[rowOffset + dx]
                            val refExisting = if ((existingPx ushr 24) == 0) {
                                sampledSourceCache[coord]?.getPixel(lxStart + dx, lyStart + dy) ?: 0
                            } else {
                                existingPx
                            }
                            val existA = Color.alpha(refExisting)
                            if (isAlphaLocked && existA == 0) continue

                            val dstA = Color.alpha(existingPx) / 255f
                            val dstR = Color.red(existingPx).toFloat()
                            val dstG = Color.green(existingPx).toFloat()
                            val dstB = Color.blue(existingPx).toFloat()

                            val outR = (dstR * (1f - wBlend) + carryR * wBlend).roundToInt().coerceIn(0, 255)
                            val outG = (dstG * (1f - wBlend) + carryG * wBlend).roundToInt().coerceIn(0, 255)
                            val outB = (dstB * (1f - wBlend) + carryB * wBlend).roundToInt().coerceIn(0, 255)
                            val outA = if (isAlphaLocked) {
                                existA
                            } else {
                                ((max(dstA, carryA * falloff) * wBlend + dstA * (1f - wBlend)) * 255f)
                                    .roundToInt()
                                    .coerceIn(0, 255)
                            }
                            buf[rowOffset + dx] = Color.argb(outA, outR, outG, outB)
                            tileChanged = true
                        }
                    }

                    if (tileChanged) {
                        tileBmp.setPixels(buf, 0, w, lxStart, lyStart, w, h)
                        modifiedTiles.add(coord)
                    }
                }
            }
        }

        if (modifiedTiles.isEmpty()) return null
        rasterStore.bumpLayerRevision(boardId, layerId)

        val finalBefore = HashMap<RasterTileCoord, ByteArray?>(modifiedTiles.size)
        val finalAfter = HashMap<RasterTileCoord, ByteArray?>(modifiedTiles.size)
        for (coord in modifiedTiles) {
            finalBefore[coord] = beforeSnapshots[coord]
            finalAfter[coord] = rasterStore.snapshotTileBytes(boardId, layerId, coord.tx, coord.ty)
        }
        return TileRasterUndoPatch(
            boardId = boardId,
            layerId = layerId,
            beforeTiles = finalBefore,
            afterTiles = finalAfter
        )
    }

    /**
     * Erases soft feathered alpha from any existing [MutableRasterTileStore] tiles touched by [points].
     */
    fun applySoftEraserToRasterTiles(
        boardId: String,
        layerId: String,
        points: List<StrokePoint>,
        eraserRadiusWorld: Float,
        strength: Float = 0.78f,
        rasterStore: MutableRasterTileStore
    ): TileRasterUndoPatch? {
        if (points.isEmpty() || !rasterStore.hasAnyTilesForLayer(boardId, layerId)) return null
        val radius = eraserRadiusWorld.coerceIn(4f, 110f)
        val rInt = ceil(radius.toDouble()).toInt()
        val rSq = radius * radius
        val step = max(3f, radius * 0.30f)
        val sampled = if (points.size >= 2) resamplePath(points, step) else points

        val beforeSnapshots = HashMap<RasterTileCoord, ByteArray?>()
        val modifiedTiles = HashSet<RasterTileCoord>()

        for (pt in sampled) {
            val cx = pt.x.roundToInt()
            val cy = pt.y.roundToInt()
            val minX = cx - rInt
            val maxX = cx + rInt
            val minY = cy - rInt
            val maxY = cy + rInt

            for (tx in floorDiv(minX, TILE_SIZE)..floorDiv(maxX, TILE_SIZE)) {
                for (ty in floorDiv(minY, TILE_SIZE)..floorDiv(maxY, TILE_SIZE)) {
                    val bmp = rasterStore.getTile(boardId, layerId, tx, ty) ?: continue
                    val coord = RasterTileCoord(tx, ty)
                    if (!beforeSnapshots.containsKey(coord)) {
                        beforeSnapshots[coord] = rasterStore.snapshotTileBytes(boardId, layerId, tx, ty)
                    }
                    val tileOriginX = tx * TILE_SIZE
                    val tileOriginY = ty * TILE_SIZE
                    val lxStart = max(0, minX - tileOriginX)
                    val lxEnd = min(TILE_SIZE - 1, maxX - tileOriginX)
                    val lyStart = max(0, minY - tileOriginY)
                    val lyEnd = min(TILE_SIZE - 1, maxY - tileOriginY)
                    if (lxStart > lxEnd || lyStart > lyEnd) continue

                    val w = lxEnd - lxStart + 1
                    val h = lyEnd - lyStart + 1
                    val buf = IntArray(w * h)
                    bmp.getPixels(buf, 0, w, lxStart, lyStart, w, h)
                    var changed = false

                    for (dy in 0 until h) {
                        val wy = tileOriginY + lyStart + dy
                        val yDiffSq = (wy - cy).toFloat().let { it * it }
                        val rowOff = dy * w
                        for (dx in 0 until w) {
                            val wx = tileOriginX + lxStart + dx
                            val distSq = (wx - cx).toFloat().let { it * it } + yDiffSq
                            if (distSq > rSq) continue
                            val px = buf[rowOff + dx]
                            val a = (px ushr 24)
                            if (a == 0) continue
                            val falloff = 1f - (sqrt(distSq) / radius)
                            val eraseFrac = (falloff * falloff * strength).coerceIn(0f, 1f)
                            val newA = (a * (1f - eraseFrac)).roundToInt().coerceIn(0, 255)
                            buf[rowOff + dx] = (newA shl 24) or (px and 0x00FFFFFF)
                            changed = true
                        }
                    }

                    if (changed) {
                        bmp.setPixels(buf, 0, w, lxStart, lyStart, w, h)
                        modifiedTiles.add(coord)
                    }
                }
            }
        }

        if (modifiedTiles.isEmpty()) return null
        rasterStore.bumpLayerRevision(boardId, layerId)
        val afterSnapshots = HashMap<RasterTileCoord, ByteArray?>(modifiedTiles.size)
        for (coord in modifiedTiles) {
            afterSnapshots[coord] = rasterStore.snapshotTileBytes(boardId, layerId, coord.tx, coord.ty)
        }
        return TileRasterUndoPatch(boardId, layerId, beforeSnapshots, afterSnapshots)
    }

    private fun sampleAverageDisk(
        cx: Int,
        cy: Int,
        r: Int,
        reader: (Int, Int) -> Int
    ): Int {
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumA = 0L
        var count = 0
        val step = max(1, r / 2)
        for (dy in -r..r step step) {
            for (dx in -r..r step step) {
                val px = reader(cx + dx, cy + dy)
                val a = Color.alpha(px)
                if (a > 0) {
                    sumR += Color.red(px) * a
                    sumG += Color.green(px) * a
                    sumB += Color.blue(px) * a
                    sumA += a
                    count++
                }
            }
        }
        if (sumA == 0L || count == 0) return 0
        return Color.argb(
            (sumA / count).toInt().coerceIn(0, 255),
            (sumR / sumA).toInt().coerceIn(0, 255),
            (sumG / sumA).toInt().coerceIn(0, 255),
            (sumB / sumA).toInt().coerceIn(0, 255)
        )
    }

    private fun alphaOver(fg: Int, bg: Int): Int {
        val fa = (fg ushr 24) / 255f
        val ba = (bg ushr 24) / 255f
        val outA = fa + ba * (1f - fa)
        if (outA <= 0.001f) return 0
        val r = ((Color.red(fg) * fa + Color.red(bg) * ba * (1f - fa)) / outA).roundToInt().coerceIn(0, 255)
        val g = ((Color.green(fg) * fa + Color.green(bg) * ba * (1f - fa)) / outA).roundToInt().coerceIn(0, 255)
        val b = ((Color.blue(fg) * fa + Color.blue(bg) * ba * (1f - fa)) / outA).roundToInt().coerceIn(0, 255)
        return Color.argb((outA * 255f).roundToInt().coerceIn(0, 255), r, g, b)
    }

    private fun resamplePath(points: List<StrokePoint>, step: Float): List<StrokePoint> {
        val out = mutableListOf(points[0])
        var prev = points[0]
        for (i in 1 until points.size) {
            val cur = points[i]
            val dist = hypot(cur.x - prev.x, cur.y - prev.y)
            if (dist >= step) {
                val steps = ceil((dist / step).toDouble()).toInt().coerceAtMost(32)
                for (s in 1..steps) {
                    val t = s / steps.toFloat()
                    out.add(
                        StrokePoint(
                            x = prev.x + (cur.x - prev.x) * t,
                            y = prev.y + (cur.y - prev.y) * t,
                            pressure = prev.pressure + (cur.pressure - prev.pressure) * t
                        )
                    )
                }
                prev = cur
            }
        }
        return out
    }

    private fun floorDiv(a: Int, b: Int): Int = floor(a.toDouble() / b.toDouble()).toInt()
    private fun floorMod(a: Int, b: Int): Int {
        val m = a % b
        return if (m < 0) m + b else m
    }
}
