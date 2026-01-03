package com.qapp.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PanicActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_PANIC -> CoreSecurityService.triggerPanic(context, "notification")
            ACTION_OFFLINE -> CoreSecurityService.goOffline(context)
        }
    }

    companion object {
        const val ACTION_PANIC = "com.qapp.app.action.NOTIFICATION_PANIC"
        const val ACTION_OFFLINE = "com.qapp.app.action.NOTIFICATION_OFFLINE"
    }
}
