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

        // ✅ لون مشترك واحد لكل الأقلام العادية (Pen, Matte, Brush, Calligraphy)
        private const val KEY_GLOBAL_PEN_COLOR = "global_pen_color"
        // ✅ الهايلايتر وحده له لون مستقل شفاف
        private const val KEY_HIGHLIGHTER_COLOR = "highlighter_color"

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

        private const val KEY_ALPHA_HIGHLIGHTER = "alpha_highlighter"
        private const val KEY_ALPHA_RECTANGLE   = "alpha_rectangle"
        private const val KEY_ALPHA_ELLIPSE     = "alpha_ellipse"
        private const val KEY_ALPHA_LASSO       = "alpha_lasso"

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

    /** ✅ اللون العام المشترك بين كل الأقلام (عدا الهايلايتر) */
    var globalPenColor: Int
        get() = prefs.getInt(KEY_GLOBAL_PEN_COLOR, Color.RED)
        set(v) = prefs.edit().putInt(KEY_GLOBAL_PEN_COLOR, v).apply()

    /** ✅ لون الهايلايتر المستقل (شفاف دائماً) */
    var highlighterColor: Int
        get() = prefs.getInt(KEY_HIGHLIGHTER_COLOR, Color.YELLOW)
        set(v) = prefs.edit().putInt(KEY_HIGHLIGHTER_COLOR, v).apply()

    private fun floatBits(key: String, default: Float): Float =
        Float.fromBits(prefs.getInt(key, default.toRawBits()))

    /** العرض بس مختلف لكل أداة — اللون بقى مشترك أو مستقل حسب النوع */
    fun getToolState(tool: DrawingTool): ToolState = when (tool) {
        DrawingTool.PEN -> ToolState(
            globalPenColor, floatBits(KEY_WIDTH_PEN, 6f), 255,
            LineStyle.valueOf(prefs.getString(KEY_LINE_STYLE_PEN, LineStyle.SOLID.name) ?: LineStyle.SOLID.name)
        )
        DrawingTool.MATTE_PEN -> ToolState(globalPenColor, floatBits(KEY_WIDTH_MATTE, 14f), 255, LineStyle.SOLID)
        DrawingTool.LASER     -> ToolState(Color.RED, floatBits(KEY_WIDTH_LASER, 7f), 255, LineStyle.SOLID)
        DrawingTool.BRUSH     -> ToolState(globalPenColor, floatBits(KEY_WIDTH_BRUSH, 10f), 230, LineStyle.SOLID)
        DrawingTool.CALLIGRAPHY -> ToolState(globalPenColor, floatBits(KEY_WIDTH_CALLIGRAPHY, 12f), 255, LineStyle.SOLID)
        DrawingTool.HIGHLIGHTER -> ToolState(
            highlighterColor, floatBits(KEY_WIDTH_HIGHLIGHTER, 34f), prefs.getInt(KEY_ALPHA_HIGHLIGHTER, 100), LineStyle.SOLID
        )
        DrawingTool.LASSO -> ToolState(
            globalPenColor, floatBits(KEY_WIDTH_LASSO, 3f), prefs.getInt(KEY_ALPHA_LASSO, 220), LineStyle.DASHED
        )
        DrawingTool.RECTANGLE -> ToolState(
            globalPenColor, floatBits(KEY_WIDTH_RECTANGLE, 5f), prefs.getInt(KEY_ALPHA_RECTANGLE, 255), LineStyle.SOLID
        )
        DrawingTool.ELLIPSE -> ToolState(
            globalPenColor, floatBits(KEY_WIDTH_ELLIPSE, 5f), prefs.getInt(KEY_ALPHA_ELLIPSE, 255), LineStyle.SOLID
        )
        DrawingTool.STROKE_ERASER, DrawingTool.PIXEL_ERASER ->
            ToolState(Color.TRANSPARENT, floatBits(KEY_WIDTH_ERASER, 24f), 255, LineStyle.SOLID)
    }

    fun saveToolState(tool: DrawingTool, state: ToolState) {
        val e = prefs.edit()
        when (tool) {
            DrawingTool.PEN -> {
                globalPenColor = state.color
                e.putInt(KEY_WIDTH_PEN, state.strokeWidth.toRawBits())
                e.putString(KEY_LINE_STYLE_PEN, state.lineStyle.name)
            }
            DrawingTool.MATTE_PEN -> { globalPenColor = state.color; e.putInt(KEY_WIDTH_MATTE, state.strokeWidth.toRawBits()) }
            DrawingTool.LASER     -> e.putInt(KEY_WIDTH_LASER, state.strokeWidth.toRawBits())
            DrawingTool.BRUSH     -> { globalPenColor = state.color; e.putInt(KEY_WIDTH_BRUSH, state.strokeWidth.toRawBits()) }
            DrawingTool.CALLIGRAPHY -> { globalPenColor = state.color; e.putInt(KEY_WIDTH_CALLIGRAPHY, state.strokeWidth.toRawBits()) }
            DrawingTool.HIGHLIGHTER -> {
                highlighterColor = state.color
                e.putInt(KEY_WIDTH_HIGHLIGHTER, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_HIGHLIGHTER, state.opacity)
            }
            DrawingTool.LASSO -> {
                globalPenColor = state.color
                e.putInt(KEY_WIDTH_LASSO, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_LASSO, state.opacity)
            }
            DrawingTool.RECTANGLE -> {
                globalPenColor = state.color
                e.putInt(KEY_WIDTH_RECTANGLE, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_RECTANGLE, state.opacity)
            }
            DrawingTool.ELLIPSE -> {
                globalPenColor = state.color
                e.putInt(KEY_WIDTH_ELLIPSE, state.strokeWidth.toRawBits())
                e.putInt(KEY_ALPHA_ELLIPSE, state.opacity)
            }
            DrawingTool.STROKE_ERASER, DrawingTool.PIXEL_ERASER ->
                e.putInt(KEY_WIDTH_ERASER, state.strokeWidth.toRawBits())
        }
        e.apply()
    }

    /** ✅ بتطبق اللون الجديد على كل الأقلام المشتركة مرة واحدة (عدا الهايلايتر) */
    fun updateGlobalPenColor(color: Int) {
        globalPenColor = color
    }
}
