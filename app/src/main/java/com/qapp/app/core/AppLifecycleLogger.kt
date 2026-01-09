package com.qapp.app.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object AppLifecycleLogger : Application.ActivityLifecycleCallbacks {

    private val startedCount = AtomicInteger(0)
    private val inForeground = AtomicBoolean(false)
    private var registered = false

    fun register(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        val count = startedCount.incrementAndGet()
        if (count == 1 && !inForeground.getAndSet(true)) {
            Log.i(LogTags.APP, "APP_FOREGROUND")
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val count = startedCount.decrementAndGet()
        if (count <= 0) {
            startedCount.set(0)
            if (!activity.isChangingConfigurations && inForeground.getAndSet(false)) {
                Log.i(LogTags.APP, "APP_BACKGROUND")
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
