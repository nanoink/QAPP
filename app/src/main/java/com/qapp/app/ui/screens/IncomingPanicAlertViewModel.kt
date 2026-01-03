package com.qapp.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.qapp.app.core.IncomingPanicAlertState
import com.qapp.app.core.IncomingPanicAlertStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

data class IncomingPanicAlertUiState(
    val isVisible: Boolean = false,
    val driverName: String = "",
    val driverPhone: String? = null,
    val vehicleMake: String = "",
    val vehicleModel: String = "",
    val vehiclePlate: String = "",
    val vehicleColor: String = "",
    val distanceLabel: String = "",
    val elapsedLabel: String = "",
    val lastUpdateLabel: String = "",
    val isActive: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    val heading: Double? = null,
    val pathPoints: List<LatLng> = emptyList(),
    val muted: Boolean = false
)

class IncomingPanicAlertViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(IncomingPanicAlertUiState())
    val uiState: StateFlow<IncomingPanicAlertUiState> = _uiState.asStateFlow()

    private var tickerJob: Job? = null
    private var lastRawState: IncomingPanicAlertState? = null
    private val pathPoints = ArrayDeque<LatLng>()
    private var lastPoint: LatLng? = null
    private var lastEventId: String? = null

    init {
        viewModelScope.launch {
            IncomingPanicAlertStore.state.collect { raw ->
                lastRawState = raw
                updateUi(raw, System.currentTimeMillis())
                handleTicker(raw)
            }
        }
    }

    fun dismissAlert() {
        IncomingPanicAlertStore.dismiss()
    }

    fun clearAlert() {
        IncomingPanicAlertStore.clear()
    }

    private fun handleTicker(raw: IncomingPanicAlertState) {
        if (!raw.isVisible || raw.startedAtMs == null || !raw.isActive) {
            tickerJob?.cancel()
            tickerJob = null
            return
        }
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val current = lastRawState ?: return@launch
                updateUi(current, System.currentTimeMillis())
            }
        }
    }

    private fun updateUi(raw: IncomingPanicAlertState, now: Long) {
        if (raw.eventId != lastEventId) {
            lastEventId = raw.eventId
            pathPoints.clear()
            lastPoint = null
        }
        val elapsedSeconds = if (raw.startedAtMs != null) {
            max(0L, (now - raw.startedAtMs) / 1000L)
        } else {
            0L
        }
        val updateSeconds = if (raw.lastUpdateAtMs != null) {
            max(0L, (now - raw.lastUpdateAtMs) / 1000L)
        } else {
            null
        }
        val point = if (raw.lat != null && raw.lng != null) {
            LatLng(raw.lat, raw.lng)
        } else {
            null
        }
        if (raw.isActive && point != null) {
            if (lastPoint == null || lastPoint != point) {
                pathPoints.addLast(point)
                lastPoint = point
                while (pathPoints.size > MAX_PATH_POINTS) {
                    pathPoints.removeFirst()
                }
            }
        } else if (!raw.isActive) {
            pathPoints.clear()
            lastPoint = null
        }
        _uiState.value = IncomingPanicAlertUiState(
            isVisible = raw.isVisible,
            driverName = raw.driverName ?: "",
            driverPhone = raw.driverPhone,
            vehicleMake = raw.vehicleMake ?: "",
            vehicleModel = raw.vehicleModel ?: "",
            vehiclePlate = raw.vehiclePlate ?: "",
            vehicleColor = raw.vehicleColor ?: "",
            distanceLabel = formatDistance(raw.distanceKm),
            elapsedLabel = formatElapsed(elapsedSeconds),
            lastUpdateLabel = formatUpdateLabel(updateSeconds),
            isActive = raw.isActive,
            lat = raw.lat,
            lng = raw.lng,
            heading = raw.heading,
            pathPoints = pathPoints.toList(),
            muted = raw.muted
        )
    }

    private fun formatDistance(distanceKm: Double?): String {
        if (distanceKm == null) return "--"
        return String.format(Locale.US, "%.1f km", distanceKm)
    }

    private fun formatElapsed(seconds: Long): String {
        val total = max(0L, seconds)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }

    private fun formatUpdateLabel(seconds: Long?): String {
        if (seconds == null) return "--"
        return "Atualizado ha ${seconds}s"
    }

    private companion object {
        private const val MAX_PATH_POINTS = 180
    }
}
