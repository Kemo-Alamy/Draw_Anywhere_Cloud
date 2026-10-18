package com.drawanywhere.app

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class DrawingCanvas(context: Context) : View(context) {

    // ─── حالة الأداة الحالية ──────────────────────────────────────────────────
    var currentTool: DrawingTool      = DrawingTool.PEN
    var currentColor: Int             = Color.RED
    var currentStrokeWidth: Float     = 8f
    var currentOpacity: Int           = 255
    var fingerDrawingEnabled: Boolean = true

    // ─── بيانات الرسم ────────────────────────────────────────────────────────
    private val strokes      = mutableListOf<StrokeData>()
    private val laserStrokes = mutableListOf<StrokeData>()

    private var activePath       = null as Path?
    private var shapeStartX      = 0f
    private var shapeStartY      = 0f
    private val shapePreviewRect = RectF()
    private var prevX            = 0f
    private var prevY            = 0f

    // ─── Off-screen Bitmap ────────────────────────────────────────────────────
    private var offBmp: Bitmap? = null
    private var offCvs: Canvas? = null

    // ─── Image Overlays ───────────────────────────────────────────────────────
    val imageOverlays = mutableListOf<ImageOverlayData>()

    // ─── ImageOverlayController (مربوط هنا) ──────────────────────────────────
    private val imageController = ImageOverlayController().apply {
        onImageRemoved = { /* الـ Controller بيشيل الصورة من الـ List مباشرةً */ }
        onInvalidate   = { invalidate() }
    }

    // ─── Laser Handler ───────────────────────────────────────────────────────
    private val laserHandler = Handler(Looper.getMainLooper())

    // ─── Paints ───────────────────────────────────────────────────────────────
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style      = Paint.Style.STROKE
        strokeCap  = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style      = Paint.Style.STROKE
        strokeCap  = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode   = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun applyToolState(tool: DrawingTool, state: ToolState) {
        if (tool == currentTool) {
            currentColor       = state.color
            currentStrokeWidth = state.strokeWidth
            currentOpacity     = state.opacity
        }
    }

    fun switchTool(tool: DrawingTool, state: ToolState) {
        currentTool        = tool
        currentColor       = state.color
        currentStrokeWidth = state.strokeWidth
        currentOpacity     = state.opacity
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            rebuildOffscreen()
            invalidate()
        }
    }

    fun clearAll() {
        strokes.clear()
        laserStrokes.clear()
        imageOverlays.clear()
        laserHandler.removeCallbacksAndMessages(null)
        offBmp?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun addImageOverlay(data: ImageOverlayData) {
        imageOverlays.add(data)
        invalidate()
    }

    // ─── onSizeChanged ────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            offBmp?.recycle()
            offBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            offCvs = Canvas(offBmp!!)
            rebuildOffscreen()
        }
    }

    // ─── Offscreen ────────────────────────────────────────────────────────────

    private fun rebuildOffscreen() {
        val bmp = offBmp ?: return
        val cvs = offCvs ?: return
        bmp.eraseColor(Color.TRANSPARENT)
        for (s in strokes) commitStroke(cvs, s)
    }

    private fun commitStroke(cvs: Canvas, s: StrokeData) {
        when (s.tool) {
            DrawingTool.RECTANGLE -> {
                configPaintFromStroke(strokePaint, s)
                s.rect?.let { cvs.drawRect(it, strokePaint) }
            }
            DrawingTool.ELLIPSE -> {
                configPaintFromStroke(strokePaint, s)
                s.rect?.let { cvs.drawOval(it, strokePaint) }
            }
            DrawingTool.PIXEL_ERASER -> {
                eraserPaint.strokeWidth = s.strokeWidth
                cvs.drawPath(s.path, eraserPaint)
            }
            DrawingTool.STROKE_ERASER,
            DrawingTool.LASER -> { /* لا يُرسم على الـ Offscreen */ }
            else -> {
                configPaintFromStroke(strokePaint, s)
                cvs.drawPath(s.path, strokePaint)
            }
        }
    }

    // ─── Paint Config ─────────────────────────────────────────────────────────

    private fun configPaintFromStroke(paint: Paint, s: StrokeData) {
        paint.color       = s.color
        paint.alpha       = s.opacity
        paint.strokeWidth = s.strokeWidth
        paint.xfermode    = null
        paint.maskFilter  = null
        paint.strokeCap   = Paint.Cap.ROUND
        paint.strokeJoin  = Paint.Join.ROUND
        paint.style       = Paint.Style.STROKE
        applyToolEffects(paint, s.tool, s.strokeWidth)
    }

    private fun configPaintLive(paint: Paint) {
        paint.color       = currentColor
        paint.alpha       = currentOpacity
        paint.strokeWidth = currentStrokeWidth
        paint.xfermode    = null
        paint.maskFilter  = null
        paint.strokeCap   = Paint.Cap.ROUND
        paint.strokeJoin  = Paint.Join.ROUND
        paint.style       = Paint.Style.STROKE
        applyToolEffects(paint, currentTool, currentStrokeWidth)
    }

    private fun applyToolEffects(paint: Paint, tool: DrawingTool, sw: Float) {
        when (tool) {
            DrawingTool.BRUSH -> {
                paint.maskFilter = BlurMaskFilter(sw * 0.25f, BlurMaskFilter.Blur.NORMAL)
            }
            DrawingTool.HIGHLIGHTER -> {
                paint.strokeCap = Paint.Cap.SQUARE
                paint.maskFilter = null
            }
            DrawingTool.CALLIGRAPHY -> {
                paint.strokeCap = Paint.Cap.BUTT
                paint.maskFilter = null
            }
            else -> { paint.maskFilter = null }
        }
    }

    // ─── onDraw ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        // ① صور الـ Overlay
        for (img in imageOverlays) {
            img.bitmap?.let { bmp ->
                canvas.save()
                canvas.rotate(
                    img.rotation,
                    img.x + img.width  / 2f,
                    img.y + img.height / 2f
                )
                canvas.drawBitmap(
                    bmp, null,
                    RectF(img.x, img.y, img.x + img.width, img.y + img.height),
                    null
                )
                canvas.restore()
            }
        }

        // ② الـ Strokes المكتملة من الـ Offscreen Bitmap
        offBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // ③ Laser Strokes (تتلاشى تدريجياً)
        val now = System.currentTimeMillis()
        val laserIter = laserStrokes.iterator()
        while (laserIter.hasNext()) {
            val laser    = laserIter.next()
            val elapsed  = (now - laser.timestamp).coerceAtLeast(0L)
            val fraction = (1f - elapsed / 4000f).coerceIn(0f, 1f)
            val alpha    = (255 * fraction).toInt()
            if (alpha > 0) {
                strokePaint.apply {
                    color       = laser.color
                    this.alpha  = alpha
                    strokeWidth = laser.strokeWidth
                    maskFilter  = null
                    xfermode    = null
                    strokeCap   = Paint.Cap.ROUND
                    style       = Paint.Style.STROKE
                }
                canvas.drawPath(laser.path, strokePaint)
            }
        }

        // ④ Preview الـ Stroke الحالي
        val path = activePath ?: return
        when (currentTool) {
            DrawingTool.RECTANGLE -> {
                configPaintLive(strokePaint)
                canvas.drawRect(shapePreviewRect, strokePaint)
            }
            DrawingTool.ELLIPSE -> {
                configPaintLive(strokePaint)
                canvas.drawOval(shapePreviewRect, strokePaint)
            }
            DrawingTool.PIXEL_ERASER -> {
                eraserPaint.strokeWidth = currentStrokeWidth
                canvas.drawPath(path, eraserPaint)
            }
            DrawingTool.STROKE_ERASER -> { /* لا preview */ }
            else -> {
                configPaintLive(strokePaint)
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    // ─── Touch Handling ───────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!fingerDrawingEnabled) return false

        // ✅ الـ ImageOverlayController بياخد الأولوية لو فيه صور على الشاشة
        // double-tap على صورة → تحذف | سحب → تتحرك | pinch → تتكبّر / تتصغّر
        if (imageOverlays.isNotEmpty() &&
            imageController.onTouchEvent(event, imageOverlays)
        ) return true

        // رسم عادي
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN            -> touchDown(x, y)
            MotionEvent.ACTION_MOVE            -> touchMove(x, y)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL          -> touchUp(x, y)
        }
        return true
    }

    // ─── Touch Phases ─────────────────────────────────────────────────────────

    private fun touchDown(x: Float, y: Float) {
        prevX = x; prevY = y
        when (currentTool) {
            DrawingTool.STROKE_ERASER -> eraseStrokeAt(x, y)
            DrawingTool.RECTANGLE,
            DrawingTool.ELLIPSE -> {
                shapeStartX = x; shapeStartY = y
                shapePreviewRect.set(x, y, x, y)
                activePath = Path()
            }
            else -> activePath = Path().apply { moveTo(x, y) }
        }
        invalidate()
    }

    private fun touchMove(x: Float, y: Float) {
        when (currentTool) {
            DrawingTool.STROKE_ERASER -> { eraseStrokeAt(x, y); return }
            DrawingTool.RECTANGLE,
            DrawingTool.ELLIPSE -> {
                shapePreviewRect.set(
                    min(shapeStartX, x), min(shapeStartY, y),
                    max(shapeStartX, x), max(shapeStartY, y)
                )
            }
            else -> {
                val path = activePath ?: return
                val midX = (prevX + x) / 2f
                val midY = (prevY + y) / 2f
                path.quadTo(prevX, prevY, midX, midY)
                prevX = x; prevY = y
            }
        }
        invalidate()
    }

    private fun touchUp(x: Float, y: Float) {
        val path = activePath
        when (currentTool) {
            DrawingTool.STROKE_ERASER -> { /* تم في touchDown/Move */ }

            DrawingTool.RECTANGLE,
            DrawingTool.ELLIPSE -> {
                if (path != null) {
                    val s = StrokeData(
                        tool        = currentTool,
                        path        = path,
                        color       = currentColor,
                        strokeWidth = currentStrokeWidth,
                        opacity     = currentOpacity,
                        rect        = RectF(shapePreviewRect)
                    )
                    strokes.add(s)
                    offCvs?.let { commitStroke(it, s) }
                }
            }

            DrawingTool.LASER -> {
                if (path != null) {
                    path.lineTo(x, y)
                    val s = StrokeData(
                        tool        = DrawingTool.LASER,
                        path        = path,
                        color       = currentColor,
                        strokeWidth = currentStrokeWidth,
                        opacity     = currentOpacity,
                        timestamp   = System.currentTimeMillis()
                    )
                    laserStrokes.add(s)
                    scheduleLaserFade(s)
                }
            }

            else -> {
                if (path != null) {
                    path.lineTo(x, y)
                    val s = StrokeData(
                        tool        = currentTool,
                        path        = path,
                        color       = currentColor,
                        strokeWidth = currentStrokeWidth,
                        opacity     = currentOpacity
                    )
                    strokes.add(s)
                    offCvs?.let { commitStroke(it, s) }
                }
            }
        }
        activePath = null
        invalidate()
    }

    // ─── Stroke Eraser ────────────────────────────────────────────────────────

    private fun eraseStrokeAt(x: Float, y: Float) {
        val iter    = strokes.iterator()
        var changed = false
        while (iter.hasNext()) {
            val s      = iter.next()
            val bounds = RectF()
            s.rect?.let { bounds.set(it) } ?: s.path.computeBounds(bounds, true)
            bounds.inset(-40f, -40f)
            if (bounds.contains(x, y)) { iter.remove(); changed = true }
        }
        if (changed) { rebuildOffscreen(); invalidate() }
    }

    // ─── Laser Fade ───────────────────────────────────────────────────────────

    private fun scheduleLaserFade(stroke: StrokeData) {
        val runnable = object : Runnable {
            override fun run() {
                if (laserStrokes.contains(stroke)) {
                    invalidate()
                    laserHandler.postDelayed(this, 50L)
                }
            }
        }
        laserHandler.post(runnable)
        laserHandler.postDelayed({
            laserStrokes.remove(stroke)
            invalidate()
        }, 4000L)
    }
}
