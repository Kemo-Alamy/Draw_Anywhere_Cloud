package com.drawanywhere.app

import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.*

/**
 * يتحكم في التفاعل مع صور الـ Overlay (تكبير/تصغير/تدوير/تحريك/حذف)
 *
 * المنطق الجديد:
 *   • إصبع واحد  → مخصص بالكامل للرسم (مُستدعى مش من هنا)
 *   • إصبعان     → Scale + Rotate + Move مع بعض (Pinch شامل)
 *   • دبل-تاب بإصبع واحد على الصورة → حذف الصورة
 */
class ImageOverlayController {

    // ─── Pinch State (إصبعين) ──────────────────────────────────────────────────
    private var pinchTarget:       ImageOverlayData? = null
    private var initialSpan:       Float = 0f
    private var initialWidth:      Float = 0f
    private var initialHeight:     Float = 0f
    private var initialAngle:      Float = 0f
    private var initialRotation:   Float = 0f
    private var initialCenterX:    Float = 0f
    private var initialCenterY:    Float = 0f
    private var initialImgCenterX: Float = 0f
    private var initialImgCenterY: Float = 0f

    // ─── Double-tap Detection (إصبع واحد) ──────────────────────────────────────
    private var lastTapTime  = 0L
    private var lastTapX     = 0f
    private var lastTapY     = 0f
    private val DOUBLE_TAP_MS   = 300L
    private val DOUBLE_TAP_SLOP = 40f

    var onImageRemoved: ((ImageOverlayData) -> Unit)? = null
    var onInvalidate:   (() -> Unit)?                 = null

    /**
     * ✅ يُستدعى فقط لما عدد الأصابع 2 أو أكتر.
     * بيكبّر/يصغّر/يدوّر/يحرّك الصورة مع بعض في حركة واحدة طبيعية.
     */
    fun onMultiTouchEvent(
        event: MotionEvent,
        overlays: MutableList<ImageOverlayData>
    ): Boolean {
        if (overlays.isEmpty()) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> startPinch(event, overlays)
            MotionEvent.ACTION_MOVE         -> movePinch(event)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val had = pinchTarget != null
                pinchTarget = null
                had
            }
            MotionEvent.ACTION_CANCEL -> { pinchTarget = null; false }
            else -> false
        }
    }

    /**
     * ✅ يُستدعى فقط عند ACTION_DOWN بإصبع واحد — بيتأكد لو دوسة مزدوجة فوق صورة.
     * بيرجع true لو اتمسحت صورة فعلاً (علشان الرسم ميبدأش بدل كده).
     */
    fun checkDoubleTapDelete(
        event: MotionEvent,
        overlays: MutableList<ImageOverlayData>
    ): Boolean {
        if (overlays.isEmpty()) return false
        val x = event.x; val y = event.y
        val now = System.currentTimeMillis()

        val isDoubleTap = now - lastTapTime < DOUBLE_TAP_MS &&
                abs(x - lastTapX) < DOUBLE_TAP_SLOP &&
                abs(y - lastTapY) < DOUBLE_TAP_SLOP

        lastTapTime = now
        lastTapX = x
        lastTapY = y

        if (!isDoubleTap) return false

        val hit = findTopHit(x, y, overlays) ?: return false
        overlays.remove(hit)
        onImageRemoved?.invoke(hit)
        onInvalidate?.invoke()
        lastTapTime = 0L
        return true
    }

    // ─── Pinch Internals ────────────────────────────────────────────────────────

    private fun startPinch(
        event: MotionEvent,
        overlays: MutableList<ImageOverlayData>
    ): Boolean {
        if (event.pointerCount < 2) return false
        val x0 = event.getX(0); val y0 = event.getY(0)
        val x1 = event.getX(1); val y1 = event.getY(1)

        // لاقي صورة تحت أي من الإصبعين
        val target = findTopHit(x0, y0, overlays) ?: findTopHit(x1, y1, overlays) ?: return false

        pinchTarget       = target
        initialSpan       = span(x0, y0, x1, y1)
        initialWidth      = target.width
        initialHeight     = target.height
        initialAngle      = angle(x0, y0, x1, y1)
        initialRotation   = target.rotation
        initialCenterX    = (x0 + x1) / 2f
        initialCenterY    = (y0 + y1) / 2f
        initialImgCenterX = target.x + target.width / 2f
        initialImgCenterY = target.y + target.height / 2f
        return true
    }

    private fun movePinch(event: MotionEvent): Boolean {
        val target = pinchTarget ?: return false
        if (event.pointerCount < 2) return false

        val x0 = event.getX(0); val y0 = event.getY(0)
        val x1 = event.getX(1); val y1 = event.getY(1)

        // تكبير/تصغير
        val currentSpan = span(x0, y0, x1, y1)
        val scaleFactor = if (initialSpan > 0f) (currentSpan / initialSpan).coerceIn(0.1f, 10f) else 1f
        val newWidth  = (initialWidth  * scaleFactor).coerceAtLeast(60f)
        val newHeight = (initialHeight * scaleFactor).coerceAtLeast(60f)

        // تدوير
        val currentAngle = angle(x0, y0, x1, y1)
        target.rotation  = initialRotation + (currentAngle - initialAngle)

        // تحريك — بيتبع حركة نقطة المنتصف بين الإصبعين
        val currentCenterX = (x0 + x1) / 2f
        val currentCenterY = (y0 + y1) / 2f
        val newImgCenterX = initialImgCenterX + (currentCenterX - initialCenterX)
        val newImgCenterY = initialImgCenterY + (currentCenterY - initialCenterY)

        target.width  = newWidth
        target.height = newHeight
        target.x = newImgCenterX - newWidth  / 2f
        target.y = newImgCenterY - newHeight / 2f

        onInvalidate?.invoke()
        return true
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun findTopHit(
        x: Float,
        y: Float,
        overlays: List<ImageOverlayData>
    ): ImageOverlayData? {
        for (i in overlays.indices.reversed()) {
            val img  = overlays[i]
            val rect = RectF(img.x, img.y, img.x + img.width, img.y + img.height)
            if (rect.contains(x, y)) return img
        }
        return null
    }

    private fun span(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0; val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }

    private fun angle(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
}
