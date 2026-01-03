package com.qapp.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VehicleSelectionStore {
    private val _selectedVehicleId = MutableStateFlow<String?>(null)
    val selectedVehicleId: StateFlow<String?> = _selectedVehicleId.asStateFlow()

    fun set(vehicleId: String?) {
        _selectedVehicleId.value = vehicleId
    }

    fun get(): String? = _selectedVehicleId.value
}
