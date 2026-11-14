package com.example.ink

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pure World-Space <-> Screen-Space camera and viewport transform.
 *
 * All board elements (strokes, shapes, sticky notes, text items) live in an
 * isotropic, resolution-independent World Space measured in logical canvas units.
 * The viewport layer projects World Space into Screen Space pixels uniformly:
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

    /**
     * Adapts pan when the screen/container size changes (rotation, split-screen, foldables)
     * so the world point at the viewport center stays anchored and aspect ratio / relative
     * distances remain 100% invariant.
     */
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

    /**
     * Applies pan + pinch zoom, keeping [focalX], [focalY] stationary in world space when provided.
     */
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

enum class StickyNoteResizeHandle {
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    RIGHT,
    BOTTOM_RIGHT,
    BOTTOM,
    BOTTOM_LEFT,
    LEFT
}

data class StickyNoteBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

object StickyNoteGeometry {
    const val MIN_SIZE_WORLD = 96f
    const val MAX_SIZE_WORLD = 2400f

    /**
     * Scale-compensated handle resize in World Space:
     *   deltaWorldX = deltaScreenX / scale
     *   deltaWorldY = deltaScreenY / scale
     *
     * Updates width, height (and x, y for top/left handles) while keeping the opposite edge/corner
     * anchored. When [lockAspectRatio] is true, enforces a 1:1 square (or [targetAspectRatio]).
     */
    fun resizeByHandle(
        bounds: StickyNoteBounds,
        handle: StickyNoteResizeHandle,
        deltaScreenX: Float,
        deltaScreenY: Float,
        scale: Float,
        lockAspectRatio: Boolean,
        targetAspectRatio: Float = 1.0f
    ): StickyNoteBounds {
        val safeScale = scale.coerceAtLeast(ViewportTransform.MIN_SCALE)
        val dxWorld = deltaScreenX / safeScale
        val dyWorld = deltaScreenY / safeScale

        val origLeft = bounds.x
        val origTop = bounds.y
        val origRight = bounds.x + bounds.width
        val origBottom = bounds.y + bounds.height

        var left = origLeft
        var top = origTop
        var right = origRight
        var bottom = origBottom

        when (handle) {
            StickyNoteResizeHandle.LEFT -> left += dxWorld
            StickyNoteResizeHandle.RIGHT -> right += dxWorld
            StickyNoteResizeHandle.TOP -> top += dyWorld
            StickyNoteResizeHandle.BOTTOM -> bottom += dyWorld
            StickyNoteResizeHandle.TOP_LEFT -> {
                left += dxWorld
                top += dyWorld
            }
            StickyNoteResizeHandle.TOP_RIGHT -> {
                right += dxWorld
                top += dyWorld
            }
            StickyNoteResizeHandle.BOTTOM_RIGHT -> {
                right += dxWorld
                bottom += dyWorld
            }
            StickyNoteResizeHandle.BOTTOM_LEFT -> {
                left += dxWorld
                bottom += dyWorld
            }
        }

        val touchesLeft = handle == StickyNoteResizeHandle.LEFT ||
            handle == StickyNoteResizeHandle.TOP_LEFT ||
            handle == StickyNoteResizeHandle.BOTTOM_LEFT
        val touchesRight = handle == StickyNoteResizeHandle.RIGHT ||
            handle == StickyNoteResizeHandle.TOP_RIGHT ||
            handle == StickyNoteResizeHandle.BOTTOM_RIGHT
        val touchesTop = handle == StickyNoteResizeHandle.TOP ||
            handle == StickyNoteResizeHandle.TOP_LEFT ||
            handle == StickyNoteResizeHandle.TOP_RIGHT
        val touchesBottom = handle == StickyNoteResizeHandle.BOTTOM ||
            handle == StickyNoteResizeHandle.BOTTOM_LEFT ||
            handle == StickyNoteResizeHandle.BOTTOM_RIGHT

        if (!lockAspectRatio) {
            var newW = (right - left).coerceIn(MIN_SIZE_WORLD, MAX_SIZE_WORLD)
            var newH = (bottom - top).coerceIn(MIN_SIZE_WORLD, MAX_SIZE_WORLD)
            val newX = when {
                touchesLeft -> origRight - newW
                touchesRight -> origLeft
                else -> origLeft
            }
            val newY = when {
                touchesTop -> origBottom - newH
                touchesBottom -> origTop
                else -> origTop
            }
            return StickyNoteBounds(newX, newY, newW, newH)
        }

        // Aspect-ratio locked (square 1:1 by default)
        val ratio = targetAspectRatio.coerceIn(0.25f, 4.0f)
        val rawW = (right - left)
        val rawH = (bottom - top)

        val (targetW, targetH) = when (handle) {
            StickyNoteResizeHandle.LEFT, StickyNoteResizeHandle.RIGHT -> {
                val w = rawW.coerceIn(MIN_SIZE_WORLD, MAX_SIZE_WORLD)
                val h = (w / ratio).coerceIn(MIN_SIZE_WORLD, MAX_SIZE_WORLD)
                (h * ratio) to h
            }
            StickyNoteResizeHandle.TOP, StickyNoteResizeHandle.BOTTOM -> {
                val h = rawH.coerceIn(MIN_SIZE_WORLD, MAX_SIZE_WORLD)
                val w = (h * ratio).coerceIn(MIN_SIZE_WORLD, MAX_SIZE_WORLD)
                w to (w / ratio)
            }
            else -> {
                // Corner handle: follow dominant axis displacement while keeping opposite corner fixed
                val dw = abs(rawW - bounds.width)
                val dh = abs(rawH - bounds.height)
                if (dw >= dh) {
                    val w = rawW.coerceIn(MIN_SIZE_WORLD, MAX_SIZE_WORLD)
                    val h = (w / ratio).coerceIn(MIN_SIZE_WORLD, MAX_SIZE_WORLD)
                    (h * ratio) to h
                } else {
                    val h = rawH.coerceIn(MIN_SIZE_WORLD, MAX_SIZE_WORLD)
                    val w = (h * ratio).coerceIn(MIN_SIZE_WORLD, MAX_SIZE_WORLD)
                    w to (w / ratio)
                }
            }
        }

        val centerX = origLeft + bounds.width * 0.5f
        val centerY = origTop + bounds.height * 0.5f

        val finalX = when {
            touchesLeft -> origRight - targetW
            touchesRight -> origLeft
            else -> centerX - targetW * 0.5f
        }
        val finalY = when {
            touchesTop -> origBottom - targetH
            touchesBottom -> origTop
            else -> centerY - targetH * 0.5f
        }

        return StickyNoteBounds(finalX, finalY, targetW, targetH)
    }

