package com.drawanywhere.app

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ✅ يلتقط أي Crash في التطبيق ويكتبه في ملف نصي
 * داخل /storage/emulated/0/Download/ عشان يبقى سهل الوصول من أي مستكشف ملفات
 */
object CrashLogger {

    private const val LOG_FILE_NAME = "drawanywhere_crash_log.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashToFile(thread, throwable)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashToFile(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTraceText = sw.toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

        val report = buildString {
            appendLine("=====================================")
            appendLine("DrawAnywhere Crash Report")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("=====================================")
            appendLine()
            appendLine(stackTraceText)
            appendLine("=====================================")
        }

        // ✅ المسار المباشر: /storage/emulated/0/Download/
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val logFile = File(downloadDir, LOG_FILE_NAME)
        // append=true عشان لو حصل أكتر من كراش، كل الكراشات تتسجل فوق بعض بالترتيب
        logFile.appendText(report + "\n\n")
    }
}
