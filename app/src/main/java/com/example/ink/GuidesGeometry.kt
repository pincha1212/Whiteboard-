package com.example.ink

import com.example.model.EllipseGuideState
import com.example.model.FrenchCurveGuideState
import com.example.model.FrenchCurveProfile
import com.example.model.PerspectiveGuideState
import com.example.model.PerspectiveMode
import com.example.model.StrokePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class PerspectiveAxisLock {
    NONE,
    VP1,
    VP2,
    VP3,
    VERTICAL,
    HORIZONTAL,
    ISO_RIGHT,
    ISO_LEFT
}

/**
 * Pure geometric constraint & projection helpers for Sketchbook-style Precision Guides:
 * Ellipse guide, French Curve templates (3 classic parametric profiles), and 1P/2P/3P/Isometric Perspective.
 */
object GuidesGeometry {

    fun projectPointToEllipseGuide(
        point: StrokePoint,
        guide: EllipseGuideState,
        snapThresholdWorld: Float = 54f
    ): StrokePoint {
        if (!guide.isVisible) return point
        val rad = guide.rotationDegrees * (PI.toFloat() / 180f)
        val cosA = cos(rad)
        val sinA = sin(rad)

        val dx = point.x - guide.centerX
        val dy = point.y - guide.centerY
        val localX = dx * cosA + dy * sinA
        val localY = -dx * sinA + dy * cosA

        val rx = guide.radiusX.coerceAtLeast(16f)
        val ry = (if (guide.isCircleLocked) guide.radiusX else guide.radiusY).coerceAtLeast(16f)

        val theta = atan2(localY / ry, localX / rx)
        val projLocalX = rx * cos(theta)
        val projLocalY = ry * sin(theta)

        val dist = hypot(localX - projLocalX, localY - projLocalY)
        if (dist > snapThresholdWorld) return point

        val worldX = guide.centerX + (projLocalX * cosA - projLocalY * sinA)
        val worldY = guide.centerY + (projLocalX * sinA + projLocalY * cosA)
        return StrokePoint(worldX, worldY, point.timestampMs, point.pressure)
    }

    fun sampleFrenchCurvePoints(guide: FrenchCurveGuideState, sampleCount: Int = 96): List<StrokePoint> {
        val rad = guide.rotationDegrees * (PI.toFloat() / 180f)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val flip = if (guide.isFlippedX) -1f else 1f
        val sc = guide.scale.coerceIn(0.45f, 3.0f)

        val pts = ArrayList<StrokePoint>(sampleCount)
        for (i in 0 until sampleCount) {
            val t = i / (sampleCount - 1).toFloat()
            val (rawX, rawY) = when (guide.profile) {
                FrenchCurveProfile.TEARDROP -> {
                    val theta = t * 2f * PI.toFloat()
                    val r = 125f * (0.45f + 0.55f * sin(theta * 0.5f))
                    val lx = r * cos(theta) * (1f + 0.35f * sin(theta))
                    val ly = r * sin(theta)
                    lx to ly
                }
                FrenchCurveProfile.EULER_SPIRAL -> {
                    val u = t * 3.4f
                    val angle = u * u * 0.58f
                    val radius = 155f * (1f - 0.55f * t)
                    val lx = -110f + radius * cos(angle) * (0.65f + 0.35f * t)
                    val ly = radius * sin(angle) * 0.85f
                    lx to ly
                }
                FrenchCurveProfile.S_ARCH -> {
                    val lx = (t - 0.5f) * 320f
                    val ly = sin((t - 0.5f) * PI.toFloat() * 1.4f) * 110f -
                        cos(t * PI.toFloat()) * 24f
                    lx to ly
                }
            }
            val sx = rawX * flip * sc
            val sy = rawY * sc
            val wx = guide.centerX + (sx * cosA - sy * sinA)
            val wy = guide.centerY + (sx * sinA + sy * cosA)
            pts.add(StrokePoint(wx, wy))
        }
        return pts
    }

