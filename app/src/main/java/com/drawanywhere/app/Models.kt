package com.drawanywhere.app

import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color

// ─── أنواع أدوات الرسم ───────────────────────────────────────────────────────
enum class DrawingTool(val displayName: String) {
    PEN("Pen"),
    LASER("Laser"),
    BRUSH("Brush"),
    HIGHLIGHTER("Highlighter"),
    CALLIGRAPHY("Calligraphy"),
    RECTANGLE("Rectangle"),
    ELLIPSE("Ellipse"),
    STROKE_ERASER("Stroke Eraser"),
    PIXEL_ERASER("Pixel Eraser")
}

// ─── اتجاه الـ Toolbar ───────────────────────────────────────────────────────
enum class ToolbarOrientation { HORIZONTAL, VERTICAL }

// ─── حالة أداة واحدة (مستقلة تماماً عن باقي الأدوات) ───────────────────────
data class ToolState(
    var color: Int = Color.RED,
    var strokeWidth: Float = 8f,
    var opacity: Int = 255        // 0–255
)

// ─── بيانات Stroke واحدة (خط / شكل مكتمل) ──────────────────────────────────
data class StrokeData(
    val tool: DrawingTool,
    val path: Path,
    val color: Int,
    val strokeWidth: Float,
    val opacity: Int,
    val rect: RectF? = null,      // للـ Rectangle / Ellipse
    val timestamp: Long = System.currentTimeMillis()
)

// ─── بيانات صورة مستوردة على الـ Canvas ─────────────────────────────────────
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
