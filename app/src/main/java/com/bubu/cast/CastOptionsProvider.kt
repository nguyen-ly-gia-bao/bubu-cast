package com.bubu.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Provider bắt buộc cho Google Cast SDK.
 * Không có class này trong Manifest -> CastContext.getSharedInstance() sẽ crash.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            // Dùng Default Media Receiver cho phát video cơ bản
            .setReceiverApplicationId("CC1AD845")
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }
}
