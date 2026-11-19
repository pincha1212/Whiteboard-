package com.example.ui.toolbar

import androidx.compose.ui.unit.dp

/**
 * Shared dimensions for compact Whiteboard controls.
 *
 * Keep visual sizes compact while preserving a consistent touch target.
 */
internal object WhiteboardUiMetrics {
    val touchTarget = 48.dp
    val compactAction = 34.dp
    val compactIcon = 16.dp
    val standardIcon = 18.dp

    val controlCorner = 10.dp
    val groupCorner = 12.dp
    val dockCorner = 14.dp

    val tightSpacing = 4.dp
    val standardSpacing = 6.dp

    val horizontalDockPadding = 6.dp
    val verticalDockPadding = 6.dp
}
