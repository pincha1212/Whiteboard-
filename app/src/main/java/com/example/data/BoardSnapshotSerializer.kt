package com.example.data

import com.example.model.CanvasColorTheme
import com.example.model.CanvasGridType
import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StickerItem
import com.example.model.StickyNote
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.TextItem
import com.example.model.WhiteboardBoard
import com.example.model.WhiteboardStroke
import org.json.JSONArray
import org.json.JSONObject

object BoardSnapshotSerializer {

    fun serializeBoard(board: WhiteboardBoard): String {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("boardId", board.id)
        root.put("title", board.title)
        root.put("gridType", board.gridType.name)
        root.put("colorTheme", board.colorTheme.name)
        root.put("timestamp", System.currentTimeMillis())

        val strokesArr = JSONArray()
        board.strokes.forEach { s ->
            val obj = JSONObject()
            obj.put("id", s.id)
            obj.put("colorHex", s.colorHex)
            obj.put("width", s.width.toDouble())
            obj.put("isHighlighter", s.isHighlighter)
            obj.put("isRainbow", s.isRainbow)
            obj.put("strokeStyle", s.strokeStyle.name)
            obj.put("capStyle", s.capStyle.name)
            obj.put("zIndex", s.zIndex)
            obj.put("brushType", s.brushType.name)
            obj.put("opacity", s.opacity.toDouble())
            val ptsArr = JSONArray()
            s.points.forEach { p ->
                ptsArr.put(JSONObject().apply {
                    put("x", p.x.toDouble())
                    put("y", p.y.toDouble())
                })
            }
            obj.put("points", ptsArr)
            strokesArr.put(obj)
        }
        root.put("strokes", strokesArr)

        val shapesArr = JSONArray()
        board.shapes.forEach { sh ->
            val obj = JSONObject()
            obj.put("id", sh.id)
            obj.put("shapeType", sh.shapeType.name)
            obj.put("x", sh.x.toDouble())
            obj.put("y", sh.y.toDouble())
            obj.put("width", sh.width.toDouble())
            obj.put("height", sh.height.toDouble())
            obj.put("strokeColorHex", sh.strokeColorHex)
            obj.put("fillColorHex", sh.fillColorHex)
            obj.put("strokeWidth", sh.strokeWidth.toDouble())
            obj.put("isFilled", sh.isFilled)
            obj.put("strokeStyle", sh.strokeStyle.name)
            obj.put("capStyle", sh.capStyle.name)
            obj.put("rotation", sh.rotation.toDouble())
            obj.put("opacity", sh.opacity.toDouble())
            obj.put("isRainbow", sh.isRainbow)
            obj.put("label", sh.label)
            obj.put("isLocked", sh.isLocked)
            sh.templateRole?.let { obj.put("templateRole", it) }
            sh.groupId?.let { obj.put("groupId", it) }
            sh.parentId?.let { obj.put("parentId", it) }
            obj.put("isCollapsed", sh.isCollapsed)
            obj.put("isHiddenByCollapse", sh.isHiddenByCollapse)
            val vertsArr = JSONArray()
            sh.vertices.forEach { v ->
                vertsArr.put(JSONObject().apply {
                    put("x", v.x.toDouble())
                    put("y", v.y.toDouble())
                })
            }
            obj.put("vertices", vertsArr)
            shapesArr.put(obj)
        }
        root.put("shapes", shapesArr)

        val notesArr = JSONArray()
        board.stickyNotes.forEach { n ->
            val obj = JSONObject()
            obj.put("id", n.id)
            obj.put("text", n.text)
            n.title?.let { obj.put("title", it) }
            obj.put("x", n.x.toDouble())
            obj.put("y", n.y.toDouble())
            obj.put("width", n.width.toDouble())
            obj.put("height", n.height.toDouble())
            obj.put("colorHex", n.colorHex)
            obj.put("rotation", n.rotation.toDouble())
            obj.put("isPinned", n.isPinned)
            obj.put("isVoiceNote", n.isVoiceNote)
            obj.put("voiceDurationSec", n.voiceDurationSec)
            obj.put("isLocked", n.isLocked)
            obj.put("isAspectRatioLocked", n.isAspectRatioLocked)
            n.groupId?.let { obj.put("groupId", it) }
            n.parentId?.let { obj.put("parentId", it) }
            obj.put("isHiddenByCollapse", n.isHiddenByCollapse)
            notesArr.put(obj)
        }
        root.put("stickyNotes", notesArr)

        val textsArr = JSONArray()
        board.textItems.forEach { t ->
            textsArr.put(JSONObject().apply {
                put("id", t.id)
                put("text", t.text)
                put("x", t.x.toDouble())
                put("y", t.y.toDouble())
                put("fontSize", t.fontSize.toDouble())
                put("colorHex", t.colorHex)
                put("isBold", t.isBold)
                put("isLocked", t.isLocked)
                put("isEditableCard", t.isEditableCard)
                t.backgroundColorHex?.let { put("backgroundColorHex", it) }
                put("width", t.width.toDouble())
                t.groupId?.let { put("groupId", it) }
                t.parentId?.let { put("parentId", it) }
                put("isHiddenByCollapse", t.isHiddenByCollapse)
            })
        }
        root.put("textItems", textsArr)

        val stickersArr = JSONArray()
        board.stickers.forEach { st ->
            stickersArr.put(JSONObject().apply {
                put("id", st.id)
                put("emoji", st.emoji)
                put("x", st.x.toDouble())
                put("y", st.y.toDouble())
                put("scale", st.scale.toDouble())
            })
        }
        root.put("stickers", stickersArr)

        return root.toString()
    }

