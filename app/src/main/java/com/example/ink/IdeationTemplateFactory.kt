package com.example.ink

import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StickyNote
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.TemplateType
import com.example.model.TextItem
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin

data class GeneratedTemplateElements(
    val shapes: List<ShapeItem> = emptyList(),
    val stickyNotes: List<StickyNote> = emptyList(),
    val textItems: List<TextItem> = emptyList()
)

/**
 * Pure Native Template Generator:
 * Every template spawns strictly 8..11 independent, unlocked native canvas objects
 * ([ShapeItem] vector shapes/connectors + [StickyNote] cards) at spatial coordinates.
 * Zero locked containers, zero special template modes, zero heavy overlay widgets.
 */
object IdeationTemplateFactory {

    private val notePalette = listOf(
        "#FFF275",
        "#86E3CE",
        "#A1F0A6",
        "#FF9AA2",
        "#D5AAFF"
    )

    fun createTemplate(
        type: TemplateType,
        worldCenterX: Float,
        worldCenterY: Float
    ): GeneratedTemplateElements {
        return when (type) {
            TemplateType.BRAINSTORM_CLUSTER -> buildBrainstormCluster(worldCenterX, worldCenterY)
            TemplateType.IMPACT_EFFORT -> buildImpactEffortMatrix(worldCenterX, worldCenterY)
            TemplateType.CONCEPT_MAP -> buildConceptMap(worldCenterX, worldCenterY)
            TemplateType.USER_FLOW -> buildUserFlow(worldCenterX, worldCenterY)
            TemplateType.RETROSPECTIVE -> buildColumnsBoard(
                cx = worldCenterX,
                cy = worldCenterY,
                titles = listOf("Qué funcionó", "Qué mejorar", "Próximos pasos"),
                headerStrokes = listOf("#16A34A", "#D97706", "#2563EB"),
                headerFills = listOf("#DCFCE7", "#FEF3C7", "#DBEAFE"),
                cards = listOf(
                    Triple(0, "Logro", "Buena comunicación diaria") to "#A1F0A6",
                    Triple(1, "Fricción", "Reducir bloqueos en QA") to "#FFF275",
                    Triple(2, "Acción", "Automatizar despliegues") to "#86E3CE"
                )
            )
            TemplateType.KANBAN -> buildColumnsBoard(
                cx = worldCenterX,
                cy = worldCenterY,
                titles = listOf("Por hacer", "Haciendo", "Hecho"),
                headerStrokes = listOf("#475569", "#2563EB", "#16A34A"),
                headerFills = listOf("#F1F5F9", "#DBEAFE", "#DCFCE7"),
                cards = listOf(
                    Triple(0, "Backlog", "Definir alcance inicial") to "#FFF275",
                    Triple(0, "Diseño", "Revisar flujo principal") to "#FF9AA2",
                    Triple(1, "En curso", "Prototipo interactivo") to "#86E3CE",
                    Triple(2, "Listo", "Configuración base") to "#A1F0A6"
                )
            )
            TemplateType.ISHIKAWA -> buildIshikawaDiagram(worldCenterX, worldCenterY)
            TemplateType.SWOT -> buildSwotMatrix(worldCenterX, worldCenterY)
        }
    }

    // 11 elements: 1 Circle + 5 Arrow connectors + 5 StickyNotes
    private fun buildBrainstormCluster(cx: Float, cy: Float): GeneratedTemplateElements {
        val centerSize = 176f
        val centerShape = nativeNode(
            shapeType = ShapeType.CIRCLE,
            x = cx - centerSize / 2f,
            y = cy - centerSize / 2f,
            width = centerSize,
            height = centerSize,
            strokeColor = "#2563EB",
            fillColor = "#DBEAFE",
            label = "Tema central"
        )

        val noteW = 186f
        val noteH = 148f
        val radius = 285f
        val connectors = mutableListOf<ShapeItem>()
        val notes = mutableListOf<StickyNote>()

        for (i in 0 until 5) {
            val angle = (-PI / 2.0) + i * (2.0 * PI / 5.0)
            val nxCenter = cx + (radius * cos(angle)).toFloat()
            val nyCenter = cy + (radius * sin(angle)).toFloat()
            notes.add(
                StickyNote(
                    title = "Idea ${i + 1}",
                    text = "Toca para editar...",
                    x = nxCenter - noteW / 2f,
                    y = nyCenter - noteH / 2f,
                    width = noteW,
                    height = noteH,
                    colorHex = notePalette[i % notePalette.size]
                )
            )
            connectors.add(
                spokeArrow(
                    cx = cx,
                    cy = cy,
                    startOffset = centerSize / 2f + 6f,
                    targetX = nxCenter,
                    targetY = nyCenter,
                    endBackoff = 96f,
                    colorHex = "#64748B"
                )
            )
        }

        return GeneratedTemplateElements(
            shapes = connectors + centerShape,
            stickyNotes = notes
        )
    }

