package com.qapp.app.core

import com.qapp.app.data.repository.DriverProfile
import com.qapp.app.data.repository.VehicleInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IncomingPanicAlertState(
    val isVisible: Boolean = false,
    val eventId: String? = null,
    val driverId: String? = null,
    val driverName: String? = null,
    val driverPhone: String? = null,
    val vehicleMake: String? = null,
    val vehicleModel: String? = null,
    val vehiclePlate: String? = null,
    val vehicleColor: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val distanceKm: Double? = null,
    val heading: Double? = null,
    val startedAtMs: Long? = null,
    val lastUpdateAtMs: Long? = null,
    val isActive: Boolean = false,
    val muted: Boolean = false
)

object IncomingPanicAlertStore {
    private val _state = MutableStateFlow(IncomingPanicAlertState())
    val state: StateFlow<IncomingPanicAlertState> = _state.asStateFlow()

    fun showAlert(
        eventId: String,
        driverId: String,
        driverName: String?,
        lat: Double,
        lng: Double,
        distanceKm: Double?,
        startedAtMs: Long,
        muted: Boolean,
        vehicle: VehicleInfo? = null
    ) {
        _state.value = IncomingPanicAlertState(
            isVisible = true,
            eventId = eventId,
            driverId = driverId,
            driverName = driverName,
            vehicleMake = vehicle?.make,
            vehicleModel = vehicle?.model,
            vehiclePlate = vehicle?.plate,
            vehicleColor = vehicle?.color,
            lat = lat,
            lng = lng,
            distanceKm = distanceKm,
            startedAtMs = startedAtMs,
            lastUpdateAtMs = startedAtMs,
            isActive = true,
            muted = muted
        )
    }

    fun updateLocation(lat: Double, lng: Double, distanceKm: Double?, heading: Double?) {
        val current = _state.value
        if (current.eventId.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        _state.value = current.copy(
            lat = lat,
            lng = lng,
            distanceKm = distanceKm,
            heading = heading,
            lastUpdateAtMs = now
        )
    }

    fun updateDriverProfile(profile: DriverProfile?) {
        val current = _state.value
        if (current.eventId.isNullOrBlank()) return
        _state.value = current.copy(
            driverName = profile?.name ?: current.driverName,
            driverPhone = profile?.phone ?: current.driverPhone
        )
    }

    fun updateVehicleInfo(vehicle: VehicleInfo?) {
        val current = _state.value
        if (current.eventId.isNullOrBlank()) return
        _state.value = current.copy(
            vehicleMake = vehicle?.make ?: current.vehicleMake,
            vehicleModel = vehicle?.model ?: current.vehicleModel,
            vehiclePlate = vehicle?.plate ?: current.vehiclePlate,
            vehicleColor = vehicle?.color ?: current.vehicleColor
        )
    }

    fun updateMuted(muted: Boolean) {
        val current = _state.value
        if (current.eventId.isNullOrBlank()) return
        _state.value = current.copy(muted = muted)
    }

    fun dismiss() {
        val current = _state.value
        if (!current.isVisible) return
        _state.value = current.copy(isVisible = false)
    }

    fun markEnded() {
        val current = _state.value
        if (current.eventId.isNullOrBlank()) return
        _state.value = current.copy(isActive = false)
    }

    fun clear() {
        _state.value = IncomingPanicAlertState()
    }
}
