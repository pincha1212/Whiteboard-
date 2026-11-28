package com.example.model

import androidx.compose.runtime.Immutable
import java.util.UUID

enum class ToolType {
    HAND,
    PENCIL,
    PEN,
    FINE_MARKER,
    CHISEL_MARKER,
    TECHNICAL_INK,
    PAINT_BRUSH,
    WATERCOLOR,
    AIRBRUSH,
    HIGHLIGHTER,
    SMUDGE,
    SOFT_ERASER,
    FLOOD_FILL,
    ERASER,
    PIXEL_ERASER,
    EYEDROPPER,
    SELECT_LASSO,
    TEXT
}

enum class StrokeBrushType(
    val displayName: String,
    val stabilization: Float,
    val defaultOpacity: Float
) {
    PENCIL("Lápiz grafito", 0.30f, 0.92f),
    FOUNTAIN_PEN("Pluma fuente", 0.35f, 1.0f),
    TECHNICAL_INK("Estilógrafo", 0.55f, 1.0f),
    FINE_MARKER("Rotulador Copic", 0.68f, 1.0f),
    CHISEL_MARKER("Rotulador biselado", 0.62f, 0.94f),
    PAINT_BRUSH("Pincel pintura", 0.42f, 0.88f),
    WATERCOLOR("Acuarela húmeda", 0.48f, 0.72f),
    AIRBRUSH("Aerógrafo", 0.45f, 0.55f),
    HIGHLIGHTER("Resaltador", 0.40f, 0.30f),
}

@Immutable
data class BrushStudioSettings(
    val flow: Float = 1.0f,
    val spacing: Float = 0.25f,
    val hardness: Float = 0.85f,
    val grainStrength: Float = 0.35f,
    val taperStart: Float = 0.20f,
    val taperEnd: Float = 0.35f,
    val velocitySensitivity: Float = 0.50f,
    val chiselAngleDeg: Float = 38f
) {
    companion object {
        fun defaultFor(type: StrokeBrushType): BrushStudioSettings = when (type) {
            StrokeBrushType.PENCIL -> BrushStudioSettings(
                flow = 0.88f,
                spacing = 0.22f,
                hardness = 0.65f,
                grainStrength = 0.72f,
                taperStart = 0.25f,
                taperEnd = 0.45f,
                velocitySensitivity = 0.45f
            )
            StrokeBrushType.FOUNTAIN_PEN -> BrushStudioSettings(
                flow = 1.0f,
                spacing = 0.14f,
                hardness = 0.96f,
                grainStrength = 0.05f,
                taperStart = 0.30f,
                taperEnd = 0.55f,
                velocitySensitivity = 0.72f
            )
            StrokeBrushType.TECHNICAL_INK -> BrushStudioSettings(
                flow = 1.0f,
                spacing = 0.12f,
                hardness = 1.0f,
                grainStrength = 0.0f,
                taperStart = 0.0f,
                taperEnd = 0.0f,
                velocitySensitivity = 0.08f
            )
            StrokeBrushType.FINE_MARKER -> BrushStudioSettings(
                flow = 0.96f,
                spacing = 0.18f,
                hardness = 0.90f,
                grainStrength = 0.10f,
                taperStart = 0.10f,
                taperEnd = 0.18f,
                velocitySensitivity = 0.20f
            )
            StrokeBrushType.CHISEL_MARKER -> BrushStudioSettings(
                flow = 0.92f,
                spacing = 0.15f,
                hardness = 0.86f,
                grainStrength = 0.14f,
                taperStart = 0.08f,
                taperEnd = 0.14f,
                velocitySensitivity = 0.32f,
                chiselAngleDeg = 38f
            )
            StrokeBrushType.PAINT_BRUSH -> BrushStudioSettings(
                flow = 0.86f,
                spacing = 0.18f,
                hardness = 0.58f,
                grainStrength = 0.48f,
                taperStart = 0.20f,
                taperEnd = 0.40f,
                velocitySensitivity = 0.62f
            )
            StrokeBrushType.WATERCOLOR -> BrushStudioSettings(
                flow = 0.72f,
                spacing = 0.24f,
                hardness = 0.32f,
                grainStrength = 0.60f,
                taperStart = 0.15f,
                taperEnd = 0.30f,
                velocitySensitivity = 0.48f
            )
            StrokeBrushType.AIRBRUSH -> BrushStudioSettings(
                flow = 0.55f,
                spacing = 0.28f,
                hardness = 0.18f,
                grainStrength = 0.30f,
                taperStart = 0.08f,
                taperEnd = 0.12f,
                velocitySensitivity = 0.25f
            )
            StrokeBrushType.HIGHLIGHTER -> BrushStudioSettings(
                flow = 0.90f,
                spacing = 0.15f,
                hardness = 0.92f,
                grainStrength = 0.0f,
                taperStart = 0.0f,
                taperEnd = 0.0f,
                velocitySensitivity = 0.05f,
                chiselAngleDeg = 18f
            )
        }
    }
}

