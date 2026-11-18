package com.example.viewmodel

import com.example.model.StickyNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

/**
 * Isolated observable state store for sticky notes on the active board.
 *
 * - [noteIds] emits only when notes are added, removed, or replaced (board switch / undo / redo).
 * - Each note exposes its own [StateFlow<StickyNote>] so drags, resizes, rotations, and text
 *   edits on one note never force sibling notes or the main canvas to recompose.
 * - Accumulates unsnapped pointer deltas during drag so snap-to-grid advances smoothly.
 */
class StickyNoteStateStore(initialNotes: List<StickyNote> = emptyList()) {
    private val flowsById = ConcurrentHashMap<String, MutableStateFlow<StickyNote>>()
    private val _noteIds = MutableStateFlow<List<String>>(emptyList())
    val noteIds: StateFlow<List<String>> = _noteIds.asStateFlow()

    private var activeDragNoteId: String? = null
    private var activeDragRawX: Float = 0f
    private var activeDragRawY: Float = 0f

    init {
        replaceAll(initialNotes)
    }

    fun flowFor(id: String): StateFlow<StickyNote>? = flowsById[id]

    fun getOrNull(id: String): StickyNote? = flowsById[id]?.value

    fun currentNotes(): List<StickyNote> {
        val ids = _noteIds.value
        val result = ArrayList<StickyNote>(ids.size)
        for (id in ids) {
            flowsById[id]?.value?.let { result.add(it) }
        }
        return result
    }

    fun replaceAll(notes: List<StickyNote>) {
        resetDragAccumulator()
        val nextIds = ArrayList<String>(notes.size)
        val keepIds = HashSet<String>(notes.size * 2)
        for (note in notes) {
            nextIds.add(note.id)
            keepIds.add(note.id)
            val existing = flowsById[note.id]
            if (existing != null) {
                if (existing.value != note) {
                    existing.value = note
                }
            } else {
                flowsById[note.id] = MutableStateFlow(note)
            }
        }
        val iterator = flowsById.keys.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (!keepIds.contains(id)) {
                iterator.remove()
            }
        }
        if (_noteIds.value != nextIds) {
            _noteIds.value = nextIds
        }
    }

    fun addNote(note: StickyNote) {
        resetDragAccumulator()
        flowsById[note.id] = MutableStateFlow(note)
        _noteIds.value = _noteIds.value + note.id
    }

    fun removeNotes(ids: Set<String>) {
        if (ids.isEmpty()) return
        resetDragAccumulator()
        for (id in ids) {
            flowsById.remove(id)
        }
        _noteIds.value = _noteIds.value.filterNot { ids.contains(it) }
    }

    fun updateSingle(id: String, transform: (StickyNote) -> StickyNote): StickyNote? {
        val flow = flowsById[id] ?: return null
        val current = flow.value
        val updated = transform(current)
        if (updated != current) {
            flow.value = updated
        }
        return updated
    }

    fun moveSingleWorld(
        id: String,
        worldDx: Float,
        worldDy: Float,
        snapToGrid: Boolean,
        gridStep: Float = 40f
    ): StickyNote? {
        val flow = flowsById[id] ?: return null
        val current = flow.value
        if (current.isLocked) return current

        if (activeDragNoteId != id) {
            activeDragNoteId = id
            activeDragRawX = current.x
            activeDragRawY = current.y
        }
        activeDragRawX += worldDx
        activeDragRawY += worldDy

        val targetX = if (snapToGrid) round(activeDragRawX / gridStep) * gridStep else activeDragRawX
        val targetY = if (snapToGrid) round(activeDragRawY / gridStep) * gridStep else activeDragRawY

        if (targetX == current.x && targetY == current.y) {
            return current
        }
        val updated = current.copy(x = targetX, y = targetY)
        flow.value = updated
        return updated
    }

    fun moveMultipleWorld(ids: Set<String>, worldDx: Float, worldDy: Float): List<StickyNote> {
        if (ids.isEmpty()) return emptyList()
        val moved = ArrayList<StickyNote>(ids.size)
        for (id in ids) {
            val flow = flowsById[id] ?: continue
            val current = flow.value
            if (!current.isLocked) {
                val next = current.copy(x = current.x + worldDx, y = current.y + worldDy)
                flow.value = next
                moved.add(next)
            }
        }
        return moved
    }

    fun resetDragAccumulator() {
        activeDragNoteId = null
        activeDragRawX = 0f
        activeDragRawY = 0f
    }
}
