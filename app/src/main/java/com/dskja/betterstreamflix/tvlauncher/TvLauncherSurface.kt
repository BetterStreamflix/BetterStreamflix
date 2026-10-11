package com.dskja.betterstreamflix.tvlauncher

import android.content.Context
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.utils.DeviceCapabilities

/** Gates Android TV Channels / Watch Next publishing to TV surfaces only. */
object TvLauncherSurface {
    fun isEnabled(context: Context): Boolean {
        if (BuildConfig.APP_LAYOUT == "mobile") return false
        if (BuildConfig.APP_LAYOUT == "tv") return true
        return DeviceCapabilities.isLeanbackDevice(context) ||
            DeviceCapabilities.isAmazonFireTv(context)
    }
}
