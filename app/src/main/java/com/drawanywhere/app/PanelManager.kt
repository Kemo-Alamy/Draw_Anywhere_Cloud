package com.drawanywhere.app

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.*

class PanelManager(
    private val service: DrawingService,
    private val windowManager: WindowManager
) {
    lateinit var drawingCanvas: DrawingCanvas
    lateinit var toolbarManager: ToolbarManager

    private var colorPanelView:     View? = null
    private var toolsPanelView:     View? = null
    private var settingsPanelView:  View? = null
    private var hsvPanelView:       View? = null
    private var lineStylePanelView: View? = null

    private val toolStates = mutableMapOf<DrawingTool, ToolState>()

    init {
        DrawingTool.entries.forEach { tool ->
            toolStates[tool] = service.appPreferences.getToolState(tool)
        }
    }

    // ─── Dismiss ─────────────────────────────────────────────────────────────

    fun dismissAll() {
        listOf(colorPanelView, toolsPanelView, settingsPanelView,
            hsvPanelView, lineStylePanelView)
            .forEach { dismissPanel(it) }
        colorPanelView     = null; toolsPanelView    = null
        settingsPanelView  = null; hsvPanelView      = null
        lineStylePanelView = null
    }

    // ─── Tools Panel ─────────────────────────────────────────────────────────

    fun toggleToolsPanel() {
        if (toolsPanelView != null) {
            dismissPanel(toolsPanelView); toolsPanelView = null; return
        }
        dismissAll(); showToolsPanel()
    }

    private fun showToolsPanel() {
        val v = inflate(R.layout.panel_tools)
        toolsPanelView = v

        // جميع الأدوات مرتبة
        val toolButtons = listOf(
            DrawingTool.PEN           to R.id.tool_pen,
            DrawingTool.MATTE_PEN     to R.id.tool_matte_pen,
            DrawingTool.LASER         to R.id.tool_laser,
            DrawingTool.BRUSH         to R.id.tool_brush,
            DrawingTool.HIGHLIGHTER   to R.id.tool_highlighter,
            DrawingTool.CALLIGRAPHY   to R.id.tool_calligraphy,
            DrawingTool.LASSO         to R.id.tool_lasso,
            DrawingTool.RECTANGLE     to R.id.tool_rectangle,
            DrawingTool.ELLIPSE       to R.id.tool_ellipse,
            DrawingTool.STROKE_ERASER to R.id.tool_stroke_eraser,
            DrawingTool.PIXEL_ERASER  to R.id.tool_pixel_eraser
        )

        for ((tool, id) in toolButtons) {
            v.findViewById<TextView>(id)?.let { btn ->
                val isActive = drawingCanvas.currentTool == tool
                btn.setBackgroundResource(
                    if (isActive) R.drawable.bg_tool_item_selected
                    else          R.drawable.bg_tool_item_default
                )
                btn.setTextColor(
                    if (isActive) service.getColor(R.color.tool_item_text_selected)
                    else          service.getColor(R.color.tool_item_text_default)
                )
                btn.setOnClickListener { selectTool(tool) }
            }
        }
        addPanel(v)
    }

    private fun selectTool(tool: DrawingTool) {
        val state = toolStates.getOrPut(tool) { service.appPreferences.getToolState(tool) }
        drawingCanvas.switchTool(tool, state)
        toolbarManager.updateColorDot(state.color)
        dismissPanel(toolsPanelView); toolsPanelView = null
    }

    // ─── Line Style Panel (برمجياً بدون XML) ─────────────────────────────────

    fun showLineStylePanel() {
        dismissAll()
        val pxD = service.resources.displayMetrics.density

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            val p = (14 * pxD).toInt()
            setPadding(p, (8 * pxD).toInt(), p, (8 * pxD).toInt())
        }

        // عنوان
        container.addView(TextView(service).apply {
            text      = "Line Style  (Pen)"
            setTextColor(service.getColor(R.color.panel_subtext))
            textSize  = 11f
            val mb    = (10 * pxD).toInt()
            setPadding(0, 0, 0, mb)
        })

        val currentStyle = toolStates[DrawingTool.PEN]?.lineStyle ?: LineStyle.SOLID
        val styles = LineStyle.entries.toList()

        for (style in styles) {
            val isActive = currentStyle == style
            val btn = TextView(service).apply {
                text      = style.label
                textSize  = 14f
                gravity   = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (48 * pxD).toInt()
                ).apply { bottomMargin = (4 * pxD).toInt() }
                setBackgroundResource(
                    if (isActive) R.drawable.bg_tool_item_selected
                    else          R.drawable.bg_tool_item_default
                )
                setTextColor(
                    if (isActive) service.getColor(R.color.tool_item_text_selected)
                    else          service.getColor(R.color.tool_item_text_default)
                )
                setOnClickListener { selectLineStyle(style) }
            }
            container.addView(btn)
        }

        lineStylePanelView = container
        addPanel(container)
    }

    private fun selectLineStyle(style: LineStyle) {
        val state = toolStates.getOrPut(DrawingTool.PEN) {
            service.appPreferences.getToolState(DrawingTool.PEN)
        }
        state.lineStyle = style
        if (drawingCanvas.currentTool == DrawingTool.PEN)
            drawingCanvas.updateLineStyle(style)
        service.appPreferences.saveToolState(DrawingTool.PEN, state)
        dismissPanel(lineStylePanelView); lineStylePanelView = null
    }

    // ─── Color Panel ─────────────────────────────────────────────────────────

    fun toggleColorPanel() {
        if (colorPanelView != null) {
            dismissPanel(colorPanelView); colorPanelView = null; return
        }
        dismissAll(); showColorPanel()
    }

    private fun showColorPanel() {
        val v = inflate(R.layout.panel_color)
        colorPanelView = v

        v.findViewById<View>(R.id.view_current_color)?.apply {
            setBackgroundColor(drawingCanvas.currentColor)
            setOnClickListener {
                dismissPanel(colorPanelView); colorPanelView = null
                showHsvPanel()
            }
        }

        val swatchIds = listOf(
            R.id.color_01, R.id.color_02, R.id.color_03, R.id.color_04, R.id.color_05,
            R.id.color_06, R.id.color_07, R.id.color_08, R.id.color_09, R.id.color_10,
            R.id.color_11, R.id.color_12, R.id.color_13, R.id.color_14, R.id.color_15,
            R.id.color_16, R.id.color_17, R.id.color_18, R.id.color_19, R.id.color_20
        )
        val colorResIds = listOf(
            R.color.preset_01, R.color.preset_02, R.color.preset_03, R.color.preset_04,
            R.color.preset_05, R.color.preset_06, R.color.preset_07, R.color.preset_08,
            R.color.preset_09, R.color.preset_10, R.color.preset_11, R.color.preset_12,
            R.color.preset_13, R.color.preset_14, R.color.preset_15, R.color.preset_16,
            R.color.preset_17, R.color.preset_18, R.color.preset_19, R.color.preset_20
        )
        swatchIds.forEachIndexed { i, id ->
            v.findViewById<View>(id)?.let { sw ->
                val color = service.getColor(colorResIds[i])
                sw.setBackgroundColor(color)
                sw.setOnClickListener {
                    applyColor(color)
                    dismissPanel(colorPanelView); colorPanelView = null
                }
            }
        }

        val state       = currentState()
        val widthLabel  = v.findViewById<TextView>(R.id.label_width_value)
        v.findViewById<SeekBar>(R.id.seek_width)?.apply {
            max = 95; progress = (state.strokeWidth - 5f).toInt().coerceIn(0, 95)
            widthLabel?.text = "${state.strokeWidth.toInt()}px"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    val w = p + 5f
                    widthLabel?.text = "${w.toInt()}px"
                    drawingCanvas.currentStrokeWidth = w
                    currentState().strokeWidth = w; persistState()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val opacityLabel = v.findViewById<TextView>(R.id.label_opacity_value)
        v.findViewById<SeekBar>(R.id.seek_opacity)?.apply {
            val pct = (state.opacity / 255f * 100).toInt()
            max = 100; progress = pct
            opacityLabel?.text = "$pct%"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    val alpha = (p / 100f * 255).toInt()
                    opacityLabel?.text = "$p%"
                    drawingCanvas.currentOpacity = alpha
                    currentState().opacity = alpha; persistState()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        addPanel(v)
    }

    // ─── HSV Panel ───────────────────────────────────────────────────────────

    private fun showHsvPanel() {
        val v = inflate(R.layout.panel_hsv)
        hsvPanelView = v

        val picker     = v.findViewById<HsvColorPickerView>(R.id.hsv_picker)
        val tvHex      = v.findViewById<TextView>(R.id.tv_hex_value)
        val seekBright = v.findViewById<SeekBar>(R.id.seek_brightness)
        val applyBtn   = v.findViewById<View>(R.id.btn_apply)

        picker?.setColor(drawingCanvas.currentColor)
        tvHex?.text = toHex(drawingCanvas.currentColor)

        picker?.onColorChanged = { color -> tvHex?.text = toHex(color) }

        val hsv = FloatArray(3)
        Color.colorToHSV(drawingCanvas.currentColor, hsv)
        seekBright?.apply {
            max = 100; progress = (hsv[2] * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    picker?.setBrightness(p / 100f)
                    tvHex?.text = toHex(picker?.getSelectedColor() ?: drawingCanvas.currentColor)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        applyBtn?.setOnClickListener {
            applyColor(picker?.getSelectedColor() ?: drawingCanvas.currentColor)
            dismissPanel(hsvPanelView); hsvPanelView = null
        }
        addPanel(v)
    }

    // ─── Settings Panel ──────────────────────────────────────────────────────

    fun toggleSettingsPanel() {
        if (settingsPanelView != null) {
            dismissPanel(settingsPanelView); settingsPanelView = null; return
        }
        dismissAll(); showSettingsPanel()
    }

    private fun showSettingsPanel() {
        val v     = inflate(R.layout.panel_settings)
        settingsPanelView = v
        val prefs = service.appPreferences

        val btnH = v.findViewById<TextView>(R.id.btn_horizontal)
        val btnV = v.findViewById<TextView>(R.id.btn_vertical)

        fun refreshOrient(o: ToolbarOrientation) {
            val sel   = R.drawable.bg_setting_selected
            val unsel = R.drawable.bg_setting_unselected
            val selT  = service.getColor(R.color.setting_selected_text)
            val unsT  = service.getColor(R.color.setting_unselected_text)
            if (o == ToolbarOrientation.HORIZONTAL) {
                btnH?.setBackgroundResource(sel);   btnH?.setTextColor(selT)
                btnV?.setBackgroundResource(unsel); btnV?.setTextColor(unsT)
            } else {
                btnV?.setBackgroundResource(sel);   btnV?.setTextColor(selT)
                btnH?.setBackgroundResource(unsel); btnH?.setTextColor(unsT)
            }
        }
        refreshOrient(prefs.toolbarOrientation)

        btnH?.setOnClickListener {
            service.applyOrientation(ToolbarOrientation.HORIZONTAL)
            dismissPanel(settingsPanelView); settingsPanelView = null
        }
        btnV?.setOnClickListener {
            service.applyOrientation(ToolbarOrientation.VERTICAL)
            dismissPanel(settingsPanelView); settingsPanelView = null
        }

        v.findViewById<CheckBox>(R.id.chk_clear_on_hide)?.apply {
            isChecked = prefs.clearOnHide
            setOnCheckedChangeListener { _, c -> prefs.clearOnHide = c }
        }
        v.findViewById<CheckBox>(R.id.chk_open_on_start)?.apply {
            isChecked = prefs.openOnStart
            setOnCheckedChangeListener { _, c -> prefs.openOnStart = c }
        }
        v.findViewById<CheckBox>(R.id.chk_finger_drawing)?.apply {
            isChecked = prefs.fingerDrawing
            setOnCheckedChangeListener { _, c ->
                prefs.fingerDrawing = c; service.refreshCanvasTouchMode()
            }
        }
        v.findViewById<View>(R.id.btn_quit)?.setOnClickListener {
            dismissAll(); service.stopSelf()
        }
        addPanel(v)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun applyColor(color: Int) {
        drawingCanvas.currentColor = color
        currentState().color = color; persistState()
        toolbarManager.updateColorDot(color)
    }

    private fun currentState(): ToolState =
        toolStates.getOrPut(drawingCanvas.currentTool) {
            service.appPreferences.getToolState(drawingCanvas.currentTool)
        }

    private fun persistState() {
        val tool  = drawingCanvas.currentTool
        val state = toolStates[tool] ?: return
        service.appPreferences.saveToolState(tool, state)
    }

    private fun toHex(color: Int): String = "#%06X".format(0xFFFFFF and color)
    private fun inflate(id: Int): View = LayoutInflater.from(service).inflate(id, null)
    private fun addPanel(view: View) = windowManager.addView(view, buildPanelParams())
    private fun dismissPanel(view: View?) {
        try { view?.let { windowManager.removeView(it) } } catch (_: Exception) { }
    }

    private fun buildPanelParams(): WindowManager.LayoutParams {
        val isH  = service.appPreferences.toolbarOrientation == ToolbarOrientation.HORIZONTAL
        val pxD  = service.resources.displayMetrics.density
        val px   = { dp: Int -> (dp * pxD).toInt() }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x       = if (isH) px(30) else px(90)
            y       = if (isH) px(130) else px(50)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
