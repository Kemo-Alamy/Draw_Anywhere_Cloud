package com.drawanywhere.app

import android.animation.ObjectAnimator
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.ImageView
import kotlin.math.abs

class ToolbarManager(
    private val service: DrawingService,
    private val windowManager: WindowManager
) {
    lateinit var drawingCanvas: DrawingCanvas
    lateinit var panelManager: PanelManager

    private var toolbarView:   View? = null
    private var toolbarParams: WindowManager.LayoutParams? = null

    private val fadeHandler   = Handler(Looper.getMainLooper())
    private val FADE_DELAY_MS = 3000L
    private var isFaded       = false
    private var canvasVisible = true

    // Drag
    private var dragStartRawX  = 0f
    private var dragStartRawY  = 0f
    private var windowInitX    = 0
    private var windowInitY    = 0
    private var isDragging     = false
    private val DRAG_THRESHOLD = 10f

    // Long press على زرار الأدوات (3 ثواني → يفتح نمط الخط)
    private val longPressHandler     = Handler(Looper.getMainLooper())
    private var longPressTriggered   = false
    private val LONG_PRESS_MS        = 3000L

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun init(orientation: ToolbarOrientation) = inflate(orientation)

    fun destroy() {
        fadeHandler.removeCallbacksAndMessages(null)
        longPressHandler.removeCallbacksAndMessages(null)
        try { toolbarView?.let { windowManager.removeView(it) } } catch (e: Exception) { }
        toolbarView = null; toolbarParams = null
    }

    fun changeOrientation(orientation: ToolbarOrientation) { destroy(); inflate(orientation) }

    // ─── Public Sync ──────────────────────────────────────────────────────────

    fun updateColorDot(color: Int) {
        toolbarView?.findViewById<View>(R.id.view_color_dot)
            ?.setBackgroundColor(color)
    }

    fun updateFingerIndicator(enabled: Boolean) {
        toolbarView?.findViewById<ImageView>(R.id.view_finger_indicator)
            ?.alpha = if (enabled) 1f else 0.35f
    }

    // ─── Inflate ──────────────────────────────────────────────────────────────

    private fun inflate(orientation: ToolbarOrientation) {
        val layoutId = if (orientation == ToolbarOrientation.HORIZONTAL)
            R.layout.overlay_toolbar_horizontal
        else
            R.layout.overlay_toolbar_vertical

        val view = LayoutInflater.from(service).inflate(layoutId, null)
        toolbarView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 80 }
        toolbarParams = params

        view.findViewById<View>(R.id.view_color_dot)
            ?.setBackgroundColor(drawingCanvas.currentColor)
        view.findViewById<ImageView>(R.id.view_finger_indicator)
            ?.alpha = if (service.appPreferences.fingerDrawing) 1f else 0.35f

        setupButtons(view)
        setupDrag(view, params)
        scheduleIdleFade()
        windowManager.addView(view, params)
    }

    // ─── Buttons ──────────────────────────────────────────────────────────────

    private fun setupButtons(view: View) {

        // ① Eye — يخفي/يُظهر الـ Canvas ويُصغّر الـ Toolbar
        view.findViewById<ImageView>(R.id.btn_hide)?.setOnClickListener {
            resetFadeTimer(); toggleCanvas()
        }

        // ② Undo
        view.findViewById<View>(R.id.btn_undo)?.setOnClickListener {
            resetFadeTimer(); drawingCanvas.undo()
        }

        // ③ Clear (vertical only)
        view.findViewById<View>(R.id.btn_clear)?.setOnClickListener {
            resetFadeTimer(); drawingCanvas.clearAll()
        }

        // ④ Add Image (vertical only)
        view.findViewById<View>(R.id.btn_add_image)?.setOnClickListener {
            resetFadeTimer(); panelManager.dismissAll()
            service.launchImagePicker()
        }

        // ⑤ Tools — click عادي يفتح الأدوات
        //            ضغط 3 ثواني يفتح نمط الخط
        view.findViewById<View>(R.id.btn_tools)?.let { btn ->
            btn.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressTriggered = false
                        resetFadeTimer()
                        longPressHandler.postDelayed({
                            longPressTriggered = true
                            panelManager.showLineStylePanel()
                        }, LONG_PRESS_MS)
                        false
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        longPressHandler.removeCallbacksAndMessages(null)
                        false
                    }
                    else -> false
                }
            }
            btn.setOnClickListener {
                resetFadeTimer()
                if (!longPressTriggered) panelManager.toggleToolsPanel()
                longPressTriggered = false
            }
        }

        // ⑥ Color Dot
        view.findViewById<View>(R.id.view_color_dot)?.setOnClickListener {
            resetFadeTimer(); panelManager.toggleColorPanel()
        }

        // ⑦ Settings
        view.findViewById<View>(R.id.btn_settings)?.setOnClickListener {
            resetFadeTimer(); panelManager.toggleSettingsPanel()
        }
    }

    // ─── Toggle Canvas + Collapse Toolbar ────────────────────────────────────

    private fun toggleCanvas() {
        canvasVisible = !canvasVisible
        if (!canvasVisible && service.appPreferences.clearOnHide)
            drawingCanvas.clearAll()
        drawingCanvas.visibility =
            if (canvasVisible) View.VISIBLE else View.INVISIBLE
        service.setCanvasTouchable(
            canvasVisible && service.appPreferences.fingerDrawing
        )
        collapseToolbar(!canvasVisible)
    }

    /**
     * لما الـ Canvas مخفي → يُخفي كل حاجة في الـ Toolbar غير زرار العين
     * الـ Toolbar يبقى صغير زي دايرة
     */
    private fun collapseToolbar(collapse: Boolean) {
        val v = toolbarView ?: return
        val vis = if (collapse) View.GONE else View.VISIBLE
        listOf(
            R.id.btn_undo, R.id.btn_clear, R.id.btn_add_image,
            R.id.btn_tools, R.id.view_color_dot,
            R.id.view_finger_indicator, R.id.btn_settings,
            R.id.toolbar_divider
        ).forEach { id -> v.findViewById<View>(id)?.visibility = vis }

        // تغيير لون العين: أحمر لما Canvas مخفي، أبيض لما شغال
        v.findViewById<ImageView>(R.id.btn_hide)?.setColorFilter(
            if (collapse)
                service.getColor(R.color.toolbar_icon_active)
            else
                service.getColor(R.color.toolbar_icon_default)
        )
    }

    // ─── Drag ─────────────────────────────────────────────────────────────────

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            restoreOpacity(); resetFadeTimer()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging    = false
                    dragStartRawX = event.rawX; dragStartRawY = event.rawY
                    windowInitX   = params.x;   windowInitY   = params.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!isDragging &&
                        (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD))
                        isDragging = true
                    if (isDragging) {
                        params.x = (windowInitX + dx).toInt()
                        params.y = (windowInitY + dy).toInt()
                        try { windowManager.updateViewLayout(view, params) }
                        catch (_: Exception) {}
                        true
                    } else false
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    val was = isDragging; isDragging = false; was
                }
                else -> false
            }
        }
    }

    // ─── Idle Fade ────────────────────────────────────────────────────────────

    private fun scheduleIdleFade() {
        fadeHandler.removeCallbacksAndMessages(null)
        fadeHandler.postDelayed({ fadeOut() }, FADE_DELAY_MS)
    }

    private fun resetFadeTimer() {
        if (isFaded) restoreOpacity(); scheduleIdleFade()
    }

    private fun fadeOut() {
        val v = toolbarView ?: return
        isFaded = true
        ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 0.28f)
            .apply { duration = 500L; start() }
    }

    private fun restoreOpacity() {
        val v = toolbarView ?: return
        if (!isFaded) return
        isFaded = false
        ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 1f)
            .apply { duration = 120L; start() }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
