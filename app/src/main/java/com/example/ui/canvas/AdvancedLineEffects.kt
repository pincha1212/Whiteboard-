package com.example.ui.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.example.ink.engine.BrushConfig
import com.example.ink.engine.BrushEngineRegistry
import com.example.ink.engine.UnifiedBrushPipeline
import com.example.model.LineCapStyle
import com.example.model.ShapeItem
import com.example.model.StrokeBrushType
import com.example.model.StrokePoint
import com.example.model.StrokeStyle
import com.example.model.WhiteboardStroke

/**
 * Advanced Line & Stroke Rendering Facade.
 * Routes all freehand strokes, styled lines, preset shapes, and style previews
 * through the unified 4-stage organic brush pipeline:
 * [com.example.ink.engine.ShapeGenerator] -> [com.example.ink.engine.PathHumanizer] -> [com.example.ink.engine.BrushEngine].
 */
object AdvancedLineEffects {

    fun DrawScope.drawAdvancedStroke(
        stroke: WhiteboardStroke,
        panX: Float,
        panY: Float,
        scale: Float,
        animPhase: Float = 0f,
        isSelected: Boolean = false,
        isDarkBoard: Boolean = false,
        isOpaqueCanvasBackground: Boolean = true,
        handDrawnFactor: Float = UnifiedBrushPipeline.DEFAULT_HAND_DRAWN_FACTOR,
        viewportW: Float = size.width,
        viewportH: Float = size.height
    ) {
        if (stroke.points.size < 2) return
        drawIntoCanvas { composeCanvas ->
            UnifiedBrushPipeline.renderStroke(
                canvas = composeCanvas.nativeCanvas,
                stroke = stroke,
                panX = panX,
                panY = panY,
                scale = scale,
                animPhase = animPhase,
                isSelected = isSelected,
                isDarkBoard = isDarkBoard,
                isOpaqueCanvasBackground = isOpaqueCanvasBackground,
                handDrawnFactor = handDrawnFactor,
                viewportW = viewportW,
                viewportH = viewportH
            )
        }
    }

    fun DrawScope.drawAdvancedLineShape(
        shape: ShapeItem,
        panX: Float,
        panY: Float,
        scale: Float,
        animPhase: Float = 0f,
        isSelected: Boolean = false,
        isDarkBoard: Boolean = false,
        isOpaqueCanvasBackground: Boolean = true,
        handDrawnFactor: Float = UnifiedBrushPipeline.DEFAULT_HAND_DRAWN_FACTOR,
        viewportW: Float = size.width,
        viewportH: Float = size.height
    ) {
        drawIntoCanvas { composeCanvas ->
            UnifiedBrushPipeline.renderShape(
                canvas = composeCanvas.nativeCanvas,
                shape = shape,
                panX = panX,
                panY = panY,
                scale = scale,
                animPhase = animPhase,
                isDarkBoard = isDarkBoard,
                isOpaqueCanvasBackground = isOpaqueCanvasBackground,
                handDrawnFactor = handDrawnFactor,
                drawLabel = false,
                viewportW = viewportW,
                viewportH = viewportH
            )
        }
    }

    fun DrawScope.drawStylePreview(
        style: StrokeStyle,
        capStyle: LineCapStyle,
        colorHex: String,
        animPhase: Float = 0f,
        isDarkBoard: Boolean = false
    ) {
        val w = size.width
        val h = size.height
        val padX = (w * 0.14f).coerceAtLeast(8f)
        val midY = h * 0.5f
        val amp = (h * 0.18f).coerceIn(2f, 6f)
        val previewPoints = listOf(
            StrokePoint(padX, midY + amp * 0.35f),
            StrokePoint(w * 0.36f, midY - amp * 0.55f),
            StrokePoint(w * 0.66f, midY + amp * 0.55f),
            StrokePoint(w - padX, midY - amp * 0.25f)
        )
        val previewStroke = WhiteboardStroke(
            points = previewPoints,
            colorHex = colorHex,
            width = 3.6f,
            strokeStyle = style,
            capStyle = capStyle
        )
        drawAdvancedStroke(
            stroke = previewStroke,
            panX = 0f,
            panY = 0f,
            scale = 1f,
            animPhase = animPhase,
            isSelected = false,
            isDarkBoard = isDarkBoard,
            isOpaqueCanvasBackground = false,
            handDrawnFactor = 0.12f
        )
    }

    fun DrawScope.drawStyledOutlinePath(
        path: Path,
        shape: ShapeItem,
        scale: Float = 1f,
        animPhase: Float = 0f,
        isDarkBoard: Boolean = false,
        isOpaqueCanvasBackground: Boolean = true,
        handDrawnFactor: Float = UnifiedBrushPipeline.DEFAULT_HAND_DRAWN_FACTOR
    ) {
        val color = parseColor(shape.strokeColorHex).let {
            it.copy(alpha = (it.alpha * shape.opacity.coerceIn(0.15f, 1.0f)))
        }
        val strokeW = (shape.strokeWidth * scale).coerceAtLeast(1.5f)
        drawStyledOutlinePath(
            path = path,
            style = shape.strokeStyle,
            color = color,
            strokeWidth = strokeW,
            brushType = shape.brushType,
            isRainbow = shape.isRainbow,
            animPhase = animPhase,
            isDarkBoard = isDarkBoard,
            isOpaqueCanvasBackground = isOpaqueCanvasBackground,
            handDrawnFactor = handDrawnFactor,
            seed = shape.id.hashCode()
        )
    }

    fun DrawScope.drawStyledOutlinePath(
        path: Path,
        style: StrokeStyle,
        color: Color,
        strokeWidth: Float,
        brushType: StrokeBrushType = StrokeBrushType.FOUNTAIN_PEN,
        isRainbow: Boolean = false,
        animPhase: Float = 0f,
        isDarkBoard: Boolean = false,
        isOpaqueCanvasBackground: Boolean = true,
        handDrawnFactor: Float = UnifiedBrushPipeline.DEFAULT_HAND_DRAWN_FACTOR,
        seed: Int = 1337
    ) {
        val androidPath = path.asAndroidPath()
        val engine = BrushEngineRegistry.forBrushType(brushType, style)
        val config = BrushConfig(
            color = color.toArgb(),
            strokeWidth = strokeWidth.coerceAtLeast(1.5f),
            alpha = color.alpha.coerceIn(0.15f, 1.0f),
            handDrawnFactor = handDrawnFactor,
            isDarkBackground = isDarkBoard,
            isOpaqueCanvasBackground = isOpaqueCanvasBackground,
            isRainbow = isRainbow,
            strokeStyle = style,
            animPhase = animPhase,
            seed = seed
        )
        drawIntoCanvas { composeCanvas ->
            UnifiedBrushPipeline.renderPathWithPipeline(
                canvas = composeCanvas.nativeCanvas,
                rawPath = androidPath,
                brushEngine = engine,
                config = config
            )
        }
    }
}
