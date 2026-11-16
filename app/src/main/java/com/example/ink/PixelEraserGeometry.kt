package com.example.ink

import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.WhiteboardStroke
import java.util.UUID
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class ClippedFragment(
    val points: List<StrokePoint>,
    val keptOrigStart: Boolean,
    val keptOrigEnd: Boolean
)

data class ClippedPolylineResult(
    val fragments: List<ClippedFragment>,
    val wasModified: Boolean
)

data class ShapeVectorContour(
    val points: List<StrokePoint>,
    val isClosed: Boolean,
    val capStyle: LineCapStyle,
    val colorHex: String,
    val width: Float,
    val strokeStyle: StrokeStyle
)

object PixelEraserGeometry {

    fun clipContourOutsideCircle(
        points: List<StrokePoint>,
        isClosed: Boolean,
        cx: Float,
        cy: Float,
        radius: Float
    ): ClippedPolylineResult {
        if (points.size < 2) {
            return ClippedPolylineResult(emptyList(), wasModified = false)
        }

        val edgeCount = if (isClosed) points.size else points.lastIndex
        val fragments = mutableListOf<ClippedFragment>()
        var currentPts = mutableListOf<StrokePoint>()
        var currentKeptStart = false
        var wasModified = false
        val rSq = radius * radius

        fun appendPt(pt: StrokePoint) {
            val last = currentPts.lastOrNull()
            if (last == null || hypot(pt.x - last.x, pt.y - last.y) > 0.05f) {
                currentPts.add(pt)
            }
        }

        fun polylineLen(pts: List<StrokePoint>): Float {
            var total = 0f
            for (i in 0 until pts.lastIndex) {
                total += hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y)
            }
            return total
        }

        fun flushCurrent(keptEnd: Boolean) {
            if (currentPts.size >= 2 && polylineLen(currentPts) >= 0.5f) {
                fragments.add(
                    ClippedFragment(
                        points = currentPts.toList(),
                        keptOrigStart = currentKeptStart,
                        keptOrigEnd = keptEnd
                    )
                )
            }
            currentPts = mutableListOf()
            currentKeptStart = false
        }

        for (i in 0 until edgeCount) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val aa = dx * dx + dy * dy

            if (aa < 1e-6f) {
                val distSq = (a.x - cx) * (a.x - cx) + (a.y - cy) * (a.y - cy)
                if (distSq <= rSq) {
                    wasModified = true
                    flushCurrent(keptEnd = false)
                }
                continue
            }

            val fx = a.x - cx
            val fy = a.y - cy
            val bb = 2f * (fx * dx + fy * dy)
            val cc = fx * fx + fy * fy - rSq
            val disc = bb * bb - 4f * aa * cc

