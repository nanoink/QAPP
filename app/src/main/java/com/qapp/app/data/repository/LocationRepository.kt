package com.qapp.app.data.repository

import android.content.Context
import android.util.Log
import com.qapp.app.core.LogTags
import com.qapp.app.core.SecurityStateStore
import com.qapp.app.core.SyncStateStore
import com.qapp.app.core.connectivity.ConnectivityMonitor

class LocationRepository(
    context: Context,
    private val driverRepository: DriverLocationUpdater = DriverRepository(),
    private val connectivityChecker: () -> Boolean = { ConnectivityMonitor.isOnline() }
) {

    init {
        SecurityStateStore.init(context)
    }

    fun clearBuffer() {
        Log.i(LogTags.LOCATION, "Location buffer cleared (no-op)")
    }

    suspend fun sendLocation(lat: Double, lng: Double, accuracy: Float) {
        if (!SecurityStateStore.isOnline()) {
            return
        }
        if (!connectivityChecker()) {
            return
        }
        if (!driverRepository.hasValidSession()) {
            Log.w(LogTags.LOCATION, "Session missing, update skipped")
            return
        }
        val updated = driverRepository.updateLocation(lat, lng)
        if (updated) {
            SyncStateStore.updateSyncTime(System.currentTimeMillis())
            Log.d(LogTags.LOCATION, "Location update sent")
        } else {
            Log.w(LogTags.LOCATION, "Location update failed")
        }
    }

    suspend fun flushNow(): Boolean {
        return true
    }

    suspend fun onNetworkChanged(isOnline: Boolean) {
        if (!isOnline || !SecurityStateStore.isOnline()) return
    }
}
