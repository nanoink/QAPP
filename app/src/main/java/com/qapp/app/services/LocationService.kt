package com.qapp.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.qapp.app.R
import com.qapp.app.core.CoreConfig
import com.qapp.app.core.DefensiveModeManager
import com.qapp.app.core.LocationStateStore
import com.qapp.app.core.LogTags
import com.qapp.app.core.PanicStateManager
import com.qapp.app.core.RealtimeManager
import com.qapp.app.core.SecurityStateStore
import com.qapp.app.core.SupabaseClientProvider
import com.qapp.app.core.connectivity.ConnectivityMonitor
import com.qapp.app.core.location.GpsStatusMonitor
import com.qapp.app.data.repository.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import io.github.jan.supabase.gotrue.auth

/**
 * Servico em primeiro plano dedicado a localizacao.
 * Mantem a atualizacao ativa enquanto o modo panico estiver ligado.
 */
class LocationService : Service() {

    private val logTag = "LocationService"
    private val logger = Logger.getLogger(LocationService::class.java.name)
    private lateinit var repository: LocationRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isTracking = false
    private var isPanicMode = false
    private var lastSentLocation: Location? = null
    private var lastSentAtMs: Long = 0L
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ensureForeground()
        touchHeartbeat()
        PanicStateManager.init(applicationContext)
        LocationStateStore.init(applicationContext)
        SecurityStateStore.init(applicationContext)
        ConnectivityMonitor.init(applicationContext)
        GpsStatusMonitor.init(applicationContext)
        DefensiveModeManager.init(applicationContext)
        repository = LocationRepository(applicationContext)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        isPanicMode = PanicStateManager.isPanicActive()
        serviceScope.launch {
            ConnectivityMonitor.status.collect { status ->
                repository.onNetworkChanged(status.isOnline)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        touchHeartbeat()
        when (intent?.action) {
            ACTION_START -> {
                ensureForeground()
                if (!SecurityStateStore.isOnline()) {
                    Log.i(logTag, "Location start skipped: offline")
                    stopSelf()
                } else {
                    Log.i(LogTags.LOCATION, "LocationService started")
                    startLocationUpdates()
                }
            }
            ACTION_SET_PANIC_ON -> setPanicMode(true)
            ACTION_SET_PANIC_OFF -> setPanicMode(false)
            ACTION_STOP -> {
                stopLocationUpdates()
                repository.clearBuffer()
                serviceScope.coroutineContext.cancelChildren()
                stopForeground(true)
                foregroundStarted = false
                stopSelf()
            }
            else -> logger.info("LocationService: action missing or ignored")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildPersistentNotification(): Notification {
        val title = getString(R.string.app_name)
        val text = "Monitoramento de localizacao ativo"
        return NotificationCompat.Builder(this, CoreConfig.FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundWithType() {
        val notification = buildPersistentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        startForegroundWithType()
        foregroundStarted = true
        Log.i(LogTags.LOCATION, "LOCATION_FGS_STARTED_OK")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CoreConfig.FOREGROUND_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Canal para operacao continua de seguranca (localizacao/microfone)."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startLocationUpdates() {
        logger.info("LocationService: start location updates")
        if (!SecurityStateStore.isOnline()) {
            Log.i(logTag, "Location updates blocked: offline")
            stopSelf()
            return
        }
        if (!hasLocationPermission()) {
            Log.w(logTag, "Missing location permission; skip updates")
            return
        }

        if (isTracking) {
            stopLocationUpdates()
        }

        val defensiveMode = DefensiveModeManager.isEnabled()
        if (defensiveMode) {
            Log.w(logTag, "Defensive mode active: reducing location update frequency")
        }
        val intervalMs = when {
            defensiveMode -> DEFENSIVE_INTERVAL_MS
            isPanicMode -> PANIC_INTERVAL_MS
            else -> NORMAL_INTERVAL_MS
        }
        val fastestMs = when {
            defensiveMode -> DEFENSIVE_FASTEST_INTERVAL_MS
            isPanicMode -> PANIC_FASTEST_INTERVAL_MS
            else -> FASTEST_INTERVAL_MS
        }
        val minDistance = when {
            defensiveMode -> DEFENSIVE_MIN_DISTANCE_METERS
            isPanicMode -> PANIC_MIN_DISTANCE_METERS
            else -> MIN_DISTANCE_METERS
        }

        val request = LocationRequest.Builder(
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalMs
        ).setMinUpdateIntervalMillis(fastestMs)
            .setMinUpdateDistanceMeters(minDistance)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                touchHeartbeat()
                LocationStateStore.update(
                    lat = location.latitude,
                    lng = location.longitude,
                    timestamp = System.currentTimeMillis()
                )
                GpsStatusMonitor.updateFix(location)
                val nowMs = System.currentTimeMillis()
                if (!shouldSendLocation(location, nowMs)) {
                    Log.d(LogTags.LOCATION, "Location skipped (distance/time)")
                    return
                }
                markLocationSent(location, nowMs)
                serviceScope.launch {
                    try {
                        repository.sendLocation(
                            location.latitude,
                            location.longitude,
                            location.accuracy
                        )
                        if (isPanicMode) {
                            repository.flushNow()
                            publishPanicLocation(location)
                        }
                    } catch (ex: Exception) {
                        logger.warning("LocationService: failed to send location")
                    }
                }
            }
        }
        locationCallback = callback
        // Guard against runtime permission revocation or race conditions.
        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            isTracking = true
        } catch (e: SecurityException) {
            Log.w(logTag, "Location permission denied; updates not started", e)
            locationCallback = null
            isTracking = false
        }
    }

    private fun stopLocationUpdates() {
        logger.info("LocationService: stop location updates")
        val callback = locationCallback
        if (callback != null) {
            // Defensive: permission can be revoked while service is running.
            try {
                fusedClient.removeLocationUpdates(callback)
            } catch (e: SecurityException) {
                Log.w(logTag, "Location permission denied; updates already stopped", e)
            }
        }
        locationCallback = null
        isTracking = false
    }

    private fun shouldSendLocation(location: Location, nowMs: Long): Boolean {
        val previous = lastSentLocation
        if (previous == null || lastSentAtMs == 0L) {
            return true
        }
        val minTimeMs = if (DefensiveModeManager.isEnabled()) {
            DEFENSIVE_MIN_TIME_BETWEEN_UPDATES_MS
        } else {
            MIN_TIME_BETWEEN_UPDATES_MS
        }
        val elapsedMs = nowMs - lastSentAtMs
        if (elapsedMs >= minTimeMs) {
            return true
        }
        val distanceMeters = distanceBetweenMeters(
            previous.latitude,
            previous.longitude,
            location.latitude,
            location.longitude
        )
        val minDistanceMeters = if (DefensiveModeManager.isEnabled()) {
            DEFENSIVE_MIN_DISTANCE_METERS
        } else {
            MIN_DISTANCE_METERS
        }
        return distanceMeters >= minDistanceMeters
    }

    private fun markLocationSent(location: Location, nowMs: Long) {
        lastSentLocation = Location(location)
        lastSentAtMs = nowMs
    }

    private fun distanceBetweenMeters(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(startLat, startLng, endLat, endLng, results)
        return results[0]
    }

    fun setPanicMode(enabled: Boolean) {
        if (isPanicMode == enabled) {
            return
        }
        isPanicMode = enabled
        if (isTracking) {
            startLocationUpdates()
        }
    }

    private suspend fun publishPanicLocation(location: Location) {
        val panicId = PanicStateManager.getActiveEventId()
        if (panicId.isNullOrBlank()) {
            return
        }
        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        if (userId.isNullOrBlank()) {
            return
        }
        val heading = if (location.hasBearing()) location.bearing.toDouble() else null
        RealtimeManager.sendPanicLocationUpdate(
            panicId = panicId,
            driverId = userId,
            lat = location.latitude,
            lng = location.longitude,
            heading = heading,
            updatedAt = formatUtcTimestamp(location.time)
        )
    }

    private fun formatUtcTimestamp(epochMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMillis))
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

    companion object {
        const val ACTION_START = "com.qapp.app.action.LOCATION_START"
        const val ACTION_STOP = "com.qapp.app.action.LOCATION_STOP"
        const val ACTION_SET_PANIC_ON = "com.qapp.app.action.LOCATION_PANIC_ON"
        const val ACTION_SET_PANIC_OFF = "com.qapp.app.action.LOCATION_PANIC_OFF"
        private const val NOTIFICATION_ID = 2001
        private const val NORMAL_INTERVAL_MS = 10_000L
        private const val FASTEST_INTERVAL_MS = 5_000L
        private const val MIN_TIME_BETWEEN_UPDATES_MS = 10_000L
        private const val MIN_DISTANCE_METERS = 25f
        private const val PANIC_INTERVAL_MS = 3_000L
        private const val PANIC_FASTEST_INTERVAL_MS = 1_500L
        private const val PANIC_MIN_DISTANCE_METERS = 8f
        private const val DEFENSIVE_INTERVAL_MS = 30_000L
        private const val DEFENSIVE_FASTEST_INTERVAL_MS = 15_000L
        private const val DEFENSIVE_MIN_TIME_BETWEEN_UPDATES_MS = 30_000L
        private const val DEFENSIVE_MIN_DISTANCE_METERS = 75f

        private val lastHeartbeatAt = AtomicLong(0L)

        fun touchHeartbeat() {
            lastHeartbeatAt.set(System.currentTimeMillis())
        }

        fun getLastHeartbeatAt(): Long = lastHeartbeatAt.get()
    }
}
