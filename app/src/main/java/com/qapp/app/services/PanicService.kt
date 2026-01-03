package com.qapp.app.services

import android.location.Location
import android.util.Log
import com.qapp.app.core.DriverIdentityStore
import com.qapp.app.core.LocationStateStore
import com.qapp.app.core.RealtimeManager
import com.qapp.app.core.SupabaseClientProvider
import com.qapp.app.core.ActiveVehicleStore
import com.qapp.app.data.repository.VehicleRecord
import com.qapp.app.data.repository.VehicleRepository
import com.qapp.app.domain.PanicCreateResult
import com.qapp.app.domain.PanicManager
import com.qapp.app.domain.PanicResolveOutcome
import com.qapp.app.domain.PanicUserMissingException
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PanicService(
    private val panicManager: PanicManager = PanicManager(),
    private val realtimeManager: RealtimeManager = RealtimeManager,
    private val vehicleRepository: VehicleRepository = VehicleRepository()
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null
    private var pendingVehicle: VehicleRecord? = null

    suspend fun triggerPanic(source: String, lat: Double?, lng: Double?): Boolean {
        val location = resolveLocation(lat, lng)
        val vehicle = resolveActiveVehicle()
        if (vehicle == null) {
            Log.w("QAPP_PANIC", "PANIC_BLOCKED_NO_ACTIVE_VEHICLE")
            return false
        }
        val result = panicManager.createEventIfNeeded(source, location, vehicle)
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            if (error is PanicUserMissingException) {
                Log.w("QAPP_PANIC", "PANIC_EVENT_DELAYED_USER_NOT_READY")
            } else {
                Log.e("QAPP_PANIC", "PANIC_INSERT_ERROR: ${error?.message}", error)
            }
            scheduleRetry(source, vehicle)
            return true
        }
        val outcome = result.getOrNull()
        if (outcome != null) {
            if (outcome.created) {
                sendRealtimeAlert(outcome, location, vehicle)
            }
            cancelRetry()
        }
        return true
    }

    suspend fun resolvePanic(): PanicResolveOutcome {
        val panicId = panicManager.getActiveEventId()
        val outcome = panicManager.resolveActiveEvent()
        if (outcome == PanicResolveOutcome.FAILED) {
            Log.w("QAPP_PANIC", "PANIC_FINALIZE_FAILED")
            return outcome
        }
        if (outcome == PanicResolveOutcome.MISSING_ID) {
            Log.w("QAPP_PANIC", "PANIC_RESOLVE_SKIPPED_MISSING_EVENT_ID")
            return outcome
        }
        Log.i("QAPP_PANIC", "Panic ended id=$panicId")
        cancelRetry()
        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        if (panicId.isNullOrBlank() || userId.isNullOrBlank()) {
            return outcome
        }
        if (outcome == PanicResolveOutcome.RESOLVED) {
            realtimeManager.sendPanicResolved(
                panicId = panicId,
                driverId = userId
            )
        }
        return outcome
    }

    private fun resolveLocation(lat: Double?, lng: Double?): Location? {
        if (lat != null && lng != null) {
            return Location("panic").apply {
                latitude = lat
                longitude = lng
                time = System.currentTimeMillis()
            }
        }
        val last = LocationStateStore.get() ?: return null
        return Location("last_known").apply {
            latitude = last.lat
            longitude = last.lng
            time = last.timestamp
        }
    }

    private fun scheduleRetry(source: String, vehicle: VehicleRecord) {
        if (retryJob?.isActive == true) return
        Log.w("QAPP_PANIC", "PANIC_INSERT_RETRY_SCHEDULED")
        pendingVehicle = vehicle
        retryJob = scope.launch {
            while (panicManager.isPanicActive() && panicManager.isPending()) {
                delay(RETRY_INTERVAL_MS)
                val location = resolveLocation(null, null)
                if (location == null) {
                    continue
                }
                val activeVehicle = pendingVehicle ?: resolveActiveVehicle()
                if (activeVehicle == null) {
                    Log.w("QAPP_PANIC", "PANIC_BLOCKED_NO_ACTIVE_VEHICLE")
                    cancelRetry()
                    break
                }
                val result = panicManager.createEventIfNeeded(source, location, activeVehicle)
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    Log.e("QAPP_PANIC", "PANIC_INSERT_ERROR: ${error?.message}", error)
                    continue
                }
                val outcome = result.getOrNull()
                if (outcome != null) {
                    if (outcome.created) {
                        sendRealtimeAlert(outcome, location, activeVehicle)
                    }
                    cancelRetry()
                    break
                }
            }
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
        pendingVehicle = null
    }

    private suspend fun sendRealtimeAlert(
        outcome: PanicCreateResult,
        location: Location?,
        vehicle: VehicleRecord
    ) {
        val panicId = outcome.eventId.toString()
        val lat = location?.latitude
        val lng = location?.longitude
        if (lat == null || lng == null) {
            Log.w("QAPP_PANIC", "Missing location; skip realtime alert")
            return
        }
        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        if (userId.isNullOrBlank()) {
            Log.w("QAPP_PANIC", "Missing user; skip realtime alert")
            return
        }
        val driverName = DriverIdentityStore.getName()
        if (driverName.isNullOrBlank()) {
            Log.w("QAPP_PANIC", "Missing driver name; sending alert without name")
        }
        val createdAt = formatUtcTimestamp(System.currentTimeMillis())
        realtimeManager.sendPanicAlert(
            panicId = panicId,
            driverId = userId,
            driverName = driverName,
            lat = lat,
            lng = lng,
            vehicle = vehicle,
            createdAt = createdAt
        )
        Log.i("QAPP_PANIC", "PANIC_EMITTED_WITH_VEHICLE id=${vehicle.id}")
        Log.i("QAPP_PANIC", "Panic alert emitted id=$panicId")
    }

    private suspend fun resolveActiveVehicle(): VehicleRecord? {
        val cached = ActiveVehicleStore.get()
        if (cached == null) {
            Log.w("QAPP_PANIC", "NO_ACTIVE_VEHICLE")
            return null
        }
        if (cached.make.isBlank() ||
            cached.model.isBlank() ||
            cached.color.isBlank() ||
            cached.plate.isBlank()
        ) {
            Log.w("QAPP_PANIC", "NO_ACTIVE_VEHICLE")
            return null
        }
        return cached
    }

    private fun formatUtcTimestamp(epochMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMillis))
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 10_000L
    }
}
