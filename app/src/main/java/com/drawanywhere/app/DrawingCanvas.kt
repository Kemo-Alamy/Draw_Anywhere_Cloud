package com.drawanywhere.app

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.random.Random

class DrawingCanvas(context: Context) : View(context) {

    var currentTool: DrawingTool      = DrawingTool.PEN
    var currentColor: Int             = Color.RED
    var currentStrokeWidth: Float     = 6f
    var currentOpacity: Int           = 255
    var currentLineStyle: LineStyle   = LineStyle.SOLID
    var fingerDrawingEnabled: Boolean = true

    var onContentChanged: ((hasContent: Boolean) -> Unit)? = null
    var onRedoStateChanged: ((Boolean) -> Unit)? = null

    private val strokes      = mutableListOf<StrokeData>()
    private val redoStack    = mutableListOf<StrokeData>()
    private val laserStrokes = mutableListOf<StrokeData>()

    private var activePath       = null as Path?
    private var lassoStartX      = 0f
    private var lassoStartY      = 0f
    private var shapeStartX      = 0f
    private var shapeStartY      = 0f
    private val shapePreviewRect = RectF()
    private var prevX            = 0f
    private var prevY            = 0f

    private var suppressNextTouch = false

    private val rawPoints = mutableListOf<PointF>()

    private var offBmp: Bitmap? = null
    private var offCvs: Canvas? = null

    val imageOverlays = mutableListOf<ImageOverlayData>()
    private val imageController = ImageOverlayController().apply {
        onInvalidate = { invalidate() }
    }

    private val laserHandler  = Handler(Looper.getMainLooper())
    private val LASER_COLOR   = Color.parseColor("#FF2200")
    private val LASER_FADE_MS = 2000L

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun applyToolState(tool: DrawingTool, state: ToolState) {
        if (tool == currentTool) {
            currentColor = state.color; currentStrokeWidth = state.strokeWidth
            currentOpacity = state.opacity; currentLineStyle = state.lineStyle
        }
    }

    fun switchTool(tool: DrawingTool, state: ToolState) {
        currentTool = tool; currentColor = state.color
        currentStrokeWidth = state.strokeWidth; currentOpacity = state.opacity
        currentLineStyle = state.lineStyle
    }

    fun updateLineStyle(style: LineStyle) { currentLineStyle = style }
    fun hasContent(): Boolean = strokes.isNotEmpty() || imageOverlays.isNotEmpty()

    fun suppressNextTouchGesture() { suppressNextTouch = true }

    fun undo() {
        if (strokes.isNotEmpty()) {
            val removed = strokes.removeAt(strokes.lastIndex)
            redoStack.add(removed)
            rebuildOffscreen(); invalidate()
            onContentChanged?.invoke(hasContent())
            onRedoStateChanged?.invoke(true)
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val restored = redoStack.removeAt(redoStack.lastIndex)
            strokes.add(restored)
            offCvs?.let { commitStroke(it, restored) }
            invalidate()
            onContentChanged?.invoke(true)
            onRedoStateChanged?.invoke(redoStack.isNotEmpty())
        }
    }

    fun clearAll() {
        strokes.clear(); laserStrokes.clear(); imageOverlays.clear()
        redoStack.clear()
        laserHandler.removeCallbacksAndMessages(null)
        offBmp?.eraseColor(Color.TRANSPARENT); invalidate()
        onContentChanged?.invoke(false)
        onRedoStateChanged?.invoke(false)
    }

    fun addImageOverlay(data: ImageOverlayData) {
        imageOverlays.add(data); invalidate(); onContentChanged?.invoke(true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            offBmp?.recycle()
            offBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            offCvs = Canvas(offBmp!!)
            rebuildOffscreen()
        }
    }

    private fun rebuildOffscreen() {
        val bmp = offBmp ?: return; val cvs = offCvs ?: return
        bmp.eraseColor(Color.TRANSPARENT)
        for (s in strokes) commitStroke(cvs, s)
    }

