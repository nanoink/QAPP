package com.qapp.app

import android.app.Application
import com.qapp.app.alerts.PanicAlertManager
import com.qapp.app.core.AppLifecycleLogger
import com.qapp.app.core.PanicAlertPendingStore
import com.qapp.app.services.PanicRealtimeService

class QAppApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLifecycleLogger.register(this)
        PanicAlertPendingStore.init(this)
        PanicAlertManager.init(this)
        PanicRealtimeService.startIfAllowed(this)
    }
}
