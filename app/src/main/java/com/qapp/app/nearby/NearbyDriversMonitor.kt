package com.qapp.app.nearby

import android.util.Log
import com.qapp.app.core.CurrentLocation
import com.qapp.app.core.LocationStateStore
import com.qapp.app.core.SecurityStateStore
import com.qapp.app.core.SupabaseClientProvider
import com.qapp.app.data.repository.NearbyDriversRepository
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object NearbyDriversMonitor {
    private const val TAG = "DRIVERS_NEARBY_BG"
    private const val LOCATION_TTL_MS = 60_000L

    private val repository = NearbyDriversRepository()
    private val client: SupabaseClient = SupabaseClientProvider.client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var allOnlineDrivers: List<DriverLocation> = emptyList()

    private var currentLocation: CurrentLocation? = null
    private var realtimeJob: Job? = null
    private var channel: RealtimeChannel? = null
    private var locationJob: Job? = null

    @Synchronized
    fun start() {
        if (!SecurityStateStore.isOnline()) {
            Log.d(TAG, "Monitor not started: offline")
            return
        }
        if (locationJob?.isActive != true) {
            val initialLocation = sanitizeLocation(LocationStateStore.get(maxAgeMs = LOCATION_TTL_MS))
            updateSelfLocation(initialLocation)
            locationJob = scope.launch {
                LocationStateStore.state.collect { location ->
                    val sanitized = sanitizeLocation(location)
                    updateSelfLocation(sanitized)
                    filterDrivers(sanitized)
                }
            }
        }
        if (realtimeJob?.isActive != true) {
            realtimeJob = scope.launch { startRealtime() }
        }
    }

    @Synchronized
    fun stop() {
        realtimeJob?.cancel()
        realtimeJob = null
        locationJob?.cancel()
        locationJob = null

        val activeChannel = channel
        channel = null
        if (activeChannel != null) {
            scope.launch {
                runCatching { activeChannel.unsubscribe() }
                client.realtime.removeChannel(activeChannel)
            }
        }

        scope.launch(Dispatchers.Main) {
            DriversNearbyRepository.updateDrivers(emptyList())
            NearbyDriversRegistry.update(emptyList())
        }
        Log.d(TAG, "Monitor stopped")
    }

    private suspend fun startRealtime() {
        try {
            refreshDriversSafely()

            val realtimeChannel = client.realtime.channel("drivers_online_monitor")
            channel = realtimeChannel
            val changes = realtimeChannel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                table = "drivers"
            }
            realtimeChannel.subscribe()
            Log.d(TAG, "Realtime connected")

            changes.collect {
                refreshDriversSafely()
            }
        } catch (e: CancellationException) {
            // Ignore cancellation from stop().
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
                distanceKm(location.lat, location.lng, driver.lat, driver.lng) <= 10.0
            }
        }
        updateDrivers(nearbyDrivers)
    }

    private suspend fun updateDrivers(list: List<DriverLocation>) {
        withContext(Dispatchers.Main) {
            DriversNearbyRepository.updateDrivers(list)
            NearbyDriversRegistry.update(list)
        }
        Log.d(TAG, "nearbyDrivers=${list.size}")
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
    }
}
