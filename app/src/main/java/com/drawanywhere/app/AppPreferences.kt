package com.drawanywhere.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "draw_anywhere_prefs"
        private const val KEY_ORIENTATION    = "toolbar_orientation"
        private const val KEY_CLEAR_ON_HIDE  = "clear_on_hide"
        private const val KEY_OPEN_ON_START  = "open_on_start"
        private const val KEY_FINGER_DRAWING = "finger_drawing"

        // ─── ألوان ───────────────────────────────────────────────────────────
        private const val KEY_COLOR_PEN         = "color_pen"
        private const val KEY_COLOR_MATTE       = "color_matte"
        private const val KEY_COLOR_LASER       = "color_laser"
        private const val KEY_COLOR_BRUSH       = "color_brush"
        private const val KEY_COLOR_HIGHLIGHTER = "color_highlighter"
        private const val KEY_COLOR_CALLIGRAPHY = "color_calligraphy"
        private const val KEY_COLOR_LASSO       = "color_lasso"
        private const val KEY_COLOR_RECTANGLE   = "color_rectangle"
        private const val KEY_COLOR_ELLIPSE     = "color_ellipse"

        // ─── عرض الخط ────────────────────────────────────────────────────────
        private const val KEY_WIDTH_PEN         = "width_pen"
        private const val KEY_WIDTH_MATTE       = "width_matte"
        private const val KEY_WIDTH_LASER       = "width_laser"
        private const val KEY_WIDTH_BRUSH       = "width_brush"
        private const val KEY_WIDTH_HIGHLIGHTER = "width_highlighter"
        private const val KEY_WIDTH_CALLIGRAPHY = "width_calligraphy"
        private const val KEY_WIDTH_LASSO       = "width_lasso"
        private const val KEY_WIDTH_RECTANGLE   = "width_rectangle"
        private const val KEY_WIDTH_ELLIPSE     = "width_ellipse"
        private const val KEY_WIDTH_ERASER      = "width_eraser"

        // ─── شفافية ───────────────────────────────────────────────────────────
        private const val KEY_ALPHA_PEN         = "alpha_pen"
        private const val KEY_ALPHA_MATTE       = "alpha_matte"
        private const val KEY_ALPHA_LASER       = "alpha_laser"
        private const val KEY_ALPHA_BRUSH       = "alpha_brush"
        private const val KEY_ALPHA_HIGHLIGHTER = "alpha_highlighter"
        private const val KEY_ALPHA_CALLIGRAPHY = "alpha_calligraphy"
        private const val KEY_ALPHA_LASSO       = "alpha_lasso"
        private const val KEY_ALPHA_RECTANGLE   = "alpha_rectangle"
        private const val KEY_ALPHA_ELLIPSE     = "alpha_ellipse"

        // ─── نمط الخط (للـ PEN فقط) ──────────────────────────────────────────
        private const val KEY_LINE_STYLE_PEN    = "line_style_pen"
    }

    var toolbarOrientation: ToolbarOrientation
        get() = ToolbarOrientation.valueOf(
            prefs.getString(KEY_ORIENTATION, ToolbarOrientation.VERTICAL.name)
                ?: ToolbarOrientation.VERTICAL.name
        )
        set(v) = prefs.edit().putString(KEY_ORIENTATION, v.name).apply()

    var clearOnHide: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_ON_HIDE, false)
        set(v) = prefs.edit().putBoolean(KEY_CLEAR_ON_HIDE, v).apply()

    var openOnStart: Boolean
        get() = prefs.getBoolean(KEY_OPEN_ON_START, true)
        set(v) = prefs.edit().putBoolean(KEY_OPEN_ON_START, v).apply()

    var fingerDrawing: Boolean
        get() = prefs.getBoolean(KEY_FINGER_DRAWING, true)
        set(v) = prefs.edit().putBoolean(KEY_FINGER_DRAWING, v).apply()

    fun getToolState(tool: DrawingTool): ToolState = when (tool) {
        DrawingTool.PEN -> ToolState(
            prefs.getInt(KEY_COLOR_PEN, Color.RED),
            floatBits(KEY_WIDTH_PEN, 8f),
            prefs.getInt(KEY_ALPHA_PEN, 255),
            LineStyle.valueOf(
                prefs.getString(KEY_LINE_STYLE_PEN, LineStyle.SOLID.name)
                    ?: LineStyle.SOLID.name
            )
        )
        DrawingTool.MATTE_PEN -> ToolState(
            prefs.getInt(KEY_COLOR_MATTE, Color.WHITE),
            floatBits(KEY_WIDTH_MATTE, 12f),
            prefs.getInt(KEY_ALPHA_MATTE, 255),
            LineStyle.SOLID
        )
        DrawingTool.LASER -> ToolState(
            Color.RED,
            floatBits(KEY_WIDTH_LASER, 7f),
            255,
            LineStyle.SOLID
        )
        DrawingTool.BRUSH -> ToolState(
            prefs.getInt(KEY_COLOR_BRUSH, Color.RED),
            floatBits(KEY_WIDTH_BRUSH, 28f),
            prefs.getInt(KEY_ALPHA_BRUSH, 210),
            LineStyle.SOLID
        )
        DrawingTool.HIGHLIGHTER -> ToolState(
            prefs.getInt(KEY_COLOR_HIGHLIGHTER, Color.YELLOW),
            floatBits(KEY_WIDTH_HIGHLIGHTER, 36f),
            prefs.getInt(KEY_ALPHA_HIGHLIGHTER, 110),
            LineStyle.SOLID
        )
        DrawingTool.CALLIGRAPHY -> ToolState(
            prefs.getInt(KEY_COLOR_CALLIGRAPHY, Color.WHITE),
            floatBits(KEY_WIDTH_CALLIGRAPHY, 14f),
            prefs.getInt(KEY_ALPHA_CALLIGRAPHY, 255),
            LineStyle.SOLID
        )
        DrawingTool.LASSO -> ToolState(
            prefs.getInt(KEY_COLOR_LASSO, 0xFF00CCFF.toInt()),
            floatBits(KEY_WIDTH_LASSO, 3f),
            prefs.getInt(KEY_ALPHA_LASSO, 220),
            LineStyle.DASHED
        )
        DrawingTool.RECTANGLE -> ToolState(
            prefs.getInt(KEY_COLOR_RECTANGLE, Color.RED),
            floatBits(KEY_WIDTH_RECTANGLE, 5f),
            prefs.getInt(KEY_ALPHA_RECTANGLE, 255),
            LineStyle.SOLID
        )
        DrawingTool.ELLIPSE -> ToolState(
            prefs.getInt(KEY_COLOR_ELLIPSE, Color.RED),
            floatBits(KEY_WIDTH_ELLIPSE, 5f),
            prefs.getInt(KEY_ALPHA_ELLIPSE, 255),
            LineStyle.SOLID
        )
        DrawingTool.STROKE_ERASER,
        DrawingTool.PIXEL_ERASER -> ToolState(
            Color.TRANSPARENT,
            floatBits(KEY_WIDTH_ERASER, 24f),
            255,
            LineStyle.SOLID
        )
    }

    fun saveToolState(tool: DrawingTool, state: ToolState) {
        val e = prefs.edit()
        when (tool) {
            DrawingTool.PEN -> {
                e.putInt(KEY_COLOR_PEN, state.color)
                e.putInt(KEY_WIDTH_PEN, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_PEN, state.opacity)
                e.putString(KEY_LINE_STYLE_PEN, state.lineStyle.name)
            }
            DrawingTool.MATTE_PEN -> {
                e.putInt(KEY_COLOR_MATTE, state.color)
                e.putInt(KEY_WIDTH_MATTE, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_MATTE, state.opacity)
            }
            DrawingTool.LASER -> {
                e.putInt(KEY_WIDTH_LASER, state.strokeWidth.toRawBits())
            }
            DrawingTool.BRUSH -> {
                e.putInt(KEY_COLOR_BRUSH, state.color)
                e.putInt(KEY_WIDTH_BRUSH, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_BRUSH, state.opacity)
            }
            DrawingTool.HIGHLIGHTER -> {
                e.putInt(KEY_COLOR_HIGHLIGHTER, state.color)
                e.putInt(KEY_WIDTH_HIGHLIGHTER, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_HIGHLIGHTER, state.opacity)
            }
            DrawingTool.CALLIGRAPHY -> {
                e.putInt(KEY_COLOR_CALLIGRAPHY, state.color)
                e.putInt(KEY_WIDTH_CALLIGRAPHY, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_CALLIGRAPHY, state.opacity)
            }
            DrawingTool.LASSO -> {
                e.putInt(KEY_COLOR_LASSO, state.color)
                e.putInt(KEY_WIDTH_LASSO, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_LASSO, state.opacity)
            }
            DrawingTool.RECTANGLE -> {
                e.putInt(KEY_COLOR_RECTANGLE, state.color)
                e.putInt(KEY_WIDTH_RECTANGLE, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_RECTANGLE, state.opacity)
            }
            DrawingTool.ELLIPSE -> {
                e.putInt(KEY_COLOR_ELLIPSE, state.color)
                e.putInt(KEY_WIDTH_ELLIPSE, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_ELLIPSE, state.opacity)
            }
            DrawingTool.STROKE_ERASER,
            DrawingTool.PIXEL_ERASER -> {
                e.putInt(KEY_WIDTH_ERASER, state.strokeWidth.toRawBits())
            }
        }
        e.apply()
    }

    private fun floatBits(key: String, default: Float): Float =
        Float.fromBits(prefs.getInt(key, default.toRawBits()))
}
