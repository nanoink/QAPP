package com.qapp.app.core

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.qapp.app.data.repository.DriverLocation
import com.qapp.app.data.repository.PanicEventRecord
import com.qapp.app.data.repository.DriverProfile
import com.qapp.app.data.repository.VehicleInfo
import com.qapp.app.ui.AlertSystemStatus
import com.qapp.app.MainActivity
import com.qapp.app.core.CoreConfig
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class PanicRealtimeListener(
    context: Context,
    private val locationStore: LocationStateStore,
    private val scope: CoroutineScope,
    private val radiusKm: Double = 10.0
) {

    private val logTag = "QAPP_PANIC"
    private val appContext = context.applicationContext
    private var alertsJob: Job? = null
    private var activeAlertId: String? = null
    private val processedEvents = LinkedHashMap<String, Long>()
    private val resolvedEvents = LinkedHashMap<String, Long>()
    private var lastKnownLocation: CachedLocation? = null
    private var systemStatusCallback: ((AlertSystemStatus) -> Unit)? = null
    private val antiSpamManager = PanicAntiSpamManager()

    fun start(
        onAlertStarted: (PanicEventRecord, DriverProfile?, VehicleInfo?, Boolean) -> Unit,
        onAlertLocation: (DriverLocation) -> Unit,
        onAlertEnded: () -> Unit,
        onSystemStatus: (AlertSystemStatus) -> Unit
    ) {
        if (alertsJob?.isActive == true) {
            return
        }
        systemStatusCallback = onSystemStatus
        RealtimeManager.connect()
        onSystemStatus(AlertSystemStatus.OK)
        alertsJob = scope.launch(Dispatchers.IO) {
            RealtimeManager.alerts.collect { message ->
                when (message) {
                    is PanicAlertMessage.Panic -> {
                        handlePanicEvent(
                            message.payload,
                            onAlertStarted,
                            onAlertLocation
                        )
                    }
                    is PanicAlertMessage.Resolved -> {
                        resolvedEvents[message.payload.panicEventId] = System.currentTimeMillis()
                        if (activeAlertId == message.payload.panicEventId) {
                            activeAlertId = null
                            Log.i(
                                logTag,
                                "PANIC_RECEIVER_EVENT_ENDED id=${message.payload.panicEventId}"
                            )
                            withContext(Dispatchers.Main) {
                                onAlertEnded()
                            }
                        }
                    }
                    is PanicAlertMessage.Location -> {
                        handleLocationUpdate(message.payload, onAlertLocation)
                    }
                }
            }
        }
    }

    fun stop() {
        alertsJob?.cancel()
        alertsJob = null
        activeAlertId = null
        processedEvents.clear()
        resolvedEvents.clear()
        systemStatusCallback?.invoke(AlertSystemStatus.DISCONNECTED)
    }

    private suspend fun handlePanicEvent(
        payload: PanicAlertPayload,
        onAlertStarted: (PanicEventRecord, DriverProfile?, VehicleInfo?, Boolean) -> Unit,
        onAlertLocation: (DriverLocation) -> Unit
    ) {
        val now = System.currentTimeMillis()
        Log.i(logTag, "Realtime panic event received id=${payload.panicEventId}")
        Log.i(logTag, "PANIC_EVENT_RECEIVED_REALTIME id=${payload.panicEventId}")
        Log.i(
            logTag,
            "PANIC_RECEIVED_EVENT id=${payload.panicEventId} emitter_user=${payload.driverId} lat=${payload.latitude} lng=${payload.longitude}"
        )
        if (payload.vehicleId.isNullOrBlank()) {
            Log.w(logTag, "PANIC_EVENT_MISSING_VEHICLE id=${payload.panicEventId}")
        }
        pruneProcessed(now)
        if (!SecurityStateStore.isOnline()) {
            logIgnored("offline", payload)
            processedEvents[payload.panicEventId] = now
            return
        }
        val currentUserId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        if (!currentUserId.isNullOrBlank() && currentUserId == payload.driverId) {
            logIgnored("self", payload)
            processedEvents[payload.panicEventId] = now
            return
        }
        if (!payload.isActive) {
            logIgnored("expired", payload)
            processedEvents[payload.panicEventId] = now
            return
        }
        if (resolvedEvents.containsKey(payload.panicEventId)) {
            logIgnored("expired", payload)
            processedEvents[payload.panicEventId] = now
            return
        }
        if (processedEvents.containsKey(payload.panicEventId)) {
            logIgnored("duplicate", payload)
            return
        }
        val cachedLocation = refreshLastKnownLocation()
        if (cachedLocation == null) {
            logIgnored("no_location", payload)
            processedEvents[payload.panicEventId] = now
            return
        }
        val distanceKm = withContext(Dispatchers.Default) {
            PanicMath.distanceKm(
                cachedLocation.lat,
                cachedLocation.lng,
                payload.latitude,
                payload.longitude
            )
        }
        Log.i(logTag, "Distance calculated: ${formatKm(distanceKm)} km")
        if (distanceKm > radiusKm) {
            logIgnored("distance", payload)
            processedEvents[payload.panicEventId] = now
            return
        }
        val decision = withContext(Dispatchers.Default) {
            antiSpamManager.checkAndRecord(
                PanicAntiSpamManager.PanicEventMeta(
                    eventId = payload.panicEventId,
                    driverId = payload.driverId,
                    lat = payload.latitude,
                    lng = payload.longitude,
                    timestamp = now
                )
            )
        }
        when (decision) {
            PanicAntiSpamManager.Decision.GLOBAL_LIMIT -> {
                logIgnored("global_rate_limit", payload)
                return
            }
            PanicAntiSpamManager.Decision.DRIVER_LIMIT -> {
                logIgnored("driver_rate_limit", payload)
                return
            }
            PanicAntiSpamManager.Decision.SPATIAL_DUPLICATE -> {
                logIgnored("spatial_duplicate", payload)
                return
            }
            PanicAntiSpamManager.Decision.ACCEPT -> Unit
        }
        val priority = PanicMath.priorityForDistance(distanceKm)
        val silentModeEnabled = withContext(Dispatchers.IO) {
            PanicSilentModeStore.isSilentModeEnabled(appContext)
        }
        val lastState = withContext(Dispatchers.IO) {
            PanicSilentModeStore.getState(appContext)
        }
        val soundDecision = if (!silentModeEnabled) {
            SoundDecision(shouldPlay = true, reason = "disabled")
        } else if (priority == PanicEventPriority.CRITICAL) {
            SoundDecision(shouldPlay = true, reason = "forced")
        } else {
            val shouldPlay = shouldPlaySound(priority, lastState, now)
            val reason = if (!shouldPlay) {
                "within_window"
            } else if (lastState == null) {
                "first"
            } else if (PanicMath.priorityRank(priority) >
                PanicMath.priorityRank(lastState.lastPriority)
            ) {
                "priority_up"
            } else {
                "window_expired"
            }
            SoundDecision(shouldPlay = shouldPlay, reason = reason)
        }
        val muted = silentModeEnabled && !soundDecision.shouldPlay
        Log.i(
            logTag,
            "[SILENT_MODE] priority=${priority.name} muted=$muted reason=${soundDecision.reason}"
        )
        if (silentModeEnabled && soundDecision.shouldPlay) {
            withContext(Dispatchers.IO) {
                PanicSilentModeStore.updateState(
                    appContext,
                    SilentAlertState(lastSoundAt = now, lastPriority = priority)
                )
            }
        }
        processedEvents[payload.panicEventId] = now
        activeAlertId = payload.panicEventId
        Log.i(logTag, "REALTIME_EVENT_ACCEPTED id=${payload.panicEventId}")
        Log.i(logTag, "PANIC_RECEIVED_RECEPTOR id=${payload.panicEventId}")
        showHeadsUpNotification(payload, distanceKm)
        val vehicleBrand = payload.vehicleBrand ?: payload.vehicleMake
        val snapshot = VehicleInfo(
            make = vehicleBrand,
            model = payload.vehicleModel,
            plate = payload.vehiclePlate,
            color = payload.vehicleColor
        )
        val driverProfile = if (!payload.driverName.isNullOrBlank()) {
            DriverProfile(name = payload.driverName)
        } else {
            null
        }
        val event = PanicEventRecord(
            id = payload.panicEventId,
            driverId = payload.driverId,
            driverName = payload.driverName,
            vehicleId = payload.vehicleId,
            isActive = true,
            startedAt = null,
            endedAt = null,
            lat = payload.latitude,
            lng = payload.longitude,
            vehicleMake = vehicleBrand,
            vehicleModel = payload.vehicleModel,
            vehicleColor = payload.vehicleColor,
            vehiclePlate = payload.vehiclePlate
        )
        withContext(Dispatchers.Main) {
            onAlertStarted(
                event,
                driverProfile,
                if (snapshot.make.isNullOrBlank() &&
                    snapshot.model.isNullOrBlank() &&
                    snapshot.plate.isNullOrBlank() &&
                    snapshot.color.isNullOrBlank()
                ) {
                    null
                } else {
                    snapshot
                },
                muted
            )
            onAlertLocation(
                DriverLocation(
                    driverId = payload.driverId,
                    latitude = payload.latitude,
                    longitude = payload.longitude,
                    updatedAt = null
                )
            )
            Log.i(logTag, "Panic accepted: alert triggered")
        }
    }

    private suspend fun handleLocationUpdate(
        payload: PanicLocationPayload,
        onAlertLocation: (DriverLocation) -> Unit
    ) {
        if (activeAlertId.isNullOrBlank() || activeAlertId != payload.panicEventId) {
            return
        }
        withContext(Dispatchers.Main) {
            onAlertLocation(
                DriverLocation(
                    driverId = payload.driverId,
                    latitude = payload.latitude,
                    longitude = payload.longitude,
                    heading = payload.heading,
                    updatedAt = payload.updatedAt
                )
            )
        }
    }

    private fun refreshLastKnownLocation(): CachedLocation? {
        val current = locationStore.get() ?: return lastKnownLocation
        val previous = lastKnownLocation
        if (previous == null) {
            lastKnownLocation = CachedLocation(current.lat, current.lng, current.timestamp)
            return lastKnownLocation
        }
        val elapsedMs = current.timestamp - previous.timestamp
        if (elapsedMs >= LOCATION_UPDATE_TIME_MS) {
            lastKnownLocation = CachedLocation(current.lat, current.lng, current.timestamp)
            return lastKnownLocation
        }
        val distanceKm = PanicMath.distanceKm(
            previous.lat,
            previous.lng,
            current.lat,
            current.lng
        )
        if (distanceKm * 1000.0 >= LOCATION_UPDATE_DISTANCE_METERS) {
            lastKnownLocation = CachedLocation(current.lat, current.lng, current.timestamp)
        }
        return lastKnownLocation
    }

    private fun pruneProcessed(nowMs: Long) {
        val iterator = processedEvents.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value > PROCESSED_TTL_MS) {
                iterator.remove()
            }
        }
        val resolvedIterator = resolvedEvents.entries.iterator()
        while (resolvedIterator.hasNext()) {
            val entry = resolvedIterator.next()
            if (nowMs - entry.value > PROCESSED_TTL_MS) {
                resolvedIterator.remove()
            }
        }
    }

    private fun formatKm(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun logIgnored(reason: String, payload: PanicAlertPayload) {
        Log.i(logTag, "PANIC_IGNORED_REASON reason=$reason id=${payload.panicEventId}")
        Log.i(logTag, "PANIC_EVENT_DISCARDED reason=$reason id=${payload.panicEventId}")
        Log.i(logTag, "REALTIME_EVENT_DISCARDED reason=$reason id=${payload.panicEventId}")
    }

    private fun showHeadsUpNotification(payload: PanicAlertPayload, distanceKm: Double) {
        val manager = NotificationManagerCompat.from(appContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CoreConfig.PANIC_CHANNEL_ID,
                "Alertas de panico",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas de panico em tempo real."
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
        val intent = Intent(appContext, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(appContext, 0, intent, flags)
        val text = "Motorista em risco a ${formatKm(distanceKm)} km"
        val notification = NotificationCompat.Builder(appContext, CoreConfig.PANIC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Alerta de panico")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(payload.panicEventId.hashCode(), notification)
    }

    private data class SoundDecision(
        val shouldPlay: Boolean,
        val reason: String
    )

    private data class CachedLocation(
        val lat: Double,
        val lng: Double,
        val timestamp: Long
    )

    companion object {
        private const val LOCATION_UPDATE_DISTANCE_METERS = 30.0
        private const val LOCATION_UPDATE_TIME_MS = 10_000L
        private const val PROCESSED_TTL_MS = 15 * 60 * 1000L
    }
}
