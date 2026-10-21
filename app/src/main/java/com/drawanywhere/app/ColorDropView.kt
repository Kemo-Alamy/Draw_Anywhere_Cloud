package com.drawanywhere.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * ✅ أيقونة لون عصرية على شكل Droplet (نقطة دهان) بدل الدايرة البسيطة
 * بتتلون تلقائياً باللون الحالي المختار + حافة بيضاء رفيعة + ظل خفيف داخلي
 */
class ColorDropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentColor: Int = Color.RED

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 3f
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55FFFFFF
    }

    private val dropPath = Path()

    fun setColor(color: Int) {
        currentColor = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildDropPath(w.toFloat(), h.toFloat())
    }

    /** يبني شكل نقطة الدهان: رأس مدبب فوق + جسم دائري تحت */
    private fun buildDropPath(w: Float, h: Float) {
        dropPath.reset()
        val cx = w / 2f
        val topY = h * 0.08f
        val bulbCenterY = h * 0.62f
        val radius = w * 0.40f

        dropPath.moveTo(cx, topY)
        dropPath.cubicTo(
            cx + radius * 1.15f, h * 0.38f,
            cx + radius, bulbCenterY - radius * 0.55f,
            cx + radius, bulbCenterY
        )
        dropPath.arcTo(
            cx - radius, bulbCenterY - radius,
            cx + radius, bulbCenterY + radius,
            0f, 180f, false
        )
        dropPath.cubicTo(
            cx - radius, bulbCenterY - radius * 0.55f,
            cx - radius * 1.15f, h * 0.38f,
            cx, topY
        )
        dropPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        fillPaint.color = currentColor
        canvas.drawPath(dropPath, fillPaint)
        canvas.drawPath(dropPath, strokePaint)

        // لمعة علوية خفيفة لإحساس ثلاثي الأبعاد
        canvas.save()
        canvas.clipPath(dropPath)
        canvas.drawCircle(width * 0.36f, height * 0.4f, width * 0.18f, highlightPaint)
        canvas.restore()
    }
}
