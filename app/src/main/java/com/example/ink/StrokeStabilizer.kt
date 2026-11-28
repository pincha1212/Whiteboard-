package com.example.ink

import com.example.model.RulerState
import com.example.model.StrokePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 1€ (One Euro) adaptive low-pass filter for real-time pen & touch smoothing.
 * Minimizes jitter at low drawing speeds while keeping latency near zero during fast flicks.
 */
class OneEuroFilter2D(
    private val minCutoff: Float = 1.4f,
    private val beta: Float = 0.035f,
    private val dCutoff: Float = 1.0f
) {
    private var initialized = false
    private var prevTimeSec = 0f
    private var prevX = 0f
    private var prevY = 0f
    private var prevDx = 0f
    private var prevDy = 0f

    fun filter(x: Float, y: Float, timestampMs: Long): Pair<Float, Float> {
        val timeSec = timestampMs / 1000f
        if (!initialized) {
            initialized = true
            prevTimeSec = timeSec
            prevX = x
            prevY = y
            prevDx = 0f
            prevDy = 0f
            return x to y
        }
        val dt = (timeSec - prevTimeSec).coerceIn(0.002f, 0.25f)
        prevTimeSec = timeSec

        val dx = (x - prevX) / dt
        val dy = (y - prevY) / dt
        val alphaD = alphaFor(dt, dCutoff)
        val edx = lowPass(dx, prevDx, alphaD)
        val edy = lowPass(dy, prevDy, alphaD)
        prevDx = edx
        prevDy = edy

        val speed = hypot(edx, edy)
        val cutoff = (minCutoff + beta * speed).coerceAtLeast(0.1f)
        val alpha = alphaFor(dt, cutoff)
        val outX = lowPass(x, prevX, alpha)
        val outY = lowPass(y, prevY, alpha)
        prevX = outX
        prevY = outY
        return outX to outY
    }

    private fun alphaFor(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff.coerceAtLeast(0.01f))
        return (1f / (1f + tau / dt)).coerceIn(0.01f, 1f)
    }

    private fun lowPass(value: Float, prev: Float, alpha: Float): Float =
        prev + alpha * (value - prev)
}

object StrokeStabilizer {

    /**
     * Applies adaptive smoothing / stabilization to compensate hand jitter or passive styluses.
     * [stabilizationLevel] ranges from 0f (raw) to ~0.75f (high stabilization).
     * Endpoints remain anchored to preserve exact start/end coordinates.
     */
    fun stabilizePoints(
        points: List<StrokePoint>,
        stabilizationLevel: Float
    ): List<StrokePoint> {
        if (points.size <= 2 || stabilizationLevel <= 0.02f) return points

        val alpha = (1f - stabilizationLevel.coerceIn(0f, 0.82f)).coerceIn(0.18f, 1f)
        val emaPoints = ArrayList<StrokePoint>(points.size)
        var curX = points.first().x
        var curY = points.first().y
        emaPoints.add(points.first())

        for (i in 1 until points.lastIndex) {
            val target = points[i]
            curX += alpha * (target.x - curX)
            curY += alpha * (target.y - curY)
            emaPoints.add(
                StrokePoint(
                    x = curX,
                    y = curY,
                    timestampMs = target.timestampMs,
                    pressure = target.pressure
                )
            )
        }
        emaPoints.add(points.last())

        if (stabilizationLevel < 0.30f || emaPoints.size < 4) {
            return emaPoints
        }

        val smoothed = ArrayList<StrokePoint>(emaPoints.size * 2)
        smoothed.add(emaPoints.first())
        for (i in 0 until emaPoints.lastIndex) {
            val a = emaPoints[i]
            val b = emaPoints[i + 1]
            if (i > 0) {
                smoothed.add(
                    StrokePoint(
                        x = a.x * 0.75f + b.x * 0.25f,
                        y = a.y * 0.75f + b.y * 0.25f,
                        timestampMs = (a.timestampMs * 3L + b.timestampMs) / 4L,
                        pressure = a.pressure * 0.75f + b.pressure * 0.25f
                    )
                )
            }
            if (i < emaPoints.lastIndex - 1) {
                smoothed.add(
                    StrokePoint(
                        x = a.x * 0.25f + b.x * 0.75f,
                        y = a.y * 0.25f + b.y * 0.75f,
                        timestampMs = (a.timestampMs + b.timestampMs * 3L) / 4L,
                        pressure = a.pressure * 0.25f + b.pressure * 0.75f
                    )
                )
            }
        }
        smoothed.add(emaPoints.last())
        return smoothed
    }

