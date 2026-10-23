package com.drawanywhere.app

import android.graphics.Path
import android.graphics.RectF
import android.graphics.PointF
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color

enum class DrawingTool(val displayName: String) {
    PEN("Pen"),
    MATTE_PEN("Matte Pen"),
    LASER("Laser"),
    BRUSH("Pencil"),
    HIGHLIGHTER("Highlighter"),
    CALLIGRAPHY("Calligraphy"),
    LASSO("Lasso"),
    RECTANGLE("Rectangle"),
    ELLIPSE("Ellipse"),
    STROKE_ERASER("Stroke Eraser"),
    PIXEL_ERASER("Pixel Eraser")
}

enum class LineStyle(val label: String) {
    SOLID("━━━━━  Solid"),
    DASHED("─ ─ ─  Dashed"),
    DOTTED("· · · ·  Dotted"),
    DASH_DOT("─·─·─  Dash·Dot")
}

enum class ToolbarOrientation { HORIZONTAL, VERTICAL }

data class ToolState(
    var color: Int = Color.RED,
    var strokeWidth: Float = 8f,
    var opacity: Int = 255,
    var lineStyle: LineStyle = LineStyle.SOLID
)

data class StrokeData(
    val tool: DrawingTool,
    val path: Path,
    val color: Int,
    val strokeWidth: Float,
    val opacity: Int,
    val lineStyle: LineStyle = LineStyle.SOLID,
    val rect: RectF? = null,
    val timestamp: Long = System.currentTimeMillis(),
    // ✅ جديد — سلسلة النقاط الخام، مستخدمة في رسم Calligraphy وBrush المحبب
    val rawPoints: List<PointF>? = null
)

data class ImageOverlayData(
    val id: Int,
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var rotation: Float = 0f,
    val uri: Uri,
    var bitmap: Bitmap? = null
)
