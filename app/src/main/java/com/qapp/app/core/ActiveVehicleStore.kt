package com.qapp.app.core

import com.qapp.app.data.repository.VehicleRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ActiveVehicleStore {
    private val _state = MutableStateFlow<VehicleRecord?>(null)
    val state: StateFlow<VehicleRecord?> = _state.asStateFlow()

    fun set(vehicle: VehicleRecord?) {
        _state.value = vehicle
    }

    fun get(): VehicleRecord? = _state.value

    fun clear() {
        _state.value = null
    }
}