    /**
     * Real-time predictive smoothing used while the finger/stylus is moving (`predictionLevel` 0..5).
     * Guarantees zero jump between live preview and committed stroke.
     */
    fun stabilizeLivePreview(
        points: List<StrokePoint>,
        predictionLevel: Int
    ): List<StrokePoint> {
        if (points.size <= 2 || predictionLevel <= 0) return points
        val level = predictionLevel.coerceIn(1, 5)
        val filtered = filterWithOneEuro(points, level)
        if (level <= 1 || filtered.size < 4) return filtered
        return catmullRomRefine(filtered, blendFactor = (level * 0.16f).coerceIn(0.2f, 0.85f))
    }

    /**
     * Stateful O(1)-per-event live preview stabilizer matching [stabilizeLivePreview] without
     * re-scanning and re-filtering points 0..N on every pointer MOVE event.
     */
    class IncrementalLivePreviewFilter {
        private var activeLevel: Int = -1
        private var baseTs: Long = 0L
        private var firstX: Float = 0f
        private var firstY: Float = 0f
        private var filter: OneEuroFilter2D? = null
        private val filteredInterior = ArrayList<StrokePoint>(128)
        private val refinedInterior = ArrayList<StrokePoint>(128)

        fun reset() {
            activeLevel = -1
            baseTs = 0L
            filter = null
            filteredInterior.clear()
            refinedInterior.clear()
        }

        fun update(rawPoints: List<StrokePoint>, predictionLevel: Int): List<StrokePoint> {
            val size = rawPoints.size
            if (size <= 2 || predictionLevel <= 0) {
                if (size <= 1) reset()
                return rawPoints
            }
            val level = predictionLevel.coerceIn(1, 5)
            val first = rawPoints[0]
            val mustRebuild = level != activeLevel ||
                filter == null ||
                filteredInterior.isEmpty() ||
                size <= filteredInterior.size ||
                firstX != first.x ||
                firstY != first.y

            if (mustRebuild) {
                activeLevel = level
                firstX = first.x
                firstY = first.y
                baseTs = first.timestampMs
                val minCutoff = (2.6f - level * 0.36f).coerceIn(0.55f, 2.5f)
                val beta = (0.06f - level * 0.008f).coerceIn(0.012f, 0.06f)
                filter = OneEuroFilter2D(minCutoff = minCutoff, beta = beta, dCutoff = 1.0f)
                filteredInterior.clear()
                refinedInterior.clear()
                filteredInterior.add(first)
                refinedInterior.add(first)
            }

            val euro = filter!!
            val oldInteriorCount = filteredInterior.size
            val targetInteriorCount = size - 1
            for (i in oldInteriorCount until targetInteriorCount) {
                val p = rawPoints[i]
                val ts = if (p.timestampMs > baseTs) p.timestampMs else baseTs + i * 16L
                val (fx, fy) = euro.filter(p.x, p.y, ts)
                filteredInterior.add(StrokePoint(fx, fy, p.timestampMs, p.pressure))
            }

            val last = rawPoints[size - 1]
            // Filtered list = filteredInterior + [last]
            if (level <= 1 || size < 4) {
                val out = ArrayList<StrokePoint>(size)
                out.addAll(filteredInterior)
                out.add(last)
                return out
            }

            val blendFactor = (level * 0.16f).coerceIn(0.2f, 0.85f)
            // Indices up to (oldInteriorCount - 2) already had fixed (p0, p1, p2) in filteredInterior.
            val recomputeFrom = (oldInteriorCount - 1).coerceAtLeast(1)
            while (refinedInterior.size > recomputeFrom) {
                refinedInterior.removeAt(refinedInterior.lastIndex)
            }
            val lastInteriorIdx = filteredInterior.lastIndex
            for (i in recomputeFrom..lastInteriorIdx) {
                val p0 = filteredInterior[i - 1]
                val p1 = filteredInterior[i]
                val p2 = if (i + 1 <= lastInteriorIdx) filteredInterior[i + 1] else last
                val avgX = (p0.x + 2f * p1.x + p2.x) * 0.25f
                val avgY = (p0.y + 2f * p1.y + p2.y) * 0.25f
                refinedInterior.add(
                    StrokePoint(
                        x = p1.x + (avgX - p1.x) * blendFactor,
                        y = p1.y + (avgY - p1.y) * blendFactor,
                        timestampMs = p1.timestampMs,
                        pressure = p1.pressure
                    )
                )
            }
            val out = ArrayList<StrokePoint>(refinedInterior.size + 1)
            out.addAll(refinedInterior)
            out.add(last)
            return out
        }
    }

