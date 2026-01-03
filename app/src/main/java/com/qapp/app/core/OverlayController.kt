package com.qapp.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.qapp.app.core.OverlayPolicyStore

object OverlayController {

    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isPermissionGranted(context: Context): Boolean {
        // SYSTEM_ALERT_WINDOW is not available on API < 23 (Android 6.0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return Settings.canDrawOverlays(context)
    }

    fun requestPermission(context: Context) {
        recordAttempt(context)
        
        // SYSTEM_ALERT_WINDOW is a special permission that requires user to manually enable it
        // on Android 6.0+. We need to open the system settings dialog.
        val packageUri = Uri.parse("package:${context.packageName}")
        
        // Try primary method: ACTION_MANAGE_OVERLAY_PERMISSION with package URI
        val primary = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        
        try {
            Log.d("PERMISSIONS", "Opening overlay settings (primary)")
            context.startActivity(primary)
            return
        } catch (ex: Exception) {
            Log.d("PERMISSIONS", "Primary method failed: ${ex.message}")
        }

        // Try secondary method: ACTION_MANAGE_OVERLAY_PERMISSION without package URI
        val secondary = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        
        try {
            Log.d("PERMISSIONS", "Opening overlay settings (secondary)")
            context.startActivity(secondary)
            return
        } catch (ex: Exception) {
            Log.d("PERMISSIONS", "Secondary method failed: ${ex.message}")
        }

        // Fallback method: open app details settings
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        
        try {
            Log.d("PERMISSIONS", "Opening app details settings (fallback)")
            context.startActivity(details)
        } catch (ex: Exception) {
            Log.w("PERMISSIONS", "Unable to open any settings screen", ex)
        }
    }

    private fun recordAttempt(context: Context) {
        overlayScope.launch {
            val state = OverlayPolicyStore.recordAttempt(context)
            Log.d("PERMISSIONS", "OVERLAY attempt #${state.attemptsCount}")
        }
    }

    fun showOverlay(context: Context): Boolean {
        if (!isPermissionGranted(context)) {
            Log.w("PERMISSIONS", "Cannot show overlay: permission not granted")
            return false
        }
        try {
            val intent = Intent(context, PanicOverlayService::class.java)
            context.startService(intent)
            return true
        } catch (ex: Exception) {
            Log.w("PERMISSIONS", "Failed to start overlay service", ex)
            return false
        }
    }

    fun hideOverlay(context: Context) {
        try {
            val intent = Intent(context, PanicOverlayService::class.java)
            context.stopService(intent)
        } catch (ex: Exception) {
            Log.w("PERMISSIONS", "Failed to stop overlay service", ex)
        }
    }
}
