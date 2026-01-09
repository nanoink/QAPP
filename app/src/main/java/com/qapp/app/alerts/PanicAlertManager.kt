package com.qapp.app.alerts

import android.content.Context
import android.util.Log
import com.qapp.app.core.IncomingPanicAlertStore
import com.qapp.app.core.PanicAlertPendingStore
import com.qapp.app.core.SecurityStateStore
import com.qapp.app.data.repository.AuthRepository
import com.qapp.app.data.repository.VehicleInfo
import com.qapp.app.nearby.DriversNearbyRepository

data class PanicEvent(
    val id: String,
    val driverId: String,
    val lat: Double?,
    val lng: Double?,
    val isActive: Boolean,
    val driverName: String? = null,
    val vehicle: VehicleInfo? = null
)

object PanicAlertManager {
    private val authRepository = AuthRepository()
    private var notificationHelper: PanicNotificationHelper? = null

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            notificationHelper = PanicNotificationHelper(context.applicationContext)
            initialized = true
        }
    }

    suspend fun handleIncomingPanic(event: PanicEvent): Boolean {
        val selfId = authRepository.currentDriverId()
        val isOnline = SecurityStateStore.isOnline()
        val inNearby = selfId != null &&
            DriversNearbyRepository.nearbyDrivers.value.any { it.id == event.driverId }
        val shouldReceive = isOnline &&
            event.isActive &&
            !selfId.isNullOrBlank() &&
            event.driverId != selfId &&
            inNearby
        Log.d("PANIC_ALERT", "received=${event.id} shouldReceive=$shouldReceive")
        if (!shouldReceive) return false
        val lat = event.lat ?: return false
        val lng = event.lng ?: return false
        val now = System.currentTimeMillis()
        IncomingPanicAlertStore.showAlert(
            eventId = event.id,
            driverId = event.driverId,
            driverName = event.driverName,
            lat = lat,
            lng = lng,
            distanceKm = null,
            startedAtMs = now,
            muted = false,
            vehicle = event.vehicle
        )
        PanicAlertPendingStore.save(event.id, event.driverId, now)
        notificationHelper?.showPanicAlert(event)
        InAppAlertBus.emit(
            PanicAlert(
                eventId = event.id,
                emitterId = event.driverId,
                lat = lat,
                lng = lng
            )
        )
        return true
    }
}