    // 10 elements: 2 Axes (Line) + 4 Quadrant Header Pills + 4 StickyNotes
    private fun buildImpactEffortMatrix(cx: Float, cy: Float): GeneratedTemplateElements {
        val halfW = 360f
        val halfH = 260f
        val vAxis = nativeConnector(
            points = listOf(StrokePoint(cx, cy - halfH), StrokePoint(cx, cy + halfH)),
            colorHex = "#334155",
            strokeWidth = 3.5f,
            capStyle = LineCapStyle.BOTH_ARROWS
        )
        val hAxis = nativeConnector(
            points = listOf(StrokePoint(cx - halfW, cy), StrokePoint(cx + halfW, cy)),
            colorHex = "#334155",
            strokeWidth = 3.5f,
            capStyle = LineCapStyle.BOTH_ARROWS
        )

        val headers = listOf(
            Triple("Hacer ya", (cx - 280f) to (cy - 235f), "#16A34A" to "#DCFCE7"),
            Triple("Planificar", (cx + 70f) to (cy - 235f), "#2563EB" to "#DBEAFE"),
            Triple("Delegar", (cx - 280f) to (cy + 24f), "#D97706" to "#FEF3C7"),
            Triple("Descartar", (cx + 70f) to (cy + 24f), "#E11D48" to "#FFE4E6")
        ).map { (label, pos, colors) ->
            nativeNode(
                shapeType = ShapeType.ROUNDED_RECT,
                x = pos.first,
                y = pos.second,
                width = 210f,
                height = 46f,
                strokeColor = colors.first,
                fillColor = colors.second,
                label = label
            )
        }

        val notes = listOf(
            StickyNote(
                title = "Alto impacto",
                text = "Bajo esfuerzo · Prioridad",
                x = cx - 280f,
                y = cy - 172f,
                width = 210f,
                height = 148f,
                colorHex = "#A1F0A6"
            ),
            StickyNote(
                title = "Proyecto clave",
                text = "Alto impacto · Planificar",
                x = cx + 70f,
                y = cy - 172f,
                width = 210f,
                height = 148f,
                colorHex = "#86E3CE"
            ),
            StickyNote(
                title = "Victorias rápidas",
                text = "Bajo esfuerzo · Delegar",
                x = cx - 280f,
                y = cy + 86f,
                width = 210f,
                height = 148f,
                colorHex = "#FFF275"
            ),
            StickyNote(
                title = "Bajo retorno",
                text = "Posponer o descartar",
                x = cx + 70f,
                y = cy + 86f,
                width = 210f,
                height = 148f,
                colorHex = "#FF9AA2"
            )
        )

        return GeneratedTemplateElements(
            shapes = listOf(vAxis, hAxis) + headers,
            stickyNotes = notes
        )
    }

    // 10 elements: 4 Node Shapes + 3 Arrow Connectors + 3 StickyNotes
    private fun buildConceptMap(cx: Float, cy: Float): GeneratedTemplateElements {
        val rootW = 220f
        val rootH = 68f
        val rootX = cx - rootW / 2f
        val rootY = cy - 230f

        val rootShape = nativeNode(
            shapeType = ShapeType.ROUNDED_RECT,
            x = rootX,
            y = rootY,
            width = rootW,
            height = rootH,
            strokeColor = "#1D4ED8",
            fillColor = "#DBEAFE",
            label = "Tema principal"
        )

        val subOffsets = listOf(-270f, 0f, 270f)
        val subW = 190f
        val subH = 58f
        val subY = cy - 55f
        val noteW = 190f
        val noteH = 145f
        val noteY = cy + 68f

        val shapes = mutableListOf(rootShape)
        val notes = mutableListOf<StickyNote>()

        subOffsets.forEachIndexed { idx, dx ->
            val sx = cx + dx - subW / 2f
            val subNode = nativeNode(
                shapeType = ShapeType.ROUNDED_RECT,
                x = sx,
                y = subY,
                width = subW,
                height = subH,
                strokeColor = "#6D28D9",
                fillColor = "#EDE9FE",
                label = "Subtema ${idx + 1}"
            )
            val connector = nativeConnector(
                points = listOf(
                    StrokePoint(cx, rootY + rootH),
                    StrokePoint(sx + subW / 2f, subY)
                ),
                colorHex = "#475569",
                strokeWidth = 3f,
                shapeType = ShapeType.ARROW,
                capStyle = LineCapStyle.END_ARROW
            )
            shapes.add(connector)
            shapes.add(subNode)

            notes.add(
                StickyNote(
                    title = "Detalle ${idx + 1}",
                    text = "Idea clave...",
                    x = sx,
                    y = noteY,
                    width = noteW,
                    height = noteH,
                    colorHex = notePalette[idx % notePalette.size]
                )
            )
        }

        return GeneratedTemplateElements(shapes = shapes, stickyNotes = notes)
    }

