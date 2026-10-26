package com.drawanywhere.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ScrollView
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

    private val CLICK_DEBOUNCE_MS = 250L
    private var lastClickTimeByView = mutableMapOf<Int, Long>()

    // ✅ true = منطقة الأدوات متفتحة | false = متقفلة — بس الـ Toggle بسيط بنفس الحركة
    private var isScrollExpanded = false
    private var scrollFullSize   = 0
    private var scrollAnimator: ValueAnimator? = null

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
    private var currentOrientation = ToolbarOrientation.VERTICAL

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun init(orientation: ToolbarOrientation) = inflate(orientation)

    fun destroy() {
        fadeHandler.removeCallbacksAndMessages(null)
        scrollAnimator?.cancel()
        try { toolbarView?.let { windowManager.removeView(it) } } catch (_: Exception) { }
        toolbarView = null; toolbarParams = null
    }

    fun changeOrientation(orientation: ToolbarOrientation) { destroy(); inflate(orientation) }

    // ─── Public Sync ──────────────────────────────────────────────────────────

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

    // ─── Inflate ──────────────────────────────────────────────────────────────

    private fun inflate(orientation: ToolbarOrientation) {
        currentOrientation = orientation
        val layoutId = if (orientation == ToolbarOrientation.HORIZONTAL) R.layout.overlay_toolbar_horizontal else R.layout.overlay_toolbar_vertical
        val view = LayoutInflater.from(service).inflate(layoutId, null)
        toolbarView = view
        isScrollExpanded = false
        scrollFullSize = 0

        val scrollContainer = view.findViewById<View>(R.id.toolbar_scroll_container)
        val fadeLenPx = (service.resources.getDimension(R.dimen.toolbar_fading_edge_length)).toInt()
        when (scrollContainer) {
            is HorizontalScrollView -> {
                scrollContainer.isHorizontalFadingEdgeEnabled = true
                scrollContainer.setFadingEdgeLength(fadeLenPx)
                // ✅ مقفول من البداية — عرض صفر
                scrollContainer.layoutParams.width = 0
            }
            is ScrollView -> {
                scrollContainer.isVerticalFadingEdgeEnabled = true
                scrollContainer.setFadingEdgeLength(fadeLenPx)
                // ✅ مقفول من البداية — ارتفاع صفر
                scrollContainer.layoutParams.height = 0
            }
        }
        scrollContainer?.alpha = 0f

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 80 }
        toolbarParams = params

        view.findViewById<ImageView>(R.id.btn_tools)?.setImageResource(toolIconMap[drawingCanvas.currentTool] ?: R.drawable.ic_pen)

        setupButtons(view)
        setupDrag(view, params)
        setupCollapseArrow(view)
        scheduleIdleFade()
        windowManager.addView(view, params)

        updateColorDot(drawingCanvas.currentColor)
        updateFingerIndicator(service.appPreferences.fingerDrawing)
        updateContentState(hasContent)
    }

    // ─── Debounced Click Helper ─────────────────────────────────────────────────

    private fun debouncedClick(viewId: Int, action: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        val last = lastClickTimeByView[viewId] ?: 0L
        if (now - last < CLICK_DEBOUNCE_MS) return
        lastClickTimeByView[viewId] = now
        action()
    }

    // ─── Buttons ──────────────────────────────────────────────────────────────

    private fun setupButtons(view: View) {

        view.findViewById<ImageView>(R.id.btn_hide)?.setOnClickListener {
            debouncedClick(R.id.btn_hide) { resetFadeTimer(); toggleCanvasVisibility() }
        }

        view.findViewById<ImageView>(R.id.btn_undo)?.setOnClickListener {
            if (!hasContent) return@setOnClickListener
            debouncedClick(R.id.btn_undo) { resetFadeTimer(); drawingCanvas.undo() }
        }

        view.findViewById<ImageView>(R.id.btn_clear)?.setOnClickListener {
            if (!hasContent) return@setOnClickListener
            debouncedClick(R.id.btn_clear) { resetFadeTimer(); drawingCanvas.clearAll() }
        }

        view.findViewById<View>(R.id.btn_add_image)?.setOnClickListener {
            debouncedClick(R.id.btn_add_image) {
                resetFadeTimer(); panelManager.dismissAll(); service.launchImagePicker()
            }
        }

        view.findViewById<View>(R.id.btn_tools)?.setOnClickListener {
            debouncedClick(R.id.btn_tools) { resetFadeTimer(); panelManager.toggleToolsPanel() }
        }

        view.findViewById<View>(R.id.btn_tools)?.setOnLongClickListener {
            resetFadeTimer()
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            panelManager.togglePenNibsPopup()
            true
        }

        view.findViewById<View>(R.id.view_color_dot)?.setOnClickListener {
            debouncedClick(R.id.view_color_dot) { resetFadeTimer(); panelManager.toggleColorPanel() }
        }

        view.findViewById<ImageView>(R.id.btn_finger)?.setOnClickListener {
            debouncedClick(R.id.btn_finger) {
                resetFadeTimer()
                val newState = !service.appPreferences.fingerDrawing
                service.appPreferences.fingerDrawing = newState
                service.refreshCanvasTouchMode()
            }
        }

        view.findViewById<View>(R.id.btn_settings)?.setOnClickListener {
            debouncedClick(R.id.btn_settings) { resetFadeTimer(); panelManager.toggleSettingsPanel() }
        }
    }

    // ─── Collapse Arrow — Toggle بسيط فتح/قفل، شكل السهم ثابت دايماً ───────────

    private fun setupCollapseArrow(view: View) {
        val arrowBtn = view.findViewById<ImageView>(R.id.btn_collapse_arrow) ?: return
        val scrollContainer = view.findViewById<View>(R.id.toolbar_scroll_container) ?: return

        arrowBtn.setOnClickListener {
            debouncedClick(R.id.btn_collapse_arrow) {
                resetFadeTimer()
                isScrollExpanded = !isScrollExpanded
                animateScrollToggle(view, scrollContainer, isScrollExpanded)
            }
        }
    }

    /**
     * ✅ نفس حركة الفتح والقفل بالظبط — مفيش فرق بينهم غير اتجاه الحجم (من 0 للكامل أو العكس)
     * شكل السهم ثابت طول الوقت (مايتغيرش)، والعين والسهم نفسهم برا منطقة التحريك دي خالص
     */
    private fun animateScrollToggle(root: View, scrollContainer: View, expand: Boolean) {
        val isH = currentOrientation == ToolbarOrientation.HORIZONTAL

        if (scrollFullSize == 0) {
            val inner = if (isH)
                root.findViewById<View>(R.id.toolbar_scroll_inner)
            else
                root.findViewById<View>(R.id.toolbar_scroll_inner)
            inner?.post {
                scrollFullSize = if (isH) inner.width else inner.height
                if (scrollFullSize > 0) animateScrollToggle(root, scrollContainer, expand)
            }
            if (scrollFullSize == 0) return
        }

        val params = scrollContainer.layoutParams
        val startSize = if (isH) params.width.coerceAtLeast(0) else params.height.coerceAtLeast(0)
        val endSize = if (expand) scrollFullSize else 0

        scrollAnimator?.cancel()
        scrollAnimator = ValueAnimator.ofInt(startSize, endSize).apply {
            duration = 240L
            interpolator = DecelerateInterpolator(1.3f)
            addUpdateListener { anim ->
                val size = anim.animatedValue as Int
                if (isH) params.width = size else params.height = size
                scrollContainer.layoutParams = params
                scrollContainer.alpha = if (scrollFullSize > 0) (size.toFloat() / scrollFullSize).coerceIn(0f, 1f) else 0f
                toolbarParams?.let { tp ->
                    try { windowManager.updateViewLayout(root, tp) } catch (_: Exception) { }
                }
            }
            start()
        }
        // ✅ شكل السهم ثابت — مفيش setImageResource هنا خالص
    }

    // ─── Toggle Canvas Visibility (Eye) ───────────────────────────────────────

    private fun toggleCanvasVisibility() {
        canvasVisible = !canvasVisible
        if (!canvasVisible && service.appPreferences.clearOnHide) drawingCanvas.clearAll()
        drawingCanvas.visibility = if (canvasVisible) View.VISIBLE else View.INVISIBLE
        service.setCanvasTouchable(canvasVisible && service.appPreferences.fingerDrawing)
        val eyeIcon = toolbarView?.findViewById<ImageView>(R.id.btn_hide)
        eyeIcon?.setImageResource(if (canvasVisible) R.drawable.ic_eye else R.drawable.ic_eye_closed)
        collapseCanvasHideState(!canvasVisible)
    }

    private fun collapseCanvasHideState(hideMode: Boolean) {
        val v = toolbarView as? ViewGroup ?: return
        val params = toolbarParams ?: return
        val isH = currentOrientation == ToolbarOrientation.HORIZONTAL
        val contentGroup = if (isH) v.findViewById<ViewGroup>(R.id.toolbar_content_row) else v.findViewById<ViewGroup>(R.id.toolbar_content_col)

        val transition = TransitionSet().apply {
            addTransition(Fade(Fade.OUT)); addTransition(android.transition.ChangeBounds()); addTransition(Fade(Fade.IN))
            ordering = TransitionSet.ORDERING_TOGETHER; duration = 480L; interpolator = DecelerateInterpolator(1.4f)
        }
        TransitionManager.beginDelayedTransition(contentGroup, transition)

        val vis = if (hideMode) View.GONE else View.VISIBLE
        contentGroup.findViewById<View>(R.id.toolbar_scroll_container)?.visibility = vis
        val arrow = contentGroup.findViewById<View>(R.id.btn_collapse_arrow)
        (arrow?.parent as? View)?.visibility = vis

        contentGroup.setBackgroundResource(if (hideMode) R.drawable.bg_toolbar_collapsed else R.drawable.bg_toolbar)
        contentGroup.post { try { windowManager.updateViewLayout(v, params) } catch (_: Exception) { } }
    }

    // ─── Drag ─────────────────────────────────────────────────────────────────

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            restoreOpacity(); resetFadeTimer()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false; dragStartRawX = event.rawX; dragStartRawY = event.rawY
                    windowInitX = params.x; windowInitY = params.y
                    false
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

    // ─── Idle Fade ────────────────────────────────────────────────────────────

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
