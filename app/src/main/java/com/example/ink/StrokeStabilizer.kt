package com.example.ink

import com.example.model.RulerState
import com.example.model.StrokePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
            emaPoints.add(StrokePoint(curX, curY))
        }
        emaPoints.add(points.last())

        // For medium/high stabilization, apply a gentle Chaikin midpoint smoothing pass
        if (stabilizationLevel < 0.30f || emaPoints.size < 4) {
            return emaPoints
        }

        val smoothed = ArrayList<StrokePoint>(emaPoints.size * 2)
        smoothed.add(emaPoints.first())
        for (i in 0 until emaPoints.lastIndex) {
            val a = emaPoints[i]
            val b = emaPoints[i + 1]
            if (i > 0) {
                smoothed.add(StrokePoint(a.x * 0.75f + b.x * 0.25f, a.y * 0.75f + b.y * 0.25f))
            }
            if (i < emaPoints.lastIndex - 1) {
                smoothed.add(StrokePoint(a.x * 0.25f + b.x * 0.75f, a.y * 0.25f + b.y * 0.75f))
            }
        }
        smoothed.add(emaPoints.last())
        return smoothed
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

        // Transform world point into ruler local coordinates
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
        return StrokePoint(worldX, worldY)
    }

    /**
     * Measures average curvature jitter across consecutive segments (useful for tests/diagnostics).
     */
    fun averageSegmentLength(points: List<StrokePoint>): Float {
        if (points.size < 2) return 0f
        var sum = 0f
        for (i in 1 until points.size) {
            sum += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return sum / (points.size - 1)
    }
}
