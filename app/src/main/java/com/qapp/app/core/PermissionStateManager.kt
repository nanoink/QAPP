package com.qapp.app.core

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

data class CriticalPermissionState(
    val locationForeground: Boolean,
    val locationBackground: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val overlay: Boolean,
    val microphone: Boolean
) {
    val areAllCriticalPermissionsGranted: Boolean =
        locationForeground &&
            locationBackground &&
            microphone
}

object PermissionStateManager {
    enum class PermissionStep {
        LOCATION_BACKGROUND,
        MICROPHONE,
        COMPLETED
    }

    fun check(context: Context): CriticalPermissionState {
        val locationForeground =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val locationBackground =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                true
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        val batteryOptimizationIgnored =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                true
            } else {
                BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
            }
        val overlay =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                true
            } else {
                Settings.canDrawOverlays(context)
            }
        val microphone =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        return CriticalPermissionState(
            locationForeground = locationForeground,
            locationBackground = locationBackground,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            overlay = overlay,
            microphone = microphone
        )
    }

    fun getNextPendingPermission(context: Context): PermissionStep {
        val state = check(context)
        return when {
            !state.locationForeground || !state.locationBackground -> PermissionStep.LOCATION_BACKGROUND
            !state.microphone -> PermissionStep.MICROPHONE
            else -> PermissionStep.COMPLETED
        }
    }
}
