package com.example.ink

import kotlin.math.abs

/**
 * Pure World-Space <-> Screen-Space camera and viewport transform.
 *
 * All board elements (strokes, shapes, basic text items) live in an
 * isotropic, resolution-independent World Space measured in logical canvas units:
 *   screenX = worldX * scale + panX
 *   screenY = worldY * scale + panY
 *   screenSize = worldSize * scale
 */
data class ViewportTransform(
    val scale: Float = 1.0f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val viewportWidthPx: Float = 1080f,
    val viewportHeightPx: Float = 1920f
) {
    private val safeScale: Float
        get() = scale.coerceAtLeast(MIN_SCALE)

    fun worldToScreenX(worldX: Float): Float = worldX * safeScale + panX

    fun worldToScreenY(worldY: Float): Float = worldY * safeScale + panY

    fun worldLengthToScreenPx(worldLength: Float): Float = worldLength * safeScale

    fun screenToWorldX(screenX: Float): Float = (screenX - panX) / safeScale

    fun screenToWorldY(screenY: Float): Float = (screenY - panY) / safeScale

    fun screenDeltaToWorld(deltaScreenPx: Float): Float = deltaScreenPx / safeScale

    fun adaptToViewportResize(newWidthPx: Float, newHeightPx: Float): ViewportTransform {
        val cleanW = newWidthPx.coerceAtLeast(1f)
        val cleanH = newHeightPx.coerceAtLeast(1f)
        if (abs(cleanW - viewportWidthPx) < 0.5f && abs(cleanH - viewportHeightPx) < 0.5f) {
            return this
        }
        val dPanX = (cleanW - viewportWidthPx) * 0.5f
        val dPanY = (cleanH - viewportHeightPx) * 0.5f
        return copy(
            panX = panX + dPanX,
            panY = panY + dPanY,
            viewportWidthPx = cleanW,
            viewportHeightPx = cleanH
        )
    }

    fun panAndZoom(
        deltaPanX: Float,
        deltaPanY: Float,
        zoomChange: Float,
        focalX: Float? = null,
        focalY: Float? = null
    ): ViewportTransform {
        val nextScale = (safeScale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        return if (focalX != null && focalY != null) {
            val worldFocalX = (focalX - panX) / safeScale
            val worldFocalY = (focalY - panY) / safeScale
            copy(
                scale = nextScale,
                panX = focalX - worldFocalX * nextScale + deltaPanX,
                panY = focalY - worldFocalY * nextScale + deltaPanY
            )
        } else {
            copy(
                scale = nextScale,
                panX = panX + deltaPanX,
                panY = panY + deltaPanY
            )
        }
    }

    companion object {
        const val MIN_SCALE = 0.20f
        const val MAX_SCALE = 5.0f
    }
}

enum class BoxResizeHandle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT
}

data class WorldBoxBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

/**
 * 4-vertex world-space resize and rotation math shared by basic text items and shapes.
 */
object BoxTransformGeometry {
    const val MIN_SIZE_WORLD = 96f
    const val MAX_SIZE_WORLD = 2400f

