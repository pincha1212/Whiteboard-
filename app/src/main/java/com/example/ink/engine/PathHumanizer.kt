package com.example.ink.engine

import android.graphics.Path
import android.graphics.PathMeasure
import com.example.model.StrokePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Sampled geometric & kinematic node produced by [PathHumanizer] for procedural [BrushEngine] rendering.
 *
 * @property x Final horizontal coordinate in Canvas space (or world space prior to camera transform).
 * @property y Final vertical coordinate in Canvas space.
 * @property angle Direction tangent angle in radians (`-PI..PI`).
 * @property velocity Normalized movement speed in `0f..1f` (`0f` = slow/heavy ink, `1f` = fast/dry stroke).
 * @property progress Brush profile value: closed contours use a `0f..1f` phase; open committed strokes use a stable endpoint profile, while live previews pin it to `0.5f`.
 * @property contourIndex Zero-based index identifying contiguous contour segments in multi-contour [Path]s.
 * @property isClosed True if this contour originated from a closed geometric loop.
 */
data class BrushNode(
    val x: Float,
    val y: Float,
    val angle: Float,
    val velocity: Float,
    // Open live previews use a constant mid-stroke value; committed strokes use an endpoint profile.
    val progress: Float,
    val contourIndex: Int = 0,
    val isClosed: Boolean = false,
    val pressure: Float = 1.0f
)

/**
 * Stage 2 of the Unified 2D Rendering Pipeline: Sampler & Humanizer.
 * Decomposes any pure [android.graphics.Path] using [android.graphics.PathMeasure] at fixed pixel
 * intervals (default 2px) into uniform [BrushNode]s and injects subtle multi-harmonic sine/cosine
 * organic perturbations controlled by [handDrawnFactor] (0f..1f).
 */