            if (disc <= 0f) {
                val aIn = (fx * fx + fy * fy) <= rSq
                val bx = b.x - cx
                val by = b.y - cy
                val bIn = (bx * bx + by * by) <= rSq
                if (aIn && bIn) {
                    wasModified = true
                    flushCurrent(keptEnd = false)
                } else {
                    if (currentPts.isEmpty()) {
                        currentPts.add(a)
                        currentKeptStart = (i == 0)
                    }
                    appendPt(b)
                }
            } else {
                val sqrtDisc = sqrt(disc)
                val t1 = (-bb - sqrtDisc) / (2f * aa)
                val t2 = (-bb + sqrtDisc) / (2f * aa)

                when {
                    t2 <= 0f || t1 >= 1f -> {
                        if (currentPts.isEmpty()) {
                            currentPts.add(a)
                            currentKeptStart = (i == 0)
                        }
                        appendPt(b)
                    }
                    t1 <= 0f && t2 >= 1f -> {
                        wasModified = true
                        flushCurrent(keptEnd = false)
                    }
                    t1 > 0f && t1 < 1f && t2 >= 1f -> {
                        wasModified = true
                        val entry = StrokePoint(a.x + dx * t1, a.y + dy * t1)
                        if (currentPts.isEmpty()) {
                            currentPts.add(a)
                            currentKeptStart = (i == 0)
                        }
                        appendPt(entry)
                        flushCurrent(keptEnd = false)
                    }
                    t1 <= 0f && t2 > 0f && t2 < 1f -> {
                        wasModified = true
                        flushCurrent(keptEnd = false)
                        val exit = StrokePoint(a.x + dx * t2, a.y + dy * t2)
                        currentPts.add(exit)
                        currentKeptStart = false
                        appendPt(b)
                    }
                    else -> {
                        // t1 > 0 && t2 < 1: eraser cuts through the middle of segment a -> b
                        wasModified = true
                        val entry = StrokePoint(a.x + dx * t1, a.y + dy * t1)
                        val exit = StrokePoint(a.x + dx * t2, a.y + dy * t2)
                        if (currentPts.isEmpty()) {
                            currentPts.add(a)
                            currentKeptStart = (i == 0)
                        }
                        appendPt(entry)
                        flushCurrent(keptEnd = false)
                        currentPts.add(exit)
                        currentKeptStart = false
                        appendPt(b)
                    }
                }
            }
        }

        if (currentPts.isNotEmpty()) {
            flushCurrent(keptEnd = true)
        }

        if (!wasModified) {
            return ClippedPolylineResult(
                fragments = listOf(
                    ClippedFragment(
                        points = points,
                        keptOrigStart = true,
                        keptOrigEnd = true
                    )
                ),
                wasModified = false
            )
        }

        // For closed contours, if vertex 0 survived outside the circle, merge tail + head
        // so a single cut opens a closed loop into 1 continuous contour instead of falsely splitting at vertex 0.
        if (isClosed && fragments.size >= 2 && fragments.first().keptOrigStart && fragments.last().keptOrigEnd) {
            val tail = fragments.removeAt(fragments.lastIndex)
            val head = fragments.removeAt(0)
            val mergedPts = ArrayList<StrokePoint>(tail.points.size + head.points.size)
            mergedPts.addAll(tail.points)
            val skipFirst = mergedPts.isNotEmpty() &&
                head.points.isNotEmpty() &&
                hypot(head.points.first().x - mergedPts.last().x, head.points.first().y - mergedPts.last().y) < 0.2f
            for (k in (if (skipFirst) 1 else 0) until head.points.size) {
                mergedPts.add(head.points[k])
            }
            fragments.add(
                0,
                ClippedFragment(
                    points = mergedPts,
                    keptOrigStart = false,
                    keptOrigEnd = false
                )
            )
        }

        return ClippedPolylineResult(fragments = fragments, wasModified = true)
    }

    fun resolveCapStyleForFragment(
        originalCapStyle: LineCapStyle,
        keptOrigStart: Boolean,
        keptOrigEnd: Boolean
    ): LineCapStyle {
        return when (originalCapStyle) {
            LineCapStyle.NONE -> LineCapStyle.NONE
            LineCapStyle.START_ARROW -> if (keptOrigStart) LineCapStyle.START_ARROW else LineCapStyle.NONE
            LineCapStyle.END_ARROW -> if (keptOrigEnd) LineCapStyle.END_ARROW else LineCapStyle.NONE
            LineCapStyle.BOTH_ARROWS -> when {
                keptOrigStart && keptOrigEnd -> LineCapStyle.BOTH_ARROWS
                keptOrigStart -> LineCapStyle.START_ARROW
                keptOrigEnd -> LineCapStyle.END_ARROW
                else -> LineCapStyle.NONE
            }
        }
    }

    fun eraseShapeAtCircle(
        shape: ShapeItem,
        cx: Float,
        cy: Float,
        radius: Float
    ): List<WhiteboardStroke>? {
        val effectiveRadius = radius + (shape.strokeWidth * 0.35f)
        val padding = effectiveRadius + shape.strokeWidth + 32f
        val maxDim = max(shape.width, shape.height)
        val rotMargin = if (shape.rotation != 0f) maxDim * 0.5f else 0f

        if (cx < shape.x - padding - rotMargin ||
            cx > shape.x + shape.width + padding + rotMargin ||
            cy < shape.y - padding - rotMargin ||
            cy > shape.y + shape.height + padding + rotMargin
        ) {
            return null
        }

        val contours = vectorizeShapeContours(shape)
        if (contours.isEmpty()) return null

        val clippedPairs = contours.map { contour ->
            val r = if (contour.width != shape.strokeWidth) radius + contour.width * 0.35f else effectiveRadius
            contour to clipContourOutsideCircle(
                points = contour.points,
                isClosed = contour.isClosed,
                cx = cx,
                cy = cy,
                radius = r
            )
        }

        val anyHit = clippedPairs.any { it.second.wasModified }
        if (!anyHit) return null

        val isHighlighter = shape.opacity < 0.41f
        val brushType = when {
            shape.opacity < 0.41f -> StrokeBrushType.HIGHLIGHTER
            shape.opacity < 0.65f -> StrokeBrushType.SOFT_BRUSH
            else -> StrokeBrushType.FOUNTAIN_PEN
        }

        val emitted = mutableListOf<WhiteboardStroke>()
        clippedPairs.forEach { (contour, clipped) ->
            if (!clipped.wasModified) {
                val pts = if (contour.isClosed) contour.points + contour.points.first() else contour.points
                if (pts.size >= 2) {
                    emitted.add(
                        WhiteboardStroke(
                            id = UUID.randomUUID().toString(),
                            points = pts,
                            colorHex = contour.colorHex,
                            width = contour.width,
                            isHighlighter = isHighlighter,
                            isRainbow = shape.isRainbow && contour.colorHex == shape.strokeColorHex,
                            strokeStyle = contour.strokeStyle,
                            capStyle = contour.capStyle,
                            brushType = brushType,
                            opacity = shape.opacity
                        )
                    )
                }
            } else {
                clipped.fragments.forEach { frag ->
                    if (frag.points.size >= 2) {
                        emitted.add(
                            WhiteboardStroke(
                                id = UUID.randomUUID().toString(),
                                points = frag.points,
                                colorHex = contour.colorHex,
                                width = contour.width,
                                isHighlighter = isHighlighter,
                                isRainbow = shape.isRainbow && contour.colorHex == shape.strokeColorHex,
                                strokeStyle = contour.strokeStyle,
                                capStyle = resolveCapStyleForFragment(
                                    contour.capStyle,
                                    frag.keptOrigStart,
                                    frag.keptOrigEnd
                                ),
                                brushType = brushType,
                                opacity = shape.opacity
                            )
                        )
                    }
                }
            }
        }
        return emitted
    }

    fun vectorizeShapeContours(shape: ShapeItem): List<ShapeVectorContour> {
        val x = shape.x
        val y = shape.y
        val w = shape.width.coerceAtLeast(1f)
        val h = shape.height.coerceAtLeast(1f)
        val cx = x + w / 2f
        val cy = y + h / 2f
        val rotRad = if (shape.rotation != 0f && shape.shapeType != ShapeType.LINE) {
            shape.rotation * (PI.toFloat() / 180f)
        } else {
            0f
        }
        val cosR = cos(rotRad)
        val sinR = sin(rotRad)

        fun rotatePt(pt: StrokePoint): StrokePoint {
            if (rotRad == 0f) return pt
            val dx = pt.x - cx
            val dy = pt.y - cy
            return StrokePoint(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
        }

        val outlineRaw = mutableListOf<Pair<List<StrokePoint>, Pair<Boolean, LineCapStyle>>>()

        when (shape.shapeType) {
            ShapeType.LINE -> {
                val p0 = shape.vertices.getOrNull(0) ?: StrokePoint(x, y)
                val p1 = shape.vertices.getOrNull(1) ?: StrokePoint(x + w, y + h)
                outlineRaw.add(subdivideOpen(listOf(p0, p1), maxStep = 6f) to (false to shape.capStyle))
            }
            ShapeType.ARROW -> {
                val head = min(22f, min(w * 0.35f, h * 0.45f))
                val midY = y + h / 2f
                val shaftEndX = x + w - head * 0.4f
                outlineRaw.add(
                    subdivideOpen(listOf(StrokePoint(x, midY), StrokePoint(shaftEndX, midY)), maxStep = 6f) to
                        (false to LineCapStyle.END_ARROW)
                )
            }
            ShapeType.DOUBLE_ARROW -> {
                val head = min(20f, min(w * 0.25f, h * 0.45f))
                val midY = y + h / 2f
                val shaftStartX = x + head * 0.4f
                val shaftEndX = x + w - head * 0.4f
                outlineRaw.add(
                    subdivideOpen(listOf(StrokePoint(shaftStartX, midY), StrokePoint(shaftEndX, midY)), maxStep = 6f) to
                        (false to LineCapStyle.BOTH_ARROWS)
                )
            }
            ShapeType.RECTANGLE -> {
                val corners = listOf(
                    StrokePoint(x, y),
                    StrokePoint(x + w, y),
                    StrokePoint(x + w, y + h),
                    StrokePoint(x, y + h)
                )
                outlineRaw.add(subdivideClosed(corners, maxStep = 4f) to (true to LineCapStyle.NONE))
            }
            ShapeType.ROUNDED_RECT -> {
                val cr = min(16f, min(w, h) / 2f)
                outlineRaw.add(sampleRoundedRect(x, y, w, h, cr) to (true to LineCapStyle.NONE))
            }
            ShapeType.CIRCLE -> {
                outlineRaw.add(sampleEllipse(cx, cy, w / 2f, h / 2f) to (true to LineCapStyle.NONE))
            }
            ShapeType.TRIANGLE -> {
                val corners = if (shape.vertices.size == 3) {
                    shape.vertices
                } else {
                    listOf(
                        StrokePoint(x + w / 2f, y),
                        StrokePoint(x + w, y + h),
                        StrokePoint(x, y + h)
                    )
                }
                outlineRaw.add(subdivideClosed(corners, maxStep = 4f) to (true to LineCapStyle.NONE))
            }
            ShapeType.DIAMOND -> {
                val corners = if (shape.vertices.size >= 4) {
                    shape.vertices
                } else {
                    listOf(
                        StrokePoint(x + w / 2f, y),
                        StrokePoint(x + w, y + h / 2f),
                        StrokePoint(x + w / 2f, y + h),
                        StrokePoint(x, y + h / 2f)
                    )
                }
                outlineRaw.add(subdivideClosed(corners, maxStep = 4f) to (true to LineCapStyle.NONE))
            }
            ShapeType.HEXAGON -> {
                val corners = listOf(
                    StrokePoint(x + w * 0.25f, y),
                    StrokePoint(x + w * 0.75f, y),
                    StrokePoint(x + w, y + h * 0.5f),
                    StrokePoint(x + w * 0.75f, y + h),
                    StrokePoint(x + w * 0.25f, y + h),
                    StrokePoint(x, y + h * 0.5f)
                )
                outlineRaw.add(subdivideClosed(corners, maxStep = 4f) to (true to LineCapStyle.NONE))
            }
            ShapeType.STAR -> {
                val outerRx = w / 2f
                val outerRy = h / 2f
                val innerRx = outerRx * 0.42f
                val innerRy = outerRy * 0.42f
                val corners = (0 until 10).map { i ->
                    val angle = -PI / 2.0 + i * (PI / 5.0)
                    val rx = if (i % 2 == 0) outerRx else innerRx
                    val ry = if (i % 2 == 0) outerRy else innerRy
                    StrokePoint(cx + (rx * cos(angle)).toFloat(), cy + (ry * sin(angle)).toFloat())
                }
                outlineRaw.add(subdivideClosed(corners, maxStep = 4f) to (true to LineCapStyle.NONE))
            }
            ShapeType.CLOUD_CALLOUT -> {
                val bubbleH = h * 0.78f
                val cr = min(bubbleH * 0.35f, min(w, bubbleH) / 2f)
                outlineRaw.add(sampleRoundedRect(x, y, w, bubbleH, cr) to (true to LineCapStyle.NONE))
                val tail = listOf(
                    StrokePoint(x + w * 0.26f, y + bubbleH - 1f),
                    StrokePoint(x + w * 0.16f, y + h),
                    StrokePoint(x + w * 0.42f, y + bubbleH - 1f)
                )
                outlineRaw.add(subdivideClosed(tail, maxStep = 4f) to (true to LineCapStyle.NONE))
            }
            ShapeType.CYLINDER -> {
                val capH = (h * 0.22f).coerceAtLeast(10f)
                val bodyPts = mutableListOf<StrokePoint>()
                bodyPts.addAll(
                    subdivideOpen(
                        listOf(StrokePoint(x, y + capH / 2f), StrokePoint(x, y + h - capH / 2f)),
                        maxStep = 4f
                    )
                )
                // Quadratic Bezier bottom arc
                val p0 = StrokePoint(x, y + h - capH / 2f)
                val pCtrl = StrokePoint(x + w / 2f, y + h + capH * 0.45f)
                val p2 = StrokePoint(x + w, y + h - capH / 2f)
                val qSteps = max(12, (w / 4f).toInt())
                for (s in 1..qSteps) {
                    val t = s / qSteps.toFloat()
                    val omt = 1f - t
                    val bx = omt * omt * p0.x + 2f * omt * t * pCtrl.x + t * t * p2.x
                    val by = omt * omt * p0.y + 2f * omt * t * pCtrl.y + t * t * p2.y
                    bodyPts.add(StrokePoint(bx, by))
                }
                val rightEdge = subdivideOpen(
                    listOf(StrokePoint(x + w, y + h - capH / 2f), StrokePoint(x + w, y + capH / 2f)),
                    maxStep = 4f
                )
                for (k in 1 until rightEdge.size) bodyPts.add(rightEdge[k])
                val topJoin = subdivideOpen(
                    listOf(StrokePoint(x + w, y + capH / 2f), StrokePoint(x, y + capH / 2f)),
                    maxStep = 4f
                )
                for (k in 1 until topJoin.lastIndex) bodyPts.add(topJoin[k])
                outlineRaw.add(bodyPts to (true to LineCapStyle.NONE))
                outlineRaw.add(sampleEllipse(cx, y + capH / 2f, w / 2f, capH / 2f) to (true to LineCapStyle.NONE))
            }
        }

        val result = mutableListOf<ShapeVectorContour>()

        // Emit optional fill scanlines beneath outline so filled preset shapes also pixel-erase cleanly
        if (shape.isFilled && shape.shapeType != ShapeType.LINE &&
            shape.shapeType != ShapeType.ARROW && shape.shapeType != ShapeType.DOUBLE_ARROW
        ) {
            val fillWidth = 6f
            val rowStep = 4.5f
            outlineRaw.forEach { (poly, meta) ->
                if (meta.first && poly.size >= 3) {
                    val rotatedPoly = poly.map(::rotatePt)
                    val scanlines = buildScanlineFill(rotatedPoly, rowStep)
                    scanlines.forEach { seg ->
                        result.add(
                            ShapeVectorContour(
                                points = seg,
                                isClosed = false,
                                capStyle = LineCapStyle.NONE,
                                colorHex = shape.fillColorHex,
                                width = fillWidth,
                                strokeStyle = StrokeStyle.SOLID
                            )
                        )
                    }
                }
            }
        }

        outlineRaw.forEach { (pts, meta) ->
            result.add(
                ShapeVectorContour(
                    points = pts.map(::rotatePt),
                    isClosed = meta.first,
                    capStyle = meta.second,
                    colorHex = shape.strokeColorHex,
                    width = shape.strokeWidth,
                    strokeStyle = shape.strokeStyle
                )
            )
        }

        return result
    }

    private fun subdivideOpen(pts: List<StrokePoint>, maxStep: Float): List<StrokePoint> {
        if (pts.size < 2) return pts
        val out = mutableListOf(pts.first())
        for (i in 0 until pts.lastIndex) {
            val a = pts[i]
            val b = pts[i + 1]
            val dist = hypot(b.x - a.x, b.y - a.y)
            val steps = ceil(dist / maxStep).toInt().coerceAtLeast(1)
            for (s in 1..steps) {
                val t = s / steps.toFloat()
                out.add(StrokePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        return out
    }

    private fun subdivideClosed(corners: List<StrokePoint>, maxStep: Float): List<StrokePoint> {
        if (corners.size < 3) return corners
        val out = mutableListOf<StrokePoint>()
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val dist = hypot(b.x - a.x, b.y - a.y)
            val steps = ceil(dist / maxStep).toInt().coerceAtLeast(1)
            for (s in 0 until steps) {
                val t = s / steps.toFloat()
                out.add(StrokePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        return out
    }

    private fun sampleEllipse(cx: Float, cy: Float, rx: Float, ry: Float): List<StrokePoint> {
        val approxPerim = (PI * (rx + ry)).toFloat()
        val count = (approxPerim / 3.5f).toInt().coerceIn(64, 180)
        return (0 until count).map { i ->
            val theta = (2.0 * PI * i) / count
            StrokePoint(cx + rx * cos(theta).toFloat(), cy + ry * sin(theta).toFloat())
        }
    }

    private fun sampleRoundedRect(x: Float, y: Float, w: Float, h: Float, cr: Float): List<StrokePoint> {
        if (cr <= 0.5f) {
            return subdivideClosed(
                listOf(StrokePoint(x, y), StrokePoint(x + w, y), StrokePoint(x + w, y + h), StrokePoint(x, y + h)),
                maxStep = 4f
            )
        }
        val pts = mutableListOf<StrokePoint>()
        val arcSteps = max(6, (cr * 0.6f).toInt())

        fun addArc(acx: Float, acy: Float, startDeg: Float, endDeg: Float) {
            for (i in 0..arcSteps) {
                val rad = (startDeg + (endDeg - startDeg) * (i / arcSteps.toFloat())) * (PI.toFloat() / 180f)
                val p = StrokePoint(acx + cr * cos(rad), acy + cr * sin(rad))
                if (pts.isEmpty() || hypot(p.x - pts.last().x, p.y - pts.last().y) > 0.25f) {
                    pts.add(p)
                }
            }
        }

        fun addLine(a: StrokePoint, b: StrokePoint) {
            val segs = subdivideOpen(listOf(a, b), maxStep = 4f)
            for (k in 1 until segs.lastIndex) {
                pts.add(segs[k])
            }
        }

        addArc(x + cr, y + cr, 180f, 270f)
        addLine(StrokePoint(x + cr, y), StrokePoint(x + w - cr, y))
        addArc(x + w - cr, y + cr, 270f, 360f)
        addLine(StrokePoint(x + w, y + cr), StrokePoint(x + w, y + h - cr))
        addArc(x + w - cr, y + h - cr, 0f, 90f)
        addLine(StrokePoint(x + w - cr, y + h), StrokePoint(x + cr, y + h))
        addArc(x + cr, y + h - cr, 90f, 180f)
        addLine(StrokePoint(x, y + h - cr), StrokePoint(x, y + cr))
        return pts
    }

    private fun buildScanlineFill(polygon: List<StrokePoint>, rowStep: Float): List<List<StrokePoint>> {
        if (polygon.size < 3) return emptyList()
        val minY = polygon.minOf { it.y }
        val maxY = polygon.maxOf { it.y }
        if (maxY - minY < 2f) return emptyList()

        val result = mutableListOf<List<StrokePoint>>()
        var scanY = minY + rowStep * 0.5f
        val xs = mutableListOf<Float>()
        while (scanY < maxY) {
            xs.clear()
            for (i in polygon.indices) {
                val p0 = polygon[i]
                val p1 = polygon[(i + 1) % polygon.size]
                if ((p0.y <= scanY && p1.y > scanY) || (p1.y <= scanY && p0.y > scanY)) {
                    val t = (scanY - p0.y) / (p1.y - p0.y)
                    xs.add(p0.x + t * (p1.x - p0.x))
                }
            }
            xs.sort()
            var k = 0
            while (k + 1 < xs.size) {
                val x0 = xs[k]
                val x1 = xs[k + 1]
                if (x1 - x0 >= 1.5f) {
                    result.add(listOf(StrokePoint(x0, scanY), StrokePoint(x1, scanY)))
                }
                k += 2
            }
            scanY += rowStep
        }
        return result
    }
}
