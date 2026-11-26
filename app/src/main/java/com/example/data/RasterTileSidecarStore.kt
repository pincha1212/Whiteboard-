package com.example.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.ink.engine.MutableRasterTileStore
import com.example.ink.engine.PaintTileRasterCache
import com.example.model.DEFAULT_LAYER_ID
import com.example.model.WhiteboardBoard
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Lossless PNG sidecar persistence for sparse 512x512 world-space ARGB_8888 raster tiles.
 * Layout: <rootDir>/<urlEncoded(boardId)>/<urlEncoded(layerId)>/<tx>_<ty>.png
 */
class RasterTileSidecarStore(
    private val rootDir: File?
) {

    fun loadAllInto(rasterStore: MutableRasterTileStore) {
        val dir = rootDir ?: return
        if (!dir.exists() || !dir.isDirectory) return

        val boardDirs = dir.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (boardDir in boardDirs) {
            val boardId = decodeSegment(boardDir.name) ?: continue
            loadBoardDirInto(boardId, boardDir, rasterStore)
        }
    }

    /**
     * Loads raster PNG tiles for a single [boardId] into [rasterStore].
     * Used on 1 GB RAM devices for lazy per-board loading.
     */
    fun loadBoardInto(boardId: String, rasterStore: MutableRasterTileStore) {
        val dir = rootDir ?: return
        if (!dir.exists() || !dir.isDirectory) return
        val boardDir = File(dir, encodeSegment(boardId))
        rasterStore.markBoardLoaded(boardId)
        if (!boardDir.exists() || !boardDir.isDirectory) return
        loadBoardDirInto(boardId, boardDir, rasterStore)
    }

    private fun loadBoardDirInto(
        boardId: String,
        boardDir: File,
        rasterStore: MutableRasterTileStore
    ) {
        rasterStore.markBoardLoaded(boardId)
        val layerDirs = boardDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (layerDir in layerDirs) {
            val layerId = decodeSegment(layerDir.name) ?: continue
            val tileFiles = layerDir.listFiles()?.filter {
                it.isFile && it.extension.equals("png", ignoreCase = true)
            }.orEmpty()
            for (tileFile in tileFiles) {
                val coord = parseTileCoord(tileFile.nameWithoutExtension) ?: continue
                val decoded = decodeMutableTile(tileFile) ?: continue
                rasterStore.putDecodedTile(boardId, layerId, coord.first, coord.second, decoded)
            }
        }
    }

    fun syncWorkspace(
        boards: List<WhiteboardBoard>,
        rasterStore: MutableRasterTileStore
    ) {
        val dir = rootDir ?: return
        if (!dir.exists() && !dir.mkdirs()) return

        val activeBoardDirNames = HashSet<String>(boards.size)
        for (board in boards) {
            val encodedBoard = encodeSegment(board.id)
            activeBoardDirNames.add(encodedBoard)
            // If this board's raster tiles are currently unloaded/evicted to save RAM,
            // keep its existing on-disk PNG sidecar untouched.
            if (!rasterStore.isBoardLoaded(board.id)) continue

            val boardDir = File(dir, encodedBoard)
            val layerIds = board.layers.map { it.id }.ifEmpty { listOf(DEFAULT_LAYER_ID) }
            val activeLayerDirNames = HashSet<String>(layerIds.size)
            var boardHasAnyRasterTiles = false

            for (layerId in layerIds) {
                val tiles = rasterStore.getTilesForLayer(board.id, layerId)
                if (tiles.isEmpty()) continue

                val encodedLayer = encodeSegment(layerId)
                activeLayerDirNames.add(encodedLayer)
                boardHasAnyRasterTiles = true
                val layerDir = File(boardDir, encodedLayer)
                if (!layerDir.exists()) layerDir.mkdirs()

                val expectedFileNames = HashSet<String>(tiles.size)
                for ((coord, bmp) in tiles) {
                    if (bmp.isRecycled) continue
                    val fileName = "${coord.tx}_${coord.ty}.png"
                    expectedFileNames.add(fileName)
                    val targetFile = File(layerDir, fileName)
                    writeTilePng(targetFile, bmp)
                }

                // Prune removed tiles in this layer directory
                layerDir.listFiles()?.forEach { child ->
                    if (child.isFile && !expectedFileNames.contains(child.name)) {
                        child.delete()
                    }
                }
            }

            if (boardDir.exists()) {
                boardDir.listFiles()?.forEach { layerChild ->
                    if (layerChild.isDirectory && !activeLayerDirNames.contains(layerChild.name)) {
                        layerChild.deleteRecursively()
                    }
                }
                if (!boardHasAnyRasterTiles) {
                    boardDir.deleteRecursively()
                }
            }
        }

        // Prune deleted boards
        dir.listFiles()?.forEach { boardChild ->
            if (boardChild.isDirectory && !activeBoardDirNames.contains(boardChild.name)) {
                boardChild.deleteRecursively()
            }
        }
    }

    fun deleteBoardDir(boardId: String) {
        val dir = rootDir ?: return
        val boardDir = File(dir, encodeSegment(boardId))
        if (boardDir.exists()) {
            boardDir.deleteRecursively()
        }
    }

    private fun writeTilePng(file: File, bmp: Bitmap) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.fd.sync()
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.e("RasterTileSidecarStore", "Failed writing raster tile ${file.name}", e)
        }
    }

    private fun decodeMutableTile(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            if (raw.isMutable &&
                raw.config == Bitmap.Config.ARGB_8888 &&
                raw.width == PaintTileRasterCache.TILE_PIXEL_SIZE &&
                raw.height == PaintTileRasterCache.TILE_PIXEL_SIZE
            ) {
                raw
            } else {
                val copy = raw.copy(Bitmap.Config.ARGB_8888, true)
                raw.recycle()
                copy
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTileCoord(stem: String): Pair<Int, Int>? {
        val parts = stem.split('_')
        if (parts.size != 2) return null
        val tx = parts[0].toIntOrNull() ?: return null
        val ty = parts[1].toIntOrNull() ?: return null
        return tx to ty
    }

    private fun encodeSegment(raw: String): String =
        URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    private fun decodeSegment(encoded: String): String? = try {
        URLDecoder.decode(encoded, "UTF-8")
    } catch (_: Exception) {
        null
    }
}
