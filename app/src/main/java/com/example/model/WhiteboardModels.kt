package com.example.model

import java.util.UUID

enum class ToolType {
    HAND,
    PEN,
    FINE_MARKER,
    HIGHLIGHTER,
    LASER_POINTER,
    ERASER,
    PIXEL_ERASER,
    SELECT_LASSO,
    SHAPE,
    STICKY_NOTE,
    TEXT,
    STICKER
}

enum class StrokeBrushType(
    val displayName: String,
    val stabilization: Float,
    val defaultOpacity: Float
) {
    FOUNTAIN_PEN("Pluma / Estilógrafo", 0.35f, 1.0f),
    FINE_MARKER("Marcador fino", 0.68f, 1.0f),
    HIGHLIGHTER("Resaltador", 0.40f, 0.30f),
}

enum class StrokeStyle { SOLID }

enum class LineCapStyle(val displayName: String, val symbol: String) {
    NONE("Sin flechas", "—"),
    END_ARROW("Flecha final", "→"),
    BOTH_ARROWS("Doble flecha", "↔"),
    START_ARROW("Flecha inicio", "←")
}

enum class StrokeStyle(
    val displayName: String,
    val category: StrokeCategory = StrokeCategory.PATTERNS
) {
    SOLID("Sólida", StrokeCategory.PATTERNS),
    DASHED("Discontinua", StrokeCategory.PATTERNS),
    DOTTED("Punteada", StrokeCategory.PATTERNS),
    DASH_DOT("Punto y raya", StrokeCategory.PATTERNS),
    DOUBLE("Doble / Paralela", StrokeCategory.PATTERNS),
    ZIGZAG("Zigzag / Dentada", StrokeCategory.PATTERNS),
    WAVY("Ondulada", StrokeCategory.PATTERNS),
    SQUARE_WAVE("Almena / Onda cuadrada", StrokeCategory.PATTERNS),
    RAILWAY("Vías de tren", StrokeCategory.PATTERNS),
    ARROW_PATTERN("Flechas continuas", StrokeCategory.PATTERNS),

    TAPERED("Presión / Tapered", StrokeCategory.PRESSURE),
    CALLIGRAPHIC("Caligráfico / Biselado", StrokeCategory.PRESSURE),
    SKETCH("Multilínea / Boceto", StrokeCategory.PRESSURE),

    NEON_GLOW("Neón / Resplandor", StrokeCategory.TEXTURE_LIGHT),
    DYNAMIC_GRADIENT("Degradado dinámico", StrokeCategory.TEXTURE_LIGHT),
    CHALK_TEXTURE("Tiza / Acuarela", StrokeCategory.TEXTURE_LIGHT)
}

enum class CanvasGridType(val title: String) {
    DOTS("Puntos"),
    GRID("Cuadrícula"),
    LINES("Rayas"),
    DIAMONDS("Diamantes"),
    BLANK("Liso")
}

enum class CanvasColorTheme(val title: String, val hex: String, val isDark: Boolean = false) {
    WHITE("Blanco Puro", "#FFFFFF", false),
    IVORY("Marfil Suave", "#FAF8F0", false),
    MINT("Menta Pálido", "#EDF7F1", false),
    SKY("Azul Cielo", "#EDF4FA", false),
    CHARCOAL("Pizarra Oscura", "#1E2024", true),
    DARK_NAVY("Azul Marino", "#101924", true)
}

enum class ShapeType(val displayName: String, val category: String) {
    RECTANGLE("Rectángulo", "Básica"),
    ROUNDED_RECT("Rectángulo Redondeado", "Básica"),
    CIRCLE("Círculo / Elipse", "Básica"),
    TRIANGLE("Triángulo", "Básica"),
    DIAMOND("Rombo", "Básica"),
    LINE("Línea recta", "Línea"),
    ARROW("Flecha derecha", "Flujo"),
    DOUBLE_ARROW("Flecha doble", "Flujo"),
    STAR("Estrella (5 puntas)", "Avanzada"),
    HEXAGON("Hexágono", "Avanzada"),
    CLOUD_CALLOUT("Nube de Diálogo", "Avanzada"),
    CYLINDER("Cilindro de Datos", "Diagrama")
}

val ShapeType.isLineLike: Boolean
    get() = this == ShapeType.LINE || this == ShapeType.ARROW || this == ShapeType.DOUBLE_ARROW


data class StrokePoint(
    val x: Float,
    val y: Float
)

data class LaserPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long = System.currentTimeMillis()
)

data class RulerState(
    val isVisible: Boolean = false,
    val centerX: Float = 380f,
    val centerY: Float = 380f,
    val angleDegrees: Float = 0f,
    val length: Float = 480f,
    val width: Float = 84f
)

internal data class StrokeGeometryMetrics(
    val pointsHash: Int,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val hasPoints: Boolean
) {
    /** Broad-phase hit test; false means the exact segment-distance check can be skipped. */
    fun intersectsCircle(centerX: Float, centerY: Float, radius: Float): Boolean {
        if (!hasPoints) return false
        val safeRadius = radius.coerceAtLeast(0f)
        return centerX + safeRadius >= minX &&
            centerX - safeRadius <= maxX &&
            centerY + safeRadius >= minY &&
            centerY - safeRadius <= maxY
    }
}

