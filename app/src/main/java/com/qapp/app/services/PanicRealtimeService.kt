package com.qapp.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qapp.app.MainActivity
import com.qapp.app.R
import com.qapp.app.core.IncomingPanicAlertStore
import com.qapp.app.core.LocationStateStore
import com.qapp.app.core.PanicEventGuard
import com.qapp.app.core.PanicAlertPendingStore
import com.qapp.app.core.PanicMath
import com.qapp.app.core.PanicStateManager
import com.qapp.app.core.SecurityStateStore
import com.qapp.app.core.SupabaseClientProvider
import com.qapp.app.data.repository.VehicleInfo
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class PanicRealtimeService : Service() {

    private val logTag = "PANIC_GLOBAL_SERVICE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var alertsJob: Job? = null
    private var channel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var guard: PanicEventGuard? = null
    private val realtimeHealthy = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        PanicAlertPendingStore.init(applicationContext)
        PanicStateManager.init(applicationContext)
        SecurityStateStore.init(applicationContext)
        LocationStateStore.init(applicationContext)
        createServiceChannel()
        startForegroundWithType()
        startListening()
        guard = PanicEventGuard(
            context = applicationContext,
            isRealtimeHealthy = { realtimeHealthy.get() }
        ) { alert ->
            handleFallbackAlert(alert)
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        alertsJob?.cancel()
        runBlocking {
            channel?.unsubscribe()
        }
        realtimeHealthy.set(false)
        guard?.stop()
        releaseWakeLock()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        if (alertsJob?.isActive == true) return
        if (!hasLocationPermission()) {
            Log.w(logTag, "Realtime service not started: missing location permission")
            stopSelf()
            return
        }
        alertsJob = scope.launch {
            val session = SupabaseClientProvider.client.auth.currentSessionOrNull()
            if (session == null) {
                Log.w(logTag, "Realtime listener blocked: missing session")
                return@launch
            }
            try {
                val realtimeChannel =
                    SupabaseClientProvider.client.realtime.channel("panic_events_global")
                channel = realtimeChannel
                val changes = realtimeChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "panic_events"
                }
                realtimeChannel.subscribe()
                realtimeHealthy.set(true)
                changes.collect { action ->
                    when (action) {
                        is PostgresAction.Insert -> handleInsert(action.record)
                        is PostgresAction.Update -> handleUpdate(action.record)
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                realtimeHealthy.set(false)
                Log.w(logTag, "Realtime listener error: ${e.message}", e)
            }
        }
    }

    private suspend fun handleInsert(record: JsonObject) {
        val isActive = record.boolean("is_active") ?: false
        if (!isActive) return
        val eventId = record.string("id") ?: return
        Log.i(logTag, "PANIC_EVENT_RECEIVED_REALTIME id=$eventId")
        val driverId = record.string("driver_id") ?: return
        if (!SecurityStateStore.isOnline()) {
            Log.i(logTag, "Alert ignored (offline) id=$eventId")
            return
        }
        val currentUserId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        if (!currentUserId.isNullOrBlank() && currentUserId == driverId) {
            Log.i(logTag, "Alert ignored (self) id=$eventId")
            return
        }
        val location = record.latLng() ?: return
        val ownLocation = LocationStateStore.get() ?: return
        val distanceKm = PanicMath.distanceKm(
            ownLocation.lat,
            ownLocation.lng,
            location.first,
            location.second
        )
        if (distanceKm > ALERT_RADIUS_KM) {
            Log.i(logTag, "Alert ignored (distance) id=$eventId")
            return
        }
        val now = System.currentTimeMillis()
        val vehicle = VehicleInfo(
            make = record.string("vehicle_brand") ?: record.string("vehicle_make"),
            model = record.string("vehicle_model"),
            plate = record.string("vehicle_plate"),
            color = record.string("vehicle_color")
        )
        IncomingPanicAlertStore.showAlert(
            eventId = eventId,
            driverId = driverId,
            driverName = record.string("driver_name"),
            lat = location.first,
            lng = location.second,
            distanceKm = distanceKm,
            startedAtMs = now,
            muted = false,
            vehicle = vehicle
        )
        PanicAlertPendingStore.save(eventId, driverId, now)
        acquireWakeLock()
        showAlertNotification(eventId, driverId)
    }

    private suspend fun handleFallbackAlert(alert: PanicEventGuard.FallbackAlert) {
        Log.i(logTag, "PANIC_EVENT_RECEIVED_FALLBACK id=${alert.eventId}")
        Log.w(logTag, "PANIC_EVENT_MISSED_REALTIME id=${alert.eventId}")
        val record = buildFallbackRecord(alert)
        handleInsert(record)
    }

    private suspend fun handleUpdate(record: JsonObject) {
        val eventId = record.string("id") ?: return
        val isActive = record.boolean("is_active")
        val endedAt = record.string("ended_at")
        if (isActive == false || !endedAt.isNullOrBlank()) {
            PanicAlertPendingStore.clearIfMatches(eventId)
            IncomingPanicAlertStore.markEnded()
            releaseWakeLock()
        }
    }

    private fun showAlertNotification(eventId: String, driverId: String) {
        createAlertChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ALERT_EVENT_ID, eventId)
            putExtra(EXTRA_ALERT_DRIVER_ID, driverId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, eventId.hashCode(), intent, flags)
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Alerta de panico")
            .setContentText("Motorista em risco nas proximidades")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(eventId.hashCode(), notification)
    }

    private fun startForegroundWithType() {
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun buildServiceNotification(): Notification {
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Monitoramento de alertas")
            .setContentText("Escutando alertas de panico em tempo real")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Monitoramento de alertas",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Canal de servico para listener global de alertas."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Alertas de panico",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas criticos recebidos em background."
                enableVibration(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "QAPP:PanicAlert"
        )
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        if (lock != null && lock.isHeld) {
            lock.release()
        }
        wakeLock = null
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.latLng(): Pair<Double, Double>? {
        val lat = this["lat"]?.jsonPrimitive?.doubleOrNull
        val lng = this["lng"]?.jsonPrimitive?.doubleOrNull
        if (lat != null && lng != null) {
            return lat to lng
        }
        val locationValue = this["location"]
        return parseLocation(locationValue)
    }

    private fun parseLocation(value: JsonElement?): Pair<Double, Double>? {
        val text = value?.jsonPrimitive?.contentOrNull
        if (!text.isNullOrBlank()) {
            val trimmed = text.substringAfter("POINT").substringAfter("(").substringBefore(")")
            val parts = trimmed.replace(",", " ").trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val lng = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lat != null && lng != null) {
                    return lat to lng
                }
            }
        }
        val obj = value?.jsonObject
        val coordinates = obj?.get("coordinates")
        if (coordinates is JsonArray && coordinates.size >= 2) {
            val lng = coordinates[0].jsonPrimitive.doubleOrNull
            val lat = coordinates[1].jsonPrimitive.doubleOrNull
            if (lat != null && lng != null) {
                return lat to lng
            }
        }
        return null
    }

    private fun buildFallbackRecord(alert: PanicEventGuard.FallbackAlert): JsonObject {
        return kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(alert.eventId))
            put("driver_id", kotlinx.serialization.json.JsonPrimitive(alert.driverId))
            put("is_active", kotlinx.serialization.json.JsonPrimitive(true))
            put("lat", kotlinx.serialization.json.JsonPrimitive(alert.lat))
            put("lng", kotlinx.serialization.json.JsonPrimitive(alert.lng))
            put("location", kotlinx.serialization.json.JsonPrimitive(alert.locationWkt))
            if (!alert.driverName.isNullOrBlank()) {
                put("driver_name", kotlinx.serialization.json.JsonPrimitive(alert.driverName))
            }
            if (!alert.vehicleBrand.isNullOrBlank()) {
                put("vehicle_brand", kotlinx.serialization.json.JsonPrimitive(alert.vehicleBrand))
            }
            if (!alert.vehicleMake.isNullOrBlank()) {
                put("vehicle_make", kotlinx.serialization.json.JsonPrimitive(alert.vehicleMake))
            }
            if (!alert.vehicleModel.isNullOrBlank()) {
                put("vehicle_model", kotlinx.serialization.json.JsonPrimitive(alert.vehicleModel))
            }
            if (!alert.vehicleColor.isNullOrBlank()) {
                put("vehicle_color", kotlinx.serialization.json.JsonPrimitive(alert.vehicleColor))
            }
            if (!alert.vehiclePlate.isNullOrBlank()) {
                put("vehicle_plate", kotlinx.serialization.json.JsonPrimitive(alert.vehiclePlate))
            }
        }
    }

    companion object {
        private const val SERVICE_CHANNEL_ID = "panic_realtime_service"
        private const val ALERT_CHANNEL_ID = "panic_realtime_alerts"
        private const val SERVICE_NOTIFICATION_ID = 3301
        private const val ALERT_RADIUS_KM = 10.0
        const val EXTRA_ALERT_EVENT_ID = "extra_alert_event_id"
        const val EXTRA_ALERT_DRIVER_ID = "extra_alert_driver_id"

        fun startIfAllowed(context: Context) {
            val fine = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) return
            val intent = Intent(context, PanicRealtimeService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