    // ─── Commit ───────────────────────────────────────────────────────────────

    private fun commitStroke(cvs: Canvas, s: StrokeData) {
        when (s.tool) {
            DrawingTool.RECTANGLE     -> { configPlainPaint(strokePaint, s); s.rect?.let { cvs.drawRect(it, strokePaint) } }
            DrawingTool.ELLIPSE       -> { configPlainPaint(strokePaint, s); s.rect?.let { cvs.drawOval(it, strokePaint) } }
            DrawingTool.PIXEL_ERASER  -> { eraserPaint.strokeWidth = s.strokeWidth; cvs.drawPath(s.path, eraserPaint) }
            DrawingTool.STROKE_ERASER, DrawingTool.LASER -> {}
            DrawingTool.PEN           -> drawFinePen(cvs, s.path, s)
            DrawingTool.CALLIGRAPHY   -> drawCalligraphy(cvs, s)
            DrawingTool.MATTE_PEN     -> drawMatteThick(cvs, s.path, s)
            DrawingTool.BRUSH         -> drawRealPencil(cvs, s)
            DrawingTool.HIGHLIGHTER   -> drawHighlighter(cvs, s.path, s)
            DrawingTool.LASSO -> {
                configPlainPaint(strokePaint, s)
                applyLineStyle(strokePaint, LineStyle.DASHED, s.strokeWidth)
                cvs.drawPath(s.path, strokePaint)
                strokePaint.pathEffect = null
            }
        }
    }

    private fun configPlainPaint(paint: Paint, s: StrokeData) {
        paint.color = s.color; paint.alpha = s.opacity; paint.strokeWidth = s.strokeWidth
        paint.xfermode = null; paint.maskFilter = null; paint.pathEffect = null
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
    }

    private fun drawFinePen(cvs: Canvas, path: Path, s: StrokeData) {
        strokePaint.apply {
            color = s.color; alpha = s.opacity; strokeWidth = s.strokeWidth
            xfermode = null; maskFilter = null
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
        }
        applyLineStyle(strokePaint, s.lineStyle, s.strokeWidth)
        cvs.drawPath(path, strokePaint)
        strokePaint.pathEffect = null
    }

    private fun drawCalligraphy(cvs: Canvas, s: StrokeData) {
        val pts = s.rawPoints ?: run { configPlainPaint(strokePaint, s); cvs.drawPath(s.path, strokePaint); return }
        if (pts.size < 2) return
        fillPaint.color = s.color; fillPaint.alpha = s.opacity
        val nibAngle = Math.toRadians(45.0)
        val baseW = s.strokeWidth
        for (i in 0 until pts.size - 1) {
            val p0 = pts[i]; val p1 = pts[i + 1]
            val dx = p1.x - p0.x; val dy = p1.y - p0.y
            val segAngle = atan2(dy, dx)
            val diff = abs(((segAngle - nibAngle + Math.PI) % (2 * Math.PI)) - Math.PI)
            val widthFactor = (0.35f + 0.65f * (diff / (Math.PI / 2)).toFloat()).coerceIn(0.35f, 1f)
            val w = baseW * widthFactor
            val quad = Path().apply {
                val perpX = -dy / (hypot(dx, dy) + 0.001f) * (w / 2f)
                val perpY =  dx / (hypot(dx, dy) + 0.001f) * (w / 2f)
                moveTo(p0.x + perpX, p0.y + perpY)
                lineTo(p1.x + perpX, p1.y + perpY)
                lineTo(p1.x - perpX, p1.y - perpY)
                lineTo(p0.x - perpX, p0.y - perpY)
                close()
            }
            cvs.drawPath(quad, fillPaint)
        }
    }

    private fun drawMatteThick(cvs: Canvas, path: Path, s: StrokeData) {
        strokePaint.apply {
            color = s.color; alpha = 255; strokeWidth = s.strokeWidth
            xfermode = null; maskFilter = null
            strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER; style = Paint.Style.STROKE
        }
        cvs.drawPath(path, strokePaint)
    }

