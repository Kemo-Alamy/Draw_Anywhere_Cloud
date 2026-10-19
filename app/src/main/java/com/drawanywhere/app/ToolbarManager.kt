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
    private var hasContent    = false

    private var dragStartRawX  = 0f
    private var dragStartRawY  = 0f
    private var windowInitX    = 0
    private var windowInitY    = 0
    private var isDragging     = false
    private val DRAG_THRESHOLD = 10f

    private val longPressHandler   = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private val LONG_PRESS_MS      = 3000L

    private val toolIconMap = mapOf(
        DrawingTool.PEN           to R.drawable.ic_pen,
        DrawingTool.MATTE_PEN     to R.drawable.ic_matte_pen,
        DrawingTool.LASER         to R.drawable.ic_laser,
        DrawingTool.BRUSH         to R.drawable.ic_brush,
        DrawingTool.HIGHLIGHTER   to R.drawable.ic_highlighter,
        DrawingTool.CALLIGRAPHY   to R.drawable.ic_calligraphy,
        DrawingTool.LASSO         to R.drawable.ic_lasso,
        DrawingTool.RECTANGLE     to R.drawable.ic_rectangle,
        DrawingTool.ELLIPSE       to R.drawable.ic_ellipse,
        DrawingTool.STROKE_ERASER to R.drawable.ic_stroke_eraser,
        DrawingTool.PIXEL_ERASER  to R.drawable.ic_pixel_eraser
    )

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun init(orientation: ToolbarOrientation) = inflate(orientation)

    fun destroy() {
        fadeHandler.removeCallbacksAndMessages(null)
        longPressHandler.removeCallbacksAndMessages(null)
        try { toolbarView?.let { windowManager.removeView(it) } } catch (_: Exception) { }
        toolbarView = null; toolbarParams = null
    }

    fun changeOrientation(orientation: ToolbarOrientation) { destroy(); inflate(orientation) }

    // ─── Public Sync ──────────────────────────────────────────────────────────

    fun updateColorDot(color: Int) {
        toolbarView?.findViewById<View>(R.id.view_color_dot)?.setBackgroundColor(color)
    }

    /**
     * ✅ يتحكم في شكل زرار اليد (Finger):
     * لونه أزرق/مميز لما Touch Pass-Through شغّال (يعني الرسم معطّل)
     * ولونه عادي لما الرسم شغّال
     */
    fun updateFingerIndicator(drawingEnabled: Boolean) {
        val icon = toolbarView?.findViewById<ImageView>(R.id.btn_finger) ?: return
        icon.setColorFilter(
            if (drawingEnabled) service.getColor(R.color.toolbar_icon_default)
            else                service.getColor(R.color.toolbar_icon_passthrough_active)
        )
    }

    /** ✅ تحديث حالة Undo/Trash — باهت لو الـ Canvas فاضي، واضح لو فيه محتوى */
    fun updateContentState(hasContentNow: Boolean) {
        hasContent = hasContentNow
        val v = toolbarView ?: return
        val alpha = if (hasContentNow) 1f else 0.35f
        v.findViewById<ImageView>(R.id.btn_undo)?.alpha = alpha
        v.findViewById<ImageView>(R.id.btn_undo)?.isEnabled = hasContentNow
        v.findViewById<ImageView>(R.id.btn_clear)?.alpha = alpha
        v.findViewById<ImageView>(R.id.btn_clear)?.isEnabled = hasContentNow
    }

    fun updateToolIcon(tool: DrawingTool) {
        val iconRes = toolIconMap[tool] ?: R.drawable.ic_pen
        toolbarView?.findViewById<ImageView>(R.id.btn_tools)?.setImageResource(iconRes)
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

        view.findViewById<View>(R.id.view_color_dot)?.setBackgroundColor(drawingCanvas.currentColor)
        view.findViewById<ImageView>(R.id.btn_tools)
            ?.setImageResource(toolIconMap[drawingCanvas.currentTool] ?: R.drawable.ic_pen)

        setupButtons(view)
        setupDrag(view, params)
        scheduleIdleFade()
        windowManager.addView(view, params)

        // ✅ تطبيق الحالة الحالية فوراً بعد الإضافة للشاشة
        updateFingerIndicator(service.appPreferences.fingerDrawing)
        updateContentState(hasContent)
    }

    // ─── Buttons ──────────────────────────────────────────────────────────────

    private fun setupButtons(view: View) {

        // ① Eye — يخفي/يُظهر الرسمة فقط (الـ Toolbar نفسه بيفضل زي ما هو، مش بيتحول لشكل تاني)
        view.findViewById<ImageView>(R.id.btn_hide)?.setOnClickListener {
            resetFadeTimer(); toggleCanvasVisibility()
        }

        // ② Undo — شغال بس لو فيه محتوى
        view.findViewById<ImageView>(R.id.btn_undo)?.setOnClickListener {
            if (!hasContent) return@setOnClickListener
            resetFadeTimer(); drawingCanvas.undo()
        }

        // ③ Clear/Trash — شغال بس لو فيه محتوى، وبعد المسح بيرجع باهت فوراً
        view.findViewById<ImageView>(R.id.btn_clear)?.setOnClickListener {
            if (!hasContent) return@setOnClickListener
            resetFadeTimer(); drawingCanvas.clearAll()
        }

        view.findViewById<View>(R.id.btn_add_image)?.setOnClickListener {
            resetFadeTimer(); panelManager.dismissAll()
            service.launchImagePicker()
        }

        // ④ Tools — ضغط عادي = قائمة الأدوات | ضغط 3 ثواني = نمط الخط
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
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressHandler.removeCallbacksAndMessages(null); false
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

        // ⑤ Color Dot — ضغط عادي يفتح Color Panel
        view.findViewById<View>(R.id.view_color_dot)?.setOnClickListener {
            resetFadeTimer(); panelManager.toggleColorPanel()
        }

        // ⑥ Finger — يبدل Touch Pass-Through فعلياً (مش شكلي)
        view.findViewById<ImageView>(R.id.btn_finger)?.setOnClickListener {
            resetFadeTimer()
            val newState = !service.appPreferences.fingerDrawing
            service.appPreferences.fingerDrawing = newState
            service.refreshCanvasTouchMode()
        }

        // ⑦ Settings
        view.findViewById<View>(R.id.btn_settings)?.setOnClickListener {
            resetFadeTimer(); panelManager.toggleSettingsPanel()
        }
    }

    // ─── Toggle Canvas Visibility (Eye) ───────────────────────────────────────

    private fun toggleCanvasVisibility() {
        canvasVisible = !canvasVisible
        if (!canvasVisible && service.appPreferences.clearOnHide)
            drawingCanvas.clearAll()
        drawingCanvas.visibility = if (canvasVisible) View.VISIBLE else View.INVISIBLE
        service.setCanvasTouchable(canvasVisible && service.appPreferences.fingerDrawing)

        // ✅ العين نفسها بتتغير شكل (مفتوحة/مقفولة) بدل ما التولبار كله يتحول لشكل غريب
        val eyeIcon = toolbarView?.findViewById<ImageView>(R.id.btn_hide)
        eyeIcon?.setImageResource(if (canvasVisible) R.drawable.ic_eye else R.drawable.ic_eye_closed)
    }

    // ─── Drag ─────────────────────────────────────────────────────────────────

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            restoreOpacity(); resetFadeTimer()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    dragStartRawX = event.rawX; dragStartRawY = event.rawY
                    windowInitX = params.x; windowInitY = params.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!isDragging && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) isDragging = true
                    if (isDragging) {
                        params.x = (windowInitX + dx).toInt()
                        params.y = (windowInitY + dy).toInt()
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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

    private fun resetFadeTimer() { if (isFaded) restoreOpacity(); scheduleIdleFade() }

    private fun fadeOut() {
        val v = toolbarView ?: return
        isFaded = true
        ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 0.28f).apply { duration = 500L; start() }
    }

    private fun restoreOpacity() {
        val v = toolbarView ?: return
        if (!isFaded) return
        isFaded = false
        ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 1f).apply { duration = 120L; start() }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
