package com.example.ink

import com.example.model.ShapeItem
import com.example.model.ShapeType
import com.example.model.StrokePoint
import com.example.model.WhiteboardStroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class RecognizedShapeResult(
    val shapeType: ShapeType,
    val confidence: Float,
    val shapeItem: ShapeItem,
    val description: String
)

object ShapeRecognizer {

    fun recognizeStroke(stroke: WhiteboardStroke): RecognizedShapeResult? {
        val rawPoints = deduplicateNearby(stroke.points, minStep = 2.0f)
        if (rawPoints.size < 3) return null

        val rawLength = computePathLength(rawPoints)
        if (rawLength < 32f) return null

        // Uniform arc-length resampling so finger speed doesn't bias geometry analysis
        val sampleCount = (rawLength / 4f).toInt().coerceIn(28, 128)
        val pts = resampleByArcLength(rawPoints, sampleCount)

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in pts) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }

        val width = (maxX - minX)
        val height = (maxY - minY)
        val diag = hypot(width, height).coerceAtLeast(1f)

        val start = pts.first()
        val end = pts.last()
        val directDist = hypot(end.x - start.x, end.y - start.y)

        // ------------------------------------------------------------------
        // 1. STRAIGHT LINE RECOGNITION (per-point perpendicular deviation)
        //    Preserves exact start -> end direction and rejects crescents/arcs.
        // ------------------------------------------------------------------
        if (directDist >= 28f) {
            val straightness = directDist / rawLength.coerceAtLeast(1f)
            var maxPerp = 0f
            var sumPerp = 0f
            var signedSum = 0f

            for (p in pts) {
                val (perp, signed) = perpendicularDistanceToSegment(p, start, end)
                if (perp > maxPerp) maxPerp = perp
                sumPerp += perp
                signedSum += signed
            }

            val meanPerp = sumPerp / pts.size
            val maxPerpRatio = maxPerp / directDist
            val meanPerpRatio = meanPerp / directDist
            val meanSignedRatio = abs(signedSum / pts.size) / directDist

            // Strict threshold: gentle crescent/arc ("medialuna") has maxPerpRatio >= 0.06..0.25 and is rejected
            if (straightness >= 0.955f && maxPerpRatio <= 0.055f && meanPerpRatio <= 0.025f && meanSignedRatio <= 0.030f) {
                var finalStart = start
                var finalEnd = end

                // Gentle axis alignment only if within 5 degrees of horizontal or vertical, preserving direction
                val dx = end.x - start.x
                val dy = end.y - start.y
                val absDx = abs(dx)
                val absDy = abs(dy)
                if (absDy < absDx * 0.0875f) {
                    val midY = (start.y + end.y) / 2f
                    finalStart = StrokePoint(start.x, midY)
                    finalEnd = StrokePoint(end.x, midY)
                } else if (absDx < absDy * 0.0875f) {
                    val midX = (start.x + end.x) / 2f
                    finalStart = StrokePoint(midX, start.y)
                    finalEnd = StrokePoint(midX, end.y)
                }

                val lMinX = min(finalStart.x, finalEnd.x)
                val lMaxX = max(finalStart.x, finalEnd.x)
                val lMinY = min(finalStart.y, finalEnd.y)
                val lMaxY = max(finalStart.y, finalEnd.y)

                val confidence = (1f - maxPerpRatio * 4f).coerceIn(0.82f, 0.99f)
                val lineShape = ShapeItem(
                    shapeType = ShapeType.LINE,
                    x = lMinX,
                    y = lMinY,
                    width = (lMaxX - lMinX).coerceAtLeast(1f),
                    height = (lMaxY - lMinY).coerceAtLeast(1f),
                    strokeColorHex = stroke.colorHex,
                    strokeWidth = stroke.width,
                    strokeStyle = stroke.strokeStyle,
                    capStyle = stroke.capStyle,
                    vertices = listOf(finalStart, finalEnd),
                    opacity = stroke.opacity,
                    isRainbow = stroke.isRainbow,
                    brushType = stroke.brushType
                )
                return RecognizedShapeResult(
                    shapeType = ShapeType.LINE,
                    confidence = confidence,
                    shapeItem = lineShape,
                    description = "Línea recta detectada (${(confidence * 100).toInt()}% precisión)"
                )
            }
        }

        // ------------------------------------------------------------------
        // 2. CLOSED SHAPE CHECK (open curves / crescents stop right here)
        // ------------------------------------------------------------------
        val closeLimit = max(max(36f, stroke.width * 2.2f), min(diag * 0.28f, rawLength * 0.22f))
        if (directDist > closeLimit || min(width, height) < 20f || diag < 36f) {
            return null
        }

        // Form a clean closed polygon for area, perimeter, and corner analysis
        val closedPts = if (directDist > 2f) pts + start else pts
        val closedPerimeter = computePathLength(closedPts).coerceAtLeast(1f)
        val area = computeShoelaceArea(closedPts)
        val bboxArea = (width * height).coerceAtLeast(1f)
        val fillRatio = area / bboxArea
        val circularity = (4f * PI.toFloat() * area) / (closedPerimeter * closedPerimeter)

        if (fillRatio < 0.20f) return null

        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val rx = (width / 2f).coerceAtLeast(1f)
        val ry = (height / 2f).coerceAtLeast(1f)

        // Compute normalized radial fit & per-point distance to best-fit ellipse/circle
        var sumNormR = 0f
        val normRadii = FloatArray(pts.size)
        var sumEllipseErr = 0f
        for (i in pts.indices) {
            val nx = (pts[i].x - cx) / rx
            val ny = (pts[i].y - cy) / ry
            val nr = hypot(nx, ny)
            normRadii[i] = nr
            sumNormR += nr
            sumEllipseErr += distanceToEllipseApprox(pts[i], cx, cy, rx, ry)
        }
        val meanNormR = sumNormR / pts.size
        var radialVar = 0f
        for (nr in normRadii) {
            val d = nr - meanNormR
            radialVar += d * d
        }
        val radialCV = sqrt(radialVar / pts.size) / meanNormR.coerceAtLeast(0.01f)
        val meanEllipseErr = (sumEllipseErr / pts.size) / diag

        // Compute per-point distance to axis-aligned bounding rectangle
        var sumRectErr = 0f
        for (p in pts) {
            sumRectErr += distanceToRectBoundary(p, minX, minY, maxX, maxY)
        }
        val meanRectErr = (sumRectErr / pts.size) / diag

        // Compute per-point distance to axis-aligned diamond
        val diamondVerts = listOf(
            StrokePoint(cx, minY),
            StrokePoint(maxX, cy),
            StrokePoint(cx, maxY),
            StrokePoint(minX, cy)
        )
        val meanDiamondErr = meanDistanceToPolygonEdges(pts, diamondVerts) / diag

        // Cyclic corner detection: rotate loop so it starts at point farthest from centroid
        // (guarantees a true corner becomes the anchor even if user started mid-edge!)
        val corners = extractCyclicCorners(pts, cx, cy, diag)
        val meanCornerPolyErr = if (corners.size >= 3) {
            meanDistanceToPolygonEdges(pts, corners) / diag
        } else {
            Float.MAX_VALUE
        }

        // ------------------------------------------------------------------
        // 3. CLASSIFY AMONG TRIANGLE, CIRCLE/ELLIPSE, RECTANGLE, DIAMOND
        // ------------------------------------------------------------------

        // A. TRIANGLE (3 real corners, low 3-edge error, beats both rect and ellipse)
        if (corners.size == 3 && isValidTriangle(corners, diag)) {
            val triErr = meanCornerPolyErr
            if (triErr <= 0.085f && triErr < meanRectErr * 0.90f && triErr < meanEllipseErr * 0.95f) {
                val tMinX = corners.minOf { it.x }
                val tMaxX = corners.maxOf { it.x }
                val tMinY = corners.minOf { it.y }
                val tMaxY = corners.maxOf { it.y }
                val confidence = (1f - triErr * 3f).coerceIn(0.82f, 0.98f)
                val triShape = ShapeItem(
                    shapeType = ShapeType.TRIANGLE,
                    x = tMinX,
                    y = tMinY,
                    width = (tMaxX - tMinX).coerceAtLeast(10f),
                    height = (tMaxY - tMinY).coerceAtLeast(10f),
                    strokeColorHex = stroke.colorHex,
                    strokeWidth = stroke.width,
                    strokeStyle = stroke.strokeStyle,
                    vertices = corners,
                    opacity = stroke.opacity,
                    isRainbow = stroke.isRainbow,
                    brushType = stroke.brushType
                )
                return RecognizedShapeResult(
                    shapeType = ShapeType.TRIANGLE,
                    confidence = confidence,
                    shapeItem = triShape,
                    description = "Triángulo detectado (${(confidence * 100).toInt()}% precisión)"
                )
            }
        }

        // B. CIRCLE / ELLIPSE (low radial variance, smooth boundary, beats rectangle & diamond)
        val aspectRatio = width / height.coerceAtLeast(1f)
        if (
            radialCV <= 0.165f &&
            meanEllipseErr <= 0.080f &&
            meanEllipseErr < meanRectErr * 0.88f &&
            meanEllipseErr < meanDiamondErr * 0.88f &&
            (aspectRatio !in 0.72f..1.38f || circularity >= 0.72f)
        ) {
            // If nearly square aspect ratio, snap cleanly to a true circle centered at (cx, cy)
            val isNearCircle = aspectRatio in 0.82f..1.22f
            val finalW = if (isNearCircle) (width + height) / 2f else width
            val finalH = if (isNearCircle) finalW else height
            val finalX = cx - finalW / 2f
            val finalY = cy - finalH / 2f

            val confidence = (1f - meanEllipseErr * 3.5f).coerceIn(0.84f, 0.99f)
            val circleShape = ShapeItem(
                shapeType = ShapeType.CIRCLE,
                x = finalX,
                y = finalY,
                width = finalW,
                height = finalH,
                strokeColorHex = stroke.colorHex,
                strokeWidth = stroke.width,
                strokeStyle = stroke.strokeStyle,
                opacity = stroke.opacity,
                isRainbow = stroke.isRainbow,
                brushType = stroke.brushType
            )
            return RecognizedShapeResult(
                shapeType = ShapeType.CIRCLE,
                confidence = confidence,
                shapeItem = circleShape,
                description = if (isNearCircle) "Círculo detectado" else "Elipse detectada"
            )
        }

        // C. DIAMOND / RHOMBUS (4 midpoint corners, fillRatio around 0.50)
        if (
            fillRatio in 0.36f..0.66f &&
            meanDiamondErr <= 0.080f &&
            meanDiamondErr < meanRectErr * 0.82f &&
            meanDiamondErr < meanEllipseErr * 0.88f
        ) {
            val confidence = (1f - meanDiamondErr * 3f).coerceIn(0.82f, 0.97f)
            val diamondShape = ShapeItem(
                shapeType = ShapeType.DIAMOND,
                x = minX,
                y = minY,
                width = width,
                height = height,
                strokeColorHex = stroke.colorHex,
                strokeWidth = stroke.width,
                strokeStyle = stroke.strokeStyle,
                vertices = diamondVerts,
                opacity = stroke.opacity,
                isRainbow = stroke.isRainbow,
                brushType = stroke.brushType
            )
            return RecognizedShapeResult(
                shapeType = ShapeType.DIAMOND,
                confidence = confidence,
                shapeItem = diamondShape,
                description = "Rombo detectado"
            )
        }

        // D. RECTANGLE / SQUARE (high fillRatio, low bounding-rect boundary error, ~4 corners)
        if (
            fillRatio >= 0.72f &&
            meanRectErr <= 0.085f &&
            meanRectErr < meanEllipseErr &&
            (corners.size in 4..5 || corners.size == 3 && fillRatio >= 0.78f)
        ) {
            val isNearSquare = aspectRatio in 0.86f..1.16f
            val finalW = if (isNearSquare) (width + height) / 2f else width
            val finalH = if (isNearSquare) finalW else height
            val finalX = cx - finalW / 2f
            val finalY = cy - finalH / 2f

            val confidence = (1f - meanRectErr * 3f).coerceIn(0.82f, 0.98f)
            val rectShape = ShapeItem(
                shapeType = ShapeType.RECTANGLE,
                x = finalX,
                y = finalY,
                width = finalW,
                height = finalH,
                strokeColorHex = stroke.colorHex,
                strokeWidth = stroke.width,
                strokeStyle = stroke.strokeStyle,
                opacity = stroke.opacity,
                isRainbow = stroke.isRainbow,
                brushType = stroke.brushType
            )
            return RecognizedShapeResult(
                shapeType = ShapeType.RECTANGLE,
                confidence = confidence,
                shapeItem = rectShape,
                description = if (isNearSquare) "Cuadrado detectado" else "Rectángulo detectado"
            )
        }

        return null
    }

    private fun deduplicateNearby(points: List<StrokePoint>, minStep: Float): List<StrokePoint> {
        if (points.isEmpty()) return emptyList()
        val out = mutableListOf(points.first())
        for (i in 1 until points.size) {
            val last = out.last()
            val cur = points[i]
            if (hypot(cur.x - last.x, cur.y - last.y) >= minStep) {
                out.add(cur)
            }
        }
        val lastRaw = points.last()
        if (out.size >= 1 && hypot(lastRaw.x - out.last().x, lastRaw.y - out.last().y) > 0.5f) {
            out.add(lastRaw)
        }
        return out
    }

    private fun computePathLength(points: List<StrokePoint>): Float {
        var total = 0f
        for (i in 1 until points.size) {
            total += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return total
    }

    private fun resampleByArcLength(points: List<StrokePoint>, targetCount: Int): List<StrokePoint> {
        if (points.size <= 2 || targetCount <= 2) return points
        val totalLen = computePathLength(points)
        if (totalLen < 1f) return points

        val step = totalLen / (targetCount - 1)
        val resampled = mutableListOf(points.first())
        var accumulated = 0f
        var prev = points.first()
        var i = 1

        while (i < points.size) {
            val cur = points[i]
            val segLen = hypot(cur.x - prev.x, cur.y - prev.y)
            if (accumulated + segLen >= step) {
                val t = ((step - accumulated) / segLen.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                val nx = prev.x + t * (cur.x - prev.x)
                val ny = prev.y + t * (cur.y - prev.y)
                val newPt = StrokePoint(nx, ny)
                resampled.add(newPt)
                prev = newPt
                accumulated = 0f
            } else {
                accumulated += segLen
                prev = cur
                i++
            }
        }
        if (resampled.size < targetCount) {
            resampled.add(points.last())
        }
        return resampled
    }

    /**
     * Returns (unsignedPerpendicularDistance, signedPerpendicularOffset) from [p] to infinite line [a]->[b],
     * clamped to segment distance if projection falls outside [a, b].
     */
    private fun perpendicularDistanceToSegment(p: StrokePoint, a: StrokePoint, b: StrokePoint): Pair<Float, Float> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 0.0001f) {
            val d = hypot(p.x - a.x, p.y - a.y)
            return d to 0f
        }
        val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
        val clampedT = t.coerceIn(0f, 1f)
        val projX = a.x + clampedT * dx
        val projY = a.y + clampedT * dy
        val dist = hypot(p.x - projX, p.y - projY)
        val cross = ((p.x - a.x) * dy - (p.y - a.y) * dx) / sqrt(lenSq)
        return dist to cross
    }

    private fun computeShoelaceArea(closedPts: List<StrokePoint>): Float {
        if (closedPts.size < 3) return 0f
        var sum = 0f
        for (i in closedPts.indices) {
            val p1 = closedPts[i]
            val p2 = closedPts[(i + 1) % closedPts.size]
            sum += (p1.x * p2.y - p2.x * p1.y)
        }
        return abs(sum) * 0.5f
    }

    private fun distanceToEllipseApprox(p: StrokePoint, cx: Float, cy: Float, rx: Float, ry: Float): Float {
        val dx = p.x - cx
        val dy = p.y - cy
        val angle = kotlin.math.atan2(dy / ry, dx / rx)
        val ex = cx + rx * cos(angle)
        val ey = cy + ry * sin(angle)
        return hypot(p.x - ex, p.y - ey)
    }

    private fun distanceToRectBoundary(p: StrokePoint, minX: Float, minY: Float, maxX: Float, maxY: Float): Float {
        val dLeft = abs(p.x - minX)
        val dRight = abs(p.x - maxX)
        val dTop = abs(p.y - minY)
        val dBottom = abs(p.y - maxY)
        return min(min(dLeft, dRight), min(dTop, dBottom))
    }

    private fun meanDistanceToPolygonEdges(pts: List<StrokePoint>, vertices: List<StrokePoint>): Float {
        if (vertices.size < 2 || pts.isEmpty()) return Float.MAX_VALUE
        var sum = 0f
        for (p in pts) {
            var best = Float.MAX_VALUE
            for (i in vertices.indices) {
                val a = vertices[i]
                val b = vertices[(i + 1) % vertices.size]
                val d = perpendicularDistanceToSegment(p, a, b).first
                if (d < best) best = d
            }
            sum += best
        }
        return sum / pts.size
    }

    private fun extractCyclicCorners(pts: List<StrokePoint>, cx: Float, cy: Float, diag: Float): List<StrokePoint> {
        if (pts.size < 6) return emptyList()
        // Drop duplicate closing endpoint before rotating
        val loop = if (hypot(pts.first().x - pts.last().x, pts.first().y - pts.last().y) < diag * 0.10f) {
            pts.dropLast(1)
        } else {
            pts
        }
        if (loop.size < 6) return emptyList()

        // Rotate loop so index 0 is farthest from centroid (guaranteed sharp corner on polygons)
        var maxDistIdx = 0
        var maxDist = -1f
        for (i in loop.indices) {
            val d = hypot(loop[i].x - cx, loop[i].y - cy)
            if (d > maxDist) {
                maxDist = d
                maxDistIdx = i
            }
        }
        val rotated = loop.subList(maxDistIdx, loop.size) + loop.subList(0, maxDistIdx) + loop[maxDistIdx]
        val simplified = rdpSimplify(rotated, epsilon = diag * 0.068f).dropLast(1)
        return mergeCollinearVertices(simplified, minCornerDeviationDeg = 22f, minEdgeLen = diag * 0.12f)
    }

    private fun rdpSimplify(pts: List<StrokePoint>, epsilon: Float): List<StrokePoint> {
        if (pts.size <= 2) return pts
        val first = pts.first()
        val last = pts.last()
        var maxDist = -1f
        var index = -1

        for (i in 1 until pts.lastIndex) {
            val d = perpendicularDistanceToSegment(pts[i], first, last).first
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }

        return if (maxDist > epsilon && index > 0) {
            val left = rdpSimplify(pts.subList(0, index + 1), epsilon)
            val right = rdpSimplify(pts.subList(index, pts.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    private fun mergeCollinearVertices(
        vertices: List<StrokePoint>,
        minCornerDeviationDeg: Float,
        minEdgeLen: Float
    ): List<StrokePoint> {
        if (vertices.size <= 3) return vertices
        val current = vertices.toMutableList()
        var changed = true

        while (changed && current.size > 3) {
            changed = false
            for (i in current.indices) {
                val prev = current[(i - 1 + current.size) % current.size]
                val cur = current[i]
                val next = current[(i + 1) % current.size]

                val d1 = hypot(cur.x - prev.x, cur.y - prev.y)
                val d2 = hypot(next.x - cur.x, next.y - cur.y)
                if (d1 < minEdgeLen || d2 < minEdgeLen) {
                    current.removeAt(i)
                    changed = true
                    break
                }

                val ux = (cur.x - prev.x) / d1
                val uy = (cur.y - prev.y) / d1
                val vx = (next.x - cur.x) / d2
                val vy = (next.y - cur.y) / d2
                val dot = (ux * vx + uy * vy).coerceIn(-1f, 1f)
                val turnAngleDeg = (acos(dot) * 180.0 / PI).toFloat()
                if (turnAngleDeg < minCornerDeviationDeg) {
                    current.removeAt(i)
                    changed = true
                    break
                }
            }
        }
        return current
    }

    private fun isValidTriangle(corners: List<StrokePoint>, diag: Float): Boolean {
        if (corners.size != 3) return false
        for (i in 0 until 3) {
            val a = corners[i]
            val b = corners[(i + 1) % 3]
            val c = corners[(i + 2) % 3]
            val ab = hypot(b.x - a.x, b.y - a.y)
            val bc = hypot(c.x - b.x, c.y - b.y)
            if (ab < diag * 0.18f || bc < diag * 0.18f) return false
            val dot = (((a.x - b.x) * (c.x - b.x) + (a.y - b.y) * (c.y - b.y)) / (ab * bc)).coerceIn(-1f, 1f)
            val interiorDeg = (acos(dot) * 180.0 / PI).toFloat()
            if (interiorDeg < 18f || interiorDeg > 148f) return false
        }
        return true
    }
}
