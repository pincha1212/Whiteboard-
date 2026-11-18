package com.example.ink

/**
 * Central defaults for the selection/transform bounding box.
 *
 * Change values here to tune interaction without modifying transform math.
 * World sizes use board-space units; handle and gutter sizes use dp.
 */
object TransformConfig {
    // Geometry limits in board-space units.
    const val MIN_SIZE_WORLD = 96f
    const val MAX_SIZE_WORLD = 2400f

    // Aspect-ratio safety limits.
    const val MIN_ASPECT_RATIO = 0.25f
    const val MAX_ASPECT_RATIO = 4f

    // Touch targets and visual handles.
    const val HANDLE_TOUCH_TARGET_DP = 48f
    const val HANDLE_VISUAL_SIZE_DP = 9f
    const val ROTATION_HANDLE_VISUAL_SIZE_DP = 10f
    const val SIDE_GUTTER_DP = 24f
    const val VERTICAL_GUTTER_DP = 64f
    const val ROTATION_GUIDE_OFFSET_DP = 24f
    const val ROTATION_GUIDE_HEIGHT_DP = 40f

    // Rotation snapping.
    const val SNAP_STEP_DEGREES = 45f
    const val SNAP_TOLERANCE_DEGREES = 3f

    // Gesture safety.
    const val MIN_GESTURE_SCALE_FACTOR = 0.01f
}