    /**
     * Full Sketchbook-style Predictive Stroke engine applied on commit (`predictionLevel` 0..5):
     * - Level 0: raw input points
     * - Level 1..2: One Euro Filter + C1 Catmull-Rom refinement
     * - Level 3..5: Splits on intentional sharp corners (> 38 deg), then beautifies straight lines
     *   and smooth circular arcs (Kasa/Pratt fit) or blends gently toward C1 spline.
     */
    fun applyPredictiveStroke(
        points: List<StrokePoint>,
        predictionLevel: Int
    ): List<StrokePoint> {
        if (points.size <= 2 || predictionLevel <= 0) return points
        val level = predictionLevel.coerceIn(1, 5)
        val filtered = filterWithOneEuro(points, level)
        if (level <= 2 || filtered.size < 4) {
            return catmullRomRefine(filtered, blendFactor = (level * 0.24f).coerceIn(0.2f, 0.65f))
        }

        // Preserve deliberate corners: split into smooth sub-runs whenever turn angle > 38 deg
        val cornerIndices = detectSharpCorners(filtered, cornerDegThreshold = 38f)
        val segments = mutableListOf<List<StrokePoint>>()
        var startIdx = 0
        for (corner in cornerIndices) {
            if (corner - startIdx >= 2) {
                segments.add(filtered.subList(startIdx, corner + 1))
            }
            startIdx = corner
        }
        if (filtered.lastIndex - startIdx >= 1) {
            segments.add(filtered.subList(startIdx, filtered.size))
        }

        if (segments.isEmpty()) return filtered

        val result = ArrayList<StrokePoint>(filtered.size * 2)
        val blendWeight = ((level - 2) / 3f).coerceIn(0.35f, 1.0f)

        segments.forEachIndexed { segIdx, segment ->
            val refined = beautifySmoothSegment(segment, blendWeight)
            if (segIdx == 0) {
                result.addAll(refined)
            } else if (refined.size > 1) {
                // Keep exact shared corner vertex with zero double-point seam
                result.addAll(refined.drop(1))
            }
        }
        return if (result.size >= 2) result else filtered
    }

    private fun filterWithOneEuro(points: List<StrokePoint>, level: Int): List<StrokePoint> {
        val minCutoff = (2.6f - level * 0.36f).coerceIn(0.55f, 2.5f)
        val beta = (0.06f - level * 0.008f).coerceIn(0.012f, 0.06f)
        val filter = OneEuroFilter2D(minCutoff = minCutoff, beta = beta, dCutoff = 1.0f)
        val out = ArrayList<StrokePoint>(points.size)
        out.add(points.first())
        val baseTs = points.first().timestampMs
        for (i in 1 until points.lastIndex) {
            val p = points[i]
            val ts = if (p.timestampMs > baseTs) p.timestampMs else baseTs + i * 16L
            val (fx, fy) = filter.filter(p.x, p.y, ts)
            out.add(StrokePoint(fx, fy, p.timestampMs, p.pressure))
        }
        out.add(points.last())
        return out
    }

