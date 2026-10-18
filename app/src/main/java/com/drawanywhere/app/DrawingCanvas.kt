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
    var currentLineStyle: LineStyle   = LineStyle.SOLID
    var fingerDrawingEnabled: Boolean = true

    // ─── بيانات الرسم ─────────────────────────────────────────────────────────
    private val strokes      = mutableListOf<StrokeData>()
    private val laserStrokes = mutableListOf<StrokeData>()

    private var activePath       = null as Path?
    private var lassoStartX      = 0f
    private var lassoStartY      = 0f
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
    private val imageController = ImageOverlayController().apply {
        onInvalidate = { invalidate() }
    }

    // ─── Laser Handler ────────────────────────────────────────────────────────
    private val laserHandler   = Handler(Looper.getMainLooper())
    // الليزر دايماً أحمر ساطع بريق عالي
    private val LASER_COLOR    = Color.parseColor("#FF2200")
    private val LASER_FADE_MS  = 2000L   // 2 ثانية بدل 4

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

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun applyToolState(tool: DrawingTool, state: ToolState) {
        if (tool == currentTool) {
            currentColor       = state.color
            currentStrokeWidth = state.strokeWidth
            currentOpacity     = state.opacity
            currentLineStyle   = state.lineStyle
        }
    }

    fun switchTool(tool: DrawingTool, state: ToolState) {
        currentTool        = tool
        currentColor       = state.color
        currentStrokeWidth = state.strokeWidth
        currentOpacity     = state.opacity
        currentLineStyle   = state.lineStyle
    }

    fun updateLineStyle(style: LineStyle) {
        currentLineStyle = style
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            rebuildOffscreen(); invalidate()
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
        imageOverlays.add(data); invalidate()
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

            DrawingTool.PEN -> {
                configPaintFromStroke(strokePaint, s)
                applyLineStyle(strokePaint, s.lineStyle, s.strokeWidth)
                cvs.drawPath(s.path, strokePaint)
                strokePaint.pathEffect = null
            }
            DrawingTool.MATTE_PEN -> {
                configPaintFromStroke(strokePaint, s)
                strokePaint.strokeCap  = Paint.Cap.SQUARE
                strokePaint.strokeJoin = Paint.Join.MITER
                strokePaint.alpha      = 255
                strokePaint.maskFilter = null
                cvs.drawPath(s.path, strokePaint)
            }
            DrawingTool.LASSO -> {
                configPaintFromStroke(strokePaint, s)
                applyLineStyle(strokePaint, LineStyle.DASHED, s.strokeWidth)
                cvs.drawPath(s.path, strokePaint)
                strokePaint.pathEffect = null
            }
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
        paint.pathEffect  = null
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
        paint.pathEffect  = null
        paint.strokeCap   = Paint.Cap.ROUND
        paint.strokeJoin  = Paint.Join.ROUND
        paint.style       = Paint.Style.STROKE
        applyToolEffects(paint, currentTool, currentStrokeWidth)
        // تطبيق نمط الخط للأدوات اللي بتدعمه
        when (currentTool) {
            DrawingTool.PEN   -> applyLineStyle(paint, currentLineStyle, currentStrokeWidth)
            DrawingTool.LASSO -> applyLineStyle(paint, LineStyle.DASHED, currentStrokeWidth)
            else              -> {}
        }
    }

    private fun applyToolEffects(paint: Paint, tool: DrawingTool, sw: Float) {
        when (tool) {
            DrawingTool.BRUSH -> {
                paint.maskFilter = BlurMaskFilter(sw * 0.3f, BlurMaskFilter.Blur.NORMAL)
            }
            DrawingTool.HIGHLIGHTER -> {
                paint.strokeCap  = Paint.Cap.SQUARE
                paint.maskFilter = null
            }
            DrawingTool.CALLIGRAPHY -> {
                paint.strokeCap  = Paint.Cap.BUTT
                paint.maskFilter = null
            }
            DrawingTool.MATTE_PEN -> {
                paint.strokeCap   = Paint.Cap.SQUARE
                paint.strokeJoin  = Paint.Join.MITER
                paint.maskFilter  = null
                paint.alpha       = 255
            }
            else -> { paint.maskFilter = null }
        }
    }

    // تطبيق نمط الخط بـ DashPathEffect
    private fun applyLineStyle(paint: Paint, style: LineStyle, sw: Float) {
        paint.pathEffect = when (style) {
            LineStyle.SOLID    -> null
            LineStyle.DASHED   -> DashPathEffect(
                floatArrayOf(sw * 4f, sw * 2f), 0f
            )
            LineStyle.DOTTED   -> DashPathEffect(
                floatArrayOf(0.1f, sw * 2.2f), 0f
            )
            LineStyle.DASH_DOT -> DashPathEffect(
                floatArrayOf(sw * 4f, sw * 1.5f, 0.1f, sw * 1.5f), 0f
            )
        }
    }

    // ─── onDraw ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        // ① صور الـ Overlay
        for (img in imageOverlays) {
            img.bitmap?.let { bmp ->
                canvas.save()
                canvas.rotate(img.rotation,
                    img.x + img.width / 2f, img.y + img.height / 2f)
                canvas.drawBitmap(bmp, null,
                    RectF(img.x, img.y, img.x + img.width, img.y + img.height), null)
                canvas.restore()
            }
        }

        // ② الـ Strokes المكتملة
        offBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // ③ Laser Strokes — تتلاشى خلال 2 ثانية بلون أحمر ساطع
        val now = System.currentTimeMillis()
        val laserIter = laserStrokes.iterator()
        while (laserIter.hasNext()) {
            val laser    = laserIter.next()
            val elapsed  = (now - laser.timestamp).coerceAtLeast(0L)
            val fraction = (1f - elapsed / LASER_FADE_MS.toFloat()).coerceIn(0f, 1f)
            val alpha    = (255 * fraction).toInt()
            if (alpha > 0) {
                strokePaint.apply {
                    color       = LASER_COLOR
                    this.alpha  = alpha
                    strokeWidth = laser.strokeWidth
                    maskFilter  = BlurMaskFilter(laser.strokeWidth * 0.5f, BlurMaskFilter.Blur.SOLID)
                    xfermode    = null
                    pathEffect  = null
                    strokeCap   = Paint.Cap.ROUND
                    style       = Paint.Style.STROKE
                }
                canvas.drawPath(laser.path, strokePaint)
                strokePaint.maskFilter = null
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
                strokePaint.pathEffect = null
            }
        }
    }

    // ─── Touch ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!fingerDrawingEnabled) return false
        if (imageOverlays.isNotEmpty() &&
            imageController.onTouchEvent(event, imageOverlays)) return true

        val x = event.x; val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN   -> touchDown(x, y)
            MotionEvent.ACTION_MOVE   -> touchMove(x, y)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> touchUp(x, y)
        }
        return true
    }

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
            DrawingTool.LASSO -> {
                lassoStartX = x; lassoStartY = y
                activePath = Path().apply { moveTo(x, y) }
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
                val p = activePath ?: return
                val midX = (prevX + x) / 2f; val midY = (prevY + y) / 2f
                p.quadTo(prevX, prevY, midX, midY)
                prevX = x; prevY = y
            }
        }
        invalidate()
    }

    private fun touchUp(x: Float, y: Float) {
        val path = activePath
        when (currentTool) {
            DrawingTool.STROKE_ERASER -> {}

            DrawingTool.RECTANGLE,
            DrawingTool.ELLIPSE -> {
                if (path != null) {
                    val s = StrokeData(currentTool, path, currentColor,
                        currentStrokeWidth, currentOpacity,
                        rect = RectF(shapePreviewRect))
                    strokes.add(s); offCvs?.let { commitStroke(it, s) }
                }
            }

            DrawingTool.LASER -> {
                if (path != null) {
                    path.lineTo(x, y)
                    val s = StrokeData(DrawingTool.LASER, path,
                        LASER_COLOR, currentStrokeWidth, 255,
                        timestamp = System.currentTimeMillis())
                    laserStrokes.add(s); scheduleLaserFade(s)
                }
            }

            DrawingTool.LASSO -> {
                if (path != null) {
                    path.lineTo(x, y)
                    path.lineTo(lassoStartX, lassoStartY) // إغلاق التحديد
                    path.close()
                    val s = StrokeData(DrawingTool.LASSO, path,
                        currentColor, currentStrokeWidth,
                        currentOpacity, LineStyle.DASHED)
                    strokes.add(s); offCvs?.let { commitStroke(it, s) }
                }
            }

            else -> {
                if (path != null) {
                    path.lineTo(x, y)
                    val s = StrokeData(currentTool, path, currentColor,
                        currentStrokeWidth, currentOpacity, currentLineStyle)
                    strokes.add(s); offCvs?.let { commitStroke(it, s) }
                }
            }
        }
        activePath = null; invalidate()
    }

    // ─── Stroke Eraser ────────────────────────────────────────────────────────

    private fun eraseStrokeAt(x: Float, y: Float) {
        val iter = strokes.iterator(); var changed = false
        while (iter.hasNext()) {
            val s = iter.next()
            val b = RectF()
            s.rect?.let { b.set(it) } ?: s.path.computeBounds(b, true)
            b.inset(-40f, -40f)
            if (b.contains(x, y)) { iter.remove(); changed = true }
        }
        if (changed) { rebuildOffscreen(); invalidate() }
    }

    // ─── Laser Fade ───────────────────────────────────────────────────────────

    private fun scheduleLaserFade(stroke: StrokeData) {
        val r = object : Runnable {
            override fun run() {
                if (laserStrokes.contains(stroke)) {
                    invalidate(); laserHandler.postDelayed(this, 33L)
                }
            }
        }
        laserHandler.post(r)
        laserHandler.postDelayed({
            laserStrokes.remove(stroke); invalidate()
        }, LASER_FADE_MS)
    }
}
