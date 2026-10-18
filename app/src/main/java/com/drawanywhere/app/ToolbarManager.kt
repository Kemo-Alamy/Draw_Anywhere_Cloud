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

    // Drag state
    private var dragStartRawX  = 0f
    private var dragStartRawY  = 0f
    private var windowInitX    = 0
    private var windowInitY    = 0
    private var isDragging     = false
    private val DRAG_THRESHOLD = 12f

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun init(orientation: ToolbarOrientation) = inflate(orientation)

    fun destroy() {
        fadeHandler.removeCallbacksAndMessages(null)
        try { toolbarView?.let { windowManager.removeView(it) } } catch (e: Exception) { }
        toolbarView   = null
        toolbarParams = null
    }

    fun changeOrientation(orientation: ToolbarOrientation) {
        destroy()
        inflate(orientation)
    }

    // ─── Public State Sync ────────────────────────────────────────────────────

    fun updateColorDot(color: Int) {
        toolbarView?.findViewById<View>(R.id.view_color_dot)
            ?.setBackgroundColor(color)
    }

    fun updateFingerIndicator(enabled: Boolean) {
        toolbarView?.findViewById<ImageView>(R.id.view_finger_indicator)
            ?.alpha = if (enabled) 1f else 0.35f
    }

    // ─── Inflate & Attach ─────────────────────────────────────────────────────

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
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 100 }
        toolbarParams = params

        // حالة أولية
        view.findViewById<View>(R.id.view_color_dot)
            ?.setBackgroundColor(drawingCanvas.currentColor)
        view.findViewById<ImageView>(R.id.view_finger_indicator)
            ?.alpha = if (service.appPreferences.fingerDrawing) 1f else 0.35f

        setupButtonClicks(view)
        setupDrag(view, params)
        scheduleIdleFade()

        windowManager.addView(view, params)
    }

    // ─── Buttons ──────────────────────────────────────────────────────────────

    private fun setupButtonClicks(view: View) {
        // ✅ btn_hide
        view.findViewById<View>(R.id.btn_hide)?.setOnClickListener {
            resetFadeTimer(); toggleCanvas()
        }
        // ✅ btn_undo
        view.findViewById<View>(R.id.btn_undo)?.setOnClickListener {
            resetFadeTimer(); drawingCanvas.undo()
        }
        // ✅ btn_clear (موجود في الـ Vertical فقط)
        view.findViewById<View>(R.id.btn_clear)?.setOnClickListener {
            resetFadeTimer(); drawingCanvas.clearAll()
        }
        // ✅ btn_add_image (موجود في الـ Vertical فقط)
        view.findViewById<View>(R.id.btn_add_image)?.setOnClickListener {
            resetFadeTimer()
            panelManager.dismissAll()
            service.launchImagePicker()
        }
        // ✅ btn_tools (موجود في الـ Vertical فقط)
        view.findViewById<View>(R.id.btn_tools)?.setOnClickListener {
            resetFadeTimer(); panelManager.toggleToolsPanel()
        }
        // ✅ view_color_dot — موجود في الاتنين Horizontal و Vertical
        // ده بيفتح الـ Color Panel (بدل btn_color اللي كان غلط)
        view.findViewById<View>(R.id.view_color_dot)?.setOnClickListener {
            resetFadeTimer(); panelManager.toggleColorPanel()
        }
        // ✅ btn_settings
        view.findViewById<View>(R.id.btn_settings)?.setOnClickListener {
            resetFadeTimer(); panelManager.toggleSettingsPanel()
        }
    }

    private fun toggleCanvas() {
        canvasVisible = !canvasVisible
        if (!canvasVisible && service.appPreferences.clearOnHide)
            drawingCanvas.clearAll()
        drawingCanvas.visibility =
            if (canvasVisible) View.VISIBLE else View.INVISIBLE
        service.setCanvasTouchable(
            canvasVisible && service.appPreferences.fingerDrawing
        )
    }

    // ─── Drag ─────────────────────────────────────────────────────────────────

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            restoreOpacity()
            resetFadeTimer()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging    = false
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    windowInitX   = params.x
                    windowInitY   = params.y
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
                        catch (e: Exception) { }
                        true
                    } else false
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = isDragging
                    isDragging = false
                    wasDragging
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
        if (isFaded) restoreOpacity()
        scheduleIdleFade()
    }

    private fun fadeOut() {
        val v = toolbarView ?: return
        isFaded = true
        ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 0.30f)
            .apply { duration = 400L; start() }
    }

    private fun restoreOpacity() {
        val v = toolbarView ?: return
        if (!isFaded) return
        isFaded = false
        ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 1f)
            .apply { duration = 150L; start() }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
