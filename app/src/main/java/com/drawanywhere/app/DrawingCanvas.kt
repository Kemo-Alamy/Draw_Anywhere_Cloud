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

    // ✅ نقاط مسار الممحاية الجزئية خلال اللمسة الواحدة — تُستخدم لتقسيم الخطوط فعليًا عند الرفع
    private val eraserSweepPoints = mutableListOf<PointF>()

    private var eraserCursorX: Float = -1000f
    private var eraserCursorY: Float = -1000f
    private var showEraserCursor: Boolean = false

    private var offBmp: Bitmap? = null
    private var offCvs: Canvas? = null

    val imageOverlays = mutableListOf<ImageOverlayData>()
    private val imageController = ImageOverlayController().apply {
        onInvalidate = { invalidate() }
    }

    private val laserHandler  = Handler(Looper.getMainLooper())
    private val LASER_COLOR   = Color.parseColor("#FF3B14")
    private val LASER_FADE_MS = 2600L

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val eraserCursorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val eraserCursorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2.2f
    }
    private val eraserCursorInnerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33000000
        strokeWidth = 1f
    }

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
        strokes.clear(); laserStrokes.clear()
        redoStack.clear()
        laserHandler.removeCallbacksAndMessages(null)
        offBmp?.eraseColor(Color.TRANSPARENT); invalidate()
        onContentChanged?.invoke(hasContent())
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

        val path = activePath
        if (path != null) {
            when (currentTool) {
                DrawingTool.LASER -> {
                    strokePaint.apply {
                        color = LASER_COLOR; alpha = 255; strokeWidth = currentStrokeWidth
                        maskFilter = BlurMaskFilter(currentStrokeWidth * 0.5f, BlurMaskFilter.Blur.SOLID)
                        xfermode = null; pathEffect = null; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
                    }
                    canvas.drawPath(path, strokePaint)
                    strokePaint.maskFilter = null
                }
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
                DrawingTool.PIXEL_ERASER  -> {}
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

        if (showEraserCursor && (currentTool == DrawingTool.STROKE_ERASER || currentTool == DrawingTool.PIXEL_ERASER)) {
            val radius = currentStrokeWidth / 2f
            canvas.drawCircle(eraserCursorX, eraserCursorY, radius, eraserCursorFillPaint)
            canvas.drawCircle(eraserCursorX, eraserCursorY, radius, eraserCursorStrokePaint)
            canvas.drawCircle(eraserCursorX, eraserCursorY, radius + 2.5f, eraserCursorInnerStrokePaint)
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

        if (event.pointerCount >= 2) {
            if (activePath != null) {
                activePath = null
                rawPoints.clear()
                invalidate()
            }
            if (imageOverlays.isNotEmpty() && imageController.onMultiTouchEvent(event, imageOverlays)) {
                return true
            }
        }

        val x = event.x; val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (imageOverlays.isNotEmpty() && imageController.checkDoubleTapDelete(event, imageOverlays)) {
                    return true
                }
                touchDown(x, y)
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) touchMove(x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchUp(x, y)
        }
        return true
    }

    private fun touchDown(x: Float, y: Float) {
        prevX = x; prevY = y
        rawPoints.clear(); rawPoints.add(PointF(x, y))
        when (currentTool) {
            DrawingTool.STROKE_ERASER -> {
                showEraserCursor = true; eraserCursorX = x; eraserCursorY = y
                eraseStrokeAt(x, y)
            }
            DrawingTool.PIXEL_ERASER -> {
                showEraserCursor = true; eraserCursorX = x; eraserCursorY = y
                activePath = Path().apply { moveTo(x, y) }
                eraserSweepPoints.clear()
                eraserSweepPoints.add(PointF(x, y))
                // مسح بصري فوري للتجاوب — سيُستبدل لاحقًا بإعادة بناء دقيقة عند رفع الإصبع
                offCvs?.let { cvs ->
                    eraserPaint.strokeWidth = currentStrokeWidth
                    cvs.drawCircle(x, y, currentStrokeWidth / 2f, eraserPaint)
                }
            }
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
            DrawingTool.STROKE_ERASER -> {
                eraserCursorX = x; eraserCursorY = y
                eraseStrokeAt(x, y)
            }
            DrawingTool.PIXEL_ERASER -> {
                eraserCursorX = x; eraserCursorY = y
                val p = activePath
                if (p != null) {
                    val midX = (prevX + x) / 2f; val midY = (prevY + y) / 2f
                    p.quadTo(prevX, prevY, midX, midY)
                }
                eraserSweepPoints.add(PointF(x, y))
                offCvs?.let { cvs ->
                    eraserPaint.strokeWidth = currentStrokeWidth
                    cvs.drawLine(prevX, prevY, x, y, eraserPaint)
                }
                prevX = x; prevY = y
            }
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
            DrawingTool.STROKE_ERASER -> {
                showEraserCursor = false
            }
            DrawingTool.PIXEL_ERASER -> {
                showEraserCursor = false
                eraserSweepPoints.add(PointF(x, y))
                // ✅ التقسيم الفعلي للخطوط والأشكال بناءً على مسار الممحاية بالكامل
                performGeometricErase(eraserSweepPoints, currentStrokeWidth / 2f)
                eraserSweepPoints.clear()
            }
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

    // ✅ الممحاية الكاملة: تتجاهل القطع المتعلقة بالممحاية الجزئية (الأصل اختفى بعد التقسيم)
    // وتفحص المسافة الفعلية من الخط — كل قطعة مستقلة منذ التقسيم
    private fun eraseStrokeAt(x: Float, y: Float) {
        val tolerance = (currentStrokeWidth / 2f) + 24f
        val iter = strokes.iterator()
        var changed = false
        while (iter.hasNext()) {
            val s = iter.next()
            if (s.tool == DrawingTool.PIXEL_ERASER) continue

            val hit = when (s.tool) {
                DrawingTool.RECTANGLE, DrawingTool.ELLIPSE -> {
                    val b = RectF()
                    s.rect?.let { b.set(it) }
                    isPointNearRectBorder(b, x, y, tolerance)
                }
                else -> isPointNearPath(s.path, x, y, tolerance + s.strokeWidth / 2f)
            }
            if (hit) { iter.remove(); changed = true }
        }
        if (changed) { rebuildOffscreen(); invalidate(); onContentChanged?.invoke(hasContent()) }
    }

    // ✅ التقسيم الفعلي — بيحول أي خط أو محيط شكل لمسته الممحاية الجزئية لقطع مستقلة تمامًا
    private fun performGeometricErase(sweep: List<PointF>, radius: Float) {
        if (sweep.isEmpty()) return
        val result = mutableListOf<StrokeData>()
        var changed = false

        for (s in strokes) {
            when (s.tool) {
                // ✅ المربع والدائرة بيتحولوا لمحيط (outline) قابل للتقسيم زي أي خط عادي —
                // مش بيتمسحوا بالكامل لمجرد ما اللمسة تلمس أي جزء منهم
                DrawingTool.RECTANGLE, DrawingTool.ELLIPSE -> {
                    val rect = s.rect
                    if (rect == null) { result.add(s); continue }

                    val outline = Path()
                    if (s.tool == DrawingTool.RECTANGLE) outline.addRect(rect, Path.Direction.CW)
                    else outline.addOval(rect, Path.Direction.CW)

                    val points = samplePathPoints(outline)
                    if (points.size < 2) { result.add(s); continue }

                    val hitRadius = radius + s.strokeWidth / 2f
                    val keep = BooleanArray(points.size)
                    for (i in points.indices) {
                        val p = points[i]
                        val erased = sweep.any { sp -> hypot(sp.x - p.x, sp.y - p.y) <= hitRadius }
                        keep[i] = !erased
                    }

                    if (keep.all { it }) { result.add(s); continue }
                    changed = true

                    var i = 0
                    while (i < points.size) {
                        if (!keep[i]) { i++; continue }
                        val start = i
                        while (i < points.size && keep[i]) i++
                        val runPoints = points.subList(start, i)
                        if (runPoints.size >= 2) {
                            val newPath = buildSmoothPath(runPoints)
                            result.add(
                                StrokeData(
                                    tool = DrawingTool.PEN,
                                    path = newPath,
                                    color = s.color,
                                    strokeWidth = s.strokeWidth,
                                    opacity = s.opacity,
                                    lineStyle = LineStyle.SOLID,
                                    rawPoints = null,
                                    timestamp = s.timestamp
                                )
                            )
                        }
                    }
                }
                DrawingTool.LASSO -> {
                    val hit = sweep.any { p -> isPointNearPath(s.path, p.x, p.y, radius + s.strokeWidth / 2f) }
                    if (hit) changed = true else result.add(s)
                }
                else -> {
                    val points: List<PointF> = s.rawPoints ?: samplePathPoints(s.path)
                    if (points.size < 2) { result.add(s); continue }

                    val keep = BooleanArray(points.size)
                    val hitRadius = radius + s.strokeWidth / 2f
                    for (i in points.indices) {
                        val p = points[i]
                        val erased = sweep.any { sp -> hypot(sp.x - p.x, sp.y - p.y) <= hitRadius }
                        keep[i] = !erased
                    }

                    if (keep.all { it }) { result.add(s); continue }
                    changed = true

                    var i = 0
                    while (i < points.size) {
                        if (!keep[i]) { i++; continue }
                        val start = i
                        while (i < points.size && keep[i]) i++
                        val runPoints = points.subList(start, i)
                        if (runPoints.size >= 2) {
                            val newPath = buildSmoothPath(runPoints)
                            result.add(
                                StrokeData(
                                    tool = s.tool,
                                    path = newPath,
                                    color = s.color,
                                    strokeWidth = s.strokeWidth,
                                    opacity = s.opacity,
                                    lineStyle = s.lineStyle,
                                    rawPoints = if (s.rawPoints != null) ArrayList(runPoints) else null,
                                    timestamp = s.timestamp
                                )
                            )
                        }
                    }
                }
            }
        }

        if (changed) {
            strokes.clear(); strokes.addAll(result)
            redoStack.clear()
            rebuildOffscreen(); invalidate()
            onContentChanged?.invoke(hasContent())
            onRedoStateChanged?.invoke(false)
        } else {
            // مفيش تغيير فعلي بالبيانات — نعيد البناء عشان نشيل أي مسح بصري مؤقت كان بيتعرض أثناء السحب فوق مفيش حاجة
            rebuildOffscreen(); invalidate()
        }
    }

    /** يأخذ عينة نقاط متباعدة بانتظام من مسار الخط (للأدوات اللي معندهاش rawPoints زي PEN، وكمان لمحيط الأشكال) */
    private fun samplePathPoints(path: Path): List<PointF> {
        val measure = PathMeasure(path, false)
        val length = measure.length
        val pts = mutableListOf<PointF>()
        val pos = FloatArray(2)
        if (length <= 0f) {
            measure.getPosTan(0f, pos, null)
            pts.add(PointF(pos[0], pos[1]))
            return pts
        }
        val step = 4f
        var d = 0f
        while (d <= length) {
            measure.getPosTan(d, pos, null)
            pts.add(PointF(pos[0], pos[1]))
            d += step
        }
        measure.getPosTan(length, pos, null)
        pts.add(PointF(pos[0], pos[1]))
        return pts
    }

    /** يبني Path سلس من نقاط متتالية بنفس تقنية الرسم الحي (quadTo) */
    private fun buildSmoothPath(points: List<PointF>): Path {
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 2) {
            path.lineTo(points[1].x, points[1].y)
            return path
        }
        for (i in 0 until points.size - 1) {
            val cur = points[i]; val next = points[i + 1]
            val midX = (cur.x + next.x) / 2f; val midY = (cur.y + next.y) / 2f
            path.quadTo(cur.x, cur.y, midX, midY)
        }
        path.lineTo(points.last().x, points.last().y)
        return path
    }

    /** يتأكد إن النقطة قريبة فعليًا من مسار الخط، بدقة بكسل، مش من صندوق تخيلي حواليه */
    private fun isPointNearPath(path: Path, x: Float, y: Float, tolerance: Float): Boolean {
        val measure = PathMeasure(path, false)
        val pos = FloatArray(2)
        val length = measure.length
        if (length <= 0f) {
            measure.getPosTan(0f, pos, null)
            return hypot(pos[0] - x, pos[1] - y) <= tolerance
        }
        var distance = 0f
        val step = 6f
        while (distance <= length) {
            measure.getPosTan(distance, pos, null)
            if (hypot(pos[0] - x, pos[1] - y) <= tolerance) return true
            distance += step
        }
        measure.getPosTan(length, pos, null)
        return hypot(pos[0] - x, pos[1] - y) <= tolerance
    }

    private fun isPointNearRectBorder(rect: RectF, x: Float, y: Float, tolerance: Float): Boolean {
        val outer = RectF(rect).apply { inset(-tolerance, -tolerance) }
        val inner = RectF(rect).apply { inset(tolerance, tolerance) }
        return outer.contains(x, y) && !inner.contains(x, y)
    }

    private fun scheduleLaserFade(stroke: StrokeData) {
        val r = object : Runnable {
            override fun run() { if (laserStrokes.contains(stroke)) { invalidate(); laserHandler.postDelayed(this, 33L) } }
        }
        laserHandler.post(r)
        laserHandler.postDelayed({ laserStrokes.remove(stroke); invalidate() }, LASER_FADE_MS)
    }
}
