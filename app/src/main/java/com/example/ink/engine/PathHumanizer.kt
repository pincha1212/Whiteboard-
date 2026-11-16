package com.example.ink.engine

import android.graphics.Path
import android.graphics.PathMeasure
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
 * @property progress Normalized contour progress from `0f` (start) to `1f` (end).
 * @property contourIndex Zero-based index identifying contiguous contour segments in multi-contour [Path]s.
 * @property isClosed True if this contour originated from a closed geometric loop.
 */
data class BrushNode(
    val x: Float,
    val y: Float,
    val angle: Float,
    val velocity: Float,
    val progress: Float,
    val contourIndex: Int = 0,
    val isClosed: Boolean = false
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
        timestampsMs: List<Long>? = null
    ): List<BrushNode> = humanizePath(path, handDrawnFactor, stepPx, seed, timestampsMs)

    fun humanizeContours(
        path: Path,
        handDrawnFactor: Float = DEFAULT_HAND_DRAWN_FACTOR,
        stepPx: Float = defaultStepPx,
        seed: Int = 1337,
        timestampsMs: List<Long>? = null
    ): List<List<BrushNode>> = humanizePathContours(path, handDrawnFactor, stepPx, seed, timestampsMs)

    companion object {
        const val DEFAULT_STEP_PX = 4f
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
            timestampsMs: List<Long>? = null
        ): List<BrushNode> = humanizePath(path, handDrawnFactor, stepPx, seed, timestampsMs)

        fun humanizePath(
            path: Path,
            handDrawnFactor: Float = DEFAULT_HAND_DRAWN_FACTOR,
            stepPx: Float = DEFAULT_STEP_PX,
            seed: Int = 1337,
            timestampsMs: List<Long>? = null
        ): List<BrushNode> {
            val contours = humanizePathContours(path, handDrawnFactor, stepPx, seed, timestampsMs)
            if (contours.isEmpty()) return emptyList()
            val totalSize = contours.sumOf { it.size }
            val flat = ArrayList<BrushNode>(totalSize)
            contours.forEach { flat.addAll(it) }
            return flat
        }

        fun humanizePathContours(
            path: Path,
            handDrawnFactor: Float = DEFAULT_HAND_DRAWN_FACTOR,
            stepPx: Float = DEFAULT_STEP_PX,
            seed: Int = 1337,
            timestampsMs: List<Long>? = null
        ): List<List<BrushNode>> {
            val clampedFactor = handDrawnFactor.coerceIn(0f, 1f)
            val safeStep = stepPx.coerceAtLeast(1f)
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

                // Adaptive sampling step based on handDrawnFactor and length to avoid high-density oversampling on low-end hardware
                val adaptiveStep = when {
                    clampedFactor <= 0.05f -> (safeStep * 2.2f).coerceIn(8f, 18f)
                    len > 800f -> (safeStep * 1.75f).coerceIn(6f, 14f)
                    len > 300f -> (safeStep * 1.4f).coerceIn(5f, 10f)
                    else -> safeStep.coerceIn(4f, 8f)
                }
                val rawSampleCount = ceil((len / adaptiveStep).toDouble()).toInt().coerceIn(2, 600)
                val endIndex = rawSampleCount

                // Keep wobble proportional on tiny detail fragments (dots/arrow wings) while expressive on real shapes.
                val lengthScale = (len / 70f).coerceIn(0.28f, 1.35f)
                val maxNormalAmp = clampedFactor * 3.8f * lengthScale
                val maxTangentAmp = clampedFactor * 1.1f * lengthScale

                val rawX = FloatArray(endIndex + 1)
                val rawY = FloatArray(endIndex + 1)
                val baseAngles = FloatArray(endIndex + 1)
                val progresses = FloatArray(endIndex + 1)

                for (i in 0..endIndex) {
                    val progress = (i.toFloat() / endIndex.toFloat()).coerceIn(0f, 1f)
                    val distance = (progress * len).coerceIn(0f, len)
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
                            val endpointEnvelope = 0.18f + 0.82f * sin(progress * PI.toFloat())
                            val dScaled = distance
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

                    nodes.add(
                        BrushNode(
                            x = rawX[i],
                            y = rawY[i],
                            angle = angle,
                            velocity = velocity,
                            progress = progresses[i],
                            contourIndex = contourIndex,
                            isClosed = isClosed
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

            val arcEnvelope = sin(progress * PI.toFloat()).coerceIn(0f, 1f)
            val syntheticVelocity = (0.18f + 0.55f * arcEnvelope * (0.45f + 0.55f * straightness))
                .coerceIn(0.05f, 1f)

            if (timestampsMs != null && timestampsMs.size >= 2 && avgTimestampDtMs != null) {
                val mappedIdx = ((progress * (timestampsMs.lastIndex)).toInt()).coerceIn(1, timestampsMs.lastIndex)
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