    internal fun detectSharpCorners(
        points: List<StrokePoint>,
        cornerDegThreshold: Float = 38f
    ): List<Int> {
        if (points.size < 5) return emptyList()
        val corners = mutableListOf<Int>()
        val stride = 2
        var lastCorner = -4
        for (i in stride until points.size - stride) {
            if (i - lastCorner < 4) continue
            val a = points[i - stride]
            val b = points[i]
            val c = points[i + stride]
            val v1x = b.x - a.x
            val v1y = b.y - a.y
            val v2x = c.x - b.x
            val v2y = c.y - b.y
            val l1 = hypot(v1x, v1y)
            val l2 = hypot(v2x, v2y)
            if (l1 < 5f || l2 < 5f) continue
            val cosTheta = ((v1x * v2x + v1y * v2y) / (l1 * l2)).coerceIn(-1f, 1f)
            val angleDeg = acos(cosTheta) * (180f / PI.toFloat())
            if (angleDeg >= cornerDegThreshold) {
                corners.add(i)
                lastCorner = i
            }
        }
        return corners
    }

    private fun beautifySmoothSegment(
        segment: List<StrokePoint>,
        blendWeight: Float
    ): List<StrokePoint> {
        if (segment.size < 3) return segment
        val first = segment.first()
        val last = segment.last()
        val chord = hypot(last.x - first.x, last.y - first.y)

        // 1. Straight-line check when chord is meaningful
        if (chord >= 28f) {
            var maxDev = 0f
            val ux = (last.x - first.x) / chord
            val uy = (last.y - first.y) / chord
            for (p in segment) {
                val proj = (p.x - first.x) * ux + (p.y - first.y) * uy
                val px = first.x + ux * proj
                val py = first.y + uy * proj
                val d = hypot(p.x - px, p.y - py)
                if (d > maxDev) maxDev = d
            }
            val lineThreshold = max(6f, chord * 0.032f)
            if (maxDev <= lineThreshold) {
                return segment.mapIndexed { idx, orig ->
                    val t = idx.toFloat() / segment.lastIndex.coerceAtLeast(1)
                    val targetX = first.x + (last.x - first.x) * t
                    val targetY = first.y + (last.y - first.y) * t
                    StrokePoint(
                        x = orig.x + (targetX - orig.x) * blendWeight,
                        y = orig.y + (targetY - orig.y) * blendWeight,
                        timestampMs = orig.timestampMs,
                        pressure = orig.pressure
                    )
                }
            }
        }

        // 2. Algebraic circle / smooth arc fit (Kasa fit) when segment has enough span
        if (segment.size >= 8) {
            val arcFitted = tryFitCircularArc(segment, blendWeight)
            if (arcFitted != null) return arcFitted
        }

        // 3. Fallback: C1 Catmull-Rom spline refinement
        return catmullRomRefine(segment, blendFactor = (0.45f + 0.45f * blendWeight).coerceIn(0f, 1f))
    }

    private fun tryFitCircularArc(
        segment: List<StrokePoint>,
        blendWeight: Float
    ): List<StrokePoint>? {
        val n = segment.size.toDouble()
        var sumX = 0.0
        var sumY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0
        var sumXY = 0.0
        var sumX3 = 0.0
        var sumY3 = 0.0
        var sumX1Y2 = 0.0
        var sumX2Y1 = 0.0

        for (p in segment) {
            val x = p.x.toDouble()
            val y = p.y.toDouble()
            val x2 = x * x
            val y2 = y * y
            sumX += x
            sumY += y
            sumX2 += x2
            sumY2 += y2
            sumXY += x * y
            sumX3 += x2 * x
            sumY3 += y2 * y
            sumX1Y2 += x * y2
            sumX2Y1 += x2 * y
        }

        val c = n * sumX2 - sumX * sumX
        val d = n * sumXY - sumX * sumY
        val e = n * sumX3 + n * sumX1Y2 - (sumX2 + sumY2) * sumX
        val g = n * sumY2 - sumY * sumY
        val h = n * sumX2Y1 + n * sumY3 - (sumX2 + sumY2) * sumY
        val denom = 2.0 * (c * g - d * d)
        if (abs(denom) < 1e-4) return null

        val cx = ((g * e - d * h) / denom).toFloat()
        val cy = ((c * h - d * e) / denom).toFloat()
        val radius = segment.map { hypot(it.x - cx, it.y - cy) }.average().toFloat()
        if (radius < 14f || radius > 4000f) return null

        val maxResidual = segment.maxOf { abs(hypot(it.x - cx, it.y - cy) - radius) }
        if (maxResidual > max(8f, radius * 0.075f)) return null

        return segment.mapIndexed { idx, orig ->
            if (idx == 0 || idx == segment.lastIndex) {
                orig
            } else {
                val dx = orig.x - cx
                val dy = orig.y - cy
                val len = hypot(dx, dy).coerceAtLeast(0.001f)
                val targetX = cx + (dx / len) * radius
                val targetY = cy + (dy / len) * radius
                StrokePoint(
                    x = orig.x + (targetX - orig.x) * blendWeight,
                    y = orig.y + (targetY - orig.y) * blendWeight,
                    timestampMs = orig.timestampMs,
                    pressure = orig.pressure
                )
            }
        }
    }

