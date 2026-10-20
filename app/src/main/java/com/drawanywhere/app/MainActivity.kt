package com.drawanywhere.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CrashLogger.install(this)

        when {
            DrawingService.isRunning -> stopDrawingService()
            Settings.canDrawOverlays(this) -> startDrawingService()
            else -> requestOverlayPermission()
        }

        finish()
    }

    private fun stopDrawingService() {
        stopService(Intent(this, DrawingService::class.java))
    }

    private fun startDrawingService() {
        val intent = Intent(this, DrawingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            @Suppress("DEPRECATION")
            startService(intent)
        }
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }
}
