package com.example.model

import java.util.UUID

enum class ToolType {
    HAND,
    PEN,
    PENCIL,
    FINE_MARKER,
    HIGHLIGHTER,
    BRUSH,
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
    GRAPHITE_PENCIL("Lápiz (grafito)", 0.18f, 0.80f),
    FINE_MARKER("Marcador fino", 0.68f, 1.0f),
    HIGHLIGHTER("Resaltador", 0.40f, 0.38f),
    SOFT_BRUSH("Pincel", 0.32f, 0.44f)
}

enum class StrokeCategory(val displayName: String) {
    PATTERNS("Patrones"),
    PRESSURE("Presión"),
    TEXTURE_LIGHT("Efectos")
}

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
    CHALK_TEXTURE("Tiza / Acuarela", StrokeCategory.TEXTURE_LIGHT),
    ANIMATED_PULSE("Línea animada", StrokeCategory.TEXTURE_LIGHT)
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


enum class TemplateCategory(val title: String) {
    RECOMMENDED("Recomendado"),
    MY_TEMPLATES("Mis plantillas"),
    BRAINSTORMING("Lluvia de ideas"),
    STRATEGY("Estrategia"),
    LEARNING("Aprendizaje"),
    PROCESS("Procesos y flujos"),
    PLANNING("Planificación"),
    PROBLEM_SOLVING("Solución de problemas")
}

enum class TemplateType(
    val title: String,
    val iconName: String,
    val description: String,
    val category: TemplateCategory,
    val isRecommended: Boolean = true,
    val badgeText: String? = null
) {
    BRAINSTORM_CLUSTER(
        title = "Lluvia de Ideas Libre (Cluster)",
        iconName = "lightbulb",
        description = "Nodo central + 6 notas satélite radiales con botón '+' rápido",
        category = TemplateCategory.BRAINSTORMING,
        isRecommended = true,
        badgeText = "Nueva"
    ),
    IMPACT_EFFORT(
        title = "Matriz Impacto / Esfuerzo",
        iconName = "grid_view",
        description = "Plano 2×2 (Hacer ya, Planificar, Delegar, Descartar) con ejes fijos",
        category = TemplateCategory.STRATEGY,
        isRecommended = true
    ),
    CONCEPT_MAP(
        title = "Mapa Conceptual / Jerárquico",
        iconName = "account_tree",
        description = "Árbol de 3 niveles con conectores ortogonales y ramas colapsables",
        category = TemplateCategory.LEARNING,
        isRecommended = true
    ),
    USER_FLOW(
        title = "Flujo de Usuario / Proceso",
        iconName = "schema",
        description = "Carriles horizontales (Swimlanes) con inicio, pasos y decisión",
        category = TemplateCategory.PROCESS,
        isRecommended = true
    ),
    RETROSPECTIVE(
        title = "Retrospectiva / Kanban Rápido",
        iconName = "history_edu",
        description = "3 columnas auto-expansibles: Qué funcionó, Qué mejorar, Próximos pasos",
        category = TemplateCategory.PLANNING,
        isRecommended = true
    ),
    KANBAN(
        title = "Kanban Rápido",
        iconName = "view_kanban",
        description = "3 columnas auto-expansibles: Por hacer, Haciendo, Hecho",
        category = TemplateCategory.PLANNING,
        isRecommended = true
    ),
    ISHIKAWA(
        title = "Diagrama de Ishikawa (Causa-Efecto)",
        iconName = "polyline",
        description = "Espina central al Problema con 6 espinas fijas y cajas editables",
        category = TemplateCategory.PROBLEM_SOLVING,
        isRecommended = true,
        badgeText = "Nueva"
    ),
    SWOT(
        title = "Análisis DAFO / FODA",
        iconName = "grid_view",
        description = "Fortalezas, Oportunidades, Debilidades y Amenazas",
        category = TemplateCategory.STRATEGY,
        isRecommended = false
    )
}

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
)

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
    val label: String = "",
    val isLocked: Boolean = false,
    val templateRole: String? = null,
    val groupId: String? = null,
    val parentId: String? = null,
    val isCollapsed: Boolean = false,
    val isHiddenByCollapse: Boolean = false
)

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

data class CustomBoardTemplate(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val strokeCount: Int = 0,
    val shapeCount: Int = 0,
    val noteCount: Int = 0,
    val textCount: Int = 0,
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
