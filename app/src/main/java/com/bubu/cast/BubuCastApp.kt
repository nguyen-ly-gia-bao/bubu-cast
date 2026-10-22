package com.bubu.cast

import android.app.Application
import android.content.Intent
import android.util.Log
import kotlin.system.exitProcess

/**
 * Application class: bắt crash toàn cục, hiện dialog lỗi để user chụp màn hình gửi.
 */
class BubuCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            Log.e("BubuCast", "CRASH trên thread ${thread.name}: $stackTrace")

            // Lưu crash vào SharedPreferences để hiện khi mở lại
            try {
                getSharedPreferences("bubu_crash", MODE_PRIVATE).edit()
                    .putString("last_crash", "${throwable.javaClass.simpleName}: ${throwable.message}\n$stackTrace".take(2000))
                    .apply()
            } catch (_: Exception) { }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
