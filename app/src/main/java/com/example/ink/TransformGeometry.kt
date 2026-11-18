package com.example.ink

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class TransformState(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotationAngle: Float
) {
    val centerX: Float get() = x + width * 0.5f
    val centerY: Float get() = y + height * 0.5f

    fun normalized(
        minSize: Float = StickyNoteGeometry.MIN_SIZE_WORLD,
        maxSize: Float = StickyNoteGeometry.MAX_SIZE_WORLD
    ): TransformState = copy(
        width = width.coerceIn(minSize, maxSize),
        height = height.coerceIn(minSize, maxSize),
        rotationAngle = normalizeAngle(rotationAngle)
    )
}

data class TransformPoint(val x: Float, val y: Float)

object TransformGeometry {
    fun localToWorld(state: TransformState, localX: Float, localY: Float): TransformPoint {
        val radians = Math.toRadians(state.rotationAngle.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return TransformPoint(
            state.centerX + localX * c - localY * s,
            state.centerY + localX * s + localY * c
        )
    }

    fun worldToLocal(state: TransformState, worldX: Float, worldY: Float): TransformPoint {
        val radians = Math.toRadians(-state.rotationAngle.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val dx = worldX - state.centerX
        val dy = worldY - state.centerY
        return TransformPoint(dx * c - dy * s, dx * s + dy * c)
    }

    fun resize(
        state: TransformState,
        handle: StickyNoteResizeHandle,
        deltaScreenX: Float,
        deltaScreenY: Float,
        cameraScale: Float,
        lockAspectRatio: Boolean = false,
        aspectRatio: Float = state.width / state.height,
        minSize: Float = StickyNoteGeometry.MIN_SIZE_WORLD,
        maxSize: Float = StickyNoteGeometry.MAX_SIZE_WORLD
    ): TransformState {
        val safeScale = cameraScale.coerceAtLeast(ViewportTransform.MIN_SCALE)
        val radians = Math.toRadians(-state.rotationAngle.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val dx = deltaScreenX / safeScale
        val dy = deltaScreenY / safeScale
        val localDx = dx * c - dy * s
        val localDy = dx * s + dy * c

        val horizontal = when (handle) {
            StickyNoteResizeHandle.TOP_LEFT,
            StickyNoteResizeHandle.LEFT,
            StickyNoteResizeHandle.BOTTOM_LEFT -> -1
            StickyNoteResizeHandle.TOP_RIGHT,
            StickyNoteResizeHandle.RIGHT,
            StickyNoteResizeHandle.BOTTOM_RIGHT -> 1
            else -> 0
        }
        val vertical = when (handle) {
            StickyNoteResizeHandle.TOP_LEFT,
            StickyNoteResizeHandle.TOP,
            StickyNoteResizeHandle.TOP_RIGHT -> -1
            StickyNoteResizeHandle.BOTTOM_LEFT,
            StickyNoteResizeHandle.BOTTOM,
            StickyNoteResizeHandle.BOTTOM_RIGHT -> 1
            else -> 0
        }

        var newWidth = if (horizontal == 0) state.width else {
            (state.width + horizontal * localDx).coerceIn(minSize, maxSize)
        }
        var newHeight = if (vertical == 0) state.height else {
            (state.height + vertical * localDy).coerceIn(minSize, maxSize)
        }

        if (lockAspectRatio) {
            val ratio = aspectRatio.coerceIn(0.25f, 4f)
            val widthFactor = newWidth / state.width.coerceAtLeast(1f)
            val heightFactor = newHeight / state.height.coerceAtLeast(1f)
            val rawFactor = when {
                horizontal != 0 && vertical != 0 ->
                    if (abs(widthFactor - 1f) >= abs(heightFactor - 1f)) widthFactor else heightFactor
                horizontal != 0 -> widthFactor
                vertical != 0 -> heightFactor
                else -> 1f
            }
            val minFactor = max(minSize / state.width, minSize / state.height)
            val maxFactor = min(maxSize / state.width, maxSize / state.height)
            val factor = rawFactor.coerceIn(minFactor, maxFactor)
            newWidth = state.width * factor
            newHeight = state.height * factor
            if (abs(newWidth / newHeight - ratio) > 0.001f) {
                newWidth = (newHeight * ratio).coerceIn(minSize, maxSize)
                newHeight = (newWidth / ratio).coerceIn(minSize, maxSize)
            }
        }

        val pivotLocalX = if (horizontal == 0) 0f else -horizontal * state.width * 0.5f
        val pivotLocalY = if (vertical == 0) 0f else -vertical * state.height * 0.5f
        val pivotWorld = localToWorld(state, pivotLocalX, pivotLocalY)
        val newCenterOffset = rotate(
            horizontal * newWidth * 0.5f,
            vertical * newHeight * 0.5f,
            state.rotationAngle
        )
        val newCenterX = pivotWorld.x + newCenterOffset.x
        val newCenterY = pivotWorld.y + newCenterOffset.y

        return state.copy(
            x = newCenterX - newWidth * 0.5f,
            y = newCenterY - newHeight * 0.5f,
            width = newWidth,
            height = newHeight
        )
    }

    fun transformByGesture(
        state: TransformState,
        previousCentroidScreen: TransformPoint,
        currentCentroidScreen: TransformPoint,
        zoomChange: Float,
        rotationDeltaDegrees: Float,
        camera: ViewportTransform,
        minSize: Float = StickyNoteGeometry.MIN_SIZE_WORLD,
        maxSize: Float = StickyNoteGeometry.MAX_SIZE_WORLD
    ): TransformState {
        val previousWorldX = camera.screenToWorldX(previousCentroidScreen.x)
        val previousWorldY = camera.screenToWorldY(previousCentroidScreen.y)
        val currentWorldX = camera.screenToWorldX(currentCentroidScreen.x)
        val currentWorldY = camera.screenToWorldY(currentCentroidScreen.y)
        val anchorLocal = worldToLocal(state, previousWorldX, previousWorldY)

        val minFactor = max(minSize / state.width, minSize / state.height)
        val maxFactor = min(maxSize / state.width, maxSize / state.height)
        val factor = zoomChange.coerceAtLeast(0.01f).coerceIn(minFactor, maxFactor)
        val width = state.width * factor
        val height = state.height * factor
        val angle = normalizeAngle(state.rotationAngle + rotationDeltaDegrees)
        val scaledAnchor = rotate(anchorLocal.x * factor, anchorLocal.y * factor, angle)
        val centerX = currentWorldX - scaledAnchor.x
        val centerY = currentWorldY - scaledAnchor.y

        return state.copy(
            x = centerX - width * 0.5f,
            y = centerY - height * 0.5f,
            width = width,
            height = height,
            rotationAngle = angle
        )
    }

    fun normalizeAngle(angle: Float): Float = ((angle % 360f) + 360f) % 360f

    private fun rotate(x: Float, y: Float, angleDegrees: Float): TransformPoint {
        val radians = Math.toRadians(angleDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return TransformPoint(x * c - y * s, x * s + y * c)
    }
}
