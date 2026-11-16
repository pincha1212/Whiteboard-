package com.example.notes.data

import com.example.notes.model.CanvasNoteData
import com.example.notes.model.ChecklistItem
import com.example.notes.model.NoteFolder
import com.example.notes.model.NoteItem
import com.example.notes.model.NoteTag
import com.example.notes.model.NoteVersion
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NoteExportImportService {

    fun exportToMarkdown(note: NoteItem): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val dateStr = sdf.format(Date(note.updatedAt))
        val tagStr = note.tags.joinToString(" ") { "#${it.name}" }

        val sb = StringBuilder()
        sb.append("# ${note.title.ifBlank { "Sin título" }}\n\n")
        sb.append("> **Carpeta:** ${note.folder} | **Fecha:** $dateStr\n")
        if (tagStr.isNotBlank()) {
            sb.append("> **Etiquetas:** $tagStr\n")
        }
        sb.append("\n---\n\n")

        if (note.isVoiceNote) {
            sb.append("🎙️ **Nota de Voz:** Duración ${note.voiceDurationSec}s\n\n")
        }

        if (note.content.isNotBlank()) {
            sb.append(note.content).append("\n\n")
        }

        if (note.checklists.isNotEmpty()) {
            sb.append("### Tareas Pendientes\n")
            note.checklists.forEach { item ->
                val box = if (item.isDone) "[x]" else "[ ]"
                sb.append("- $box ${item.text}\n")
            }
            sb.append("\n")
        }

        return sb.toString()
    }

    fun exportToPlainText(note: NoteItem): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val dateStr = sdf.format(Date(note.updatedAt))

        val sb = StringBuilder()
        sb.append(note.title.ifBlank { "Sin título" }).append("\n")
        sb.append("Carpeta: ${note.folder} | Actualizado: $dateStr\n")
        sb.append("--------------------------------------------------\n\n")

        if (note.content.isNotBlank()) {
            sb.append(note.content).append("\n\n")
        }

        if (note.checklists.isNotEmpty()) {
            sb.append("TAREAS:\n")
            note.checklists.forEach { item ->
                val state = if (item.isDone) "[✓]" else "[ ]"
                sb.append("$state ${item.text}\n")
            }
        }

        return sb.toString()
    }

    fun exportAllToJson(notes: List<NoteItem>, folders: List<NoteFolder>): String {
        val root = JSONObject()
        root.put("app", "Chronos Notes Offline-First")
        root.put("version", "2.0.0")
        root.put("exportedAt", System.currentTimeMillis())

        val foldersArray = JSONArray()
        folders.forEach { f ->
            val fo = JSONObject()
            fo.put("id", f.id)
            fo.put("name", f.name)
            fo.put("iconName", f.iconName)
            fo.put("colorHex", f.colorHex)
            fo.put("isDefault", f.isDefault)
            f.linkedBoardId?.let { fo.put("linkedBoardId", it) }
            foldersArray.put(fo)
        }
        root.put("folders", foldersArray)

        val notesArray = JSONArray()
        notes.forEach { n ->
            val no = JSONObject()
            no.put("id", n.id)
            no.put("title", n.title)
            no.put("content", n.content)
            no.put("folder", n.folder)
            no.put("colorHex", n.colorHex)
            no.put("isPinned", n.isPinned)
            no.put("isLocked", n.isLocked)
            no.put("pinHash", n.pinHash ?: "")
            no.put("isVoiceNote", n.isVoiceNote)
            no.put("voiceDurationSec", n.voiceDurationSec)
            n.voiceAudioUri?.let { no.put("voiceAudioUri", it) }
            no.put("isDeleted", n.isDeleted)
            n.deletedAt?.let { no.put("deletedAt", it) }
            n.canvasData?.let { cd ->
                no.put("canvasData", JSONObject().apply {
                    put("boardId", cd.boardId)
                    put("x", cd.x.toDouble())
                    put("y", cd.y.toDouble())
                    put("width", cd.width.toDouble())
                    put("height", cd.height.toDouble())
                    put("rotation", cd.rotation.toDouble())
                    put("color", cd.color)
                    put("isLocked", cd.isLocked)
                    put("isAspectRatioLocked", cd.isAspectRatioLocked)
                    cd.groupId?.let { put("groupId", it) }
                    cd.parentId?.let { put("parentId", it) }
                    put("isHiddenByCollapse", cd.isHiddenByCollapse)
                })
            }
            n.archivedCanvasData?.let { cd ->
                no.put("archivedCanvasData", JSONObject().apply {
                    put("boardId", cd.boardId)
                    put("x", cd.x.toDouble())
                    put("y", cd.y.toDouble())
                    put("width", cd.width.toDouble())
                    put("height", cd.height.toDouble())
                    put("rotation", cd.rotation.toDouble())
                    put("color", cd.color)
                    put("isLocked", cd.isLocked)
                    put("isAspectRatioLocked", cd.isAspectRatioLocked)
                    cd.groupId?.let { put("groupId", it) }
                    cd.parentId?.let { put("parentId", it) }
                    put("isHiddenByCollapse", cd.isHiddenByCollapse)
                })
            }
            val linkedBoardsArr = JSONArray()
            n.linkedBoardIds.forEach { linkedBoardsArr.put(it) }
            no.put("linkedBoardIds", linkedBoardsArr)
            no.put("createdAt", n.createdAt)
            no.put("updatedAt", n.updatedAt)

            // Tags
            val tagsArr = JSONArray()
            n.tags.forEach { t ->
                val to = JSONObject()
                to.put("name", t.name)
                to.put("colorHex", t.colorHex)
                tagsArr.put(to)
            }
            no.put("tags", tagsArr)

            // Checklists
            val checksArr = JSONArray()
            n.checklists.forEach { c ->
                val co = JSONObject()
                co.put("id", c.id)
                co.put("text", c.text)
                co.put("isDone", c.isDone)
                checksArr.put(co)
            }
            no.put("checklists", checksArr)

            // Version history
            val versArr = JSONArray()
            n.versionHistory.forEach { v ->
                val vo = JSONObject()
                vo.put("versionId", v.versionId)
                vo.put("timestamp", v.timestamp)
                vo.put("title", v.title)
                vo.put("content", v.content)
                vo.put("characterCount", v.characterCount)
                vo.put("changeSummary", v.changeSummary)
                versArr.put(vo)
            }
            no.put("versionHistory", versArr)

            notesArray.put(no)
        }
        root.put("notes", notesArray)

        return root.toString(2)
    }

    fun importFromJson(jsonString: String): Pair<List<NoteItem>, List<NoteFolder>> {
        val root = JSONObject(jsonString)
        val importedFolders = mutableListOf<NoteFolder>()
        val importedNotes = mutableListOf<NoteItem>()

        if (root.has("folders")) {
            val fa = root.getJSONArray("folders")
            for (i in 0 until fa.length()) {
                val fo = fa.getJSONObject(i)
                importedFolders.add(
                    NoteFolder(
                        id = fo.optString("id"),
                        name = fo.optString("name", "General"),
                        iconName = fo.optString("iconName", "folder"),
                        colorHex = fo.optString("colorHex", "#0078D4"),
                        isDefault = fo.optBoolean("isDefault", false),
                        linkedBoardId = fo.optString("linkedBoardId", "").takeIf { it.isNotBlank() }
                    )
                )
            }
        }

        if (root.has("notes")) {
            val na = root.getJSONArray("notes")
            for (i in 0 until na.length()) {
                val no = na.getJSONObject(i)

                val tags = mutableListOf<NoteTag>()
                if (no.has("tags")) {
                    val ta = no.getJSONArray("tags")
                    for (j in 0 until ta.length()) {
                        val to = ta.getJSONObject(j)
                        tags.add(NoteTag(to.optString("name"), to.optString("colorHex", "#0078D4")))
                    }
                }

                val checklists = mutableListOf<ChecklistItem>()
                if (no.has("checklists")) {
                    val ca = no.getJSONArray("checklists")
                    for (j in 0 until ca.length()) {
                        val co = ca.getJSONObject(j)
                        checklists.add(
                            ChecklistItem(
                                id = co.optString("id"),
                                text = co.optString("text"),
                                isDone = co.optBoolean("isDone")
                            )
                        )
                    }
                }

                val versions = mutableListOf<NoteVersion>()
                if (no.has("versionHistory")) {
                    val va = no.getJSONArray("versionHistory")
                    for (j in 0 until va.length()) {
                        val vo = va.getJSONObject(j)
                        versions.add(
                            NoteVersion(
                                versionId = vo.optString("versionId"),
                                timestamp = vo.optLong("timestamp"),
                                title = vo.optString("title"),
                                content = vo.optString("content"),
                                characterCount = vo.optInt("characterCount"),
                                changeSummary = vo.optString("changeSummary")
                            )
                        )
                    }
                }

                val pinHash = no.optString("pinHash").takeIf { it.isNotBlank() }
                val voiceAudioUri = no.optString("voiceAudioUri", "").takeIf { it.isNotBlank() }
                val cdObj = no.optJSONObject("canvasData")
                val canvasData = if (cdObj != null && cdObj.optString("boardId").isNotBlank()) {
                    CanvasNoteData(
                        boardId = cdObj.optString("boardId"),
                        x = cdObj.optDouble("x", 0.0).toFloat(),
                        y = cdObj.optDouble("y", 0.0).toFloat(),
                        width = cdObj.optDouble("width", 220.0).toFloat(),
                        height = cdObj.optDouble("height", 200.0).toFloat(),
                        rotation = cdObj.optDouble("rotation", 0.0).toFloat(),
                        color = cdObj.optString("color", "#FFF275"),
                        isLocked = cdObj.optBoolean("isLocked", false),
                        isAspectRatioLocked = cdObj.optBoolean("isAspectRatioLocked", false),
                        groupId = cdObj.optString("groupId", "").takeIf { it.isNotBlank() },
                        parentId = cdObj.optString("parentId", "").takeIf { it.isNotBlank() },
                        isHiddenByCollapse = cdObj.optBoolean("isHiddenByCollapse", false)
                    )
                } else null
                val archivedCdObj = no.optJSONObject("archivedCanvasData")
                val archivedCanvasData = if (archivedCdObj != null && archivedCdObj.optString("boardId").isNotBlank()) {
                    CanvasNoteData(
                        boardId = archivedCdObj.optString("boardId"),
                        x = archivedCdObj.optDouble("x", 0.0).toFloat(),
                        y = archivedCdObj.optDouble("y", 0.0).toFloat(),
                        width = archivedCdObj.optDouble("width", 220.0).toFloat(),
                        height = archivedCdObj.optDouble("height", 200.0).toFloat(),
                        rotation = archivedCdObj.optDouble("rotation", 0.0).toFloat(),
                        color = archivedCdObj.optString("color", "#FFF275"),
                        isLocked = archivedCdObj.optBoolean("isLocked", false),
                        isAspectRatioLocked = archivedCdObj.optBoolean("isAspectRatioLocked", false),
                        groupId = archivedCdObj.optString("groupId", "").takeIf { it.isNotBlank() },
                        parentId = archivedCdObj.optString("parentId", "").takeIf { it.isNotBlank() },
                        isHiddenByCollapse = archivedCdObj.optBoolean("isHiddenByCollapse", false)
                    )
                } else null
                val linkedBoardIds = mutableSetOf<String>()
                no.optJSONArray("linkedBoardIds")?.let { linked ->
                    for (index in 0 until linked.length()) {
                        linked.optString(index).takeIf { it.isNotBlank() }?.let(linkedBoardIds::add)
                    }
                }
                val isDeleted = no.optBoolean("isDeleted", false)
                val deletedAt = if (no.has("deletedAt")) no.optLong("deletedAt") else null

                importedNotes.add(
                    NoteItem(
                        id = no.optString("id"),
                        title = no.optString("title"),
                        content = no.optString("content"),
                        folder = no.optString("folder", "Bandeja de Entrada"),
                        tags = tags,
                        colorHex = no.optString("colorHex", "#FFFFFF"),
                        isPinned = no.optBoolean("isPinned", false),
                        isLocked = no.optBoolean("isLocked", false),
                        pinHash = pinHash,
                        isVoiceNote = no.optBoolean("isVoiceNote", false),
                        voiceDurationSec = no.optInt("voiceDurationSec", 0),
                        voiceAudioUri = voiceAudioUri,
                        checklists = checklists,
                        versionHistory = versions,
                        canvasData = canvasData,
                        archivedCanvasData = archivedCanvasData,
                        linkedBoardIds = linkedBoardIds,
                        isDeleted = isDeleted,
                        deletedAt = deletedAt,
                        createdAt = no.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = no.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
        }

        return Pair(importedNotes, importedFolders)
    }
}
