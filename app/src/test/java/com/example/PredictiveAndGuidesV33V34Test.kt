package com.example

import com.example.ink.GuidesGeometry
import com.example.ink.OneEuroFilter2D
import com.example.ink.PerspectiveAxisLock
import com.example.ink.StrokeStabilizer
import com.example.model.EllipseGuideState
import com.example.model.FrenchCurveGuideState
import com.example.model.FrenchCurveProfile
import com.example.model.PerspectiveGuideState
import com.example.model.PerspectiveMode
import com.example.model.StrokePoint
import com.example.model.VanishingPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PredictiveAndGuidesV33V34Test {

    @Test
    fun `one euro filter attenuates high frequency jitter on slow movement`() {
        val filter = OneEuroFilter2D(minCutoff = 1.0f, beta = 0.02f)
        val ys = mutableListOf<Float>()
        for (i in 0 until 20) {
            val jitter = if (i % 2 == 0) 6f else -6f
            val (_, fy) = filter.filter(x = i * 4f, y = 100f + jitter, timestampMs = 1000L + i * 25L)
            ys.add(fy)
        }
        val maxDeviationAfterWarmup = ys.drop(4).maxOf { abs(it - 100f) }
        assertTrue("Expected jitter < 3.5px after filter warmup, got $maxDeviationAfterWarmup", maxDeviationAfterWarmup < 3.5f)
    }

    @Test
    fun `predictive stroke preserves intentional L corner while straightening segments`() {
        val raw = mutableListOf<StrokePoint>()
        var ts = 100L
        // Horizontal leg with mild wobble
        for (i in 0..10) {
            val jitterY = if (i in 1..9 && i % 2 == 0) 2.2f else 0f
            raw += StrokePoint(x = i * 25f, y = 100f + jitterY, timestampMs = ts)
            ts += 16L
        }
        // Sharp 90-degree vertical leg down from (250, 100)
        for (j in 1..10) {
            val jitterX = if (j in 1..9 && j % 2 == 1) -2.0f else 0f
            raw += StrokePoint(x = 250f + jitterX, y = 100f + j * 25f, timestampMs = ts)
            ts += 16L
        }

        val predicted = StrokeStabilizer.applyPredictiveStroke(raw, predictionLevel = 4)
        assertTrue(predicted.size >= raw.size - 2)
        // Corner region near (250, 100) must remain sharp and close to (250, 100), not cut across diagonal
        val closestToCorner = predicted.minByOrNull { hypot(it.x - 250f, it.y - 100f) }!!
        val distToCorner = hypot(closestToCorner.x - 250f, closestToCorner.y - 100f)
        assertTrue("Corner must stay sharp near (250, 100), dist=$distToCorner", distToCorner < 12f)
    }

    @Test
    fun `ruler detents snap near 15 degree increments and respect lock`() {
        val snapped45 = StrokeStabilizer.snapRulerAngleToDetents(
            rawAngleDeg = 46.8f,
            snapEnabled = true,
            isLocked = false,
            currentAngleDeg = 0f
        )
        assertEquals(45f, snapped45, 0.01f)

        val locked = StrokeStabilizer.snapRulerAngleToDetents(
            rawAngleDeg = 90f,
            snapEnabled = true,
            isLocked = true,
            currentAngleDeg = 17.5f
        )
        assertEquals(17.5f, locked, 0.01f)
    }

    @Test
    fun `ellipse, french curve, and perspective guides project pointer points accurately`() {
        val ellipse = EllipseGuideState(
            isVisible = true,
            centerX = 500f,
            centerY = 400f,
            radiusX = 200f,
            radiusY = 100f,
            rotationDegrees = 0f
        )
        val nearTop = StrokePoint(500f, 312f)
        val projectedEllipse = GuidesGeometry.projectPointToEllipseGuide(nearTop, ellipse, snapThresholdWorld = 40f)
        assertEquals(500f, projectedEllipse.x, 1.0f)
        assertEquals(300f, projectedEllipse.y, 1.0f)

        val french = FrenchCurveGuideState(
            isVisible = true,
            profile = FrenchCurveProfile.S_ARCH,
            centerX = 300f,
            centerY = 300f,
            scale = 1.0f
        )
        val curveSample = GuidesGeometry.sampleFrenchCurvePoints(french)[48]
        val slightlyOff = StrokePoint(curveSample.x + 8f, curveSample.y - 6f)
        val projectedFrench = GuidesGeometry.projectPointToFrenchCurveGuide(slightlyOff, french, snapThresholdWorld = 30f)
        assertTrue(hypot(projectedFrench.x - curveSample.x, projectedFrench.y - curveSample.y) < 12f)

        val perspective = PerspectiveGuideState(
            mode = PerspectiveMode.TWO_POINT,
            vp1 = VanishingPoint(100f, 500f),
            vp2 = VanishingPoint(900f, 500f),
            horizonY = 500f,
            isSnapEnabled = true
        )
        val origin = StrokePoint(400f, 650f)
        // Direction pointing almost directly toward VP1 (100, 500) -> (-300, -150)
        val towardVp1 = StrokePoint(400f - 60f, 650f - 30f)
        val lock = GuidesGeometry.detectPerspectiveAxisLock(origin, towardVp1, perspective)
        assertEquals(PerspectiveAxisLock.VP1, lock)

        val noisyPoint = StrokePoint(400f - 100f, 650f - 40f)
        val lockedPt = GuidesGeometry.projectPointOnPerspectiveLock(origin, noisyPoint, lock, perspective)
        assertNotEquals(noisyPoint, lockedPt)
        // Cross product with (origin -> vp1) must be ~0 (collinear with vanishing ray)
        val vx1 = perspective.vp1.x - origin.x
        val vy1 = perspective.vp1.y - origin.y
        val px = lockedPt.x - origin.x
        val py = lockedPt.y - origin.y
        val cross = abs(vx1 * py - vy1 * px) / (hypot(vx1, vy1) * hypot(px, py))
        assertTrue("Expected collinear projection onto VP1 ray, cross=$cross", cross < 0.001f)
    }

    @Test
    fun `circular arc beautification preserves radius smoothness on curved stroke`() {
        val arc = (0..18).map { idx ->
            val angle = Math.toRadians((idx * 8).toDouble())
            val r = 180f + if (idx % 2 == 0) 2f else -2f
            StrokePoint(
                x = 300f + (r * cos(angle)).toFloat(),
                y = 300f + (r * sin(angle)).toFloat(),
                timestampMs = 100L + idx * 16L
            )
        }
        val beautified = StrokeStabilizer.applyPredictiveStroke(arc, predictionLevel = 5)
        val radii = beautified.map { hypot(it.x - 300f, it.y - 300f) }
        val maxDiff = radii.maxOf { abs(it - 180f) }
        assertTrue("Expected beautified arc within 3.5px of target radius 180, got $maxDiff", maxDiff < 3.5f)
    }
}