    // 9 elements: 4 Flow Nodes + 3 Arrow Connectors + 2 StickyNotes
    private fun buildUserFlow(cx: Float, cy: Float): GeneratedTemplateElements {
        val topY = cy - 165f
        val startNode = nativeNode(
            shapeType = ShapeType.CIRCLE,
            x = cx - 320f,
            y = topY,
            width = 112f,
            height = 68f,
            strokeColor = "#16A34A",
            fillColor = "#DCFCE7",
            label = "Inicio"
        )
        val actionNode = nativeNode(
            shapeType = ShapeType.ROUNDED_RECT,
            x = cx - 104f,
            y = topY,
            width = 176f,
            height = 68f,
            strokeColor = "#2563EB",
            fillColor = "#DBEAFE",
            label = "Acción"
        )
        val decisionNode = nativeNode(
            shapeType = ShapeType.DIAMOND,
            x = cx - 86f,
            y = topY + 135f,
            width = 140f,
            height = 96f,
            strokeColor = "#D97706",
            fillColor = "#FEF3C7",
            label = "¿Válido?"
        )
        val endNode = nativeNode(
            shapeType = ShapeType.CIRCLE,
            x = cx + 170f,
            y = topY + 149f,
            width = 120f,
            height = 68f,
            strokeColor = "#16A34A",
            fillColor = "#DCFCE7",
            label = "Fin"
        )

        val a1 = nativeConnector(
            points = listOf(
                StrokePoint(startNode.x + startNode.width, startNode.y + startNode.height / 2f),
                StrokePoint(actionNode.x, actionNode.y + actionNode.height / 2f)
            ),
            colorHex = "#334155",
            strokeWidth = 3f,
            shapeType = ShapeType.ARROW,
            capStyle = LineCapStyle.END_ARROW
        )
        val a2 = nativeConnector(
            points = listOf(
                StrokePoint(actionNode.x + actionNode.width / 2f, actionNode.y + actionNode.height),
                StrokePoint(decisionNode.x + decisionNode.width / 2f, decisionNode.y)
            ),
            colorHex = "#334155",
            strokeWidth = 3f,
            shapeType = ShapeType.ARROW,
            capStyle = LineCapStyle.END_ARROW
        )
        val a3 = nativeConnector(
            points = listOf(
                StrokePoint(decisionNode.x + decisionNode.width, decisionNode.y + decisionNode.height / 2f),
                StrokePoint(endNode.x, endNode.y + endNode.height / 2f)
            ),
            colorHex = "#16A34A",
            strokeWidth = 3f,
            shapeType = ShapeType.ARROW,
            capStyle = LineCapStyle.END_ARROW
        )

        val notes = listOf(
            StickyNote(
                title = "Si falla",
                text = "Mostrar mensaje de ajuste",
                x = cx - 320f,
                y = topY + 128f,
                width = 185f,
                height = 136f,
                colorHex = "#FF9AA2"
            ),
            StickyNote(
                title = "Resultado",
                text = "Confirmar acción al usuario",
                x = cx + 140f,
                y = topY - 12f,
                width = 185f,
                height = 136f,
                colorHex = "#A1F0A6"
            )
        )

        return GeneratedTemplateElements(
            shapes = listOf(startNode, actionNode, decisionNode, endNode, a1, a2, a3),
            stickyNotes = notes
        )
    }

