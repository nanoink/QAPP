package com.qapp.app.core

import android.content.Context

object SecurityOnboardingStore {
    private const val PREFS_NAME = "qapp_security_onboarding"
    private const val KEY_HAS_SEEN = "hasSeenSecurityOnboarding"
    private const val KEY_CRITICAL_GRANTED = "criticalPermissionsGranted"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSeen(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HAS_SEEN, false)
    }

    fun criticalPermissionsGranted(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CRITICAL_GRANTED, false)
    }

    fun markCompleted(context: Context, criticalGranted: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HAS_SEEN, true)
            .putBoolean(KEY_CRITICAL_GRANTED, criticalGranted)
            .apply()
    }
}