enum class LayerBlendMode(val displayName: String, val shortCode: String) {
    NORMAL("Normal", "NRM"),
    MULTIPLY("Multiplicar", "MUL"),
    SCREEN("Trama", "SCR"),
    OVERLAY("Superponer", "OVR")
}

enum class SymmetryMode(val displayName: String) {
    NONE("Sin simetría"),
    VERTICAL("Simetría vertical"),
    HORIZONTAL("Simetría horizontal"),
    QUAD("Simetría radial (4 ejes)")
}

const val DEFAULT_LAYER_ID = "layer_1"

@Immutable
data class DrawingLayer(
    val id: String = DEFAULT_LAYER_ID,
    val name: String = "Capa 1",
    val isVisible: Boolean = true,
    val opacity: Float = 1.0f,
    val isLocked: Boolean = false,
    val blendMode: LayerBlendMode = LayerBlendMode.NORMAL,
    val isAlphaLocked: Boolean = false,
    val isReference: Boolean = false,
    val rasterRevision: Long = 0L
)

@Immutable
data class BrushSlot(
    val id: String = UUID.randomUUID().toString(),
    val brushType: StrokeBrushType,
    val colorHex: String,
    val strokeWidth: Float,
    val opacity: Float = brushType.defaultOpacity,
    val isRainbow: Boolean = false,
    val studioSettings: BrushStudioSettings = BrushStudioSettings.defaultFor(brushType)
) {
    val toolType: ToolType
        get() = when (brushType) {
            StrokeBrushType.PENCIL -> ToolType.PENCIL
            StrokeBrushType.FOUNTAIN_PEN -> ToolType.PEN
            StrokeBrushType.TECHNICAL_INK -> ToolType.TECHNICAL_INK
            StrokeBrushType.FINE_MARKER -> ToolType.FINE_MARKER
            StrokeBrushType.CHISEL_MARKER -> ToolType.CHISEL_MARKER
            StrokeBrushType.PAINT_BRUSH -> ToolType.PAINT_BRUSH
            StrokeBrushType.WATERCOLOR -> ToolType.WATERCOLOR
            StrokeBrushType.AIRBRUSH -> ToolType.AIRBRUSH
            StrokeBrushType.HIGHLIGHTER -> ToolType.HIGHLIGHTER
        }
}

enum class StrokeStyle { SOLID }

enum class LineCapStyle(val displayName: String, val symbol: String) {
    NONE("Sin flechas", "—"),
    END_ARROW("Flecha final", "→"),
    BOTH_ARROWS("Doble flecha", "↔"),
    START_ARROW("Flecha inicio", "←")
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
    val y: Float,
    val timestampMs: Long = 0L,
    val pressure: Float = 1.0f
)

data class RulerState(
    val isVisible: Boolean = false,
    val centerX: Float = 380f,
    val centerY: Float = 380f,
    val angleDegrees: Float = 0f,
    val length: Float = 480f,
    val width: Float = 84f,
    val snapAnglesEnabled: Boolean = true,
    val isAngleLocked: Boolean = false
)

enum class GuideType {
    NONE,
    RULER,
    ELLIPSE,
    FRENCH_CURVE,
    PERSPECTIVE
}

@Immutable
data class EllipseGuideState(
    val isVisible: Boolean = false,
    val centerX: Float = 420f,
    val centerY: Float = 420f,
    val radiusX: Float = 220f,
    val radiusY: Float = 135f,
    val rotationDegrees: Float = 0f,
    val isCircleLocked: Boolean = false
)

