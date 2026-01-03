package com.qapp.app.core

import com.qapp.app.data.repository.AlertRepository
import com.qapp.app.data.repository.DriverLocation
import com.qapp.app.data.repository.PanicEventRecord
import com.qapp.app.data.repository.DriverProfile
import com.qapp.app.data.repository.VehicleInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AlertRealtimeListener(
    private val repository: AlertRepository,
    private val scope: CoroutineScope
) {

    private var panicJob: Job? = null
    private var activeDriverId: String? = null
    private var activeAlertId: String? = null

    fun start(
        onAlertStarted: (PanicEventRecord, DriverProfile?, VehicleInfo?) -> Unit,
        onAlertLocation: (DriverLocation) -> Unit,
        onAlertEnded: () -> Unit
    ) {
        if (panicJob != null) return
        panicJob = scope.launch {
            RealtimeManager.connect()
            RealtimeManager.alerts.collect { message ->
                when (message) {
                    is PanicAlertMessage.Panic -> {
                        val payload = message.payload
                        if (activeAlertId == payload.panicEventId) return@collect
                        activeAlertId = payload.panicEventId
                        activeDriverId = payload.driverId
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
                        if (activeAlertId == message.payload.panicEventId) {
                            activeAlertId = null
                            activeDriverId = null
                            onAlertEnded()
                        }
                    }
                    is PanicAlertMessage.Location -> {
                        // Listener legacy: ignore location updates.
                    }
                }
            }
        }
    }

    fun stop() {
        panicJob?.cancel()
        panicJob = null
        activeDriverId = null
        activeAlertId = null
    }
}
