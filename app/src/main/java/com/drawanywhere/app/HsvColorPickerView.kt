package com.drawanywhere.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class HsvColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onColorChanged: ((Int) -> Unit)? = null

    // H:0-360  S:0-1  V:0-1
    private val hsv = floatArrayOf(0f, 1f, 1f)

    private var centerX     = 0f
    private var centerY     = 0f
    private var radius      = 0f
    private var selectorX   = 0f
    private var selectorY   = 0f
    private var wheelBitmap: Bitmap? = null

    private val SELECTOR_R = 11f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 1.5f
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f
    }

    // ─── Size ─────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        centerX = w / 2f
        centerY = h / 2f
        radius  = minOf(w, h) / 2f - SELECTOR_R - 4f
        buildWheel(w, h)
        syncSelector()
    }

    // ─── Build Wheel ─────────────────────────────────────────────────────────

    private fun buildWheel(w: Int, h: Int) {
        wheelBitmap?.recycle()
        val bmp    = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val hsTemp = floatArrayOf(0f, 0f, hsv[2])
        for (py in 0 until h) {
            for (px in 0 until w) {
                val dx   = px - centerX
                val dy   = py - centerY
                val dist = sqrt(dx * dx + dy * dy)
                if (dist <= radius) {
                    hsTemp[0] = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                        .toFloat() + 360f) % 360f)
                    hsTemp[1] = (dist / radius).coerceIn(0f, 1f)
                    pixels[py * w + px] = Color.HSVToColor(hsTemp)
                } else {
                    pixels[py * w + px] = Color.TRANSPARENT
                }
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        wheelBitmap = bmp
    }

    // ─── Sync Selector Position ───────────────────────────────────────────────

    private fun syncSelector() {
        val angle = Math.toRadians(hsv[0].toDouble())
        val dist  = hsv[1] * radius
        selectorX = centerX + (cos(angle) * dist).toFloat()
        selectorY = centerY + (sin(angle) * dist).toFloat()
    }

    // ─── Draw ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        wheelBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        fillPaint.color = getSelectedColor()
        // دائرة مملوءة باللون الحالي
        canvas.drawCircle(selectorX, selectorY, SELECTOR_R, fillPaint)
        // حلقة بيضاء داخلية
        canvas.drawCircle(selectorX, selectorY, SELECTOR_R, innerRingPaint)
        // حلقة سوداء خارجية للـ contrast
        canvas.drawCircle(selectorX, selectorY, SELECTOR_R + 2f, outerRingPaint)
    }

    // ─── Touch ───────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN &&
            event.actionMasked != MotionEvent.ACTION_MOVE) return false

        val dx        = event.x - centerX
        val dy        = event.y - centerY
        val dist      = sqrt(dx * dx + dy * dy)
        val clampDist = dist.coerceAtMost(radius)

        hsv[0] = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            .toFloat() + 360f) % 360f)
        hsv[1] = (clampDist / radius).coerceIn(0f, 1f)

        // تحديث موضع الدائرة (مقيّدة بحافة الـ Wheel)
        val angle = Math.toRadians(hsv[0].toDouble())
        selectorX = centerX + (cos(angle) * clampDist).toFloat()
        selectorY = centerY + (sin(angle) * clampDist).toFloat()

        onColorChanged?.invoke(getSelectedColor())
        invalidate()
        return true
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        if (radius > 0f) { buildWheel(width, height); syncSelector(); invalidate() }
    }

    fun getSelectedColor(): Int = Color.HSVToColor(hsv)

    fun setBrightness(v: Float) {
        hsv[2] = v.coerceIn(0f, 1f)
        if (radius > 0f) { buildWheel(width, height); invalidate() }
        onColorChanged?.invoke(getSelectedColor())
    }
}
