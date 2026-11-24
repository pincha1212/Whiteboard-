package com.example.viewmodel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.example.ink.engine.FloodFillResult
import com.example.ink.engine.MutableRasterTileStore
import com.example.ink.engine.PaintTileRasterCache
import com.example.ink.engine.PigmentMixEngine
import com.example.ink.engine.RasterTileCoord
import com.example.ink.engine.ScanlineFloodFillEngine
import com.example.ink.engine.TileRasterUndoPatch
import com.example.model.DrawingLayer
import com.example.model.StrokePoint
import com.example.model.WhiteboardBoard
import kotlin.math.roundToInt

/**
 * Coordinates v3.2.0 pixel-level raster operations ([PigmentMixEngine], [ScanlineFloodFillEngine],
 * Reference Layer boundary sampling, and atomic multi-step undo patch merging) on top of
 * [MutableRasterTileStore] and [PaintTileRasterCache].
 */
class StudioEngineController(
    private val rasterStore: MutableRasterTileStore
) {
    private val bakingCache = PaintTileRasterCache(rasterStore)

    fun resetCache() {
        bakingCache.clear()
    }

    fun executeSmudgeStroke(
        board: WhiteboardBoard,
        layer: DrawingLayer,
        points: List<StrokePoint>,
        brushRadiusWorld: Float,
        strength: Float = 0.72f,
        handDrawnFactor: Float = 0.24f
    ): TileRasterUndoPatch? {
        if (!layer.isVisible || layer.isLocked || points.size < 2) return null
        val sampler: (Int, Int) -> Bitmap? = { tx, ty ->
            bakingCache.bakeSingleLayerTile(
                boardId = board.id,
                layer = layer,
                strokes = board.strokes,
                shapes = board.shapes,
                tx = tx,
                ty = ty,
                isDarkBoard = board.colorTheme.isDark,
                handDrawnFactor = handDrawnFactor
            )
        }
        return PigmentMixEngine.applySmudgeStroke(
            boardId = board.id,
            layerId = layer.id,
            points = points,
            brushRadiusWorld = brushRadiusWorld,
            strength = strength,
            isAlphaLocked = layer.isAlphaLocked,
            rasterStore = rasterStore,
            layerSampler = sampler
        )
    }

    fun executeSoftEraserSegment(
        board: WhiteboardBoard,
        layer: DrawingLayer,
        points: List<StrokePoint>,
        eraserRadiusWorld: Float,
        strength: Float = 0.78f
    ): TileRasterUndoPatch? {
        if (!layer.isVisible || layer.isLocked || points.isEmpty()) return null
        return PigmentMixEngine.applySoftEraserToRasterTiles(
            boardId = board.id,
            layerId = layer.id,
            points = points,
            eraserRadiusWorld = eraserRadiusWorld,
            strength = strength,
            rasterStore = rasterStore
        )
    }

    fun executeFloodFill(
        board: WhiteboardBoard,
        targetLayer: DrawingLayer,
        seedWorldX: Float,
        seedWorldY: Float,
        fillColorHex: String,
        fillOpacity: Float,
        tolerance: Int,
        handDrawnFactor: Float = 0.24f
    ): FloodFillResult {
        if (!targetLayer.isVisible || targetLayer.isLocked) {
            return FloodFillResult(null, 0, didHitRegionEdge = false)
        }
        val fillColorInt = try {
            Color.parseColor(fillColorHex)
        } catch (_: Exception) {
            Color.BLACK
        }

        val layers = board.layers.ifEmpty { listOf(targetLayer) }
        val refLayers = layers.filter { it.isVisible && it.isReference }
        val boundaryLayers = if (refLayers.isNotEmpty()) refLayers else listOf(targetLayer)

        val boundarySampler: (Int, Int) -> Bitmap? = { tx, ty ->
            if (boundaryLayers.size == 1) {
                bakingCache.bakeSingleLayerTile(
                    boardId = board.id,
                    layer = boundaryLayers.first(),
                    strokes = board.strokes,
                    shapes = board.shapes,
                    tx = tx,
                    ty = ty,
                    isDarkBoard = board.colorTheme.isDark,
                    handDrawnFactor = handDrawnFactor
                )
            } else {
                var merged: Bitmap? = null
                var mergeCanvas: Canvas? = null
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                for (bLayer in boundaryLayers) {
                    val bmp = bakingCache.bakeSingleLayerTile(
                        boardId = board.id,
                        layer = bLayer,
                        strokes = board.strokes,
                        shapes = board.shapes,
                        tx = tx,
                        ty = ty,
                        isDarkBoard = board.colorTheme.isDark,
                        handDrawnFactor = handDrawnFactor
                    ) ?: continue
                    if (merged == null) {
                        merged = bmp.copy(Bitmap.Config.ARGB_8888, true)
                        mergeCanvas = merged?.let { Canvas(it) }
                    } else {
                        mergeCanvas?.drawBitmap(bmp, 0f, 0f, paint)
                    }
                }
                merged
            }
        }

        val targetSampler: (Int, Int) -> Bitmap? = { tx, ty ->
            bakingCache.bakeSingleLayerTile(
                boardId = board.id,
                layer = targetLayer,
                strokes = board.strokes,
                shapes = board.shapes,
                tx = tx,
                ty = ty,
                isDarkBoard = board.colorTheme.isDark,
                handDrawnFactor = handDrawnFactor
            )
        }

        val sx = seedWorldX.roundToInt()
        val sy = seedWorldY.roundToInt()
        val regionHalf = 960
        val regionWorld = Rect(sx - regionHalf, sy - regionHalf, sx + regionHalf, sy + regionHalf)

        return ScanlineFloodFillEngine.floodFill(
            boardId = board.id,
            targetLayerId = targetLayer.id,
            seedWorldX = sx,
            seedWorldY = sy,
            fillColorInt = fillColorInt,
            fillOpacity = fillOpacity,
            tolerance = tolerance,
            gapClosingPx = 1,
            isAlphaLocked = targetLayer.isAlphaLocked,
            regionWorld = regionWorld,
            rasterStore = rasterStore,
            boundaryTileSampler = boundarySampler,
            targetExistingTileSampler = targetSampler
        )
    }

    /**
     * Builds a lossless undo patch capturing every raster tile currently present on [layerId]
     * so deleting/clearing a layer restores its exact raster pixels on Undo.
     */
    fun createClearLayerPatch(boardId: String, layerId: String): TileRasterUndoPatch? {
        val existing = rasterStore.snapshotLayerTileBytes(boardId, layerId)
        if (existing.isEmpty()) return null
        val after = existing.keys.associateWith { null }
        return TileRasterUndoPatch(
            boardId = boardId,
            layerId = layerId,
            beforeTiles = existing,
            afterTiles = after
        )
    }

    companion object {
        fun mergeUndoPatches(
            existing: TileRasterUndoPatch?,
            incoming: TileRasterUndoPatch?
        ): TileRasterUndoPatch? {
            if (existing == null) return incoming
            if (incoming == null) return existing
            if (existing.boardId != incoming.boardId || existing.layerId != incoming.layerId) {
                return incoming
            }
            val mergedBefore = HashMap<RasterTileCoord, ByteArray?>(existing.beforeTiles)
            for ((coord, bytes) in incoming.beforeTiles) {
                if (!mergedBefore.containsKey(coord)) {
                    mergedBefore[coord] = bytes
                }
            }
            val mergedAfter = HashMap<RasterTileCoord, ByteArray?>(existing.afterTiles)
            mergedAfter.putAll(incoming.afterTiles)
            return TileRasterUndoPatch(
                boardId = existing.boardId,
                layerId = existing.layerId,
                beforeTiles = mergedBefore,
                afterTiles = mergedAfter
            )
        }
    }
}
