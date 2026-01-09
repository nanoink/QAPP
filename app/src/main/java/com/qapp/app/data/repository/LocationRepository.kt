package com.qapp.app.data.repository

import android.content.Context
import android.util.Log
import com.qapp.app.core.LogTags
import com.qapp.app.core.SecurityStateStore
import com.qapp.app.core.SyncStateStore
import com.qapp.app.core.connectivity.ConnectivityMonitor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocationRepository(
    context: Context,
    private val driverRepository: DriverLocationUpdater = DriverRepository(),
    private val connectivityChecker: () -> Boolean = { ConnectivityMonitor.isOnline() }
) {

    private val bufferLock = Mutex()
    private val buffer = ArrayDeque<QueuedLocation>()

    init {
        SecurityStateStore.init(context)
    }

    suspend fun clearBuffer() {
        bufferLock.withLock {
            buffer.clear()
        }
        Log.i(LogTags.LOCATION, "Location buffer cleared")
    }

    suspend fun sendLocation(lat: Double, lng: Double, accuracy: Float) {
        if (!SecurityStateStore.isOnline()) {
            return
        }
        val entry = QueuedLocation(
            lat = lat,
            lng = lng,
            accuracy = accuracy,
            timestamp = System.currentTimeMillis()
        )
        if (!connectivityChecker()) {
            enqueue(entry, reason = "offline")
            return
        }
        if (!driverRepository.hasValidSession()) {
            enqueue(entry, reason = "session_missing")
            return
        }
        enqueue(entry, reason = "live")
        flushBufferedIfPossible()
    }

    suspend fun flushNow(): Boolean {
        flushBufferedIfPossible()
        return bufferLock.withLock { buffer.isEmpty() }
    }

    suspend fun onNetworkChanged(isOnline: Boolean) {
        if (!isOnline || !SecurityStateStore.isOnline()) return
        flushBufferedIfPossible()
    }

    private suspend fun enqueue(entry: QueuedLocation, reason: String) {
        bufferLock.withLock {
            if (buffer.size >= BUFFER_LIMIT) {
                buffer.removeFirst()
                Log.w(LogTags.LOCATION, "Location buffer full; dropping oldest")
            }
            buffer.addLast(entry)
        }
        if (reason == "session_missing") {
            Log.w(LogTags.LOCATION, "Session missing; location queued")
        }
    }

    private suspend fun flushBufferedIfPossible() {
        if (!connectivityChecker()) return
        if (!driverRepository.hasValidSession()) return
        while (true) {
            val next = bufferLock.withLock { buffer.firstOrNull() } ?: break
            val updated = driverRepository.updateLocation(next.lat, next.lng)
            if (updated) {
                SyncStateStore.updateSyncTime(System.currentTimeMillis())
                bufferLock.withLock { buffer.removeFirst() }
                Log.d(LogTags.LOCATION, "Location update sent")
            } else {
                Log.w(LogTags.LOCATION, "Location update failed")
                break
            }
        }
    }

    private data class QueuedLocation(
        val lat: Double,
        val lng: Double,
        val accuracy: Float,
        val timestamp: Long
    )

    private companion object {
        private const val BUFFER_LIMIT = 50
    }
}