    // 8-9 elements: 3 Header Pills + 2 Vertical Divider Lines + 3..4 StickyNotes
    private fun buildColumnsBoard(
        cx: Float,
        cy: Float,
        titles: List<String>,
        headerStrokes: List<String>,
        headerFills: List<String>,
        cards: List<Pair<Triple<Int, String, String>, String>>
    ): GeneratedTemplateElements {
        val colW = 240f
        val gap = 36f
        val totalW = colW * 3f + gap * 2f
        val startX = cx - totalW / 2f
        val headerY = cy - 210f
        val headerH = 54f

        val shapes = mutableListOf<ShapeItem>()
        for (idx in 0 until 3) {
            val x = startX + idx * (colW + gap)
            shapes.add(
                nativeNode(
                    shapeType = ShapeType.ROUNDED_RECT,
                    x = x,
                    y = headerY,
                    width = colW,
                    height = headerH,
                    strokeColor = headerStrokes[idx],
                    fillColor = headerFills[idx],
                    label = titles[idx]
                )
            )
            if (idx < 2) {
                val divX = x + colW + gap / 2f
                shapes.add(
                    nativeConnector(
                        points = listOf(
                            StrokePoint(divX, headerY),
                            StrokePoint(divX, headerY + 430f)
                        ),
                        colorHex = "#CBD5E1",
                        strokeWidth = 2.5f,
                        style = StrokeStyle.DASHED
                    )
                )
            }
        }

        val colOffsets = IntArray(3)
        val notes = cards.map { (info, colorHex) ->
            val (colIdx, title, body) = info
            val cardIdx = colOffsets[colIdx]++
            val nx = startX + colIdx * (colW + gap) + 12f
            val ny = headerY + headerH + 22f + cardIdx * 170f
            StickyNote(
                title = title,
                text = body,
                x = nx,
                y = ny,
                width = colW - 24f,
                height = 150f,
                colorHex = colorHex
            )
        }

        return GeneratedTemplateElements(shapes = shapes, stickyNotes = notes)
    }

    // 10 elements: 1 Spine Arrow + 1 Problem Box + 4 Diagonal Bones + 4 StickyNotes
    private fun buildIshikawaDiagram(cx: Float, cy: Float): GeneratedTemplateElements {
        val spineStartX = cx - 360f
        val spineEndX = cx + 160f

        val spine = nativeConnector(
            points = listOf(StrokePoint(spineStartX, cy), StrokePoint(spineEndX, cy)),
            colorHex = "#1E293B",
            strokeWidth = 4f,
            shapeType = ShapeType.ARROW,
            capStyle = LineCapStyle.END_ARROW
        )

        val problemBox = nativeNode(
            shapeType = ShapeType.ROUNDED_RECT,
            x = spineEndX + 12f,
            y = cy - 54f,
            width = 190f,
            height = 108f,
            strokeColor = "#DC2626",
            fillColor = "#FFE4E6",
            label = "Problema"
        )

        val shapes = mutableListOf(spine, problemBox)
        val notes = mutableListOf<StickyNote>()

        val anchors = listOf(cx - 210f, cx + 20f)
        val topCategories = listOf("Método", "Personas")
        val bottomCategories = listOf("Herramientas", "Entorno")

        anchors.forEachIndexed { idx, joinX ->
            val outerX = joinX - 95f
            val topY = cy - 125f
            val botY = cy + 125f

            shapes.add(
                nativeConnector(
                    points = listOf(StrokePoint(outerX, topY), StrokePoint(joinX, cy)),
                    colorHex = "#475569",
                    strokeWidth = 3f
                )
            )
            notes.add(
                StickyNote(
                    title = topCategories[idx],
                    text = "Causa raíz...",
                    x = outerX - 90f,
                    y = topY - 146f,
                    width = 180f,
                    height = 136f,
                    colorHex = "#86E3CE"
                )
            )

            shapes.add(
                nativeConnector(
                    points = listOf(StrokePoint(outerX, botY), StrokePoint(joinX, cy)),
                    colorHex = "#475569",
                    strokeWidth = 3f
                )
            )
            notes.add(
                StickyNote(
                    title = bottomCategories[idx],
                    text = "Causa raíz...",
                    x = outerX - 90f,
                    y = botY + 10f,
                    width = 180f,
                    height = 136f,
                    colorHex = "#FFF275"
                )
            )
        }

        return GeneratedTemplateElements(shapes = shapes, stickyNotes = notes)
    }

