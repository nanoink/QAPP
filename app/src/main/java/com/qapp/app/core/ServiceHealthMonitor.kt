package com.qapp.app.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.qapp.app.core.DefensiveModeManager
import com.qapp.app.core.location.GpsStatusMonitor
import com.qapp.app.services.LocationService
import com.qapp.app.services.VoiceTriggerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

enum class ServiceHealthState {
    OK,
    RESTARTING,
    FAILED
}

data class ServiceHealthStatus(
    val location: ServiceHealthState,
    val voice: ServiceHealthState,
    val realtime: ServiceHealthState
) {
    val isUnstable: Boolean
        get() = location != ServiceHealthState.OK ||
            voice != ServiceHealthState.OK ||
            realtime != ServiceHealthState.OK
}

object ServiceHealthMonitor {

    private const val TAG = "SERVICE_HEALTH"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var appContext: Context
    private var initialized = false

    private val locationTracker = RestartTracker()
    private val voiceTracker = RestartTracker()
    private val realtimeTracker = RestartTracker()

    private val _status =
        kotlinx.coroutines.flow.MutableStateFlow(
            ServiceHealthStatus(
                location = ServiceHealthState.OK,
                voice = ServiceHealthState.OK,
                realtime = ServiceHealthState.OK
            )
        )
    val status: kotlinx.coroutines.flow.StateFlow<ServiceHealthStatus> = _status
    private var voiceDegraded = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            SecurityStateStore.init(appContext)
            GpsStatusMonitor.init(appContext)
            DefensiveModeManager.init(appContext)
            PanicStateManager.init(appContext)
            initialized = true
        }
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                if (!SecurityStateStore.isOnline()) {
                    resetStatus()
                    continue
                }
                val now = System.currentTimeMillis()
                val locationState = checkLocation(now)
                val voiceState = checkVoice(now)
                val realtimeState = checkRealtime(now)
                _status.value = ServiceHealthStatus(
                    location = locationState,
                    voice = voiceState,
                    realtime = realtimeState
                )
                DefensiveModeManager.onHealthStatus(_status.value)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        resetStatus()
    }

    private fun resetStatus() {
        locationTracker.reset()
        voiceTracker.reset()
        realtimeTracker.reset()
        voiceDegraded = false
        _status.value = ServiceHealthStatus(
            location = ServiceHealthState.OK,
            voice = ServiceHealthState.OK,
            realtime = ServiceHealthState.OK
        )
    }

    private fun checkLocation(now: Long): ServiceHealthState {
        if (!hasLocationPermission()) {
            Log.i(TAG, "[SERVICE_HEALTH] location=FAILED")
            return ServiceHealthState.FAILED
        }
        if (PanicStateManager.isPanicActive()) {
            Log.i(TAG, "LOCATION_FGS_RESTART_SKIPPED (panic active)")
            return ServiceHealthState.OK
        }
        val running = isServiceRunning(LocationService::class.java.name)
        val lastFixAt = GpsStatusMonitor.status.value.lastFixAt
        val fixStale = lastFixAt == null || now - lastFixAt > GPS_FIX_TIMEOUT_MS
        if (running && !fixStale) {
            locationTracker.reset()
            Log.i(TAG, "[SERVICE_HEALTH] location=OK")
            return ServiceHealthState.OK
        }
        return restartWithBackoff(
            tracker = locationTracker,
            label = "location",
            now = now
        ) {
            restartLocationService()
        }
    }

    private fun checkVoice(now: Long): ServiceHealthState {
        val panicActive = PanicStateManager.isPanicActive()
        if (!hasAudioPermission()) {
            markVoiceDegraded("permission_missing")
            if (panicActive) {
                Log.i(TAG, "VOICE_RESTART_SKIPPED panic_active")
                return ServiceHealthState.OK
            }
            Log.i(TAG, "[SERVICE_HEALTH] voice=FAILED")
            return ServiceHealthState.FAILED
        }
        val running = isServiceRunning(VoiceTriggerService::class.java.name)
        val heartbeatFresh =
            now - VoiceTriggerService.getLastHeartbeatAt() <= SERVICE_HEARTBEAT_TIMEOUT_MS
        if (panicActive) {
            if (!running || !heartbeatFresh) {
                markVoiceDegraded("panic_active")
            } else {
                markVoiceRecovered()
            }
            Log.i(TAG, "VOICE_RESTART_SKIPPED panic_active")
            return ServiceHealthState.OK
        }
        if (running && heartbeatFresh) {
            markVoiceRecovered()
            voiceTracker.reset()
            Log.i(TAG, "[SERVICE_HEALTH] voice=OK")
            return ServiceHealthState.OK
        }
        markVoiceDegraded(if (!running) "not_running" else "heartbeat_stale")
        return restartWithBackoff(
            tracker = voiceTracker,
            label = "voice",
            now = now
        ) {
            restartVoiceService()
        }
    }

    private fun checkRealtime(now: Long): ServiceHealthState {
        val state = RealtimeStateStore.state.value
        val disconnectedTooLong =
            !state.isConnected && now - state.lastChangedAt > REALTIME_TIMEOUT_MS
        if (!disconnectedTooLong) {
            realtimeTracker.reset()
            Log.i(TAG, "[SERVICE_HEALTH] realtime=OK")
            return ServiceHealthState.OK
        }
        return restartWithBackoff(
            tracker = realtimeTracker,
            label = "realtime",
            now = now
        ) {
            RealtimeManager.disconnect(permanent = false)
            RealtimeManager.connect()
        }
    }

    private fun restartWithBackoff(
        tracker: RestartTracker,
        label: String,
        now: Long,
        action: () -> Unit
    ): ServiceHealthState {
        if (tracker.attempts >= MAX_ATTEMPTS) {
            Log.i(TAG, "[SERVICE_HEALTH] $label=FAILED")
            DefensiveModeManager.recordServiceFailure(label)
            return ServiceHealthState.FAILED
        }
        if (tracker.nextAllowedAt > now) {
            Log.i(TAG, "[SERVICE_HEALTH] $label=FAILED")
            return ServiceHealthState.FAILED
        }
        tracker.attempts += 1
        val backoff = min(BASE_BACKOFF_MS * (1 shl (tracker.attempts - 1)), MAX_BACKOFF_MS)
        tracker.nextAllowedAt = now + backoff
        action()
        Log.i(TAG, "[SERVICE_HEALTH] $label=RESTARTING")
        DefensiveModeManager.recordServiceFailure(label)
        return ServiceHealthState.RESTARTING
    }

    private fun restartLocationService() {
        if (!SecurityStateStore.isOnline()) {
            return
        }
        val stopIntent = Intent(appContext, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP
        }
        appContext.startService(stopIntent)
        val startIntent = Intent(appContext, LocationService::class.java).apply {
            action = LocationService.ACTION_START
        }
        if (isServiceRunning(LocationService::class.java.name)) {
            appContext.startService(startIntent)
            return
        }
        ContextCompat.startForegroundService(appContext, startIntent)
    }

    private fun restartVoiceService() {
        val stopIntent = Intent(appContext, VoiceTriggerService::class.java).apply {
            action = VoiceTriggerService.ACTION_STOP
        }
        appContext.startService(stopIntent)
        val startIntent = Intent(appContext, VoiceTriggerService::class.java).apply {
            action = VoiceTriggerService.ACTION_START
        }
        if (isServiceRunning(VoiceTriggerService::class.java.name)) {
            appContext.startService(startIntent)
            return
        }
        ContextCompat.startForegroundService(appContext, startIntent)
    }

    private fun isServiceRunning(serviceClassName: String): Boolean {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val services = manager.getRunningServices(Int.MAX_VALUE)
        return services.any { it.service.className == serviceClassName }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private data class RestartTracker(
        var attempts: Int = 0,
        var nextAllowedAt: Long = 0L
    ) {
        fun reset() {
            attempts = 0
            nextAllowedAt = 0L
        }
    }

    private const val CHECK_INTERVAL_MS = 30_000L
    private const val GPS_FIX_TIMEOUT_MS = 60_000L
    private const val REALTIME_TIMEOUT_MS = 30_000L
    private const val SERVICE_HEARTBEAT_TIMEOUT_MS = 60_000L
    private const val MAX_ATTEMPTS = 5
    private const val BASE_BACKOFF_MS = 5_000L
    private const val MAX_BACKOFF_MS = 60_000L

    private fun markVoiceDegraded(reason: String) {
        if (voiceDegraded) return
        voiceDegraded = true
        Log.w(TAG, "VOICE_SERVICE_DEGRADED reason=$reason")
    }

    private fun markVoiceRecovered() {
        if (!voiceDegraded) return
        voiceDegraded = false
        Log.i(TAG, "VOICE_SERVICE_RECOVERED")
    }
}