    fun projectPointToFrenchCurveGuide(
        point: StrokePoint,
        guide: FrenchCurveGuideState,
        snapThresholdWorld: Float = 48f
    ): StrokePoint {
        if (!guide.isVisible) return point
        val curve = sampleFrenchCurvePoints(guide)
        if (curve.size < 2) return point

        var bestDist = Float.MAX_VALUE
        var bestX = point.x
        var bestY = point.y

        for (i in 0 until curve.lastIndex) {
            val a = curve[i]
            val b = curve[i + 1]
            val abx = b.x - a.x
            val aby = b.y - a.y
            val lenSq = abx * abx + aby * aby
            val t = if (lenSq < 1e-4f) 0f else (((point.x - a.x) * abx + (point.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
            val cx = a.x + abx * t
            val cy = a.y + aby * t
            val d = hypot(point.x - cx, point.y - cy)
            if (d < bestDist) {
                bestDist = d
                bestX = cx
                bestY = cy
            }
        }
        return if (bestDist <= snapThresholdWorld) {
            StrokePoint(bestX, bestY, point.timestampMs, point.pressure)
        } else {
            point
        }
    }

    /**
     * Resolves the best matching vanishing/orthogonal axis once the stroke leaves the deadzone (>= 18px).
     */
    fun detectPerspectiveAxisLock(
        origin: StrokePoint,
        current: StrokePoint,
        guide: PerspectiveGuideState
    ): PerspectiveAxisLock {
        if (guide.mode == PerspectiveMode.OFF || !guide.isSnapEnabled) return PerspectiveAxisLock.NONE
        val dx = current.x - origin.x
        val dy = current.y - origin.y
        val len = hypot(dx, dy)
        if (len < 18f) return PerspectiveAxisLock.NONE
        val ux = dx / len
        val uy = dy / len

        val candidates = mutableListOf<Pair<PerspectiveAxisLock, Pair<Float, Float>>>()

        when (guide.mode) {
            PerspectiveMode.ONE_POINT -> {
                candidates += PerspectiveAxisLock.VP1 to normalizeVec(guide.vp1.x - origin.x, guide.vp1.y - origin.y)
                candidates += PerspectiveAxisLock.HORIZONTAL to (1f to 0f)
                candidates += PerspectiveAxisLock.VERTICAL to (0f to 1f)
            }
            PerspectiveMode.TWO_POINT -> {
                candidates += PerspectiveAxisLock.VP1 to normalizeVec(guide.vp1.x - origin.x, guide.vp1.y - origin.y)
                candidates += PerspectiveAxisLock.VP2 to normalizeVec(guide.vp2.x - origin.x, guide.vp2.y - origin.y)
                candidates += PerspectiveAxisLock.VERTICAL to (0f to 1f)
            }
            PerspectiveMode.THREE_POINT -> {
                candidates += PerspectiveAxisLock.VP1 to normalizeVec(guide.vp1.x - origin.x, guide.vp1.y - origin.y)
                candidates += PerspectiveAxisLock.VP2 to normalizeVec(guide.vp2.x - origin.x, guide.vp2.y - origin.y)
                candidates += PerspectiveAxisLock.VP3 to normalizeVec(guide.vp3.x - origin.x, guide.vp3.y - origin.y)
            }
            PerspectiveMode.ISOMETRIC -> {
                val rad = guide.isometricAngleDeg * (PI.toFloat() / 180f)
                candidates += PerspectiveAxisLock.ISO_RIGHT to (cos(rad) to -sin(rad))
                candidates += PerspectiveAxisLock.ISO_LEFT to (cos(rad) to sin(rad))
                candidates += PerspectiveAxisLock.VERTICAL to (0f to 1f)
            }
            PerspectiveMode.OFF -> Unit
        }

        var bestLock = PerspectiveAxisLock.NONE
        var bestAngleDeg = 16.5f // Must align within 16.5 deg to engage snap

        for ((lock, dir) in candidates) {
            val dot = abs(ux * dir.first + uy * dir.second).coerceIn(0f, 1f)
            val angleDeg = acos(dot) * (180f / PI.toFloat())
            if (angleDeg < bestAngleDeg) {
                bestAngleDeg = angleDeg
                bestLock = lock
            }
        }
        return bestLock
    }

    fun projectPointOnPerspectiveLock(
        origin: StrokePoint,
        point: StrokePoint,
        lock: PerspectiveAxisLock,
        guide: PerspectiveGuideState
    ): StrokePoint {
        if (lock == PerspectiveAxisLock.NONE) return point
        val dir = when (lock) {
            PerspectiveAxisLock.VP1 -> normalizeVec(guide.vp1.x - origin.x, guide.vp1.y - origin.y)
            PerspectiveAxisLock.VP2 -> normalizeVec(guide.vp2.x - origin.x, guide.vp2.y - origin.y)
            PerspectiveAxisLock.VP3 -> normalizeVec(guide.vp3.x - origin.x, guide.vp3.y - origin.y)
            PerspectiveAxisLock.HORIZONTAL -> 1f to 0f
            PerspectiveAxisLock.VERTICAL -> 0f to 1f
            PerspectiveAxisLock.ISO_RIGHT -> {
                val rad = guide.isometricAngleDeg * (PI.toFloat() / 180f)
                cos(rad) to -sin(rad)
            }
            PerspectiveAxisLock.ISO_LEFT -> {
                val rad = guide.isometricAngleDeg * (PI.toFloat() / 180f)
                cos(rad) to sin(rad)
            }
            PerspectiveAxisLock.NONE -> return point
        }
        val t = (point.x - origin.x) * dir.first + (point.y - origin.y) * dir.second
        return StrokePoint(
            x = origin.x + dir.first * t,
            y = origin.y + dir.second * t,
            timestampMs = point.timestampMs,
            pressure = point.pressure
        )
    }

    private fun normalizeVec(x: Float, y: Float): Pair<Float, Float> {
        val len = hypot(x, y).coerceAtLeast(0.001f)
        return (x / len) to (y / len)
    }
}
