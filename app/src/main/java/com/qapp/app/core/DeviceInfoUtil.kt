package com.qapp.app.core

import android.os.Build
import android.util.Log

object DeviceInfoUtil {

    fun manufacturer(): String = Build.MANUFACTURER?.lowercase() ?: "unknown"

    fun isAggressiveBatteryDevice(): Boolean {
        val maker = manufacturer()
        val aggressive = maker.contains("xiaomi") ||
            maker.contains("redmi") ||
            maker.contains("poco") ||
            maker.contains("samsung") ||
            maker.contains("huawei") ||
            maker.contains("motorola")
        if (aggressive) {
            Log.i(LogTags.WATCHDOG, "Aggressive battery maker detected: $maker")
        }
        return aggressive
    }
}