    fun deserializeIntoBoard(json: String, currentBoard: WhiteboardBoard): WhiteboardBoard? {
        return try {
            val root = JSONObject(json)
            val gridName = root.optString("gridType", currentBoard.gridType.name)
            val gridType = CanvasGridType.values().firstOrNull { it.name == gridName } ?: currentBoard.gridType

            val themeName = root.optString("colorTheme", currentBoard.colorTheme.name)
            val colorTheme = CanvasColorTheme.values().firstOrNull { it.name == themeName } ?: currentBoard.colorTheme

            val strokes = mutableListOf<WhiteboardStroke>()
            val strokesArr = root.optJSONArray("strokes")
            if (strokesArr != null) {
                for (i in 0 until strokesArr.length()) {
                    val obj = strokesArr.getJSONObject(i)
                    val pts = mutableListOf<StrokePoint>()
                    val ptsArr = obj.optJSONArray("points")
                    if (ptsArr != null) {
                        for (j in 0 until ptsArr.length()) {
                            val po = ptsArr.getJSONObject(j)
                            pts.add(StrokePoint(po.getDouble("x").toFloat(), po.getDouble("y").toFloat()))
                        }
                    }
                    val styleName = obj.optString("strokeStyle", StrokeStyle.SOLID.name)
                    val style = StrokeStyle.values().firstOrNull { it.name == styleName } ?: StrokeStyle.SOLID
                    val capName = obj.optString("capStyle", LineCapStyle.NONE.name)
                    val capStyle = LineCapStyle.values().firstOrNull { it.name == capName } ?: LineCapStyle.NONE
                    val isHL = obj.optBoolean("isHighlighter", false)
                    val defaultBrush = if (isHL) StrokeBrushType.HIGHLIGHTER else StrokeBrushType.FOUNTAIN_PEN
                    val brushName = obj.optString("brushType", defaultBrush.name)
                    val brushType = StrokeBrushType.values().firstOrNull { it.name == brushName } ?: defaultBrush
                    val opacity = obj.optDouble("opacity", brushType.defaultOpacity.toDouble()).toFloat().coerceIn(0.1f, 1.0f)
                    strokes.add(
                        WhiteboardStroke(
                            id = obj.getString("id"),
                            points = pts,
                            colorHex = obj.getString("colorHex"),
                            width = obj.getDouble("width").toFloat(),
                            isHighlighter = isHL,
                            isRainbow = obj.optBoolean("isRainbow", false),
                            strokeStyle = style,
                            capStyle = capStyle,
                            zIndex = obj.optInt("zIndex", 0),
                            brushType = brushType,
                            opacity = opacity
                        )
                    )
                }
            }

            val shapes = mutableListOf<ShapeItem>()
            val shapesArr = root.optJSONArray("shapes")
            if (shapesArr != null) {
                for (i in 0 until shapesArr.length()) {
                    val obj = shapesArr.getJSONObject(i)
                    val typeName = obj.optString("shapeType", ShapeType.RECTANGLE.name)
                    val shapeType = ShapeType.values().firstOrNull { it.name == typeName } ?: ShapeType.RECTANGLE
                    val styleName = obj.optString("strokeStyle", StrokeStyle.SOLID.name)
                    val style = StrokeStyle.values().firstOrNull { it.name == styleName } ?: StrokeStyle.SOLID
                    val capName = obj.optString("capStyle", LineCapStyle.NONE.name)
                    val capStyle = LineCapStyle.values().firstOrNull { it.name == capName } ?: LineCapStyle.NONE
                    val verts = mutableListOf<StrokePoint>()
                    val vertsArr = obj.optJSONArray("vertices")
                    if (vertsArr != null) {
                        for (j in 0 until vertsArr.length()) {
                            val vo = vertsArr.getJSONObject(j)
                            verts.add(StrokePoint(vo.getDouble("x").toFloat(), vo.getDouble("y").toFloat()))
                        }
                    }
                    shapes.add(
                        ShapeItem(
                            id = obj.getString("id"),
                            shapeType = shapeType,
                            x = obj.getDouble("x").toFloat(),
                            y = obj.getDouble("y").toFloat(),
                            width = obj.getDouble("width").toFloat(),
                            height = obj.getDouble("height").toFloat(),
                            strokeColorHex = obj.optString("strokeColorHex", "#2563EB"),
                            fillColorHex = obj.optString("fillColorHex", "#DBEAFE"),
                            strokeWidth = obj.optDouble("strokeWidth", 4.0).toFloat(),
                            isFilled = obj.optBoolean("isFilled", false),
                            strokeStyle = style,
                            capStyle = capStyle,
                            rotation = obj.optDouble("rotation", 0.0).toFloat(),
                            vertices = verts,
                            opacity = obj.optDouble("opacity", 1.0).toFloat().coerceIn(0.15f, 1.0f),
                            isRainbow = obj.optBoolean("isRainbow", false),
                            label = obj.optString("label", ""),
                            isLocked = obj.optBoolean("isLocked", false),
                            templateRole = obj.optString("templateRole", "").takeIf { it.isNotEmpty() },
                            groupId = obj.optString("groupId", "").takeIf { it.isNotEmpty() },
                            parentId = obj.optString("parentId", "").takeIf { it.isNotEmpty() },
                            isCollapsed = obj.optBoolean("isCollapsed", false),
                            isHiddenByCollapse = obj.optBoolean("isHiddenByCollapse", false)
                        )
                    )
                }
            }

            val notes = mutableListOf<StickyNote>()
            val notesArr = root.optJSONArray("stickyNotes")
            if (notesArr != null) {
                for (i in 0 until notesArr.length()) {
                    val obj = notesArr.getJSONObject(i)
                    notes.add(
                        StickyNote(
                            id = obj.getString("id"),
                            text = obj.optString("text", ""),
                            title = if (obj.has("title")) obj.optString("title", "") else null,
                            x = obj.getDouble("x").toFloat(),
                            y = obj.getDouble("y").toFloat(),
                            width = obj.optDouble("width", 220.0).toFloat(),
                            height = obj.optDouble("height", 200.0).toFloat(),
                            colorHex = obj.optString("colorHex", "#FFF275"),
                            rotation = obj.optDouble("rotation", 0.0).toFloat(),
                            isPinned = obj.optBoolean("isPinned", false),
                            isVoiceNote = obj.optBoolean("isVoiceNote", false),
                            voiceDurationSec = obj.optInt("voiceDurationSec", 0),
                            isLocked = obj.optBoolean("isLocked", false),
                            isAspectRatioLocked = obj.optBoolean("isAspectRatioLocked", false),
                            groupId = obj.optString("groupId", "").takeIf { it.isNotEmpty() },
                            parentId = obj.optString("parentId", "").takeIf { it.isNotEmpty() },
                            isHiddenByCollapse = obj.optBoolean("isHiddenByCollapse", false)
                        )
                    )
                }
            }

            val texts = mutableListOf<TextItem>()
            val textsArr = root.optJSONArray("textItems")
            if (textsArr != null) {
                for (i in 0 until textsArr.length()) {
                    val obj = textsArr.getJSONObject(i)
                    texts.add(
                        TextItem(
                            id = obj.getString("id"),
                            text = obj.optString("text", ""),
                            x = obj.getDouble("x").toFloat(),
                            y = obj.getDouble("y").toFloat(),
                            fontSize = obj.optDouble("fontSize", 24.0).toFloat(),
                            colorHex = obj.optString("colorHex", "#1E1E1E"),
                            isBold = obj.optBoolean("isBold", false),
                            isLocked = obj.optBoolean("isLocked", false),
                            isEditableCard = obj.optBoolean("isEditableCard", false),
                            backgroundColorHex = obj.optString("backgroundColorHex", "").takeIf { it.isNotEmpty() },
                            width = obj.optDouble("width", 190.0).toFloat(),
                            groupId = obj.optString("groupId", "").takeIf { it.isNotEmpty() },
                            parentId = obj.optString("parentId", "").takeIf { it.isNotEmpty() },
                            isHiddenByCollapse = obj.optBoolean("isHiddenByCollapse", false)
                        )
                    )
                }
            }

            val stickers = mutableListOf<StickerItem>()
            val stickersArr = root.optJSONArray("stickers")
            if (stickersArr != null) {
                for (i in 0 until stickersArr.length()) {
                    val obj = stickersArr.getJSONObject(i)
                    stickers.add(
                        StickerItem(
                            id = obj.getString("id"),
                            emoji = obj.optString("emoji", ""),
                            x = obj.getDouble("x").toFloat(),
                            y = obj.getDouble("y").toFloat(),
                            scale = obj.optDouble("scale", 1.3).toFloat()
                        )
                    )
                }
            }

            currentBoard.copy(
                updatedAt = System.currentTimeMillis(),
                gridType = gridType,
                colorTheme = colorTheme,
                strokes = strokes,
                shapes = shapes,
                stickyNotes = notes,
                textItems = texts,
                stickers = stickers
            )
        } catch (_: Exception) {
            null
        }
    }
}
