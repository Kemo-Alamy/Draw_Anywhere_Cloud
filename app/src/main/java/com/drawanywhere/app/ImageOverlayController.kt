package com.drawanywhere.app

import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.*

/**
 * يتحكم في التفاعل مع صور الـ Overlay (سحب / تكبير / تصغير / حذف)
 * يُستدعى من DrawingCanvas.onTouchEvent عند اختيار أداة تحريك الصور
 *
 * المنطق:
 *   • إصبع واحد داخل حدود الصورة  → سحب (Drag)
 *   • إصبعان                       → Scale + Rotate (Pinch)
 *   • Double-tap على الصورة        → حذف الصورة
 */
class ImageOverlayController {

    // ─── Drag State ───────────────────────────────────────────────────────────
    private var dragTarget: ImageOverlayData? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // ─── Pinch State ─────────────────────────────────────────────────────────
    private var pinchTarget:    ImageOverlayData? = null
    private var initialSpan:    Float = 0f
    private var initialWidth:   Float = 0f
    private var initialHeight:  Float = 0f
    private var initialAngle:   Float = 0f
    private var initialRotation: Float = 0f

    // ─── Double-tap Detection ─────────────────────────────────────────────────
    private var lastTapTime  = 0L
    private var lastTapX     = 0f
    private var lastTapY     = 0f
    private val DOUBLE_TAP_MS   = 300L
    private val DOUBLE_TAP_SLOP = 40f

    // ─── Callbacks ───────────────────────────────────────────────────────────
    var onImageRemoved: ((ImageOverlayData) -> Unit)? = null
    var onInvalidate:   (() -> Unit)?                 = null

    // ─── Main Touch Handler ───────────────────────────────────────────────────

    /**
     * يُعالج الـ MotionEvent ويرجع true لو اتعمل حاجة
     * يُستدعى من DrawingCanvas.onTouchEvent
     */
    fun onTouchEvent(
        event: MotionEvent,
        overlays: MutableList<ImageOverlayData>
    ): Boolean {
        if (overlays.isEmpty()) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN          -> handleDown(event, overlays)
            MotionEvent.ACTION_POINTER_DOWN  -> handleSecondPointer(event, overlays)
            MotionEvent.ACTION_MOVE          -> handleMove(event, overlays)
            MotionEvent.ACTION_UP            -> handleUp(event, overlays)
            MotionEvent.ACTION_POINTER_UP    -> { pinchTarget = null; true }
            MotionEvent.ACTION_CANCEL        -> { reset(); false }
            else -> false
        }
    }

    // ─── Down ─────────────────────────────────────────────────────────────────

    private fun handleDown(
        event: MotionEvent,
        overlays: MutableList<ImageOverlayData>
    ): Boolean {
        val x = event.x
        val y = event.y

        // Double-tap → حذف الصورة
        val now = System.currentTimeMillis()
        if (now - lastTapTime < DOUBLE_TAP_MS &&
            abs(x - lastTapX) < DOUBLE_TAP_SLOP &&
            abs(y - lastTapY) < DOUBLE_TAP_SLOP
        ) {
            val hit = findTopHit(x, y, overlays)
            if (hit != null) {
                overlays.remove(hit)
                onImageRemoved?.invoke(hit)
                onInvalidate?.invoke()
                lastTapTime = 0L
                return true
            }
        }
        lastTapTime = now
        lastTapX    = x
        lastTapY    = y

        // Drag
        val hit = findTopHit(x, y, overlays)
        if (hit != null) {
            dragTarget  = hit
            dragOffsetX = x - hit.x
            dragOffsetY = y - hit.y
            return true
        }
        return false
    }

    // ─── Second Pointer (Pinch Start) ─────────────────────────────────────────

    private fun handleSecondPointer(
        event: MotionEvent,
        overlays: MutableList<ImageOverlayData>
    ): Boolean {
        if (dragTarget == null) return false

        pinchTarget     = dragTarget
        dragTarget      = null

        val x0 = event.getX(0); val y0 = event.getY(0)
        val x1 = event.getX(1); val y1 = event.getY(1)
        initialSpan     = span(x0, y0, x1, y1)
        initialWidth    = pinchTarget!!.width
        initialHeight   = pinchTarget!!.height
        initialAngle    = angle(x0, y0, x1, y1)
        initialRotation = pinchTarget!!.rotation
        return true
    }

    // ─── Move ─────────────────────────────────────────────────────────────────

    private fun handleMove(
        event: MotionEvent,
        overlays: MutableList<ImageOverlayData>
    ): Boolean {
        // Pinch
        val pTarget = pinchTarget
        if (pTarget != null && event.pointerCount >= 2) {
            val x0 = event.getX(0); val y0 = event.getY(0)
            val x1 = event.getX(1); val y1 = event.getY(1)

            val currentSpan  = span(x0, y0, x1, y1)
            if (initialSpan > 0f) {
                val scaleFactor = (currentSpan / initialSpan).coerceIn(0.1f, 10f)
                pTarget.width   = (initialWidth  * scaleFactor).coerceAtLeast(60f)
                pTarget.height  = (initialHeight * scaleFactor).coerceAtLeast(60f)
            }

            val currentAngle   = angle(x0, y0, x1, y1)
            pTarget.rotation   = initialRotation + (currentAngle - initialAngle)

            onInvalidate?.invoke()
            return true
        }

        // Drag
        val dTarget = dragTarget
        if (dTarget != null && event.pointerCount == 1) {
            dTarget.x = event.x - dragOffsetX
            dTarget.y = event.y - dragOffsetY
            onInvalidate?.invoke()
            return true
        }
        return false
    }

    // ─── Up ───────────────────────────────────────────────────────────────────

    private fun handleUp(
        event: MotionEvent,
        overlays: MutableList<ImageOverlayData>
    ): Boolean {
        val hadTarget = dragTarget != null || pinchTarget != null
        reset()
        return hadTarget
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** إيجاد أعلى صورة (آخر واحدة في الـ List) اللي النقطة (x,y) جوا حدودها */
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

    private fun reset() {
        dragTarget  = null
        pinchTarget = null
    }
}
