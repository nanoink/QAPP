package com.qapp.app.nearby

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DriversNearbyRepository {
    private val _nearbyDrivers = MutableStateFlow<List<DriverLocation>>(emptyList())
    val nearbyDrivers: StateFlow<List<DriverLocation>> = _nearbyDrivers.asStateFlow()

    fun updateDrivers(list: List<DriverLocation>) {
        _nearbyDrivers.value = list
    }
}
