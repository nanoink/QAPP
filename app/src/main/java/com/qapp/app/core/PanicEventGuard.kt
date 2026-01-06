package com.qapp.app.core

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qapp.app.core.PanicEventGuard.FallbackAlert
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val Context.panicEventGuardStore by preferencesDataStore("qapp_panic_guard")

class PanicEventGuard(
    context: Context,
    private val isRealtimeHealthy: () -> Boolean,
    private val onFallbackAlert: suspend (FallbackAlert) -> Unit
) {

    data class FallbackAlert(
        val eventId: String,
        val driverId: String,
        val driverName: String?,
        val lat: Double,
        val lng: Double,
        val locationWkt: String,
        val createdAtMs: Long?,
        val vehicleBrand: String?,
        val vehicleMake: String?,
        val vehicleModel: String?,
        val vehicleColor: String?,
        val vehiclePlate: String?
    )

    private val appContext = context.applicationContext
    private val client = SupabaseClientProvider.client
    private val logTag = "QAPP_PANIC_GUARD"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var lastSeenAtMs: Long = 0L
    private val processedEvents = LinkedHashMap<String, Long>()

    fun start() {
        if (pollJob?.isActive == true) return
        PanicEventGuardStore.init(appContext)
        lastSeenAtMs = runBlocking { PanicEventGuardStore.getLastSeen(appContext) }
        if (lastSeenAtMs == 0L) {
            lastSeenAtMs = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        }
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!shouldPoll()) {
                    continue
                }
                try {
                    pollOnce()
                } catch (e: Exception) {
                    Log.w(logTag, "Fallback poll failed: ${e.message}", e)
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun shouldPoll(): Boolean {
        if (!SecurityStateStore.isOnline()) return false
        if (PanicStateManager.isPanicActive()) return false
        val realtimeState = RealtimeStateStore.state.value.state
        val realtimeOk = isRealtimeHealthy() && realtimeState == RealtimeState.CONNECTED
        return !realtimeOk
    }

    private suspend fun pollOnce() {
        val session = client.auth.currentSessionOrNull()
        if (session == null) return
        val currentUserId = session.user?.id ?: return
        val sinceIso = formatUtcTimestamp(lastSeenAtMs)
        val result = client.postgrest["panic_events"].select {
            filter {
                gt("started_at", sinceIso)
                eq("is_active", true)
                neq("driver_id", currentUserId)
            }
            limit(POLL_LIMIT)
        }
        val records = result.decodeList<PanicEventFallback>()
        if (records.isEmpty()) return
        val sorted = records.sortedBy { parseTimestamp(it.startedAt ?: it.createdAt) ?: 0L }
        var maxSeen = lastSeenAtMs
        for (record in sorted) {
            val createdAtMs = parseTimestamp(record.startedAt ?: record.createdAt)
            if (createdAtMs != null && createdAtMs > maxSeen) {
                maxSeen = createdAtMs
            }
            if (!shouldProcessEvent(record.id)) {
                Log.i(logTag, "PANIC_EVENT_DEDUPLICATED id=${record.id}")
                continue
            }
            val location = parseLocation(record.location)
            if (location == null) {
                Log.w(logTag, "Fallback event missing location id=${record.id}")
                markProcessed(record.id)
                continue
            }
            val ownLocation = LocationStateStore.get()
            if (ownLocation == null) {
                Log.i(logTag, "Fallback skipped: no local location id=${record.id}")
                markProcessed(record.id)
                continue
            }
            val distanceKm = PanicMath.distanceKm(
                ownLocation.lat,
                ownLocation.lng,
                location.first,
                location.second
            )
            if (distanceKm > ALERT_RADIUS_KM) {
                markProcessed(record.id)
                continue
            }
            val locationWkt = record.location ?: "POINT(${location.second} ${location.first})"
            val alert = FallbackAlert(
                eventId = record.id,
                driverId = record.driverId,
                driverName = record.driverName,
                lat = location.first,
                lng = location.second,
                locationWkt = locationWkt,
                createdAtMs = createdAtMs,
                vehicleBrand = record.vehicleBrand,
                vehicleMake = record.vehicleMake,
                vehicleModel = record.vehicleModel,
                vehicleColor = record.vehicleColor,
                vehiclePlate = record.vehiclePlate
            )
            Log.i(logTag, "PANIC_EVENT_RECEIVED_FALLBACK id=${record.id}")
            Log.w(logTag, "PANIC_EVENT_MISSED_REALTIME id=${record.id}")
            onFallbackAlert(alert)
            markProcessed(record.id)
        }
        if (maxSeen > lastSeenAtMs) {
            lastSeenAtMs = maxSeen
            PanicEventGuardStore.updateLastSeen(appContext, lastSeenAtMs)
        }
    }

    private fun shouldProcessEvent(eventId: String): Boolean {
        if (processedEvents.containsKey(eventId)) return false
        val activeEvent = IncomingPanicAlertStore.state.value.eventId
        if (!activeEvent.isNullOrBlank() && activeEvent == eventId) return false
        val pendingEvent = PanicAlertPendingStore.current()?.eventId
        if (!pendingEvent.isNullOrBlank() && pendingEvent == eventId) return false
        return true
    }

    private fun markProcessed(eventId: String) {
        processedEvents[eventId] = System.currentTimeMillis()
        pruneProcessed()
    }

    private fun pruneProcessed() {
        val now = System.currentTimeMillis()
        val iterator = processedEvents.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > PROCESSED_TTL_MS) {
                iterator.remove()
            }
        }
    }

    private fun parseLocation(value: String?): Pair<Double, Double>? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.substringAfter("POINT").substringAfter("(").substringBefore(")")
        val parts = trimmed.replace(",", " ").trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val lng = parts[0].toDoubleOrNull() ?: return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        return lat to lng
    }

    private fun parseTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US)
                formatter.timeZone = TimeZone.getTimeZone("UTC")
                val date = formatter.parse(value)
                if (date != null) {
                    return date.time
                }
            } catch (_: Exception) {
                // ignore and try next format
            }
        }
        return null
    }

    private fun formatUtcTimestamp(epochMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMillis))
    }

    companion object {
        private const val POLL_INTERVAL_MS = 8_000L
        private const val POLL_LIMIT = 5L
        private const val ALERT_RADIUS_KM = 10.0
        private const val PROCESSED_TTL_MS = 15 * 60 * 1000L
        private const val INITIAL_LOOKBACK_MS = 2 * 60 * 1000L
    }
}

@Serializable
private data class PanicEventFallback(
    val id: String,
    @SerialName("driver_id")
    val driverId: String,
    @SerialName("driver_name")
    val driverName: String? = null,
    @SerialName("is_active")
    val isActive: Boolean? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("started_at")
    val startedAt: String? = null,
    val location: String? = null,
    @SerialName("vehicle_brand")
    val vehicleBrand: String? = null,
    @SerialName("vehicle_make")
    val vehicleMake: String? = null,
    @SerialName("vehicle_model")
    val vehicleModel: String? = null,
    @SerialName("vehicle_color")
    val vehicleColor: String? = null,
    @SerialName("vehicle_plate")
    val vehiclePlate: String? = null
)

private object PanicEventGuardStore {
    private val keyLastSeen = longPreferencesKey("last_seen_ms")

    fun init(context: Context) {
        // no-op, reserved for future migrations
        if (context == context.applicationContext) return
    }

    suspend fun getLastSeen(context: Context): Long {
        val prefs = context.panicEventGuardStore.data.first()
        return prefs[keyLastSeen] ?: 0L
    }

    suspend fun updateLastSeen(context: Context, value: Long) {
        context.panicEventGuardStore.edit { prefs ->
            prefs[keyLastSeen] = value
        }
    }
}
