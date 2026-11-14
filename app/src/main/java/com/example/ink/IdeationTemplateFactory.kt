package com.example.ink

import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StickyNote
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.TemplateType
import com.example.model.TextItem
import com.example.model.WhiteboardBoard
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class GeneratedTemplateElements(
    val shapes: List<ShapeItem> = emptyList(),
    val stickyNotes: List<StickyNote> = emptyList(),
    val textItems: List<TextItem> = emptyList()
)

object IdeationTemplateFactory {

    private val branchColors = listOf(
        "#FFF275",
        "#86E3CE",
        "#A1F0A6",
        "#FF9AA2",
        "#D5AAFF",
        "#FFB347"
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
            TemplateType.RETROSPECTIVE -> buildThreeColumnBoard(
                cx = worldCenterX,
                cy = worldCenterY,
                titles = listOf("Qué funcionó", "Qué mejorar", "Próximos pasos"),
                columnStrokes = listOf("#16A34A", "#D97706", "#2563EB"),
                columnFills = listOf("#F0FDF4", "#FFFBEB", "#EFF6FF"),
                noteColors = listOf("#A1F0A6", "#FFF275", "#86E3CE"),
                starterTexts = listOf(
                    "Buena comunicación diaria",
                    "Reducir bloqueos en QA",
                    "Automatizar despliegues"
                )
            )
            TemplateType.KANBAN -> buildThreeColumnBoard(
                cx = worldCenterX,
                cy = worldCenterY,
                titles = listOf("Por hacer", "Haciendo", "Hecho"),
                columnStrokes = listOf("#64748B", "#2563EB", "#16A34A"),
                columnFills = listOf("#F8FAFC", "#EFF6FF", "#F0FDF4"),
                noteColors = listOf("#FFF275", "#86E3CE", "#A1F0A6"),
                starterTexts = listOf(
                    "Definir alcance inicial",
                    "Diseñar prototipo",
                    "Configurar proyecto"
                )
            )
            TemplateType.ISHIKAWA -> buildIshikawaDiagram(worldCenterX, worldCenterY)
            TemplateType.SWOT -> buildSwotMatrix(worldCenterX, worldCenterY)
        }
    }

    private fun buildBrainstormCluster(cx: Float, cy: Float): GeneratedTemplateElements {
        val groupId = "cluster_${UUID.randomUUID()}"
        val centerSize = 200f
        val centerShape = ShapeItem(
            shapeType = ShapeType.CIRCLE,
            x = cx - centerSize / 2f,
            y = cy - centerSize / 2f,
            width = centerSize,
            height = centerSize,
            strokeColorHex = "#2563EB",
            fillColorHex = "#DBEAFE",
            strokeWidth = 4f,
            isFilled = true,
            label = "Tema Central",
            templateRole = "CLUSTER_CENTER",
            groupId = groupId
        )

        val noteW = 186f
        val noteH = 140f
        val radius = 295f
        val shapes = mutableListOf(centerShape)
        val notes = mutableListOf<StickyNote>()

        val prompts = listOf(
            "Idea 1",
            "Idea 2",
            "Idea 3",
            "Idea 4",
            "Idea 5",
            "Idea 6"
        )

        for (i in 0 until 6) {
            val angle = (-PI / 2.0) + i * (2.0 * PI / 6.0)
            val nxCenter = cx + (radius * cos(angle)).toFloat()
            val nyCenter = cy + (radius * sin(angle)).toFloat()
            val note = StickyNote(
                text = prompts[i],
                x = nxCenter - noteW / 2f,
                y = nyCenter - noteH / 2f,
                width = noteW,
                height = noteH,
                colorHex = branchColors[i % branchColors.size],
                groupId = groupId,
                parentId = centerShape.id
            )
            notes.add(note)
            shapes.add(
                createSpokeLine(
                    cx = cx,
                    cy = cy,
                    centerRadius = centerSize / 2f + 4f,
                    targetX = nxCenter,
                    targetY = nyCenter,
                    targetBackoff = 82f,
                    colorHex = "#64748B",
                    groupId = groupId,
                    parentId = centerShape.id
                )
            )
        }

        // Put connector spokes behind center circle visually by ordering lines before center node
        val orderedShapes = shapes.drop(1) + centerShape
        return GeneratedTemplateElements(shapes = orderedShapes, stickyNotes = notes)
    }

    fun addClusterBranch(board: WhiteboardBoard, clusterShapeId: String): WhiteboardBoard {
        val centerShape = board.shapes.find { it.id == clusterShapeId } ?: return board
        val groupId = centerShape.groupId ?: clusterShapeId
        val cx = centerShape.x + centerShape.width / 2f
        val cy = centerShape.y + centerShape.height / 2f
        val centerR = max(centerShape.width, centerShape.height) / 2f

        val existingBranchCount = board.stickyNotes.count { it.groupId == groupId || it.parentId == centerShape.id }
        val ringIndex = if (existingBranchCount < 8) 0 else 1 + (existingBranchCount - 8) / 10
        val indexInRing = if (existingBranchCount < 8) existingBranchCount else (existingBranchCount - 8) % 10
        val slotsInRing = if (ringIndex == 0) 8 else 10
        val radius = if (ringIndex == 0) 305f else 305f + ringIndex * 185f
        val angle = (-PI / 2.0) + (indexInRing + ringIndex * 0.35) * (2.0 * PI / slotsInRing)

        val noteW = 186f
        val noteH = 140f
        val nxCenter = cx + (radius * cos(angle)).toFloat()
        val nyCenter = cy + (radius * sin(angle)).toFloat()

        val newNote = StickyNote(
            text = "Idea ${existingBranchCount + 1}",
            x = nxCenter - noteW / 2f,
            y = nyCenter - noteH / 2f,
            width = noteW,
            height = noteH,
            colorHex = branchColors[existingBranchCount % branchColors.size],
            groupId = groupId,
            parentId = centerShape.id
        )

        val spoke = createSpokeLine(
            cx = cx,
            cy = cy,
            centerRadius = centerR + 4f,
            targetX = nxCenter,
            targetY = nyCenter,
            targetBackoff = 82f,
            colorHex = "#64748B",
            groupId = groupId,
            parentId = centerShape.id
        )

        val centerIdx = board.shapes.indexOfFirst { it.id == clusterShapeId }
        val nextShapes = if (centerIdx >= 0) {
            board.shapes.toMutableList().apply { add(centerIdx, spoke) }
        } else {
            board.shapes + spoke
        }

        return board.copy(
            shapes = nextShapes,
            stickyNotes = board.stickyNotes + newNote
        )
    }

    private fun createSpokeLine(
        cx: Float,
        cy: Float,
        centerRadius: Float,
        targetX: Float,
        targetY: Float,
        targetBackoff: Float,
        colorHex: String,
        groupId: String,
        parentId: String
    ): ShapeItem {
        val dx = targetX - cx
        val dy = targetY - cy
        val dist = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val ux = dx / dist
        val uy = dy / dist
        val p0 = StrokePoint(cx + ux * centerRadius, cy + uy * centerRadius)
        val endDist = (dist - targetBackoff).coerceAtLeast(centerRadius + 24f)
        val p1 = StrokePoint(cx + ux * endDist, cy + uy * endDist)
        return ShapeItem(
            shapeType = ShapeType.LINE,
            x = kotlin.math.min(p0.x, p1.x),
            y = kotlin.math.min(p0.y, p1.y),
            width = kotlin.math.abs(p1.x - p0.x).coerceAtLeast(1f),
            height = kotlin.math.abs(p1.y - p0.y).coerceAtLeast(1f),
            strokeColorHex = colorHex,
            strokeWidth = 3f,
            capStyle = LineCapStyle.END_ARROW,
            vertices = listOf(p0, p1),
            templateRole = "CLUSTER_SPOKE",
            groupId = groupId,
            parentId = parentId
        )
    }

    private fun buildImpactEffortMatrix(cx: Float, cy: Float): GeneratedTemplateElements {
        val groupId = "matrix_${UUID.randomUUID()}"
        val qW = 420f
        val qH = 310f
        val left = cx - qW
        val top = cy - qH

        val quadrants = listOf(
            Triple("Hacer ya\n(Prioridad máxima)", left to top, "#DCFCE7" to "#16A34A"),
            Triple("Planificar\n(Proyectos clave)", cx to top, "#DBEAFE" to "#2563EB"),
            Triple("Delegar\n(Victorias rápidas)", left to cy, "#FEF3C7" to "#D97706"),
            Triple("Descartar\n(Bajo retorno)", cx to cy, "#FFE4E6" to "#E11D48")
        )

        val shapes = mutableListOf<ShapeItem>()
        val texts = mutableListOf<TextItem>()
        val notes = mutableListOf<StickyNote>()

        quadrants.forEachIndexed { idx, (title, pos, colors) ->
            val (qx, qy) = pos
            val (fillHex, strokeHex) = colors
            shapes.add(
                ShapeItem(
                    shapeType = ShapeType.RECTANGLE,
                    x = qx,
                    y = qy,
                    width = qW,
                    height = qH,
                    strokeColorHex = strokeHex,
                    fillColorHex = fillHex,
                    strokeWidth = 2f,
                    isFilled = true,
                    isLocked = true,
                    templateRole = "MATRIX_QUADRANT",
                    groupId = groupId
                )
            )
            texts.add(
                TextItem(
                    text = title,
                    x = qx + 18f,
                    y = qy + 14f,
                    fontSize = 18f,
                    colorHex = strokeHex,
                    isBold = true,
                    isLocked = true,
                    width = 250f,
                    groupId = groupId
                )
            )

            val noteColors = listOf("#A1F0A6", "#86E3CE", "#FFF275", "#FF9AA2")
            val noteLabels = listOf(
                "Hacer ya:\nImpacto alto, esfuerzo bajo",
                "Planificar:\nDefinir hitos y fechas",
                "Delegar:\nAsignar al equipo",
                "Descartar:\nPosponer o eliminar"
            )
            notes.add(
                StickyNote(
                    text = noteLabels[idx],
                    x = qx + 48f,
                    y = qy + 92f,
                    width = 210f,
                    height = 165f,
                    colorHex = noteColors[idx],
                    groupId = groupId
                )
            )
        }

        // Locked Cartesian axes with arrows
        val axisExtend = 38f
        shapes.add(
            ShapeItem(
                shapeType = ShapeType.LINE,
                x = cx,
                y = top - axisExtend,
                width = 1f,
                height = qH * 2f + axisExtend * 2f,
                strokeColorHex = "#1E293B",
                strokeWidth = 4f,
                capStyle = LineCapStyle.BOTH_ARROWS,
                vertices = listOf(
                    StrokePoint(cx, top - axisExtend),
                    StrokePoint(cx, top + qH * 2f + axisExtend)
                ),
                isLocked = true,
                templateRole = "MATRIX_AXIS",
                groupId = groupId
            )
        )
        shapes.add(
            ShapeItem(
                shapeType = ShapeType.LINE,
                x = left - axisExtend,
                y = cy,
                width = qW * 2f + axisExtend * 2f,
                height = 1f,
                strokeColorHex = "#1E293B",
                strokeWidth = 4f,
                capStyle = LineCapStyle.BOTH_ARROWS,
                vertices = listOf(
                    StrokePoint(left - axisExtend, cy),
                    StrokePoint(left + qW * 2f + axisExtend, cy)
                ),
                isLocked = true,
                templateRole = "MATRIX_AXIS",
                groupId = groupId
            )
        )

        // Locked axis labels
        texts.add(
            TextItem(
                text = "ALTO IMPACTO",
                x = cx - 86f,
                y = top - 78f,
                fontSize = 18f,
                colorHex = "#0F172A",
                isBold = true,
                isLocked = true,
                width = 180f,
                groupId = groupId
            )
        )
        texts.add(
            TextItem(
                text = "BAJO IMPACTO",
                x = cx - 86f,
                y = top + qH * 2f + 46f,
                fontSize = 18f,
                colorHex = "#0F172A",
                isBold = true,
                isLocked = true,
                width = 180f,
                groupId = groupId
            )
        )
        texts.add(
            TextItem(
                text = "BAJO ESFUERZO",
                x = left - 185f,
                y = cy - 18f,
                fontSize = 17f,
                colorHex = "#0F172A",
                isBold = true,
                isLocked = true,
                width = 170f,
                groupId = groupId
            )
        )
        texts.add(
            TextItem(
                text = "ALTO ESFUERZO",
                x = left + qW * 2f + 46f,
                y = cy - 18f,
                fontSize = 17f,
                colorHex = "#0F172A",
                isBold = true,
                isLocked = true,
                width = 170f,
                groupId = groupId
            )
        )

        return GeneratedTemplateElements(shapes = shapes, stickyNotes = notes, textItems = texts)
    }

    private fun buildConceptMap(cx: Float, cy: Float): GeneratedTemplateElements {
        val groupId = "concept_${UUID.randomUUID()}"
        val shapes = mutableListOf<ShapeItem>()
        val notes = mutableListOf<StickyNote>()

        val rootW = 240f
        val rootH = 78f
        val rootX = cx - rootW / 2f
        val rootY = cy - 245f
        val rootShape = ShapeItem(
            shapeType = ShapeType.ROUNDED_RECT,
            x = rootX,
            y = rootY,
            width = rootW,
            height = rootH,
            strokeColorHex = "#1D4ED8",
            fillColorHex = "#DBEAFE",
            strokeWidth = 3.5f,
            isFilled = true,
            label = "Tema Principal",
            templateRole = "CONCEPT_NODE",
            groupId = groupId
        )
        shapes.add(rootShape)

        val subOffsets = listOf(-330f, 0f, 330f)
        val subTitles = listOf("Subtema 1", "Subtema 2", "Subtema 3")
        val subW = 196f
        val subH = 66f
        val subY = cy - 60f

        val detailW = 148f
        val detailH = 112f
        val detailY = cy + 125f

        subOffsets.forEachIndexed { sIdx, dx ->
            val sx = cx + dx - subW / 2f
            val subShape = ShapeItem(
                shapeType = ShapeType.ROUNDED_RECT,
                x = sx,
                y = subY,
                width = subW,
                height = subH,
                strokeColorHex = "#6D28D9",
                fillColorHex = "#EDE9FE",
                strokeWidth = 3f,
                isFilled = true,
                label = subTitles[sIdx],
                templateRole = "CONCEPT_NODE",
                groupId = groupId,
                parentId = rootShape.id
            )
            shapes.add(subShape)
            shapes.add(
                createOrthogonalConnector(
                    startX = cx,
                    startY = rootY + rootH,
                    endX = sx + subW / 2f,
                    endY = subY,
                    colorHex = "#475569",
                    groupId = groupId,
                    parentId = rootShape.id
                )
            )

            // 2 Level-3 Detail cards per Subtema with 90-degree orthogonal connectors
            val detailOffsets = listOf(-82f, 82f)
            detailOffsets.forEachIndexed { dIdx, dOff ->
                val dCenterX = sx + subW / 2f + dOff
                val note = StickyNote(
                    text = "Detalle ${sIdx + 1}.${dIdx + 1}",
                    x = dCenterX - detailW / 2f,
                    y = detailY,
                    width = detailW,
                    height = detailH,
                    colorHex = branchColors[(sIdx * 2 + dIdx) % branchColors.size],
                    groupId = groupId,
                    parentId = subShape.id
                )
                notes.add(note)
                shapes.add(
                    createOrthogonalConnector(
                        startX = sx + subW / 2f,
                        startY = subY + subH,
                        endX = dCenterX,
                        endY = detailY,
                        colorHex = "#64748B",
                        groupId = groupId,
                        parentId = subShape.id
                    )
                )
            }
        }

        return GeneratedTemplateElements(shapes = shapes, stickyNotes = notes)
    }

    private fun createOrthogonalConnector(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        colorHex: String,
        groupId: String,
        parentId: String,
        style: StrokeStyle = StrokeStyle.SOLID
    ): ShapeItem {
        val midY = (startY + endY) / 2f
        val pts = listOf(
            StrokePoint(startX, startY),
            StrokePoint(startX, midY),
            StrokePoint(endX, midY),
            StrokePoint(endX, endY)
        )
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        return ShapeItem(
            shapeType = ShapeType.LINE,
            x = minX,
            y = minY,
            width = (maxX - minX).coerceAtLeast(1f),
            height = (maxY - minY).coerceAtLeast(1f),
            strokeColorHex = colorHex,
            strokeWidth = 3f,
            strokeStyle = style,
            capStyle = LineCapStyle.END_ARROW,
            vertices = pts,
            isLocked = true,
            templateRole = "CONCEPT_CONNECTOR",
            groupId = groupId,
            parentId = parentId
        )
    }

    fun toggleConceptBranchCollapse(board: WhiteboardBoard, nodeId: String): WhiteboardBoard {
        val target = board.shapes.find { it.id == nodeId } ?: return board
        val nextCollapsed = !target.isCollapsed

        // Build map of collapsed states after toggling target
        val collapsedNodeIds = board.shapes
            .filter { it.id == nodeId && nextCollapsed || it.id != nodeId && it.isCollapsed }
            .map { it.id }
            .toSet()

        val parentOfShape = board.shapes.associate { it.id to it.parentId }

        fun isHiddenByAnyAncestor(parentId: String?): Boolean {
            var cur = parentId
            var guard = 0
            while (cur != null && guard < 16) {
                if (cur in collapsedNodeIds) return true
                cur = parentOfShape[cur]
                guard++
            }
            return false
        }

        val updatedShapes = board.shapes.map { s ->
            val updatedCollapsed = if (s.id == nodeId) nextCollapsed else s.isCollapsed
            s.copy(
                isCollapsed = updatedCollapsed,
                isHiddenByCollapse = isHiddenByAnyAncestor(s.parentId)
            )
        }
        val updatedNotes = board.stickyNotes.map { n ->
            n.copy(isHiddenByCollapse = isHiddenByAnyAncestor(n.parentId))
        }
        val updatedTexts = board.textItems.map { t ->
            t.copy(isHiddenByCollapse = isHiddenByAnyAncestor(t.parentId))
        }

        return board.copy(
            shapes = updatedShapes,
            stickyNotes = updatedNotes,
            textItems = updatedTexts
        )
    }

    fun countHiddenDescendants(board: WhiteboardBoard, nodeId: String): Int {
        var count = 0
        val queue = ArrayDeque<String>()
        queue.add(nodeId)
        val visited = mutableSetOf(nodeId)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            board.shapes.filter { it.parentId == cur && it.templateRole != "CONCEPT_CONNECTOR" }.forEach { child ->
                if (visited.add(child.id)) {
                    count++
                    queue.add(child.id)
                }
            }
            count += board.stickyNotes.count { it.parentId == cur }
        }
        return count
    }

    private fun buildUserFlow(cx: Float, cy: Float): GeneratedTemplateElements {
        val groupId = "flow_${UUID.randomUUID()}"
        val laneW = 920f
        val laneH = 165f
        val left = cx - laneW / 2f
        val top = cy - (laneH * 3f) / 2f

        val laneNames = listOf("Usuario", "Interfaz / App", "Sistema / Lógica")
        val laneFills = listOf("#F8FAFC", "#F1F5F9", "#F8FAFC")

        val shapes = mutableListOf<ShapeItem>()
        val texts = mutableListOf<TextItem>()

        // 3 locked horizontal Swimlanes
        laneNames.forEachIndexed { idx, name ->
            val ly = top + idx * laneH
            shapes.add(
                ShapeItem(
                    shapeType = ShapeType.RECTANGLE,
                    x = left,
                    y = ly,
                    width = laneW,
                    height = laneH,
                    strokeColorHex = "#94A3B8",
                    fillColorHex = laneFills[idx],
                    strokeWidth = 2f,
                    isFilled = true,
                    isLocked = true,
                    templateRole = "SWIMLANE",
                    groupId = groupId
                )
            )
            texts.add(
                TextItem(
                    text = name,
                    x = left + 16f,
                    y = ly + 14f,
                    fontSize = 16f,
                    colorHex = "#334155",
                    isBold = true,
                    isLocked = true,
                    width = 150f,
                    groupId = groupId
                )
            )
        }

        // Flow geometric shapes placed cleanly in lanes
        val startNode = ShapeItem(
            shapeType = ShapeType.CIRCLE,
            x = left + 165f,
            y = top + 48f,
            width = 104f,
            height = 70f,
            strokeColorHex = "#16A34A",
            fillColorHex = "#DCFCE7",
            strokeWidth = 3f,
            isFilled = true,
            label = "Inicio",
            groupId = groupId
        )
        val step1Node = ShapeItem(
            shapeType = ShapeType.ROUNDED_RECT,
            x = left + 335f,
            y = top + 48f,
            width = 168f,
            height = 70f,
            strokeColorHex = "#2563EB",
            fillColorHex = "#DBEAFE",
            strokeWidth = 3f,
            isFilled = true,
            label = "Acción / Entrada",
            groupId = groupId
        )
        val decisionNode = ShapeItem(
            shapeType = ShapeType.DIAMOND,
            x = left + 349f,
            y = top + laneH + 34f,
            width = 140f,
            height = 96f,
            strokeColorHex = "#D97706",
            fillColorHex = "#FEF3C7",
            strokeWidth = 3f,
            isFilled = true,
            label = "¿Válido?",
            groupId = groupId
        )
        val retryNode = ShapeItem(
            shapeType = ShapeType.ROUNDED_RECT,
            x = left + 625f,
            y = top + 48f,
            width = 176f,
            height = 70f,
            strokeColorHex = "#E11D48",
            fillColorHex = "#FFE4E6",
            strokeWidth = 3f,
            isFilled = true,
            label = "Mostrar ajuste",
            groupId = groupId
        )
        val processNode = ShapeItem(
            shapeType = ShapeType.ROUNDED_RECT,
            x = left + 335f,
            y = top + laneH * 2f + 48f,
            width = 168f,
            height = 70f,
            strokeColorHex = "#7C3AED",
            fillColorHex = "#EDE9FE",
            strokeWidth = 3f,
            isFilled = true,
            label = "Procesar flujo",
            groupId = groupId
        )
        val endNode = ShapeItem(
            shapeType = ShapeType.CIRCLE,
            x = left + 655f,
            y = top + laneH * 2f + 48f,
            width = 112f,
            height = 70f,
            strokeColorHex = "#16A34A",
            fillColorHex = "#DCFCE7",
            strokeWidth = 3f,
            isFilled = true,
            label = "Fin",
            groupId = groupId
        )

        // Connectors linking Start -> Step1 -> Decision -> Process/Retry -> End
        val c1 = createFlowArrow(
            pts = listOf(StrokePoint(startNode.x + startNode.width, startNode.y + startNode.height / 2f), StrokePoint(step1Node.x, step1Node.y + step1Node.height / 2f)),
            colorHex = "#334155",
            groupId = groupId
        )
        val c2 = createFlowArrow(
            pts = listOf(StrokePoint(step1Node.x + step1Node.width / 2f, step1Node.y + step1Node.height), StrokePoint(decisionNode.x + decisionNode.width / 2f, decisionNode.y)),
            colorHex = "#334155",
            groupId = groupId
        )
        val cNo = createFlowArrow(
            pts = listOf(
                StrokePoint(decisionNode.x + decisionNode.width, decisionNode.y + decisionNode.height / 2f),
                StrokePoint(retryNode.x + retryNode.width / 2f, decisionNode.y + decisionNode.height / 2f),
                StrokePoint(retryNode.x + retryNode.width / 2f, retryNode.y + retryNode.height)
            ),
            colorHex = "#E11D48",
            style = StrokeStyle.DASHED,
            groupId = groupId
        )
        val cYes = createFlowArrow(
            pts = listOf(
                StrokePoint(decisionNode.x + decisionNode.width / 2f, decisionNode.y + decisionNode.height),
                StrokePoint(processNode.x + processNode.width / 2f, processNode.y)
            ),
            colorHex = "#16A34A",
            groupId = groupId
        )
        val cEnd = createFlowArrow(
            pts = listOf(
                StrokePoint(processNode.x + processNode.width, processNode.y + processNode.height / 2f),
                StrokePoint(endNode.x, endNode.y + endNode.height / 2f)
            ),
            colorHex = "#334155",
            groupId = groupId
        )

        texts.add(
            TextItem(
                text = "No",
                x = decisionNode.x + decisionNode.width + 18f,
                y = decisionNode.y + decisionNode.height / 2f - 28f,
                fontSize = 15f,
                colorHex = "#E11D48",
                isBold = true,
                isLocked = true,
                width = 60f,
                groupId = groupId
            )
        )
        texts.add(
            TextItem(
                text = "Sí",
                x = decisionNode.x + decisionNode.width / 2f + 12f,
                y = decisionNode.y + decisionNode.height + 6f,
                fontSize = 15f,
                colorHex = "#16A34A",
                isBold = true,
                isLocked = true,
                width = 60f,
                groupId = groupId
            )
        )

        shapes.addAll(listOf(startNode, step1Node, decisionNode, retryNode, processNode, endNode, c1, c2, cNo, cYes, cEnd))
        return GeneratedTemplateElements(shapes = shapes, textItems = texts)
    }

    private fun createFlowArrow(
        pts: List<StrokePoint>,
        colorHex: String,
        style: StrokeStyle = StrokeStyle.SOLID,
        groupId: String
    ): ShapeItem {
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        return ShapeItem(
            shapeType = ShapeType.LINE,
            x = minX,
            y = minY,
            width = (maxX - minX).coerceAtLeast(1f),
            height = (maxY - minY).coerceAtLeast(1f),
            strokeColorHex = colorHex,
            strokeWidth = 3f,
            strokeStyle = style,
            capStyle = LineCapStyle.END_ARROW,
            vertices = pts,
            groupId = groupId
        )
    }

    private fun buildThreeColumnBoard(
        cx: Float,
        cy: Float,
        titles: List<String>,
        columnStrokes: List<String>,
        columnFills: List<String>,
        noteColors: List<String>,
        starterTexts: List<String>
    ): GeneratedTemplateElements {
        val groupId = "cols_${UUID.randomUUID()}"
        val colW = 276f
        val colH = 440f
        val gap = 22f
        val totalW = colW * 3f + gap * 2f
        val startX = cx - totalW / 2f
        val topY = cy - colH / 2f

        val shapes = mutableListOf<ShapeItem>()
        val notes = mutableListOf<StickyNote>()
        val texts = mutableListOf<TextItem>()

        for (idx in 0 until 3) {
            val x = startX + idx * (colW + gap)
            val colId = "${groupId}_col_$idx"
            shapes.add(
                ShapeItem(
                    id = colId,
                    shapeType = ShapeType.ROUNDED_RECT,
                    x = x,
                    y = topY,
                    width = colW,
                    height = colH,
                    strokeColorHex = columnStrokes[idx],
                    fillColorHex = columnFills[idx],
                    strokeWidth = 2.5f,
                    isFilled = true,
                    isLocked = true,
                    templateRole = "COLUMN_CONTAINER",
                    groupId = groupId
                )
            )
            texts.add(
                TextItem(
                    text = titles[idx],
                    x = x + 16f,
                    y = topY + 14f,
                    fontSize = 18f,
                    colorHex = columnStrokes[idx],
                    isBold = true,
                    isLocked = true,
                    width = 200f,
                    groupId = groupId
                )
            )
            notes.add(
                StickyNote(
                    text = starterTexts[idx],
                    x = x + 20f,
                    y = topY + 62f,
                    width = colW - 40f,
                    height = 152f,
                    colorHex = noteColors[idx],
                    groupId = groupId
                )
            )
        }

        return GeneratedTemplateElements(shapes = shapes, stickyNotes = notes, textItems = texts)
    }

    fun recalculateAutoExpandingColumns(board: WhiteboardBoard): WhiteboardBoard {
        val minColHeight = 440f
        var changed = false
        val updatedShapes = board.shapes.map { col ->
            if (col.templateRole != "COLUMN_CONTAINER") {
                col
            } else {
                val notesInside = board.stickyNotes.filter { note ->
                    val noteCenterX = note.x + note.width / 2f
                    noteCenterX >= col.x &&
                        noteCenterX <= col.x + col.width &&
                        note.y >= col.y - 30f
                }
                val maxBottom = notesInside.maxOfOrNull { it.y + it.height + 28f } ?: (col.y + minColHeight)
                val requiredHeight = max(minColHeight, maxBottom - col.y)
                if (kotlin.math.abs(requiredHeight - col.height) > 0.5f) {
                    changed = true
                    col.copy(height = requiredHeight)
                } else {
                    col
                }
            }
        }
        return if (changed) board.copy(shapes = updatedShapes) else board
    }

    fun addNoteToColumn(board: WhiteboardBoard, columnShapeId: String): WhiteboardBoard {
        val col = board.shapes.find { it.id == columnShapeId } ?: return board
        val notesInside = board.stickyNotes.filter { note ->
            val noteCenterX = note.x + note.width / 2f
            noteCenterX >= col.x &&
                noteCenterX <= col.x + col.width &&
                note.y >= col.y - 30f
        }
        val nextY = if (notesInside.isEmpty()) {
            col.y + 62f
        } else {
            notesInside.maxOf { it.y + it.height } + 16f
        }
        val noteColor = notesInside.lastOrNull()?.colorHex ?: "#FFF275"
        val newNote = StickyNote(
            text = "Nueva tarjeta",
            x = col.x + 20f,
            y = nextY,
            width = (col.width - 40f).coerceAtLeast(160f),
            height = 152f,
            colorHex = noteColor,
            groupId = col.groupId
        )
        return recalculateAutoExpandingColumns(
            board.copy(stickyNotes = board.stickyNotes + newNote)
        )
    }

    private fun buildIshikawaDiagram(cx: Float, cy: Float): GeneratedTemplateElements {
        val groupId = "ishikawa_${UUID.randomUUID()}"
        val shapes = mutableListOf<ShapeItem>()
        val texts = mutableListOf<TextItem>()

        val spineStartX = cx - 440f
        val spineEndX = cx + 220f

        // Central locked horizontal spine pointing to the Problem head
        shapes.add(
            ShapeItem(
                shapeType = ShapeType.LINE,
                x = spineStartX,
                y = cy,
                width = spineEndX - spineStartX,
                height = 1f,
                strokeColorHex = "#1E293B",
                strokeWidth = 5f,
                capStyle = LineCapStyle.END_ARROW,
                vertices = listOf(StrokePoint(spineStartX, cy), StrokePoint(spineEndX, cy)),
                isLocked = true,
                templateRole = "ISHIKAWA_SPINE",
                groupId = groupId
            )
        )

        // Editable Problem box at head
        shapes.add(
            ShapeItem(
                shapeType = ShapeType.ROUNDED_RECT,
                x = spineEndX + 12f,
                y = cy - 58f,
                width = 210f,
                height = 116f,
                strokeColorHex = "#DC2626",
                fillColorHex = "#FFE4E6",
                strokeWidth = 3.5f,
                isFilled = true,
                label = "Problema",
                templateRole = "ISHIKAWA_PROBLEM",
                groupId = groupId
            )
        )

        // 6 Locked diagonal bones (Método, Herramientas, Personas top; Tiempo, Entorno, Medición bottom)
        val topCauses = listOf("Método", "Herramientas", "Personas")
        val bottomCauses = listOf("Tiempo", "Entorno", "Medición")
        val anchorXs = listOf(cx - 260f, cx - 50f, cx + 150f)

        topCauses.forEachIndexed { idx, label ->
            val joinX = anchorXs[idx]
            val outerX = joinX - 115f
            val outerY = cy - 220f
            shapes.add(
                ShapeItem(
                    shapeType = ShapeType.LINE,
                    x = outerX,
                    y = outerY,
                    width = joinX - outerX,
                    height = cy - outerY,
                    strokeColorHex = "#334155",
                    strokeWidth = 3.5f,
                    vertices = listOf(StrokePoint(outerX, outerY), StrokePoint(joinX, cy)),
                    isLocked = true,
                    templateRole = "ISHIKAWA_BONE",
                    groupId = groupId
                )
            )
            texts.add(
                TextItem(
                    text = label,
                    x = outerX - 78f,
                    y = outerY - 58f,
                    fontSize = 16f,
                    colorHex = "#0F172A",
                    isBold = true,
                    isLocked = false,
                    isEditableCard = true,
                    backgroundColorHex = "#DBEAFE",
                    width = 168f,
                    groupId = groupId
                )
            )
        }

        bottomCauses.forEachIndexed { idx, label ->
            val joinX = anchorXs[idx]
            val outerX = joinX - 115f
            val outerY = cy + 220f
            shapes.add(
                ShapeItem(
                    shapeType = ShapeType.LINE,
                    x = outerX,
                    y = cy,
                    width = joinX - outerX,
                    height = outerY - cy,
                    strokeColorHex = "#334155",
                    strokeWidth = 3.5f,
                    vertices = listOf(StrokePoint(outerX, outerY), StrokePoint(joinX, cy)),
                    isLocked = true,
                    templateRole = "ISHIKAWA_BONE",
                    groupId = groupId
                )
            )
            texts.add(
                TextItem(
                    text = label,
                    x = outerX - 78f,
                    y = outerY + 12f,
                    fontSize = 16f,
                    colorHex = "#0F172A",
                    isBold = true,
                    isLocked = false,
                    isEditableCard = true,
                    backgroundColorHex = "#FEF3C7",
                    width = 168f,
                    groupId = groupId
                )
            )
        }

        return GeneratedTemplateElements(shapes = shapes, textItems = texts)
    }

    private fun buildSwotMatrix(cx: Float, cy: Float): GeneratedTemplateElements {
        val groupId = "swot_${UUID.randomUUID()}"
        val qW = 390f
        val qH = 290f
        val left = cx - qW
        val top = cy - qH
        val items = listOf(
            Triple("Fortalezas", left to top, "#DCFCE7" to "#16A34A"),
            Triple("Oportunidades", cx to top, "#DBEAFE" to "#2563EB"),
            Triple("Debilidades", left to cy, "#FEF3C7" to "#D97706"),
            Triple("Amenazas", cx to cy, "#FFE4E6" to "#E11D48")
        )
        val noteColors = listOf("#A1F0A6", "#86E3CE", "#FFF275", "#FF9AA2")
        val shapes = mutableListOf<ShapeItem>()
        val notes = mutableListOf<StickyNote>()
        val texts = mutableListOf<TextItem>()
        items.forEachIndexed { idx, (title, pos, colors) ->
            shapes.add(
                ShapeItem(
                    shapeType = ShapeType.RECTANGLE,
                    x = pos.first,
                    y = pos.second,
                    width = qW,
                    height = qH,
                    strokeColorHex = colors.second,
                    fillColorHex = colors.first,
                    strokeWidth = 2.5f,
                    isFilled = true,
                    isLocked = true,
                    groupId = groupId
                )
            )
            texts.add(
                TextItem(
                    text = title,
                    x = pos.first + 16f,
                    y = pos.second + 14f,
                    fontSize = 18f,
                    colorHex = colors.second,
                    isBold = true,
                    isLocked = true,
                    width = 200f,
                    groupId = groupId
                )
            )
            notes.add(
                StickyNote(
                    text = "$title clave...",
                    x = pos.first + 34f,
                    y = pos.second + 68f,
                    width = 210f,
                    height = 160f,
                    colorHex = noteColors[idx],
                    groupId = groupId
                )
            )
        }
        return GeneratedTemplateElements(shapes = shapes, stickyNotes = notes, textItems = texts)
    }
}
