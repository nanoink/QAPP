package com.qapp.app.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.qapp.app.core.CurrentLocation
import com.qapp.app.core.LocationStateStore
import com.qapp.app.core.SecurityState
import com.qapp.app.core.SecurityStateStore
import com.qapp.app.core.SupabaseClientProvider
import com.qapp.app.data.repository.NearbyDriversRepository
import com.qapp.app.nearby.DriverLocation
import com.qapp.app.nearby.NearbyDriversRegistry
import com.qapp.app.nearby.distanceKm
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DriversNearbyViewModel(
    private val repository: NearbyDriversRepository = NearbyDriversRepository(),
    private val client: SupabaseClient = SupabaseClientProvider.client
) : ViewModel() {

    private val _drivers = MutableStateFlow<List<DriverLocation>>(emptyList())
    val drivers: StateFlow<List<DriverLocation>> = _drivers.asStateFlow()

    private val _selfLocation = MutableStateFlow<LatLng?>(null)
    val selfLocation: StateFlow<LatLng?> = _selfLocation.asStateFlow()

    private val _selfOnline = MutableStateFlow(false)
    val selfOnline: StateFlow<Boolean> = _selfOnline.asStateFlow()

    private var currentLocation: CurrentLocation? = null

    @Volatile
    private var allOnlineDrivers: List<DriverLocation> = emptyList()

    private var realtimeJob: Job? = null
    private var realtimeChannel: RealtimeChannel? = null
    private var locationJob: Job? = null
    private var onlineJob: Job? = null

    fun start() {
        if (locationJob?.isActive != true) {
            val initialLocation = sanitizeLocation(LocationStateStore.get(maxAgeMs = LOCATION_TTL_MS))
            updateSelfLocation(initialLocation)
            locationJob = viewModelScope.launch {
                LocationStateStore.state.collect { location ->
                    val sanitized = sanitizeLocation(location)
                    updateSelfLocation(sanitized)
                    filterDrivers(sanitized)
                }
            }
        }
        if (onlineJob?.isActive != true) {
            updateSelfOnline(SecurityStateStore.state.value)
            onlineJob = viewModelScope.launch {
                SecurityStateStore.state.collect { securityState ->
                    updateSelfOnline(securityState)
                }
            }
        }
        if (realtimeJob?.isActive != true) {
            realtimeJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                startRealtime()
            }
        }
    }

    fun stop() {
        realtimeJob?.cancel()
        realtimeJob = null
        locationJob?.cancel()
        locationJob = null
        onlineJob?.cancel()
        onlineJob = null

        val channel = realtimeChannel
        realtimeChannel = null

        if (channel != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { channel.unsubscribe() }
                client.realtime.removeChannel(channel)
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            _drivers.value = emptyList()
            NearbyDriversRegistry.update(emptyList())
        }
    }

    private suspend fun startRealtime() {
        try {
            refreshDriversSafely()

            val channel = client.realtime.channel("drivers_online")
            realtimeChannel = channel
            val changes = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                table = "drivers"
            }
            channel.subscribe()
            Log.d(TAG, "Realtime connected")

            changes.collect {
                refreshDriversSafely()
            }
        } catch (e: CancellationException) {
            // Ignore cancellation from lifecycle stop().
        } catch (e: Exception) {
            Log.w(TAG, "Realtime subscription failed", e)
        }
    }

    private suspend fun refreshDriversSafely() {
        try {
            refreshDrivers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh drivers", e)
        }
    }

    private suspend fun refreshDrivers() {
        val freshDrivers = repository.fetchOnlineDrivers()
        val selfId = client.auth.currentUserOrNull()?.id
        allOnlineDrivers = if (selfId.isNullOrBlank()) {
            freshDrivers
        } else {
            freshDrivers.filterNot { it.id == selfId }
        }
        filterDrivers(currentLocation)
    }

    private suspend fun filterDrivers(location: CurrentLocation?) {
        if (location == null) {
            updateDrivers(emptyList())
            return
        }
        val driversSnapshot = allOnlineDrivers
        val nearbyDrivers = withContext(Dispatchers.Default) {
            driversSnapshot.filter { driver ->
                calculateDistanceKm(location.lat, location.lng, driver.lat, driver.lng) <= 10.0
            }
        }
        updateDrivers(nearbyDrivers)
    }

    private suspend fun updateDrivers(list: List<DriverLocation>) {
        withContext(Dispatchers.Main) {
            _drivers.value = list
            NearbyDriversRegistry.update(list)
        }
        Log.d(TAG, "nearbyDrivers=${list.size}")
    }

    private fun calculateDistanceKm(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        return distanceKm(lat1, lng1, lat2, lng2)
    }

    private fun sanitizeLocation(location: CurrentLocation?): CurrentLocation? {
        if (location == null) return null
        val now = System.currentTimeMillis()
        if (now - location.timestamp > LOCATION_TTL_MS) {
            LocationStateStore.clear()
            return null
        }
        return location
    }

    private fun updateSelfLocation(location: CurrentLocation?) {
        currentLocation = location
        val latLng = location?.let { LatLng(it.lat, it.lng) }
        _selfLocation.value = latLng
        Log.d(TAG, "selfOnline=${_selfOnline.value} selfLocation=$latLng")
    }

    private fun updateSelfOnline(securityState: SecurityState) {
        val hasUser = client.auth.currentUserOrNull()?.id?.isNotBlank() == true
        _selfOnline.value = hasUser && securityState != SecurityState.OFFLINE
        Log.d(TAG, "selfOnline=${_selfOnline.value} selfLocation=${_selfLocation.value}")
    }

    override fun onCleared() {
        stop()
    }

    companion object {
        private const val TAG = "DRIVERS_NEARBY"
        private const val LOCATION_TTL_MS = 60_000L
    }
}