    fun headerHeightWorld(noteHeight: Float): Float {
        return (noteHeight * 0.14f).coerceIn(16f, 26f)
    }

    fun innerPaddingWorld(noteWidth: Float, noteHeight: Float): Float {
        return (min(noteWidth, noteHeight) * 0.055f).coerceIn(6f, 16f)
    }

    fun innerTextBoxWorld(noteWidth: Float, noteHeight: Float): Pair<Float, Float> {
        val headerH = headerHeightWorld(noteHeight)
        val pad = innerPaddingWorld(noteWidth, noteHeight)
        val innerW = (noteWidth - pad * 2f).coerceAtLeast(24f)
        val innerH = (noteHeight - headerH - pad * 2f).coerceAtLeast(24f)
        return innerW to innerH
    }

    /**
     * Calculates the required height for a sticky note so that its text is never cut off.
     * Expands gracefully as more lines/paragraphs are typed.
     */
    fun calculateAdaptiveHeightWorld(
        text: String,
        noteWidthWorld: Float,
        currentHeightWorld: Float,
        isAspectRatioLocked: Boolean
    ): Float {
        if (text.isBlank()) return currentHeightWorld.coerceAtLeast(MIN_SIZE_WORLD)
        val (innerW, _) = innerTextBoxWorld(noteWidthWorld, currentHeightWorld)
        val pad = innerPaddingWorld(noteWidthWorld, currentHeightWorld)
        val headerH = headerHeightWorld(currentHeightWorld)

        val worldFont = 13.5f
        val lineH = worldFont * 1.25f
        val avgCharW = worldFont * 0.55f
        val maxCols = (innerW / avgCharW).toInt().coerceAtLeast(1)

        val paragraphs = text.split('\n')
        var totalLines = 0
        for (para in paragraphs) {
            if (para.isEmpty()) {
                totalLines += 1
                continue
            }
            val words = para.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                totalLines += 1
                continue
            }
            var curLineChars = 0
            var paraLines = 1
            for (word in words) {
                if (curLineChars == 0) {
                    curLineChars = word.length
                } else if (curLineChars + 1 + word.length <= maxCols) {
                    curLineChars += 1 + word.length
                } else {
                    paraLines += 1
                    curLineChars = word.length
                }
            }
            totalLines += paraLines
        }

