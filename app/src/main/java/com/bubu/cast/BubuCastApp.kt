package com.bubu.cast

import android.app.Application
import android.util.Log

/**
 * Application class: bắt crash toàn cục để log, tránh out lặng lẽ không rõ nguyên nhân.
 */
class BubuCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("BubuCast", "CRASH trên thread ${thread.name}", throwable)
            // Ghi ra logcat rồi để hệ thống xử lý như bình thường
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