data class WhiteboardStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<StrokePoint>,
    val colorHex: String,
    val width: Float,
    val isHighlighter: Boolean = false,
    val isRainbow: Boolean = false,
    val strokeStyle: StrokeStyle = StrokeStyle.SOLID,
    val capStyle: LineCapStyle = LineCapStyle.NONE,
    val zIndex: Int = 0,
    val brushType: StrokeBrushType = if (isHighlighter) StrokeBrushType.HIGHLIGHTER else StrokeBrushType.FOUNTAIN_PEN,
    val opacity: Float = if (isHighlighter) StrokeBrushType.HIGHLIGHTER.defaultOpacity else 1.0f
) {
    /**
     * Lazily computed once per immutable stroke instance. New geometry must be created with copy(),
     * which naturally receives a fresh summary instead of reusing stale bounds or hashes.
     */
    internal val geometryMetrics: StrokeGeometryMetrics by lazy {
        var hash = 1
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (point in points) {
            hash = 31 * hash + point.hashCode()
            if (point.x < minX) minX = point.x
            if (point.x > maxX) maxX = point.x
            if (point.y < minY) minY = point.y
            if (point.y > maxY) maxY = point.y
        }
        StrokeGeometryMetrics(
            pointsHash = hash,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
            hasPoints = points.isNotEmpty()
        )
    }
}

data class StickyNote(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float = 220f,
    val height: Float = 200f,
    val colorHex: String = "#FFF275",
    val rotation: Float = 0f,
    val isPinned: Boolean = false,
    val isVoiceNote: Boolean = false,
    val voiceDurationSec: Int = 0,
    val tags: List<String> = emptyList(),
    val isLocked: Boolean = false,
    val isAspectRatioLocked: Boolean = false,
    val groupId: String? = null,
    val parentId: String? = null,
    val isHiddenByCollapse: Boolean = false,
    // null means a legacy canvas note whose title was embedded in text.
    val title: String? = null
)

data class ImageCardItem(
    val id: String = UUID.randomUUID().toString(),
    val imageUri: String,
    val x: Float,
    val y: Float,
    val width: Float = 260f,
    val height: Float = 200f,
    val caption: String = "",
    val tags: List<String> = emptyList(),
    val rotation: Float = 0f,
    val isPinned: Boolean = false
)

data class ShapeItem(
    val id: String = UUID.randomUUID().toString(),
    val shapeType: ShapeType,
    val x: Float,
    val y: Float,
    val width: Float = 200f,
    val height: Float = 140f,
    val strokeColorHex: String = "#0078D4",
    val fillColorHex: String = "#E8F3FF",
    val strokeWidth: Float = 4f,
    val isFilled: Boolean = false,
    val strokeStyle: StrokeStyle = StrokeStyle.SOLID,
    val capStyle: LineCapStyle = LineCapStyle.NONE,
    val rotation: Float = 0f,
    val vertices: List<StrokePoint> = emptyList(),
    val opacity: Float = 1.0f,
    val isRainbow: Boolean = false,
    val brushType: StrokeBrushType = StrokeBrushType.FOUNTAIN_PEN,
    val label: String = "",
    val isLocked: Boolean = false,
    val templateRole: String? = null,
    val groupId: String? = null,
    val parentId: String? = null,
    val isCollapsed: Boolean = false,
    val isHiddenByCollapse: Boolean = false
) {
    /** Relative vertex coordinates are unchanged by pan/move and are reused in render cache keys. */
    internal val relativeVerticesHash: Int by lazy {
        var hash = 1
        for (vertex in vertices) {
            hash = 31 * hash + (vertex.x - x).toBits()
            hash = 31 * hash + (vertex.y - y).toBits()
        }
        hash
    }
}

data class TextItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val x: Float,
    val y: Float,
    val fontSize: Float = 24f,
    val colorHex: String = "#1E1E1E",
    val isBold: Boolean = false,
    val isLocked: Boolean = false,
    val isEditableCard: Boolean = false,
    val backgroundColorHex: String? = null,
    val width: Float = 190f,
    val groupId: String? = null,
    val parentId: String? = null,
    val isHiddenByCollapse: Boolean = false
)

data class StickerItem(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    val x: Float,
    val y: Float,
    val scale: Float = 1.3f
)

data class AuditLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val actionName: String,
    val details: String,
    val category: String = "CANVAS"
)

data class BoardVersionSnapshot(
    val versionId: String = UUID.randomUUID().toString(),
    val boardId: String,
    val versionName: String,
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
    val strokeCount: Int,
    val noteCount: Int,
    val shapeCount: Int,
    val serializedData: String
)

data class WhiteboardBoard(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val gridType: CanvasGridType = CanvasGridType.GRID,
    val colorTheme: CanvasColorTheme = CanvasColorTheme.WHITE,
    val strokes: List<WhiteboardStroke> = emptyList(),
    val stickyNotes: List<StickyNote> = emptyList(),
    val shapes: List<ShapeItem> = emptyList(),
    val imageCards: List<ImageCardItem> = emptyList(),
    val textItems: List<TextItem> = emptyList(),
    val stickers: List<StickerItem> = emptyList(),
    val auditLogs: List<AuditLogEntry> = emptyList(),
    val snapshots: List<BoardVersionSnapshot> = emptyList()
)

data class ShapeRecognitionProposal(
    val originalStrokeId: String,
    val recognizedType: ShapeType,
    val confidence: Float,
    val suggestedShape: ShapeItem
)
