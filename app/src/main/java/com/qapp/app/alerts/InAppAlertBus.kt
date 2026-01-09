package com.qapp.app.alerts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class PanicAlert(
    val eventId: String,
    val emitterId: String,
    val lat: Double,
    val lng: Double
)

object InAppAlertBus {
    private val _alerts = MutableSharedFlow<PanicAlert>()
    val alerts = _alerts.asSharedFlow()

    suspend fun emit(alert: PanicAlert) {
        _alerts.emit(alert)
    }
}