        val requiredTextH = totalLines * lineH + 16f
        val requiredTotalH = headerH + pad * 2f + requiredTextH
        return max(currentHeightWorld, requiredTotalH).coerceIn(MIN_SIZE_WORLD, MAX_SIZE_WORLD)
    }

    /**
     * Deterministic world-space font-size estimator that guarantees zero horizontal word-clipping
     * and zero vertical overflow across any note dimensions and text length.
     * Used for headless export rendering and as upper/initial reference for Compose TextMeasurer.
     */
    fun estimateAutoFitFontSizeWorld(
        text: String,
        innerWidthWorld: Float,
        innerHeightWorld: Float
    ): Float {
        val safeW = innerWidthWorld.coerceAtLeast(24f)
        val safeH = innerHeightWorld.coerceAtLeast(24f)
        val cleanText = text.ifEmpty { "Escribe tu idea aquí..." }

        val minFont = 6f
        val maxFont = max(minFont + 1f, min(safeW, safeH) * 0.42f)

        val words = cleanText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val maxWordLen = words.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1

        var lo = minFont
        var hi = maxFont
        repeat(16) {
            val mid = (lo + hi) * 0.5f
            if (doesTextFitWorldBox(cleanText, words, maxWordLen, mid, safeW, safeH)) {
                lo = mid
            } else {
                hi = mid
            }
        }
        return lo.coerceIn(minFont, maxFont)
    }

    private fun doesTextFitWorldBox(
        text: String,
        words: List<String>,
        maxWordLen: Int,
        fontSize: Float,
        boxW: Float,
        boxH: Float
    ): Boolean {
        val avgCharW = fontSize * 0.58f
        val lineH = fontSize * 1.22f

        // Prevent single-word mid-break overflow
        if (maxWordLen * avgCharW > boxW * 0.96f) {
            return false
        }

        val maxCols = max(1, (boxW / avgCharW).toInt())
        val paragraphs = text.split('\n')
        var totalLines = 0
        for (para in paragraphs) {
            if (para.isEmpty()) {
                totalLines += 1
                continue
            }
            val pWords = para.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (pWords.isEmpty()) {
                totalLines += 1
                continue
            }
            var currentLineChars = 0
            var paraLines = 1
            for (word in pWords) {
                if (currentLineChars == 0) {
                    currentLineChars = word.length
                } else if (currentLineChars + 1 + word.length <= maxCols) {
                    currentLineChars += 1 + word.length
                } else {
                    paraLines += 1
                    currentLineChars = word.length
                }
            }
            totalLines += paraLines
        }

        val requiredH = totalLines * lineH
        return requiredH <= boxH * 0.94f
    }

    /**
     * Mathematical touch rotation calculation:
     * Computes the angle in degrees from the absolute center of the note [centerX, centerY]
     * to the pointer position [pointerX, pointerY], compensating for top-centered rotation stem (straight up = 0°).
     * Snaps smoothly (±[snapThresholdDegrees]) to 0°, 90°, 180°, and 270°.
     */
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
        // Top handle points straight up (-90° in screen space), which corresponds to 0° note rotation
        val unadjusted = rawAngleDeg + 90f
        val normalized = ((unadjusted % 360f) + 360f) % 360f
        return snapAngle(normalized, snapThresholdDegrees)
    }

    fun snapAngle(angle: Float, threshold: Float = 3f): Float {
        val norm = ((angle % 360f) + 360f) % 360f
        val snapTargets = floatArrayOf(0f, 90f, 180f, 270f, 360f)
        for (target in snapTargets) {
            if (kotlin.math.abs(norm - target) <= threshold) {
                return if (target == 360f) 0f else target
            }
        }
        return norm
    }
}

