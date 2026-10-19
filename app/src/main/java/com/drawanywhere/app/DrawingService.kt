package com.drawanywhere.app

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class DrawingService : Service() {

    companion object {
        @Volatile var isRunning: Boolean = false
        const val ACTION_ADD_IMAGE = "com.drawanywhere.app.ACTION_ADD_IMAGE"
        const val EXTRA_IMAGE_URI  = "extra_image_uri"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "draw_anywhere_channel"
    }

    internal lateinit var windowManager:  WindowManager
    internal lateinit var drawingCanvas:  DrawingCanvas
    internal lateinit var toolbarManager: ToolbarManager
    internal lateinit var panelManager:   PanelManager
    internal lateinit var appPreferences: AppPreferences

    override fun onCreate() {
        super.onCreate()
        isRunning      = true
        appPreferences = AppPreferences(this)
        windowManager  = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        drawingCanvas  = DrawingCanvas(this)
        toolbarManager = ToolbarManager(this, windowManager)
        panelManager   = PanelManager(this, windowManager)

        toolbarManager.drawingCanvas = drawingCanvas
        toolbarManager.panelManager  = panelManager
        panelManager.drawingCanvas   = drawingCanvas
        panelManager.toolbarManager  = toolbarManager

        drawingCanvas.fingerDrawingEnabled = appPreferences.fingerDrawing
        drawingCanvas.applyToolState(DrawingTool.PEN, appPreferences.getToolState(DrawingTool.PEN))

        // ✅ كل ما محتوى الـ Canvas يتغير (رسم/مسح/undo) الـ Toolbar يحدّث حالة Undo/Trash
        drawingCanvas.onContentChanged = { hasContent ->
            if (::toolbarManager.isInitialized) toolbarManager.updateContentState(hasContent)
        }

        attachCanvasOverlay()
        toolbarManager.init(appPreferences.toolbarOrientation)
        refreshCanvasTouchMode()
        // ✅ الحالة الأولية: Undo/Trash باهتين لأن الـ Canvas فاضي
        toolbarManager.updateContentState(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ADD_IMAGE) {
            val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
            if (uriString != null) handleAddImage(Uri.parse(uriString))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            if (::panelManager.isInitialized)   panelManager.dismissAll()
            if (::toolbarManager.isInitialized)  toolbarManager.destroy()
            if (::drawingCanvas.isInitialized)   windowManager.removeView(drawingCanvas)
        } catch (e: Exception) { e.printStackTrace() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachCanvasOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        windowManager.addView(drawingCanvas, params)
    }

    /**
     * ✅ يتحكم فعلياً في استقبال اللمس:
     * touchable=false → FLAG_NOT_TOUCHABLE مفعّل → كل اللمسات بتعدي
     * مباشرة للتطبيقات تحت (Touch Pass-Through حقيقي)
     */
    fun setCanvasTouchable(touchable: Boolean) {
        try {
            val p = drawingCanvas.layoutParams as? WindowManager.LayoutParams ?: return
            p.flags = if (touchable)
                p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else
                p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(drawingCanvas, p)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun refreshCanvasTouchMode() {
        val enabled = appPreferences.fingerDrawing
        drawingCanvas.fingerDrawingEnabled = enabled
        setCanvasTouchable(enabled)
        if (::toolbarManager.isInitialized) toolbarManager.updateFingerIndicator(enabled)
    }

    fun applyOrientation(orientation: ToolbarOrientation) {
        appPreferences.toolbarOrientation = orientation
        toolbarManager.changeOrientation(orientation)
    }

    fun launchImagePicker() {
        val intent = Intent(this, ImagePickerActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun handleAddImage(uri: Uri) {
        try {
            val raw = loadBitmap(uri) ?: return
            val bmp = scaleBitmapIfNeeded(raw)
            if (raw !== bmp) raw.recycle()
            val overlay = ImageOverlayData(
                id = System.currentTimeMillis().toInt(), x = 60f, y = 200f,
                width = bmp.width.toFloat(), height = bmp.height.toFloat(), uri = uri, bitmap = bmp
            )
            if (::drawingCanvas.isInitialized) drawingCanvas.addImageOverlay(overlay)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(contentResolver, uri)
            val bmp = ImageDecoder.decodeBitmap(src)
            if (bmp.config == Bitmap.Config.HARDWARE)
                bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
            else bmp
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    } catch (e: Exception) { e.printStackTrace(); null }

    private fun scaleBitmapIfNeeded(bmp: Bitmap): Bitmap {
        val max = 900
        val w = bmp.width; val h = bmp.height
        if (w <= max && h <= max) return bmp
        val scale = max.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false); enableLights(false); enableVibration(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true).setOngoing(true).build()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
