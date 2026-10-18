package com.drawanywhere.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

/**
 * Activity شفافة — وظيفتها الوحيدة:
 * فتح معرض الصور ← تمرير الـ URI للـ DrawingService ← إغلاق نفسها
 */
class ImagePickerActivity : Activity() {

    companion object {
        private const val REQUEST_PICK = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val intent = Intent(
                Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ).apply { type = "image/*" }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_PICK)
        } catch (e: Exception) {
            e.printStackTrace()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                // محاولة الحصول على إذن قراءة دائم للـ URI
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) { /* بعض الـ URIs لا تدعم الـ Persistent Permission */ }

                // إرسال الـ URI للـ DrawingService
                val svcIntent = Intent(this, DrawingService::class.java).apply {
                    action = DrawingService.ACTION_ADD_IMAGE
                    putExtra(DrawingService.EXTRA_IMAGE_URI, uri.toString())
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startService(svcIntent)
                }
            }
        }
        finish()
    }
}
