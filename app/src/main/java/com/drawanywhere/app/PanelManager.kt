package com.drawanywhere.app

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.*
import androidx.viewpager2.widget.ViewPager2

class PanelManager(
    private val service: DrawingService,
    private val windowManager: WindowManager
) {
    lateinit var drawingCanvas: DrawingCanvas
    lateinit var toolbarManager: ToolbarManager

    private var colorPanelView:     View? = null
    private var toolsPanelView:     View? = null
    private var settingsPanelView:  View? = null
    private var lineStylePanelView: View? = null

    private val toolStates = mutableMapOf<DrawingTool, ToolState>()

    init {
        DrawingTool.entries.forEach { tool ->
            toolStates[tool] = service.appPreferences.getToolState(tool)
        }
    }

    // ─── Dismiss ─────────────────────────────────────────────────────────────

    fun dismissAll() {
        listOf(colorPanelView, toolsPanelView, settingsPanelView, lineStylePanelView)
            .forEach { dismissPanel(it) }
        colorPanelView = null; toolsPanelView = null
        settingsPanelView = null; lineStylePanelView = null
    }

    // ─── Tools Panel (نفس مقاس popup_unified بدون Pager) ─────────────────────

    fun toggleToolsPanel() {
        if (toolsPanelView != null) {
            dismissPanel(toolsPanelView); toolsPanelView = null; return
        }
        dismissAll(); showToolsPanel()
    }

    private fun showToolsPanel() {
        val container = inflate(R.layout.popup_unified_container_single)
        toolsPanelView = container

        val innerContainer = container.findViewById<LinearLayout>(R.id.unified_single_content)
        LayoutInflater.from(service).inflate(R.layout.panel_tools, innerContainer, true)

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
            container.findViewById<TextView>(id)?.let { btn ->
                val isActive = drawingCanvas.currentTool == tool
                btn.setBackgroundResource(
                    if (isActive) R.drawable.bg_tool_item_selected else R.drawable.bg_tool_item_default
                )
                btn.setTextColor(
                    if (isActive) service.getColor(R.color.tool_item_text_selected)
                    else          service.getColor(R.color.tool_item_text_default)
                )
                btn.setOnClickListener { selectTool(tool) }
            }
        }
        addPanel(container, anchorTo = PanelAnchor.TOOLS_BUTTON)
    }

    private fun selectTool(tool: DrawingTool) {
        val state = toolStates.getOrPut(tool) { service.appPreferences.getToolState(tool) }
        drawingCanvas.switchTool(tool, state)
        toolbarManager.updateColorDot(state.color)
        toolbarManager.updateToolIcon(tool)
        dismissPanel(toolsPanelView); toolsPanelView = null
    }

    // ─── Line Style Panel (نفس مقاس popup_unified بدون Pager) ────────────────

    fun showLineStylePanel() {
        dismissAll()
        val container = inflate(R.layout.popup_unified_container_single)
        lineStylePanelView = container
        val innerContainer = container.findViewById<LinearLayout>(R.id.unified_single_content)
        val pxD = service.resources.displayMetrics.density

        innerContainer.addView(TextView(service).apply {
            text = "Line Style  (Pen)"
            setTextColor(service.getColor(R.color.panel_subtext))
            textSize = 11f
            setPadding((14 * pxD).toInt(), (6 * pxD).toInt(), (14 * pxD).toInt(), (10 * pxD).toInt())
        })

        val currentStyle = toolStates[DrawingTool.PEN]?.lineStyle ?: LineStyle.SOLID
        for (style in LineStyle.entries) {
            val isActive = currentStyle == style
            innerContainer.addView(TextView(service).apply {
                text = style.label
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (46 * pxD).toInt()
                ).apply {
                    marginStart = (10 * pxD).toInt(); marginEnd = (10 * pxD).toInt()
                    bottomMargin = (4 * pxD).toInt()
                }
                setBackgroundResource(
                    if (isActive) R.drawable.bg_tool_item_selected else R.drawable.bg_tool_item_default
                )
                setTextColor(
                    if (isActive) service.getColor(R.color.tool_item_text_selected)
                    else          service.getColor(R.color.tool_item_text_default)
                )
                setOnClickListener { selectLineStyle(style) }
            })
        }
        addPanel(container, anchorTo = PanelAnchor.TOOLS_BUTTON)
    }

    private fun selectLineStyle(style: LineStyle) {
        val state = toolStates.getOrPut(DrawingTool.PEN) { service.appPreferences.getToolState(DrawingTool.PEN) }
        state.lineStyle = style
        if (drawingCanvas.currentTool == DrawingTool.PEN) drawingCanvas.updateLineStyle(style)
        service.appPreferences.saveToolState(DrawingTool.PEN, state)
        dismissPanel(lineStylePanelView); lineStylePanelView = null
    }

    // ─── Color Panel (الحاوية الموحدة popup_unified_container) ───────────────

    fun toggleColorPanel() {
        if (colorPanelView != null) {
            dismissPanel(colorPanelView); colorPanelView = null; return
        }
        dismissAll(); showColorPanel()
    }

    private fun showColorPanel() {
        val v = inflate(R.layout.popup_unified_container)
        colorPanelView = v

        val pager = v.findViewById<ViewPager2>(R.id.unified_pager)
        val dot0  = v.findViewById<View>(R.id.unified_dot_0)
        val dot1  = v.findViewById<View>(R.id.unified_dot_1)

        val adapter = ColorPagerAdapter(
            onPage1Ready = { page -> bindColorPage1(page) },
            onPage2Ready = { page -> bindColorPage2(page) }
        )
        pager?.adapter = adapter
        pager?.offscreenPageLimit = 2

        pager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dot0?.setBackgroundResource(if (position == 0) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)
                dot1?.setBackgroundResource(if (position == 1) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)
            }
        })

        addPanel(v, anchorTo = PanelAnchor.COLOR_DOT)
    }

    private fun bindColorPage1(page: View) {
        page.findViewById<View>(R.id.view_current_color)?.setBackgroundColor(drawingCanvas.currentColor)

        val swatchIds = listOf(
            R.id.color_01, R.id.color_02, R.id.color_03, R.id.color_04, R.id.color_05, R.id.color_06,
            R.id.color_07, R.id.color_08, R.id.color_09, R.id.color_10, R.id.color_11, R.id.color_12,
            R.id.color_16, R.id.color_17, R.id.color_18, R.id.color_19, R.id.color_20
        )
        val colorResIds = listOf(
            R.color.preset_01, R.color.preset_02, R.color.preset_03, R.color.preset_04, R.color.preset_05, R.color.preset_06,
            R.color.preset_07, R.color.preset_08, R.color.preset_09, R.color.preset_10, R.color.preset_11, R.color.preset_12,
            R.color.preset_16, R.color.preset_17, R.color.preset_18, R.color.preset_19, R.color.preset_20
        )
        swatchIds.forEachIndexed { i, id ->
            page.findViewById<View>(id)?.let { sw ->
                val color = service.getColor(colorResIds[i])
                sw.setBackgroundColor(color)
                sw.setOnClickListener {
                    applyColor(color)
                    dismissPanel(colorPanelView); colorPanelView = null
                }
            }
        }

        val state      = currentState()
        val widthLabel = page.findViewById<TextView>(R.id.label_width_value)
        page.findViewById<SeekBar>(R.id.seek_width)?.apply {
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

        val opacityLabel = page.findViewById<TextView>(R.id.label_opacity_value)
        page.findViewById<SeekBar>(R.id.seek_opacity)?.apply {
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
    }

    private fun bindColorPage2(page: View) {
        val picker     = page.findViewById<HsvColorPickerView>(R.id.hsv_picker)
        val tvHex      = page.findViewById<TextView>(R.id.tv_hex_value)
        val seekBright = page.findViewById<SeekBar>(R.id.seek_brightness)
        val applyBtn   = page.findViewById<View>(R.id.btn_apply)

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
            dismissPanel(colorPanelView); colorPanelView = null
        }
    }

    // ─── Settings Panel (الحاوية الموحدة popup_unified_container) ────────────

    fun toggleSettingsPanel() {
        if (settingsPanelView != null) {
            dismissPanel(settingsPanelView); settingsPanelView = null; return
        }
        dismissAll(); showSettingsPanel()
    }

    private fun showSettingsPanel() {
        val v = inflate(R.layout.popup_unified_container)
        settingsPanelView = v

        val pager = v.findViewById<ViewPager2>(R.id.unified_pager)
        val dot0  = v.findViewById<View>(R.id.unified_dot_0)
        val dot1  = v.findViewById<View>(R.id.unified_dot_1)

        val adapter = SettingsPagerAdapter(
            onSettingsPageReady = { page -> bindSettingsPage(page) },
            onAboutPageReady    = { /* عرض فقط */ }
        )
        pager?.adapter = adapter
        pager?.offscreenPageLimit = 2

        pager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dot0?.setBackgroundResource(if (position == 0) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)
                dot1?.setBackgroundResource(if (position == 1) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)
            }
        })

        addPanel(v, anchorTo = PanelAnchor.SETTINGS_BUTTON)
    }

    private fun bindSettingsPage(page: View) {
        val prefs = service.appPreferences
        val btnH  = page.findViewById<TextView>(R.id.btn_horizontal)
        val btnV  = page.findViewById<TextView>(R.id.btn_vertical)

        fun refreshOrient(o: ToolbarOrientation) {
            val sel = R.drawable.bg_setting_selected
            val uns = R.drawable.bg_setting_unselected
            val selT = service.getColor(R.color.setting_selected_text)
            val unsT = service.getColor(R.color.setting_unselected_text)
            if (o == ToolbarOrientation.HORIZONTAL) {
                btnH?.setBackgroundResource(sel); btnH?.setTextColor(selT)
                btnV?.setBackgroundResource(uns); btnV?.setTextColor(unsT)
            } else {
                btnV?.setBackgroundResource(sel); btnV?.setTextColor(selT)
                btnH?.setBackgroundResource(uns); btnH?.setTextColor(unsT)
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

        page.findViewById<CheckBox>(R.id.chk_clear_on_hide)?.apply {
            isChecked = prefs.clearOnHide
            setOnCheckedChangeListener { _, c -> prefs.clearOnHide = c }
        }
        page.findViewById<CheckBox>(R.id.chk_open_on_start)?.apply {
            isChecked = prefs.openOnStart
            setOnCheckedChangeListener { _, c -> prefs.openOnStart = c }
        }
        page.findViewById<CheckBox>(R.id.chk_finger_drawing)?.apply {
            isChecked = prefs.fingerDrawing
            setOnCheckedChangeListener { _, c ->
                prefs.fingerDrawing = c; service.refreshCanvasTouchMode()
            }
        }
        page.findViewById<View>(R.id.btn_quit)?.setOnClickListener {
            dismissAll(); service.stopSelf()
        }
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

    private fun addPanel(view: View, anchorTo: PanelAnchor) =
        windowManager.addView(view, buildPanelParams(anchorTo))

    private fun dismissPanel(view: View?) {
        try { view?.let { windowManager.removeView(it) } } catch (_: Exception) { }
    }

    private enum class PanelAnchor { TOOLS_BUTTON, COLOR_DOT, SETTINGS_BUTTON }

    /**
     * ✅ تثبيت الـ Popup جنب الزرار المسؤول عنه مباشرة
     * كل الـ Popups دلوقتي بنفس الحجم بالظبط (240×330dp) فمفيش قفزة في الحجم بينهم
     */
    private fun buildPanelParams(anchorTo: PanelAnchor): WindowManager.LayoutParams {
        val isH = service.appPreferences.toolbarOrientation == ToolbarOrientation.HORIZONTAL
        val pxD = service.resources.displayMetrics.density
        val px  = { dp: Int -> (dp * pxD).toInt() }

        val baseX = px(30); val baseY = px(80)

        val (offsetX, offsetY) = when (anchorTo) {
            PanelAnchor.TOOLS_BUTTON    -> if (isH) Pair(0, px(56))     else Pair(px(58), px(130))
            PanelAnchor.COLOR_DOT       -> if (isH) Pair(0, px(56))     else Pair(px(58), px(170))
            PanelAnchor.SETTINGS_BUTTON -> if (isH) Pair(px(-70), px(56)) else Pair(px(58), px(260))
        }

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
            x = baseX + offsetX
            y = baseY + offsetY
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
