package com.qapp.app.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import com.qapp.app.core.CurrentLocation
import com.qapp.app.core.LocationStateStore
import com.qapp.app.core.SupabaseClientProvider
import com.qapp.app.data.repository.NearbyDriversRepository
import com.qapp.app.nearby.DriverLocation
import com.qapp.app.nearby.NearbyDriversRegistry
import com.qapp.app.nearby.distanceKm
import io.github.jan.supabase.SupabaseClient
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

    private val _userLocation = MutableStateFlow<CurrentLocation?>(null)
    val userLocation: StateFlow<CurrentLocation?> = _userLocation.asStateFlow()

    private var realtimeJob: Job? = null
    private var realtimeChannel: RealtimeChannel? = null

    fun start() {
        if (realtimeJob?.isActive == true) return
        realtimeJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            startRealtime()
        }
    }

    fun stop() {
        realtimeJob?.cancel()
        realtimeJob = null

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
        val current = LocationStateStore.get()
        _userLocation.value = current
        if (current == null) {
            updateDrivers(emptyList())
            return
        }

        val allOnlineDrivers = repository.fetchOnlineDrivers()
        val nearbyDrivers = allOnlineDrivers.filter { driver ->
            calculateDistanceKm(current.lat, current.lng, driver.lat, driver.lng) <= 10.0
        }
        updateDrivers(nearbyDrivers)
    }

    private suspend fun updateDrivers(list: List<DriverLocation>) {
        withContext(Dispatchers.Main) {
            _drivers.value = list
            NearbyDriversRegistry.update(list)
        }
        Log.d(TAG, "Drivers nearby updated count=${list.size}")
    }

    private fun calculateDistanceKm(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        return distanceKm(lat1, lng1, lat2, lng2)
    }

    override fun onCleared() {
        stop()
    }

    companion object {
        private const val TAG = "DRIVERS_NEARBY"
    }
}
