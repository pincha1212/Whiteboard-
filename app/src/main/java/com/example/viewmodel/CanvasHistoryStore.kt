package com.example.viewmodel

import com.example.ink.engine.MemoryBudgetProfile
import com.example.ink.engine.TileRasterUndoPatch
import com.example.model.DrawingLayer
import com.example.model.ShapeItem
import com.example.model.TextItem
import com.example.model.WhiteboardBoard
import com.example.model.WhiteboardStroke

data class BoardHistorySnapshot(
    val strokes: List<WhiteboardStroke>,
    val shapes: List<ShapeItem>,
    val texts: List<TextItem>,
    val layers: List<DrawingLayer>,
    val activeLayerId: String,
    val rasterPatches: List<TileRasterUndoPatch> = emptyList(),
    val estimatedBytes: Long
)

/**
 * Memory-bounded undo/redo stack driven by [MemoryBudgetProfile].
 * Enforces both [MemoryBudgetProfile.maxUndoSteps] and [MemoryBudgetProfile.undoHistoryBudgetBytes]
 * so 1 GB RAM devices retain up to 15 steps / 2.5 MB without OOM.
 */
class CanvasHistoryStore(
    val memoryBudget: MemoryBudgetProfile = MemoryBudgetProfile.standard()
) {
    private val undoStack = mutableListOf<BoardHistorySnapshot>()
    private val redoStack = mutableListOf<BoardHistorySnapshot>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    fun retainedBytes(): Long =
        undoStack.sumOf { it.estimatedBytes } + redoStack.sumOf { it.estimatedBytes }

    fun pushSnapshot(
        board: WhiteboardBoard,
        rasterPatches: List<TileRasterUndoPatch> = emptyList()
    ) {
        undoStack.add(createSnapshot(board, rasterPatches))
        while (undoStack.size > memoryBudget.maxUndoSteps) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
        trimToBudget(memoryBudget.undoHistoryBudgetBytes)
    }

    fun appendRasterPatchToTop(patch: TileRasterUndoPatch) {
        if (undoStack.isEmpty()) return
        val idx = undoStack.lastIndex
        val top = undoStack[idx]
        var extraBytes = 0L
        patch.beforeTiles.values.forEach { extraBytes += it?.size?.toLong() ?: 0L }
        patch.afterTiles.values.forEach { extraBytes += it?.size?.toLong() ?: 0L }
        undoStack[idx] = top.copy(
            rasterPatches = top.rasterPatches + patch,
            estimatedBytes = top.estimatedBytes + extraBytes
        )
        trimToBudget(memoryBudget.undoHistoryBudgetBytes)
    }

    fun popUndo(currentBoard: WhiteboardBoard): BoardHistorySnapshot? {
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(createSnapshot(currentBoard, previous.rasterPatches))
        while (redoStack.size > memoryBudget.maxUndoSteps) {
            redoStack.removeAt(0)
        }
        trimToBudget(memoryBudget.undoHistoryBudgetBytes)
        return previous
    }

    fun popRedo(currentBoard: WhiteboardBoard): BoardHistorySnapshot? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(createSnapshot(currentBoard, next.rasterPatches))
        while (undoStack.size > memoryBudget.maxUndoSteps) {
            undoStack.removeAt(0)
        }
        trimToBudget(memoryBudget.undoHistoryBudgetBytes)
        return next
    }

    /**
     * Releases oldest history entries under Android memory pressure.
     */
    fun trimEmergency(targetFraction: Float = 0.5f) {
        val clamped = targetFraction.coerceIn(0f, 1f)
        if (clamped <= 0.01f) {
            clear()
            return
        }
        val targetBytes = (memoryBudget.undoHistoryBudgetBytes * clamped).toLong().coerceAtLeast(0L)
        while ((undoStack.isNotEmpty() || redoStack.isNotEmpty()) && retainedBytes() > targetBytes) {
            when {
                redoStack.isNotEmpty() -> redoStack.removeAt(0)
                undoStack.isNotEmpty() -> undoStack.removeAt(0)
                else -> break
            }
        }
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun trimToBudget(budgetBytes: Long) {
        while (undoStack.size + redoStack.size > 1 && retainedBytes() > budgetBytes) {
            when {
                undoStack.size > 1 -> undoStack.removeAt(0)
                redoStack.size > 1 -> redoStack.removeAt(0)
                else -> break
            }
        }
    }

    companion object {
        fun createSnapshot(
            board: WhiteboardBoard,
            rasterPatches: List<TileRasterUndoPatch> = emptyList()
        ): BoardHistorySnapshot {
            var bytes = 256L
            board.strokes.forEach { stroke ->
                bytes += 128L + stroke.points.size.toLong() * 40L
            }
            board.shapes.forEach { shape ->
                bytes += 160L + shape.vertices.size.toLong() * 40L
            }
            board.textItems.forEach { item ->
                bytes += 160L + item.text.length.toLong() * 2L
            }
            rasterPatches.forEach { patch ->
                patch.beforeTiles.values.forEach { bytes += it?.size?.toLong() ?: 0L }
                patch.afterTiles.values.forEach { bytes += it?.size?.toLong() ?: 0L }
            }
            return BoardHistorySnapshot(
                strokes = board.strokes,
                shapes = board.shapes,
                texts = board.textItems,
                layers = board.layers,
                activeLayerId = board.activeLayerId,
                rasterPatches = rasterPatches,
                estimatedBytes = bytes
            )
        }
    }
}