class PathHumanizer(
    private val defaultStepPx: Float = DEFAULT_STEP_PX
) {

    fun humanize(
        path: Path,
        handDrawnFactor: Float = DEFAULT_HAND_DRAWN_FACTOR,
        stepPx: Float = defaultStepPx,
        seed: Int = 1337,
        timestampsMs: List<Long>? = null,
        pressures: List<Float>? = null
    ): List<BrushNode> = humanizePath(path, handDrawnFactor, stepPx, seed, timestampsMs, pressures = pressures)

    fun humanizeContours(
        path: Path,
        handDrawnFactor: Float = DEFAULT_HAND_DRAWN_FACTOR,
        stepPx: Float = defaultStepPx,
        seed: Int = 1337,
        timestampsMs: List<Long>? = null,
        pressures: List<Float>? = null
    ): List<List<BrushNode>> = humanizePathContours(path, handDrawnFactor, stepPx, seed, timestampsMs, pressures = pressures)

    companion object {
        const val DEFAULT_STEP_PX = 2f
        const val DEFAULT_HAND_DRAWN_FACTOR = 0.25f

        /**
         * Samples every contour of [path] at [stepPx] intervals (2px by default) and returns a flat
         * ordered [List] of [BrushNode]s separated cleanly by [BrushNode.contourIndex].
         */
        fun humanize(
            path: Path,
            handDrawnFactor: Float = DEFAULT_HAND_DRAWN_FACTOR,
            stepPx: Float = DEFAULT_STEP_PX,
            seed: Int = 1337,
            timestampsMs: List<Long>? = null,
            pressures: List<Float>? = null
        ): List<BrushNode> = humanizePath(path, handDrawnFactor, stepPx, seed, timestampsMs, pressures = pressures)

        fun humanizePath(
            path: Path,
            handDrawnFactor: Float = DEFAULT_HAND_DRAWN_FACTOR,
            stepPx: Float = DEFAULT_STEP_PX,
            seed: Int = 1337,
            timestampsMs: List<Long>? = null,
            stableOpenContours: Boolean = false,
            pressures: List<Float>? = null
        ): List<BrushNode> {
            val contours = humanizePathContours(
                path = path,
                handDrawnFactor = handDrawnFactor,
                stepPx = stepPx,
                seed = seed,
                timestampsMs = timestampsMs,
                stableOpenContours = stableOpenContours,
                pressures = pressures
            )
            if (contours.isEmpty()) return emptyList()
            val totalSize = contours.sumOf { it.size }
            val flat = ArrayList<BrushNode>(totalSize)
            contours.forEach { flat.addAll(it) }
            return flat
        }

        /**
         * Fast pure-Kotlin polyline sampler for open freehand [StrokePoint] lists (zero Path / PathMeasure JNI calls).
         */
        fun humanizeStrokePoints(
            points: List<StrokePoint>,
            handDrawnFactor: Float = 0f,
            stepPx: Float = DEFAULT_STEP_PX,
            seed: Int = 1337,
            stableOpenContours: Boolean = true
        ): List<BrushNode> {
            val ptCount = points.size
            if (ptCount == 0) return emptyList()
            if (ptCount == 1) {
                val p = points[0]
                val pr = p.pressure.coerceIn(0.15f, 1.25f)
                return listOf(
                    BrushNode(p.x, p.y, 0f, 0.35f, 0.5f, 0, false, pr),
                    BrushNode(p.x + 0.5f, p.y + 0.5f, 0.7853982f, 0.35f, 0.5f, 0, false, pr)
                )
            }

            val cumLen = FloatArray(ptCount)
            var totalLen = 0f
            for (i in 1 until ptCount) {
                val prev = points[i - 1]
                val cur = points[i]
                totalLen += hypot(cur.x - prev.x, cur.y - prev.y)
                cumLen[i] = totalLen
            }

            if (totalLen <= 0.1f) {
                val p = points[0]
                val pr = p.pressure.coerceIn(0.15f, 1.25f)
                return listOf(
                    BrushNode(p.x, p.y, 0f, 0.35f, 0.5f, 0, false, pr),
                    BrushNode(p.x + 0.5f, p.y + 0.5f, 0.7853982f, 0.35f, 0.5f, 0, false, pr)
                )
            }

            val safeStep = stepPx.coerceIn(1f, 32f)
            val endIndex = ceil((totalLen / safeStep).toDouble()).toInt().coerceAtLeast(2)
            val clampedFactor = handDrawnFactor.coerceIn(0f, 1f)
            val useOrganic = clampedFactor > 0.001f

            val phaseA = if (useOrganic) ((seed * 1103515245 + 12345) and 0xFFFF) / 65535f * (2f * PI.toFloat()) else 0f
            val phaseB = if (useOrganic) (((seed * 214013 + 2531011) ushr 8) and 0xFFFF) / 65535f * (2f * PI.toFloat()) else 0f
            val phaseC = if (useOrganic) (((seed * 1664525 + 1013904223) ushr 12) and 0xFFFF) / 65535f * (2f * PI.toFloat()) else 0f
            val maxNormalAmp = clampedFactor * 3.8f
            val maxTangentAmp = clampedFactor * 1.1f

            val firstTs = points[0].timestampMs
            val lastTs = points[ptCount - 1].timestampMs
            val hasTimestamps = lastTs > firstTs
            val avgDtMs = if (hasTimestamps) {
                (lastTs - firstTs).coerceAtLeast(1L).toFloat() / (ptCount - 1).coerceAtLeast(1)
            } else {
                0f
            }

            val rawX = FloatArray(endIndex + 1)
            val rawY = FloatArray(endIndex + 1)
            val baseAngles = FloatArray(endIndex + 1)
            val progresses = FloatArray(endIndex + 1)
            val nodePressures = FloatArray(endIndex + 1)
            val nodeSegmentIdx = IntArray(endIndex + 1)

            var segIdx = 0
            for (i in 0..endIndex) {
                val dist = if (i == endIndex) totalLen else (i * safeStep).coerceAtMost(totalLen)
                while (segIdx < ptCount - 2 && cumLen[segIdx + 1] < dist) {
                    segIdx++
                }
                nodeSegmentIdx[i] = segIdx

                val segStart = cumLen[segIdx]
                val segEnd = cumLen[segIdx + 1]
                val segLen = (segEnd - segStart)
                val t = if (segLen > 1e-4f) ((dist - segStart) / segLen).coerceIn(0f, 1f) else 0f

                val a = points[segIdx]
                val b = points[segIdx + 1]
                val px = a.x + (b.x - a.x) * t
                val py = a.y + (b.y - a.y) * t
                nodePressures[i] = (a.pressure + (b.pressure - a.pressure) * t).coerceIn(0.15f, 1.25f)

                val progress = if (stableOpenContours) {
                    0.5f
                } else {
                    val startEnv = (dist / 48f).coerceIn(0f, 1f)
                    val endEnv = ((totalLen - dist) / 48f).coerceIn(0f, 1f)
                    0.5f * min(startEnv, endEnv)
                }
                progresses[i] = progress

                val dx = b.x - a.x
                val dy = b.y - a.y
                val dLen = hypot(dx, dy).coerceAtLeast(0.0001f)
                val tx = dx / dLen
                val ty = dy / dLen
                baseAngles[i] = atan2(ty, tx)

                if (useOrganic) {
                    val nx = -ty
                    val ny = tx
                    val endpointEnvelope = if (stableOpenContours) 1f else sin(progress * PI.toFloat()).coerceIn(0f, 1f)
                    val normalWave = (
                        0.54f * sin(dist * 0.042f + phaseA) +
                            0.31f * cos(dist * 0.105f + phaseB) +
                            0.15f * sin(dist * 0.225f - phaseC)
                        ) * endpointEnvelope
                    val tangentWave = (
                        0.62f * cos(dist * 0.036f + phaseB) +
                            0.38f * sin(dist * 0.088f + phaseA)
                        ) * endpointEnvelope
                    rawX[i] = px + nx * (normalWave * maxNormalAmp) + tx * (tangentWave * maxTangentAmp)
                    rawY[i] = py + ny * (normalWave * maxNormalAmp) + ty * (tangentWave * maxTangentAmp)
                } else {
                    rawX[i] = px
                    rawY[i] = py
                }
            }

            val nodes = ArrayList<BrushNode>(endIndex + 1)
            for (i in 0..endIndex) {
                val prevIdx = (i - 1).coerceAtLeast(0)
                val nextIdx = (i + 1).coerceAtMost(endIndex)
                val dx = rawX[nextIdx] - rawX[prevIdx]
                val dy = rawY[nextIdx] - rawY[prevIdx]
                val dLen = hypot(dx, dy)
                val angle = if (dLen > 0.001f) atan2(dy, dx) else baseAngles[i]

                val v1x = rawX[i] - rawX[prevIdx]
                val v1y = rawY[i] - rawY[prevIdx]
                val v2x = rawX[nextIdx] - rawX[i]
                val v2y = rawY[nextIdx] - rawY[i]
                val l1 = hypot(v1x, v1y).coerceAtLeast(0.001f)
                val l2 = hypot(v2x, v2y).coerceAtLeast(0.001f)
                val cosTheta = ((v1x / l1) * (v2x / l2) + (v1y / l1) * (v2y / l2)).coerceIn(-1f, 1f)
                val straightness = ((cosTheta + 1f) * 0.5f).coerceIn(0f, 1f)
                val syntheticVelocity = (0.18f + 0.55f * (0.45f + 0.55f * straightness)).coerceIn(0.05f, 1f)

                val velocity = if (hasTimestamps) {
                    val sIdx = nodeSegmentIdx[i].coerceIn(0, ptCount - 2)
                    val dt = (points[sIdx + 1].timestampMs - points[sIdx].timestampMs).coerceAtLeast(1L).toFloat()
                    val speedRatio = (avgDtMs / dt).coerceIn(0.2f, 2.5f)
                    val pxPerMsFactor = (dLen / dt).coerceIn(0f, 3.5f) / 3.5f
                    val measured = 0.55f * pxPerMsFactor + 0.45f * (speedRatio / 2.5f)
                    (0.65f * measured + 0.35f * syntheticVelocity).coerceIn(0.05f, 1f)
                } else {
                    val endFactor = if (i == 0 || i == endIndex) 0.72f else 1f
                    (syntheticVelocity * endFactor).coerceIn(0.05f, 1f)
                }

                nodes.add(
                    BrushNode(
                        x = rawX[i],
                        y = rawY[i],
                        angle = angle,
                        velocity = velocity,
                        progress = progresses[i],
                        contourIndex = 0,
                        isClosed = false,
                        pressure = nodePressures[i]
                    )
                )
            }
            return nodes
        }

        fun humanizePathContours(
            path: Path,
            handDrawnFactor: Float = DEFAULT_HAND_DRAWN_FACTOR,
            stepPx: Float = DEFAULT_STEP_PX,
            seed: Int = 1337,
            timestampsMs: List<Long>? = null,
            stableOpenContours: Boolean = false,
            pressures: List<Float>? = null
        ): List<List<BrushNode>> {
            val clampedFactor = handDrawnFactor.coerceIn(0f, 1f)
            val safeStep = stepPx.coerceIn(1f, 32f)
            val pm = PathMeasure(path, false)
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            val result = mutableListOf<List<BrushNode>>()
            var contourIndex = 0

            val phaseA = ((seed * 1103515245 + 12345) and 0xFFFF) / 65535f * (2f * PI.toFloat())
            val phaseB = (((seed * 214013 + 2531011) ushr 8) and 0xFFFF) / 65535f * (2f * PI.toFloat())
            val phaseC = (((seed * 1664525 + 1013904223) ushr 12) and 0xFFFF) / 65535f * (2f * PI.toFloat())

            val avgTimestampDtMs: Float? = if (timestampsMs != null && timestampsMs.size >= 2) {
                val totalDt = (timestampsMs.last() - timestampsMs.first()).coerceAtLeast(1L).toFloat()
                totalDt / (timestampsMs.size - 1).coerceAtLeast(1)
            } else {
                null
            }

            do {
                val len = pm.length
                if (len <= 0.1f) continue
                val startPos = FloatArray(2)
                val endPos = FloatArray(2)
                val hasLoopEndpoints = len > 4f &&
                    pm.getPosTan(0f, startPos, null) &&
                    pm.getPosTan(len, endPos, null) &&
                    hypot(startPos[0] - endPos[0], startPos[1] - endPos[1]) < 0.75f
                val isClosed = pm.isClosed || hasLoopEndpoints

                // Sample from the contour origin at a fixed interval. Extending an open
                // stroke must not redistribute nodes that were already rendered.
                val rawSampleCount = ceil((len / safeStep).toDouble()).toInt().coerceAtLeast(2)
                val endIndex = rawSampleCount

                // Fixed amplitudes prevent earlier points from changing as total length grows.
                val maxNormalAmp = clampedFactor * 3.8f
                val maxTangentAmp = clampedFactor * 1.1f

                val rawX = FloatArray(endIndex + 1)
                val rawY = FloatArray(endIndex + 1)
                val baseAngles = FloatArray(endIndex + 1)
                val progresses = FloatArray(endIndex + 1)

                for (i in 0..endIndex) {
                    val distance = if (i == endIndex) len else (i * safeStep).coerceAtMost(len)
                    // Keep the live brush profile constant while its open path is growing.
                    val progress = when {
                        isClosed -> (distance / len).coerceIn(0f, 1f)
                        stableOpenContours -> 0.5f
                        else -> {
                            // Committed open strokes use a fixed endpoint profile.
                            val startEnvelope = (distance / 48f).coerceIn(0f, 1f)
                            val endEnvelope = ((len - distance) / 48f).coerceIn(0f, 1f)
                            0.5f * min(startEnvelope, endEnvelope)
                        }
                    }
                    progresses[i] = progress

                    if (!pm.getPosTan(distance, pos, tan)) {
                        rawX[i] = if (i > 0) rawX[i - 1] else 0f
                        rawY[i] = if (i > 0) rawY[i - 1] else 0f
                        baseAngles[i] = if (i > 0) baseAngles[i - 1] else 0f
                        continue
                    }

                    val tanLen = hypot(tan[0], tan[1]).coerceAtLeast(0.0001f)
                    val tx = tan[0] / tanLen
                    val ty = tan[1] / tanLen
                    val nx = -ty
                    val ny = tx
                    baseAngles[i] = atan2(ty, tx)

                    if (clampedFactor > 0.001f) {
                        val contourShift = contourIndex * 1.37f
                        val normalWave: Float
                        val tangentWave: Float

                        if (isClosed) {
                            // Closed loops use integer multiples of 2*PI*progress so t=0 and t=1 meet seamlessly.
                            val theta = 2f * PI.toFloat() * progress
                            normalWave =
                                0.54f * sin(theta * 3f + phaseA + contourShift) +
                                    0.31f * cos(theta * 5f + phaseB + contourShift) +
                                    0.15f * sin(theta * 9f - phaseC)
                            tangentWave =
                                0.62f * cos(theta * 2f + phaseB) +
                                    0.38f * sin(theta * 6f + phaseA)
                        } else {
                            // Open strokes taper smoothly near endpoints so anchor vertices & arrowheads stay aligned.
                            val dScaled = distance
                            val endpointEnvelope = if (stableOpenContours) 1f else sin(progress * PI.toFloat()).coerceIn(0f, 1f)
                            normalWave = (
                                0.54f * sin(dScaled * 0.042f + phaseA + contourShift) +
                                    0.31f * cos(dScaled * 0.105f + phaseB + contourShift) +
                                    0.15f * sin(dScaled * 0.225f - phaseC)
                                ) * endpointEnvelope
                            tangentWave = (
                                0.62f * cos(dScaled * 0.036f + phaseB) +
                                    0.38f * sin(dScaled * 0.088f + phaseA)
                                ) * endpointEnvelope
                        }

                        rawX[i] = pos[0] + nx * (normalWave * maxNormalAmp) + tx * (tangentWave * maxTangentAmp)
                        rawY[i] = pos[1] + ny * (normalWave * maxNormalAmp) + ty * (tangentWave * maxTangentAmp)
                    } else {
                        rawX[i] = pos[0]
                        rawY[i] = pos[1]
                    }
                }

                // Recompute tangent angles & velocities along the displaced organic trajectory
                val nodes = ArrayList<BrushNode>(endIndex + 1)
                for (i in 0..endIndex) {
                    val prevIdx = if (isClosed) (i - 1 + endIndex) % endIndex else (i - 1).coerceAtLeast(0)
                    val nextIdx = if (isClosed) (i + 1) % endIndex else (i + 1).coerceAtMost(endIndex)
                    val dx = rawX[nextIdx] - rawX[prevIdx]
                    val dy = rawY[nextIdx] - rawY[prevIdx]
                    val dLen = hypot(dx, dy)
                    val angle = if (dLen > 0.001f) atan2(dy, dx) else baseAngles[i]

                    val velocity = computeNodeVelocity(
                        index = i,
                        totalCount = endIndex,
                        progress = progresses[i],
                        dx = dx,
                        dy = dy,
                        prevX = rawX[prevIdx],
                        prevY = rawY[prevIdx],
                        curX = rawX[i],
                        curY = rawY[i],
                        nextX = rawX[nextIdx],
                        nextY = rawY[nextIdx],
                        timestampsMs = timestampsMs,
                        avgTimestampDtMs = avgTimestampDtMs
                    )

                    val nodePressure = if (pressures != null && pressures.isNotEmpty()) {
                        val t = (i.toFloat() / endIndex.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) *
                            pressures.lastIndex
                        val idx0 = t.toInt().coerceIn(0, pressures.lastIndex)
                        val idx1 = (idx0 + 1).coerceAtMost(pressures.lastIndex)
                        val frac = (t - idx0).coerceIn(0f, 1f)
                        (pressures[idx0] + (pressures[idx1] - pressures[idx0]) * frac).coerceIn(0.15f, 1.25f)
                    } else {
                        1.0f
                    }

                    nodes.add(
                        BrushNode(
                            x = rawX[i],
                            y = rawY[i],
                            angle = angle,
                            velocity = velocity,
                            progress = progresses[i],
                            contourIndex = contourIndex,
                            isClosed = isClosed,
                            pressure = nodePressure
                        )
                    )
                }

                if (nodes.size >= 2) {
                    result.add(nodes)
                    contourIndex++
                }
            } while (pm.nextContour())

            return result
        }

        private fun computeNodeVelocity(
            index: Int,
            totalCount: Int,
            progress: Float,
            dx: Float,
            dy: Float,
            prevX: Float,
            prevY: Float,
            curX: Float,
            curY: Float,
            nextX: Float,
            nextY: Float,
            timestampsMs: List<Long>?,
            avgTimestampDtMs: Float?
        ): Float {
            // Curvature-based corner deceleration: human hands slow at sharp rectangle/polygon corners
            val v1x = curX - prevX
            val v1y = curY - prevY
            val v2x = nextX - curX
            val v2y = nextY - curY
            val l1 = hypot(v1x, v1y).coerceAtLeast(0.001f)
            val l2 = hypot(v2x, v2y).coerceAtLeast(0.001f)
            val cosTheta = ((v1x / l1) * (v2x / l2) + (v1y / l1) * (v2y / l2)).coerceIn(-1f, 1f)
            val straightness = ((cosTheta + 1f) * 0.5f).coerceIn(0f, 1f)

            // Velocity depends on local geometry only; total stroke length is not stable
            // while the user is still drawing.
            val syntheticVelocity = (0.18f + 0.55f * (0.45f + 0.55f * straightness))
                .coerceIn(0.05f, 1f)

            if (timestampsMs != null && timestampsMs.size >= 2 && avgTimestampDtMs != null) {
                val mappedProgress = (index.toFloat() / totalCount.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                val mappedIdx = (mappedProgress * timestampsMs.lastIndex).toInt().coerceIn(1, timestampsMs.lastIndex)
                val dt = (timestampsMs[mappedIdx] - timestampsMs[mappedIdx - 1]).coerceAtLeast(1L).toFloat()
                val speedRatio = (avgTimestampDtMs / dt).coerceIn(0.2f, 2.5f)
                val pxPerMsFactor = (hypot(dx, dy) / dt).coerceIn(0f, 3.5f) / 3.5f
                val measured = (0.55f * pxPerMsFactor + 0.45f * (speedRatio / 2.5f))
                return (0.65f * measured + 0.35f * syntheticVelocity).coerceIn(0.05f, 1f)
            }

            // Guarantee endpoints feel deliberate at start (index=0) and release (index=totalCount)
            val endFactor = if (index == 0 || index == totalCount) 0.72f else 1f
            return (syntheticVelocity * endFactor).coerceIn(0.05f, 1f)
        }
    }
}