    private fun catmullRomRefine(
        points: List<StrokePoint>,
        blendFactor: Float
    ): List<StrokePoint> {
        if (points.size < 4) return points
        val out = ArrayList<StrokePoint>(points.size)
        out.add(points.first())
        for (i in 1 until points.lastIndex) {
            val p0 = points[(i - 1).coerceAtLeast(0)]
            val p1 = points[i]
            val p2 = points[(i + 1).coerceAtMost(points.lastIndex)]
            val avgX = (p0.x + 2f * p1.x + p2.x) * 0.25f
            val avgY = (p0.y + 2f * p1.y + p2.y) * 0.25f
            out.add(
                StrokePoint(
                    x = p1.x + (avgX - p1.x) * blendFactor,
                    y = p1.y + (avgY - p1.y) * blendFactor,
                    timestampMs = p1.timestampMs,
                    pressure = p1.pressure
                )
            )
        }
        out.add(points.last())
        return out
    }

    /**
     * Snaps interactive ruler rotation onto 15-degree magnetic detents within +/- 3.5 degrees.
     */
    fun snapRulerAngleToDetents(
        rawAngleDeg: Float,
        snapEnabled: Boolean,
        isLocked: Boolean,
        currentAngleDeg: Float
    ): Float {
        if (isLocked) return currentAngleDeg
        val normalized = ((rawAngleDeg % 360f) + 360f) % 360f
        if (!snapEnabled) return normalized
        val nearestDetent = round(normalized / 15f) * 15f
        return if (abs(normalized - nearestDetent) <= 3.5f) {
            ((nearestDetent % 360f) + 360f) % 360f
        } else {
            normalized
        }
    }

    fun isNearRulerDetent(angleDeg: Float): Boolean {
        val normalized = ((angleDeg % 360f) + 360f) % 360f
        val nearestDetent = round(normalized / 15f) * 15f
        return abs(normalized - nearestDetent) <= 0.6f
    }

    /**
     * When the interactive ruler is visible, snaps points drawn near either straight edge
     * directly onto the ruler line so users can trace crisp ruled lines.
     */
    fun projectPointToRulerIfNear(
        point: StrokePoint,
        ruler: RulerState,
        snapThresholdWorld: Float = 26f
    ): StrokePoint {
        if (!ruler.isVisible) return point
        val rad = ruler.angleDegrees * (PI.toFloat() / 180f)
        val cosA = cos(rad)
        val sinA = sin(rad)

        val dx = point.x - ruler.centerX
        val dy = point.y - ruler.centerY

        val localX = dx * cosA + dy * sinA
        val localY = -dx * sinA + dy * cosA

        val halfLen = ruler.length / 2f
        val halfW = ruler.width / 2f

        if (localX < -halfLen - 24f || localX > halfLen + 24f) {
            return point
        }

        val distTop = abs(localY + halfW)
        val distBottom = abs(localY - halfW)
        val snappedLocalY = when {
            distTop <= snapThresholdWorld && distTop <= distBottom -> -halfW - 2f
            distBottom <= snapThresholdWorld -> halfW + 2f
            else -> return point
        }

        val worldX = ruler.centerX + (localX * cosA - snappedLocalY * sinA)
        val worldY = ruler.centerY + (localX * sinA + snappedLocalY * cosA)
        return StrokePoint(worldX, worldY, point.timestampMs, point.pressure)
    }

    fun averageSegmentLength(points: List<StrokePoint>): Float {
        if (points.size < 2) return 0f
        var sum = 0f
        for (i in 1 until points.size) {
            sum += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return sum / (points.size - 1)
    }
}
