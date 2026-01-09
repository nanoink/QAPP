package com.qapp.app.nearby

import androidx.compose.runtime.mutableStateListOf

object NearbyDriversRegistry {
    private val drivers = mutableStateListOf<DriverLocation>()

    fun update(list: List<DriverLocation>) {
        drivers.clear()
        drivers.addAll(list)
    }

    fun get(): List<DriverLocation> = drivers
}
