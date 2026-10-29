package com.drawanywhere.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.animation.DecelerateInterpolator
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

    private var dragStartRawX  = 0f
    private var dragStartRawY  = 0f
    private var windowInitX    = 0
    private var windowInitY    = 0
    private var isDragging     = false
    private val DRAG_THRESHOLD = 10f

    private var eyeAnimator: ValueAnimator? = null
    private var isScrollExpanded = false  // حالة الـ scroll section

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

    private var hasContent = false

    fun init(orientation: ToolbarOrientation) = inflate(orientation)

    fun destroy() {
        fadeHandler.removeCallbacksAndMessages(null)
        eyeAnimator?.cancel()
        try { toolbarView?.let { windowManager.removeView(it) } } catch (_: Exception) { }
        toolbarView = null; toolbarParams = null
    }

    fun changeOrientation(orientation: ToolbarOrientation) { destroy(); inflate(orientation) }

    fun updateColorDot(color: Int) {
        toolbarView?.findViewById<ColorDropView>(R.id.view_color_dot)?.setColor(color)
    }

    fun updateFingerIndicator(drawingEnabled: Boolean) {
        val icon = toolbarView?.findViewById<ImageView>(R.id.btn_finger) ?: return
        if (drawingEnabled) {
            icon.setImageResource(R.drawable.ic_finger)
            icon.setColorFilter(service.getColor(R.color.toolbar_icon_default))
        } else {
            icon.setImageResource(R.drawable.ic_touch_passthrough)
            icon.setColorFilter(service.getColor(R.color.toolbar_icon_passthrough_active))
        }
    }

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

    private fun inflate(orientation: ToolbarOrientation) {
        val layoutId = if (orientation == ToolbarOrientation.HORIZONTAL)
            R.layout.overlay_toolbar_horizontal else R.layout.overlay_toolbar_vertical
        val view = LayoutInflater.from(service).inflate(layoutId, null)
        toolbarView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 80 }
        toolbarParams = params

        view.findViewById<ImageView>(R.id.btn_tools)?.setImageResource(
            toolIconMap[drawingCanvas.currentTool] ?: R.drawable.ic_pen
        )

        // Initial state: scroll hidden
        isScrollExpanded = false
        view.findViewById<View>(R.id.toolbar_scroll_container)?.visibility = View.GONE
        view.findViewById<View>(R.id.toolbar_divider)?.visibility = View.GONE

        setupButtons(view)
        setupDrag(view, params)
        scheduleIdleFade()
        windowManager.addView(view, params)

        updateColorDot(drawingCanvas.currentColor)
        updateFingerIndicator(service.appPreferences.fingerDrawing)
        updateContentState(hasContent)
    }

    private fun setupButtons(view: View) {

        view.findViewById<ImageView>(R.id.btn_hide)?.setOnClickListener {
            resetFadeTimer(); toggleCanvasVisibility()
        }

        view.findViewById<ImageView>(R.id.btn_undo)?.setOnClickListener {
            if (!hasContent) return@setOnClickListener
            resetFadeTimer(); drawingCanvas.undo()
        }

        // ✅ Redo
        val btnRedo = view.findViewById<ImageView>(R.id.btn_redo)
        btnRedo?.alpha = 0.35f
        btnRedo?.isEnabled = false
        btnRedo?.setOnClickListener { resetFadeTimer(); drawingCanvas.redo() }
        drawingCanvas.onRedoStateChanged = { hasRedo ->
            view.post {
                btnRedo?.animate()?.alpha(if (hasRedo) 1f else 0.35f)?.setDuration(150L)?.start()
                btnRedo?.isEnabled = hasRedo
            }
        }

        view.findViewById<ImageView>(R.id.btn_clear)?.setOnClickListener {
            if (!hasContent) return@setOnClickListener
            resetFadeTimer(); drawingCanvas.clearAll()
        }

        view.findViewById<View>(R.id.btn_add_image)?.setOnClickListener {
            resetFadeTimer(); panelManager.dismissAll(); service.launchImagePicker()
        }

        attachToolsButtonGestures(view)

        view.findViewById<View>(R.id.view_color_dot)?.setOnClickListener {
            resetFadeTimer(); panelManager.toggleColorPanel()
        }

        view.findViewById<ImageView>(R.id.btn_finger)?.setOnClickListener {
            resetFadeTimer()
            val newState = !service.appPreferences.fingerDrawing
            service.appPreferences.fingerDrawing = newState
            service.refreshCanvasTouchMode()
        }

        view.findViewById<View>(R.id.btn_settings)?.setOnClickListener {
            resetFadeTimer(); panelManager.toggleSettingsPanel()
        }

        // ✅ السهم — بيوسع/يطوي الأزرار الإضافية
        val arrowContainer  = view.findViewById<View>(R.id.btn_collapse_arrow_container)
        val arrowIcon       = view.findViewById<ImageView>(R.id.btn_collapse_arrow)
        val scrollContainer = view.findViewById<View>(R.id.toolbar_scroll_container)
        val divider         = view.findViewById<View>(R.id.toolbar_divider)

        arrowContainer?.setOnClickListener {
            resetFadeTimer()
            isScrollExpanded = !isScrollExpanded
            if (isScrollExpanded) {
                scrollContainer?.visibility = View.VISIBLE
                divider?.visibility = View.VISIBLE
            } else {
                scrollContainer?.visibility = View.GONE
                divider?.visibility = View.GONE
            }
            // السهم بيتقلب 180° للأعلى لما الـ scroll يظهر، وبيرجع لما يختفي
            arrowIcon?.animate()
                ?.rotation(if (isScrollExpanded) 180f else 0f)
                ?.setDuration(250L)?.start()
            // تحديث حجم الـ window
            view.post {
                try { windowManager.updateViewLayout(view, toolbarParams ?: return@post) }
                catch (_: Exception) { }
            }
        }
    }

    private fun attachToolsButtonGestures(view: View) {
        val btn = view.findViewById<View>(R.id.btn_tools) ?: return
        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
        var pressDownX = 0f; var pressDownY = 0f
        var longPressArmed = false; var longPressFired = false
        val LONG_PRESS_MS = 3000L

        val longPressRunnable = Runnable {
            if (!longPressArmed || longPressFired) return@Runnable
            longPressFired = true
            btn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            resetFadeTimer(); panelManager.togglePenNibsPopup()
        }

        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressArmed = true; longPressFired = false
                    pressDownX = event.rawX; pressDownY = event.rawY
                    v.postDelayed(longPressRunnable, LONG_PRESS_MS); true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!longPressArmed) return@setOnTouchListener false
                    val dx = abs(event.rawX - pressDownX); val dy = abs(event.rawY - pressDownY)
                    if (dx > touchSlop * 3 || dy > touchSlop * 3) {
                        longPressArmed = false; v.removeCallbacks(longPressRunnable)
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)
                    if (!longPressFired && longPressArmed) {
                        v.performClick(); resetFadeTimer(); panelManager.toggleToolsPanel()
                    }
                    longPressArmed = false; longPressFired = false; true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    longPressArmed = false; longPressFired = false; true
                }
                else -> false
            }
        }
        btn.setOnClickListener(null)
    }

    private fun toggleCanvasVisibility() {
        canvasVisible = !canvasVisible
        if (!canvasVisible && service.appPreferences.clearOnHide) drawingCanvas.clearAll()
        drawingCanvas.visibility = if (canvasVisible) View.VISIBLE else View.INVISIBLE
        service.setCanvasTouchable(canvasVisible && service.appPreferences.fingerDrawing)
        toolbarView?.findViewById<ImageView>(R.id.btn_hide)
            ?.setImageResource(if (canvasVisible) R.drawable.ic_eye else R.drawable.ic_eye_closed)
        collapseToolbar(!canvasVisible)
    }

    // ✅ الإصلاح الكامل: background على الـ child مش الـ root + تقسيم core/scroll
    private fun collapseToolbar(collapse: Boolean) {
        val v = toolbarView as? ViewGroup ?: return
        val params = toolbarParams ?: return
        eyeAnimator?.cancel()

        // ✅ الـ background يتطبق على toolbar_content_row/col مش على الـ root
        val contentView: View = v.findViewById(R.id.toolbar_content_row)
            ?: v.findViewById(R.id.toolbar_content_col)
            ?: v

        // الأزرار الأساسية (تتأنم مع العين)
        val coreViewIds = listOf(
            R.id.btn_undo, R.id.btn_redo, R.id.btn_clear,
            R.id.btn_tools, R.id.view_color_dot,
            R.id.btn_collapse_arrow_container
        )
        // قسم الـ scroll (بيتخفى مع العين + بيتحكم فيه السهم)
        val scrollViewIds = listOf(
            R.id.toolbar_divider,
            R.id.toolbar_scroll_container
        )

        val coreViews   = coreViewIds.mapNotNull { v.findViewById<View>(it) }
        val scrollViews = scrollViewIds.mapNotNull { v.findViewById<View>(it) }
        val allViews    = coreViews + scrollViews

        if (!collapse) {
            // فتح العين: أظهر الـ core، واستعد الـ scroll لو كان مفتوح
            contentView.setBackgroundResource(R.drawable.bg_toolbar)
            coreViews.forEach { it.alpha = 0f; it.visibility = View.VISIBLE }
            if (isScrollExpanded) {
                scrollViews.forEach { it.alpha = 0f; it.visibility = View.VISIBLE }
            }
            // ابدأ بـ rotation صح للسهم
            v.findViewById<ImageView>(R.id.btn_collapse_arrow)?.rotation =
                if (isScrollExpanded) 180f else 0f
            try { windowManager.updateViewLayout(v, params) } catch (_: Exception) { }
        }

        val viewsToAnimate: List<View> = if (collapse) allViews
        else coreViews + if (isScrollExpanded) scrollViews else emptyList()

        eyeAnimator = ValueAnimator.ofFloat(
            if (collapse) 1f else 0f,
            if (collapse) 0f else 1f
        ).apply {
            duration = 280L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val a = anim.animatedValue as Float
                viewsToAnimate.forEach { it.alpha = a }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (collapse) {
                        allViews.forEach { it.visibility = View.GONE; it.alpha = 1f }
                        contentView.setBackgroundResource(R.drawable.bg_toolbar_collapsed)
                        try { windowManager.updateViewLayout(v, params) } catch (_: Exception) { }
                    }
                }
            })
            start()
        }
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            restoreOpacity(); resetFadeTimer()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false; dragStartRawX = event.rawX; dragStartRawY = event.rawY
                    windowInitX = params.x; windowInitY = params.y; false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX; val dy = event.rawY - dragStartRawY
                    if (!isDragging && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) isDragging = true
                    if (isDragging) {
                        params.x = (windowInitX + dx).toInt(); params.y = (windowInitY + dy).toInt()
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { val was = isDragging; isDragging = false; was }
                else -> false
            }
        }
    }

    private fun scheduleIdleFade() {
        fadeHandler.removeCallbacksAndMessages(null)
        fadeHandler.postDelayed({ fadeOut() }, FADE_DELAY_MS)
    }
    private fun resetFadeTimer() { if (isFaded) restoreOpacity(); scheduleIdleFade() }
    private fun fadeOut() {
        val v = toolbarView ?: return; isFaded = true
        ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 0.28f).apply { duration = 500L; start() }
    }
    private fun restoreOpacity() {
        val v = toolbarView ?: return; if (!isFaded) return; isFaded = false
        ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 1f).apply { duration = 120L; start() }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