    private fun drawRealPencil(cvs: Canvas, s: StrokeData) {
        strokePaint.apply {
            color = s.color; alpha = (s.opacity * 0.88f).toInt(); strokeWidth = s.strokeWidth
            xfermode = null; maskFilter = null
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
        }
        cvs.drawPath(s.path, strokePaint)

        val pts = s.rawPoints ?: return
        if (pts.size < 2) return
        val rnd = Random(s.timestamp)
        fillPaint.color = s.color
        for (i in 0 until pts.size - 1) {
            val p0 = pts[i]; val p1 = pts[i + 1]
            val steps = 3
            for (j in 0..steps) {
                val t = j / steps.toFloat()
                val x = p0.x + (p1.x - p0.x) * t
                val y = p0.y + (p1.y - p0.y) * t
                val jitterX = (rnd.nextFloat() - 0.5f) * s.strokeWidth * 0.35f
                val jitterY = (rnd.nextFloat() - 0.5f) * s.strokeWidth * 0.35f
                val r = s.strokeWidth * 0.08f
                fillPaint.alpha = ((s.opacity * 0.18f) * rnd.nextFloat()).toInt().coerceIn(0, 60)
                cvs.drawCircle(x + jitterX, y + jitterY, r, fillPaint)
            }
        }
    }

    private fun drawHighlighter(cvs: Canvas, path: Path, s: StrokeData) {
        strokePaint.apply {
            color = s.color; alpha = s.opacity; strokeWidth = s.strokeWidth
            xfermode = null; maskFilter = null
            strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER; style = Paint.Style.STROKE
        }
        cvs.drawPath(path, strokePaint)
    }

    private fun applyLineStyle(paint: Paint, style: LineStyle, sw: Float) {
        paint.pathEffect = when (style) {
            LineStyle.SOLID    -> null
            LineStyle.DASHED   -> DashPathEffect(floatArrayOf(sw * 4f, sw * 2f), 0f)
            LineStyle.DOTTED   -> DashPathEffect(floatArrayOf(0.1f, sw * 2.2f), 0f)
            LineStyle.DASH_DOT -> DashPathEffect(floatArrayOf(sw * 4f, sw * 1.5f, 0.1f, sw * 1.5f), 0f)
        }
    }