enum class FrenchCurveProfile(val displayName: String) {
    TEARDROP("Gota clásica"),
    EULER_SPIRAL("Espiral de Euler"),
    S_ARCH("Curva en S")
}

@Immutable
data class FrenchCurveGuideState(
    val isVisible: Boolean = false,
    val centerX: Float = 420f,
    val centerY: Float = 420f,
    val scale: Float = 1.0f,
    val rotationDegrees: Float = 0f,
    val isFlippedX: Boolean = false,
    val profile: FrenchCurveProfile = FrenchCurveProfile.TEARDROP
)

enum class PerspectiveMode(val displayName: String, val shortCode: String) {
    OFF("Apagada", "OFF"),
    ONE_POINT("1 Punto", "1P"),
    TWO_POINT("2 Puntos", "2P"),
    THREE_POINT("3 Puntos", "3P"),
    ISOMETRIC("Isométrica", "ISO")
}

@Immutable
data class VanishingPoint(
    val x: Float,
    val y: Float
)

@Immutable
data class PerspectiveGuideState(
    val mode: PerspectiveMode = PerspectiveMode.OFF,
    val horizonY: Float = 340f,
    val vp1: VanishingPoint = VanishingPoint(-140f, 340f),
    val vp2: VanishingPoint = VanishingPoint(980f, 340f),
    val vp3: VanishingPoint = VanishingPoint(420f, 1180f),
    val isometricAngleDeg: Float = 30f,
    val gridDensity: Int = 12,
    val isSnapEnabled: Boolean = true,
    val isLocked: Boolean = false,
    val showGridOverlay: Boolean = true
)

@Immutable
data class GuidesOverlayState(
    val activeGuide: GuideType = GuideType.NONE,
    val ellipse: EllipseGuideState = EllipseGuideState(),
    val frenchCurve: FrenchCurveGuideState = FrenchCurveGuideState(),
    val perspective: PerspectiveGuideState = PerspectiveGuideState()
)

internal data class StrokeGeometryMetrics(
    val pointsHash: Int,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val hasPoints: Boolean
) {
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
    val opacity: Float = if (isHighlighter) StrokeBrushType.HIGHLIGHTER.defaultOpacity else 1.0f,
    val layerId: String = DEFAULT_LAYER_ID,
    val studioSettings: BrushStudioSettings = BrushStudioSettings.defaultFor(brushType)
) {
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

    internal val renderSignature: Long by lazy {
        var h = id.hashCode().toLong()
        h = (h xor geometryMetrics.pointsHash.toLong()).let { it xor (it ushr 27) }
        h = (h xor width.toBits().toLong()).let { it xor (it ushr 27) }
        h = (h xor colorHex.hashCode().toLong()).let { it xor (it ushr 27) }
        h = (h xor opacity.toBits().toLong()).let { it xor (it ushr 27) }
        h = (h xor studioSettings.hashCode().toLong()).let { it xor (it ushr 27) }
        val flags = ((brushType.ordinal shl 8) or
            (capStyle.ordinal shl 4) or
            (if (isHighlighter) 2 else 0) or
            (if (isRainbow) 1 else 0)).toLong()
        (h xor flags).let { it xor (it ushr 27) }
    }
}

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
    val isHiddenByCollapse: Boolean = false,
    val layerId: String = DEFAULT_LAYER_ID
) {
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
    val rotation: Float = 0f,
    val groupId: String? = null,
    val parentId: String? = null,
    val isHiddenByCollapse: Boolean = false,
    val height: Float = 0f
)

data class WhiteboardBoard(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val gridType: CanvasGridType = CanvasGridType.GRID,
    val colorTheme: CanvasColorTheme = CanvasColorTheme.WHITE,
    val strokes: List<WhiteboardStroke> = emptyList(),
    val shapes: List<ShapeItem> = emptyList(),
    val textItems: List<TextItem> = emptyList(),
    val layers: List<DrawingLayer> = listOf(DrawingLayer()),
    val activeLayerId: String = DEFAULT_LAYER_ID
)

data class ShapeRecognitionProposal(
    val originalStrokeId: String,
    val recognizedType: ShapeType,
    val confidence: Float,
    val suggestedShape: ShapeItem
)