    fun resizeByCornerHandle(
        bounds: WorldBoxBounds,
        handle: BoxResizeHandle,
        deltaScreenX: Float,
        deltaScreenY: Float,
        scale: Float,
        rotationDegrees: Float = 0f,
        minWidthWorld: Float = MIN_SIZE_WORLD,
        minHeightWorld: Float = 52f
    ): WorldBoxBounds {
        val safeScale = scale.coerceAtLeast(ViewportTransform.MIN_SCALE)
        val dxWorld = deltaScreenX / safeScale
        val dyWorld = deltaScreenY / safeScale

        val normalizedRot = ((rotationDegrees % 360f) + 360f) % 360f
        val localDx: Float
        val localDy: Float
        val hasRotation = normalizedRot != 0f
        if (hasRotation) {
            val rad = -normalizedRot * (kotlin.math.PI.toFloat() / 180f)
            val cosA = kotlin.math.cos(rad)
            val sinA = kotlin.math.sin(rad)
            localDx = dxWorld * cosA - dyWorld * sinA
            localDy = dxWorld * sinA + dyWorld * cosA
        } else {
            localDx = dxWorld
            localDy = dyWorld
        }

        val origLeft = bounds.x
        val origTop = bounds.y
        val origRight = bounds.x + bounds.width
        val origBottom = bounds.y + bounds.height

        var left = origLeft
        var top = origTop
        var right = origRight
        var bottom = origBottom

        when (handle) {
            BoxResizeHandle.TOP_LEFT -> {
                left += localDx
                top += localDy
            }
            BoxResizeHandle.TOP_RIGHT -> {
                right += localDx
                top += localDy
            }
            BoxResizeHandle.BOTTOM_RIGHT -> {
                right += localDx
                bottom += localDy
            }
            BoxResizeHandle.BOTTOM_LEFT -> {
                left += localDx
                bottom += localDy
            }
        }

        val touchesLeft = handle == BoxResizeHandle.TOP_LEFT || handle == BoxResizeHandle.BOTTOM_LEFT
        val touchesTop = handle == BoxResizeHandle.TOP_LEFT || handle == BoxResizeHandle.TOP_RIGHT

        val finalW = (right - left).coerceIn(minWidthWorld, MAX_SIZE_WORLD)
        val finalH = (bottom - top).coerceIn(minHeightWorld, MAX_SIZE_WORLD)
        val unrotatedX = if (touchesLeft) origRight - finalW else origLeft
        val unrotatedY = if (touchesTop) origBottom - finalH else origTop

        if (!hasRotation) {
            return WorldBoxBounds(unrotatedX, unrotatedY, finalW, finalH)
        }

        val oldCx = origLeft + bounds.width * 0.5f
        val oldCy = origTop + bounds.height * 0.5f
        val localShiftX = (unrotatedX + finalW * 0.5f) - oldCx
        val localShiftY = (unrotatedY + finalH * 0.5f) - oldCy

        val invRad = normalizedRot * (kotlin.math.PI.toFloat() / 180f)
        val cosInv = kotlin.math.cos(invRad)
        val sinInv = kotlin.math.sin(invRad)
        val worldShiftX = localShiftX * cosInv - localShiftY * sinInv
        val worldShiftY = localShiftX * sinInv + localShiftY * cosInv

        val finalCx = oldCx + worldShiftX
        val finalCy = oldCy + worldShiftY
        return WorldBoxBounds(
            x = finalCx - finalW * 0.5f,
            y = finalCy - finalH * 0.5f,
            width = finalW,
            height = finalH
        )
    }

    fun toLocalUnrotated(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        rotationDegrees: Float,
        worldX: Float,
        worldY: Float
    ): Pair<Float, Float> {
        if (rotationDegrees == 0f) return worldX to worldY
        val centerX = x + width * 0.5f
        val centerY = y + height * 0.5f
        val radians = -rotationDegrees * (kotlin.math.PI.toFloat() / 180f)
        val dx = worldX - centerX
        val dy = worldY - centerY
        return (centerX + dx * kotlin.math.cos(radians) - dy * kotlin.math.sin(radians)) to
            (centerY + dx * kotlin.math.sin(radians) + dy * kotlin.math.cos(radians))
    }

    fun calculateRotationDegrees(
        centerX: Float,
        centerY: Float,
        pointerX: Float,
        pointerY: Float,
        snapThresholdDegrees: Float = 3f
    ): Float {
        val dx = (pointerX - centerX).toDouble()
        val dy = (pointerY - centerY).toDouble()
        val rawAngleRad = kotlin.math.atan2(dy, dx)
        val rawAngleDeg = Math.toDegrees(rawAngleRad).toFloat()
        val unadjusted = rawAngleDeg + 90f
        val normalized = ((unadjusted % 360f) + 360f) % 360f
        return snapAngle(normalized, snapThresholdDegrees)
    }

    fun snapAngle(angle: Float, threshold: Float = 3f): Float {
        val norm = ((angle % 360f) + 360f) % 360f
        val snapTargets = floatArrayOf(0f, 90f, 180f, 270f, 360f)
        for (target in snapTargets) {
            if (abs(norm - target) <= threshold) {
                return if (target == 360f) 0f else target
            }
        }
        return norm
    }
}
