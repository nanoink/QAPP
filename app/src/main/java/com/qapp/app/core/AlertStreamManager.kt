package com.qapp.app.core

import android.location.Location
import android.util.Log
import com.qapp.app.core.RealtimeManager
import com.qapp.app.core.RealtimeStateStore
import com.qapp.app.data.repository.AlertRepository
import com.qapp.app.data.repository.DriverLocation
import com.qapp.app.data.repository.DriverProfile
import com.qapp.app.data.repository.PanicEventRecord
import com.qapp.app.data.repository.VehicleInfo
import com.qapp.app.ui.AlertSystemStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AlertStreamManager(
    private val repository: AlertRepository,
    private val locationStore: LocationStateStore,
    private val scope: CoroutineScope,
    private val radiusMeters: Double = 10_000.0
) {

    private var alertsJob: Job? = null
    private var activeAlertId: String? = null
    private var activeDriverId: String? = null
    private val seenAlerts = mutableSetOf<String>()
    private var systemStatusCallback: ((AlertSystemStatus) -> Unit)? = null

    fun start(
        onAlertStarted: (PanicEventRecord, DriverProfile?, VehicleInfo?) -> Unit,
        onAlertLocation: (DriverLocation) -> Unit,
        onAlertEnded: () -> Unit,
        onSystemStatus: (AlertSystemStatus) -> Unit
    ) {
        if (alertsJob?.isActive == true) {
            return
        }
        systemStatusCallback = onSystemStatus
        RealtimeManager.connect()
        RealtimeStateStore.updateState(RealtimeState.CONNECTING)
        onSystemStatus(AlertSystemStatus.OK)
        alertsJob = scope.launch {
            RealtimeStateStore.updateState(RealtimeState.CONNECTED)
            RealtimeManager.alerts.collect { message ->
                when (message) {
                    is PanicAlertMessage.Panic -> {
                        val payload = message.payload
                        if (seenAlerts.contains(payload.panicEventId)) return@collect
                        Log.i("QAPP_PANIC", "Panic alert received id=${payload.panicEventId}")
                        val ownLocation = locationStore.get()
                        if (ownLocation == null) {
                            Log.i("QAPP_PANIC", "Panic ignored (missing location)")
                            return@collect
                        }
                        val distanceMeters = distanceBetweenMeters(
                            ownLocation.lat,
                            ownLocation.lng,
                            payload.latitude,
                            payload.longitude
                        )
                        if (distanceMeters > radiusMeters) {
                            Log.i("QAPP_PANIC", "Panic ignored (out of range)")
                            return@collect
                        }
                        activeAlertId = payload.panicEventId
                        activeDriverId = payload.driverId
                        seenAlerts.add(payload.panicEventId)
                        val event = PanicEventRecord(
                            id = payload.panicEventId,
                            driverId = payload.driverId,
                            isActive = true,
                            startedAt = null,
                            endedAt = null,
                            lat = payload.latitude,
                            lng = payload.longitude
                        )
                        val driver = repository.getDriverProfile(payload.driverId)
                        val vehicle = repository.getVehicleByDriverId(payload.driverId)
                        onAlertStarted(event, driver, vehicle)
                        onAlertLocation(
                            DriverLocation(
                                driverId = payload.driverId,
                                latitude = payload.latitude,
                                longitude = payload.longitude,
                                updatedAt = null
                            )
                        )
                    }
                    is PanicAlertMessage.Resolved -> {
                        val payload = message.payload
                        if (activeAlertId == payload.panicEventId) {
                            clearAlert(onAlertEnded)
                        }
                    }
                    is PanicAlertMessage.Location -> {
                        // Stream manager legacy: ignore location updates.
                    }
                }
            }
        }
    }

    fun stop() {
        alertsJob?.cancel()
        alertsJob = null
        activeAlertId = null
        activeDriverId = null
        seenAlerts.clear()
        RealtimeStateStore.updateState(RealtimeState.DISCONNECTED)
        systemStatusCallback?.invoke(AlertSystemStatus.DISCONNECTED)
    }

    private fun clearAlert(onAlertEnded: () -> Unit) {
        activeAlertId = null
        activeDriverId = null
        onAlertEnded()
    }

    private fun distanceBetweenMeters(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(startLat, startLng, endLat, endLng, results)
        return results[0]
    }
}