    // ─── onDraw ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        for (img in imageOverlays) {
            img.bitmap?.let { bmp ->
                canvas.save()
                canvas.rotate(img.rotation, img.x + img.width / 2f, img.y + img.height / 2f)
                canvas.drawBitmap(bmp, null, RectF(img.x, img.y, img.x + img.width, img.y + img.height), null)
                canvas.restore()
            }
        }

        offBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        val now = System.currentTimeMillis()
        val laserIter = laserStrokes.iterator()
        while (laserIter.hasNext()) {
            val laser = laserIter.next()
            val elapsed  = (now - laser.timestamp).coerceAtLeast(0L)
            val fraction = (1f - elapsed / LASER_FADE_MS.toFloat()).coerceIn(0f, 1f)
            val alpha    = (255 * fraction).toInt()
            if (alpha > 0) {
                strokePaint.apply {
                    color = LASER_COLOR; this.alpha = alpha; strokeWidth = laser.strokeWidth
                    maskFilter = BlurMaskFilter(laser.strokeWidth * 0.5f, BlurMaskFilter.Blur.SOLID)
                    xfermode = null; pathEffect = null; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
                }
                canvas.drawPath(laser.path, strokePaint)
                strokePaint.maskFilter = null
            }
        }

        val path = activePath ?: return

        when (currentTool) {
            DrawingTool.LASER -> {}
            DrawingTool.RECTANGLE -> {
                strokePaint.apply { color = currentColor; alpha = currentOpacity; strokeWidth = currentStrokeWidth
                    xfermode = null; maskFilter = null; pathEffect = null; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
                canvas.drawRect(shapePreviewRect, strokePaint)
            }
            DrawingTool.ELLIPSE -> {
                strokePaint.apply { color = currentColor; alpha = currentOpacity; strokeWidth = currentStrokeWidth
                    xfermode = null; maskFilter = null; pathEffect = null; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
                canvas.drawOval(shapePreviewRect, strokePaint)
            }
            DrawingTool.PIXEL_ERASER  -> { eraserPaint.strokeWidth = currentStrokeWidth; canvas.drawPath(path, eraserPaint) }
            DrawingTool.STROKE_ERASER -> {}
            DrawingTool.PEN -> {
                val live = StrokeData(DrawingTool.PEN, path, currentColor, currentStrokeWidth, currentOpacity, currentLineStyle)
                drawFinePen(canvas, path, live)
            }
            DrawingTool.CALLIGRAPHY -> {
                val live = StrokeData(DrawingTool.CALLIGRAPHY, path, currentColor, currentStrokeWidth, currentOpacity, rawPoints = ArrayList(rawPoints))
                drawCalligraphy(canvas, live)
            }
            DrawingTool.MATTE_PEN -> {
                val live = StrokeData(DrawingTool.MATTE_PEN, path, currentColor, currentStrokeWidth, 255)
                drawMatteThick(canvas, path, live)
            }
            DrawingTool.BRUSH -> {
                val live = StrokeData(DrawingTool.BRUSH, path, currentColor, currentStrokeWidth, currentOpacity, rawPoints = ArrayList(rawPoints))
                drawRealPencil(canvas, live)
            }
            DrawingTool.HIGHLIGHTER -> {
                val live = StrokeData(DrawingTool.HIGHLIGHTER, path, currentColor, currentStrokeWidth, currentOpacity)
                drawHighlighter(canvas, path, live)
            }
            DrawingTool.LASSO -> {
                strokePaint.apply { color = currentColor; alpha = currentOpacity; strokeWidth = currentStrokeWidth
                    xfermode = null; maskFilter = null; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
                applyLineStyle(strokePaint, LineStyle.DASHED, currentStrokeWidth)
                canvas.drawPath(path, strokePaint)
                strokePaint.pathEffect = null
            }
        }
    }

    // ─── Touch ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!fingerDrawingEnabled) return false

        if (suppressNextTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                suppressNextTouch = false
            }
            return true
        }

        // ✅ إصبعين أو أكتر = تحريك/تكبير الصورة. إصبع واحد = رسم دايماً حتى فوق الصورة
        if (event.pointerCount >= 2) {
            if (activePath != null) {
                activePath = null
                rawPoints.clear()
                invalidate()
            }
            if (imageOverlays.isNotEmpty() && imageController.onTouchEvent(event, imageOverlays)) {
                return true
            }
        }

        val x = event.x; val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchDown(x, y)
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) touchMove(x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchUp(x, y)
        }
        return true
    }

    private fun touchDown(x: Float, y: Float) {
        prevX = x; prevY = y
        rawPoints.clear(); rawPoints.add(PointF(x, y))
        when (currentTool) {
            DrawingTool.STROKE_ERASER -> eraseStrokeAt(x, y)
            DrawingTool.RECTANGLE, DrawingTool.ELLIPSE -> {
                shapeStartX = x; shapeStartY = y; shapePreviewRect.set(x, y, x, y); activePath = Path()
            }
            DrawingTool.LASSO -> { lassoStartX = x; lassoStartY = y; activePath = Path().apply { moveTo(x, y) } }
            else -> activePath = Path().apply { moveTo(x, y) }
        }
        invalidate()
    }

    private fun touchMove(x: Float, y: Float) {
        when (currentTool) {
            DrawingTool.STROKE_ERASER -> { eraseStrokeAt(x, y); return }
            DrawingTool.RECTANGLE, DrawingTool.ELLIPSE ->
                shapePreviewRect.set(min(shapeStartX, x), min(shapeStartY, y), max(shapeStartX, x), max(shapeStartY, y))
            else -> {
                val p = activePath ?: return
                val midX = (prevX + x) / 2f; val midY = (prevY + y) / 2f
                p.quadTo(prevX, prevY, midX, midY)
                rawPoints.add(PointF(x, y))
                prevX = x; prevY = y
            }
        }
        invalidate()
    }

    private fun touchUp(x: Float, y: Float) {
        val path = activePath
        when (currentTool) {
            DrawingTool.STROKE_ERASER -> {}
            DrawingTool.RECTANGLE, DrawingTool.ELLIPSE -> {
                if (path != null) {
                    val s = StrokeData(currentTool, path, currentColor, currentStrokeWidth, currentOpacity, rect = RectF(shapePreviewRect))
                    strokes.add(s); redoStack.clear()
                    offCvs?.let { commitStroke(it, s) }
                    onContentChanged?.invoke(true); onRedoStateChanged?.invoke(false)
                }
            }
            DrawingTool.LASER -> {
                if (path != null) {
                    path.lineTo(x, y)
                    val s = StrokeData(DrawingTool.LASER, path, LASER_COLOR, currentStrokeWidth, 255, timestamp = System.currentTimeMillis())
                    laserStrokes.add(s); scheduleLaserFade(s)
                }
            }
            DrawingTool.LASSO -> {
                if (path != null) {
                    path.lineTo(x, y); path.lineTo(lassoStartX, lassoStartY); path.close()
                    val s = StrokeData(DrawingTool.LASSO, path, currentColor, currentStrokeWidth, currentOpacity, LineStyle.DASHED)
                    strokes.add(s); redoStack.clear()
                    offCvs?.let { commitStroke(it, s) }
                    onContentChanged?.invoke(true); onRedoStateChanged?.invoke(false)
                }
            }
            DrawingTool.CALLIGRAPHY, DrawingTool.BRUSH -> {
                if (path != null) {
                    path.lineTo(x, y); rawPoints.add(PointF(x, y))
                    val s = StrokeData(currentTool, path, currentColor, currentStrokeWidth, currentOpacity, rawPoints = ArrayList(rawPoints))
                    strokes.add(s); redoStack.clear()
                    offCvs?.let { commitStroke(it, s) }
                    onContentChanged?.invoke(true); onRedoStateChanged?.invoke(false)
                }
            }
            else -> {
                if (path != null) {
                    path.lineTo(x, y)
                    val s = StrokeData(currentTool, path, currentColor, currentStrokeWidth, currentOpacity, currentLineStyle)
                    strokes.add(s); redoStack.clear()
                    offCvs?.let { commitStroke(it, s) }
                    onContentChanged?.invoke(true); onRedoStateChanged?.invoke(false)
                }
            }
        }
        activePath = null; rawPoints.clear(); invalidate()
    }

    private fun eraseStrokeAt(x: Float, y: Float) {
        val iter = strokes.iterator(); var changed = false
        while (iter.hasNext()) {
            val s = iter.next(); val b = RectF()
            s.rect?.let { b.set(it) } ?: s.path.computeBounds(b, true)
            b.inset(-40f, -40f)
            if (b.contains(x, y)) { iter.remove(); changed = true }
        }
        if (changed) { rebuildOffscreen(); invalidate(); onContentChanged?.invoke(hasContent()) }
    }

    private fun scheduleLaserFade(stroke: StrokeData) {
        val r = object : Runnable {
            override fun run() { if (laserStrokes.contains(stroke)) { invalidate(); laserHandler.postDelayed(this, 33L) } }
        }
        laserHandler.post(r)
        laserHandler.postDelayed({ laserStrokes.remove(stroke); invalidate() }, LASER_FADE_MS)
    }
}