    // 10 elements: 2 Divider Lines + 4 Header Pills + 4 StickyNotes
    private fun buildSwotMatrix(cx: Float, cy: Float): GeneratedTemplateElements {
        val halfW = 340f
        val halfH = 250f

        val vLine = nativeConnector(
            points = listOf(StrokePoint(cx, cy - halfH), StrokePoint(cx, cy + halfH)),
            colorHex = "#94A3B8",
            strokeWidth = 2.5f
        )
        val hLine = nativeConnector(
            points = listOf(StrokePoint(cx - halfW, cy), StrokePoint(cx + halfW, cy)),
            colorHex = "#94A3B8",
            strokeWidth = 2.5f
        )

        val quads = listOf(
            Triple("Fortalezas", (cx - 265f) to (cy - 225f), "#16A34A" to "#DCFCE7") to "#A1F0A6",
            Triple("Oportunidades", (cx + 55f) to (cy - 225f), "#2563EB" to "#DBEAFE") to "#86E3CE",
            Triple("Debilidades", (cx - 265f) to (cy + 24f), "#D97706" to "#FEF3C7") to "#FFF275",
            Triple("Amenazas", (cx + 55f) to (cy + 24f), "#E11D48" to "#FFE4E6") to "#FF9AA2"
        )

        val headers = mutableListOf<ShapeItem>()
        val notes = mutableListOf<StickyNote>()

        quads.forEach { (quad, noteColor) ->
            val (title, pos, colors) = quad
            headers.add(
                nativeNode(
                    shapeType = ShapeType.ROUNDED_RECT,
                    x = pos.first,
                    y = pos.second,
                    width = 210f,
                    height = 46f,
                    strokeColor = colors.first,
                    fillColor = colors.second,
                    label = title
                )
            )
            notes.add(
                StickyNote(
                    title = title,
                    text = "Puntos clave...",
                    x = pos.first,
                    y = pos.second + 62f,
                    width = 210f,
                    height = 148f,
                    colorHex = noteColor
                )
            )
        }

        return GeneratedTemplateElements(
            shapes = listOf(vLine, hLine) + headers,
            stickyNotes = notes
        )
    }

    private fun nativeNode(
        shapeType: ShapeType,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        strokeColor: String,
        fillColor: String,
        label: String
    ): ShapeItem {
        return ShapeItem(
            shapeType = shapeType,
            x = x,
            y = y,
            width = width,
            height = height,
            strokeColorHex = strokeColor,
            fillColorHex = fillColor,
            strokeWidth = 3f,
            isFilled = true,
            brushType = StrokeBrushType.FINE_MARKER,
            label = label,
            isLocked = false
        )
    }

    private fun nativeConnector(
        points: List<StrokePoint>,
        colorHex: String,
        strokeWidth: Float = 3f,
        shapeType: ShapeType = ShapeType.LINE,
        capStyle: LineCapStyle = LineCapStyle.NONE,
        style: StrokeStyle = StrokeStyle.SOLID
    ): ShapeItem {
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        return ShapeItem(
            shapeType = shapeType,
            x = minX,
            y = minY,
            width = (maxX - minX).coerceAtLeast(1f),
            height = (maxY - minY).coerceAtLeast(1f),
            strokeColorHex = colorHex,
            strokeWidth = strokeWidth,
            strokeStyle = style,
            capStyle = capStyle,
            vertices = points,
            brushType = StrokeBrushType.FINE_MARKER,
            isLocked = false
        )
    }

    private fun spokeArrow(
        cx: Float,
        cy: Float,
        startOffset: Float,
        targetX: Float,
        targetY: Float,
        endBackoff: Float,
        colorHex: String
    ): ShapeItem {
        val dx = targetX - cx
        val dy = targetY - cy
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        val ux = dx / dist
        val uy = dy / dist
        val p0 = StrokePoint(cx + ux * startOffset, cy + uy * startOffset)
        val endDist = max(startOffset + 20f, min(dist - endBackoff, dist - 20f))
        val p1 = StrokePoint(cx + ux * endDist, cy + uy * endDist)
        return nativeConnector(
            points = listOf(p0, p1),
            colorHex = colorHex,
            strokeWidth = 2.8f,
            shapeType = ShapeType.ARROW,
            capStyle = LineCapStyle.END_ARROW
        )
    }
}
