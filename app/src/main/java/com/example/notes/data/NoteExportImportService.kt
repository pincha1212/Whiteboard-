package com.example.notes.data

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
                        isDefault = fo.optBoolean("isDefault", false)
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
                        checklists = checklists,
                        versionHistory = versions,
                        createdAt = no.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = no.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
        }

        return Pair(importedNotes, importedFolders)
    }
}
