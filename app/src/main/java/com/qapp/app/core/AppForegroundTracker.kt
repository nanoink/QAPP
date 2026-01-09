package com.qapp.app.core

import android.app.ActivityManager
import android.content.Context
import android.os.Process

object AppForegroundTracker {

    fun isInForeground(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pid = Process.myPid()
        val process = manager.runningAppProcesses?.firstOrNull { it.pid == pid } ?: return false
        return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }
}
