package com.qapp.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qapp.app.R
import com.qapp.app.core.CoreConfig
import com.qapp.app.core.DefensiveModeManager
import com.qapp.app.core.LocationStateStore
import com.qapp.app.core.LogTags
import com.qapp.app.core.RealtimeManager
import com.qapp.app.core.SecurityState
import com.qapp.app.core.SecurityStateStore
import com.qapp.app.core.PanicStateManager
import com.qapp.app.core.RealtimeStateStore
import com.qapp.app.core.ServiceHealthMonitor
import com.qapp.app.core.connectivity.ConnectivityMonitor
import com.qapp.app.core.location.GpsStatusMonitor
import com.qapp.app.data.repository.VehicleRepository
import com.qapp.app.domain.PanicResolveOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.logging.Logger

/**
 * Servico base em primeiro plano para tarefas de seguranca.
 *
 * - Mantem o app ativo em segundo plano para monitoramento continuo.
 * - Usa localizacao em segundo plano para reagir a eventos criticos mesmo com o app fechado.
 * - Usa service normal para deteccao/alerta por voz.
 */
class CoreSecurityService : Service() {

    private val logger = Logger.getLogger(CoreSecurityService::class.java.name)
    private val panicService = PanicService()
    private val vehicleRepository = VehicleRepository()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var panicPublished = false
    @Volatile
    private var lastPanicSource: String = "button"
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var watchdogGraceUntilMs: Long = 0L
    @Volatile
    private var onlineStartedAtMs: Long = 0L
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        SecurityStateStore.init(applicationContext)
        PanicStateManager.init(applicationContext)
        LocationStateStore.init(applicationContext)
        ConnectivityMonitor.init(applicationContext)
        GpsStatusMonitor.init(applicationContext)
        ServiceHealthMonitor.init(applicationContext)
        DefensiveModeManager.init(applicationContext)
        if (!canStartLocationFgs(applicationContext)) {
            android.util.Log.w(
                LogTags.LOCATION,
                "CoreSecurityService not started: missing permission or not eligible for location FGS"
            )
            stopSelf()
            return
        }
        if (SecurityStateStore.isOnline()) {
            val now = System.currentTimeMillis()
            onlineStartedAtMs = now
            watchdogGraceUntilMs = now + WATCHDOG_START_GRACE_MS
            ServiceHealthMonitor.start()
        }
        createNotificationChannel()
        startForegroundWithType()
        updateWakeLock()
        PanicForegroundService.startIfOnline(this)
        startWatchdog()
        serviceScope.launch {
            ConnectivityMonitor.status.collect { status ->
                if (status.isOnline) {
                    publishPanicIfNeeded()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWatchdog()
        ServiceHealthMonitor.stop()
        releaseWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PANIC -> triggerPanic(intent.getStringExtra(EXTRA_REASON) ?: "manual")
            ACTION_STOP_PANIC -> stopPanic()
            ACTION_GO_ONLINE -> goOnline()
            ACTION_GO_OFFLINE -> goOffline()
            else -> logger.info("CoreSecurityService: action missing or ignored")
        }
        // START_STICKY mantem o servico ativo para seguranca mesmo apos encerramento do processo.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildPersistentNotification(): Notification {
        val title = getString(R.string.app_name)
        val text = "Monitoramento de seguranca ativo"
        return NotificationCompat.Builder(this, CoreConfig.FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundWithType() {
        val notification = buildPersistentNotification()
        // Android 14: never use FOREGROUND_SERVICE_TYPE_MICROPHONE.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                CoreConfig.FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(CoreConfig.FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CoreConfig.FOREGROUND_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Canal para operacao continua de seguranca (localizacao/microfone)."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun triggerPanic(reason: String) {
        if (!SecurityStateStore.isOnline()) {
            logger.info("CoreSecurityService: panic blocked while offline")
            return
        }
        val normalizedSource = if (reason.lowercase().contains("voice")) "voice" else "button"
        android.util.Log.i("QAPP_PANIC", "Panic trigger detected ($normalizedSource)")
        if (PanicStateManager.isPanicActive()) {
            logger.info("CoreSecurityService: panic already active")
            return
        }
        serviceScope.launch {
            val vehicleAllowed = hasActiveVehicle()
            if (!vehicleAllowed) {
                return@launch
            }
            val last = LocationStateStore.get()
            val lat = last?.lat
            val lng = last?.lng
            val outcome = panicService.triggerPanic(reason, lat, lng)
            if (outcome == null) {
                logger.warning("CoreSecurityService: panic insert failed; activation aborted")
                return@launch
            }
            lastPanicSource = reason
            PanicStateManager.activatePanic(reason)
            PanicStateManager.setActiveEventId(outcome.eventId.toString())
            SecurityStateStore.setState(SecurityState.PANIC)
            logger.info("CoreSecurityService: start panic")
            updateWakeLock()
            if (!isServiceRunning(LocationService::class.java.name)) {
                startLocationService()
            }
            enablePanicMode()
            if (!isServiceRunning(VoiceTriggerService::class.java.name)) {
                ensureVoiceTriggerService()
            }
            panicPublished = true
        }
    }

    fun stopPanic() {
        val hasEventId = !PanicStateManager.getActiveEventId().isNullOrBlank()
        if (!PanicStateManager.isPanicActive() && !hasEventId && !PanicStateManager.isPending()) {
            logger.info("CoreSecurityService: panic already inactive")
            return
        }
        logger.info("CoreSecurityService: stop panic")
        PanicStateManager.markFinalizing("stop_request")
        serviceScope.launch {
            val outcome = panicService.resolvePanic()
            if (outcome == PanicResolveOutcome.FAILED) {
                return@launch
            }
            if (SecurityStateStore.getState() == SecurityState.PANIC) {
                SecurityStateStore.setState(SecurityState.ONLINE)
            }
            panicPublished = false
            updateWakeLock()
            enablePanicMode(false)
            stopLocationUpdates()
        }
    }

    private fun goOnline() {
        serviceScope.launch {
            if (!hasActiveVehicle()) {
                return@launch
            }
            val current = SecurityStateStore.getState()
            if (current == SecurityState.ONLINE) {
                logger.info("CoreSecurityService: already online")
            } else if (current == SecurityState.OFFLINE) {
                SecurityStateStore.setState(SecurityState.ONLINE)
            }
            val now = System.currentTimeMillis()
            onlineStartedAtMs = now
            watchdogGraceUntilMs = now + WATCHDOG_START_GRACE_MS
            updateWakeLock()
            startLocationService()
            enablePanicMode(false)
            ensureVoiceTriggerService()
            PanicForegroundService.startIfOnline(this@CoreSecurityService)
            RealtimeManager.connect()
            ServiceHealthMonitor.start()
            startWatchdog()
        }
    }

    private fun goOffline() {
        if (SecurityStateStore.getState() == SecurityState.OFFLINE) {
            logger.info("CoreSecurityService: already offline; forcing cleanup")
        }
        SecurityStateStore.setState(SecurityState.OFFLINE)
        onlineStartedAtMs = 0L
        watchdogGraceUntilMs = 0L
        updateWakeLock()
        if (PanicStateManager.isPanicActive()) {
            stopPanic()
        }
        stopVoiceTriggerService()
        stopLocationUpdates()
        PanicForegroundService.stop(this)
        RealtimeManager.disconnect(permanent = true)
        ServiceHealthMonitor.stop()
        stopWatchdog()
        stopSelf()
    }

    private suspend fun hasActiveVehicle(): Boolean {
        val status = vehicleRepository.getActiveVehicleStatus()
        if (!status.hasAny) {
            android.util.Log.w("QAPP_PANIC", "NO_VEHICLE_REGISTERED")
            android.util.Log.w("QAPP_PANIC", "PANIC_BLOCKED_NO_ACTIVE_VEHICLE")
            return false
        }
        if (status.vehicle == null) {
            android.util.Log.w("QAPP_PANIC", "NO_ACTIVE_VEHICLE")
            android.util.Log.w("QAPP_PANIC", "PANIC_BLOCKED_NO_ACTIVE_VEHICLE")
            return false
        }
        return true
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationService::class.java).setAction(LocationService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun enablePanicMode(enabled: Boolean = true) {
        val action = if (enabled) {
            LocationService.ACTION_SET_PANIC_ON
        } else {
            LocationService.ACTION_SET_PANIC_OFF
        }
        val intent = Intent(this, LocationService::class.java).setAction(action)
        startService(intent)
    }

    private fun ensureVoiceTriggerService() {
        if (!hasMicrophonePermission()) {
            android.util.Log.w(LogTags.VOICE, "Microphone permission missing; skipping voice trigger")
            return
        }
        val intent =
            Intent(this, VoiceTriggerService::class.java).setAction(VoiceTriggerService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVoiceTriggerService() {
        val intent = Intent(this, VoiceTriggerService::class.java).setAction(VoiceTriggerService.ACTION_STOP)
        startService(intent)
    }

    private fun stopLocationUpdates() {
        val intent = Intent(this, LocationService::class.java).setAction(LocationService.ACTION_STOP)
        startService(intent)
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        if (!SecurityStateStore.isOnline()) return
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(30_000)
                if (SecurityStateStore.isOnline()) {
                    val now = System.currentTimeMillis()
                    if (onlineStartedAtMs == 0L) {
                        onlineStartedAtMs = now
                    }
                    val uptimeMs = now - onlineStartedAtMs
                    val inGracePeriod = now < watchdogGraceUntilMs
                    val hasFix = GpsStatusMonitor.status.value.lastFixAt != null
                    val locationHeartbeatFresh =
                        isHeartbeatFresh(LocationService.getLastHeartbeatAt(), now)
                    val voiceHeartbeatFresh =
                        isHeartbeatFresh(VoiceTriggerService.getLastHeartbeatAt(), now)
                    val canRestart = !inGracePeriod &&
                        uptimeMs >= WATCHDOG_MIN_UPTIME_MS &&
                        !hasFix
                    if (canRestart && !locationHeartbeatFresh) {
                        logger.info("Watchdog: restarting LocationService")
                        startLocationService()
                    }
                    if (canRestart && !voiceHeartbeatFresh) {
                        logger.info("Watchdog: restarting VoiceTriggerService")
                        ensureVoiceTriggerService()
                    }
                }
                if (PanicStateManager.isPanicActive()) {
                    enablePanicMode()
                }
                if (SecurityStateStore.isOnline() && !RealtimeStateStore.state.value.isConnected) {
                    android.util.Log.w(LogTags.REALTIME, "Realtime disconnected while online")
                }
                updateWakeLock()
            }
        }
    }

    private fun updateWakeLock() {
        val shouldHold = SecurityStateStore.isOnline() || PanicStateManager.isPanicActive()
        if (shouldHold) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    private fun isHeartbeatFresh(lastHeartbeatAt: Long, now: Long): Boolean {
        if (lastHeartbeatAt <= 0L) return false
        return now - lastHeartbeatAt <= SERVICE_HEARTBEAT_TIMEOUT_MS
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "QAPP:CoreSecurity"
        )
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
        android.util.Log.i(LogTags.WATCHDOG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        if (lock != null && lock.isHeld) {
            lock.release()
            android.util.Log.i(LogTags.WATCHDOG, "WakeLock released")
        }
        wakeLock = null
    }

    private fun isServiceRunning(serviceName: String): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val services = manager.getRunningServices(Int.MAX_VALUE)
        return services.any { it.service.className == serviceName }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private suspend fun publishPanicIfNeeded() {
        if (!PanicStateManager.isPanicActive()) return
        if (panicPublished) return
        if (PanicStateManager.getActiveEventId().isNullOrBlank()) return
        val last = LocationStateStore.get()
        val lat = last?.lat
        val lng = last?.lng
        val outcome = panicService.triggerPanic(lastPanicSource, lat, lng)
        panicPublished = outcome != null
    }

    companion object {
        private const val WATCHDOG_START_GRACE_MS = 45_000L
        private const val WATCHDOG_MIN_UPTIME_MS = 60_000L
        private const val SERVICE_HEARTBEAT_TIMEOUT_MS = 30_000L
        const val ACTION_START_PANIC = "com.qapp.app.action.START_PANIC"
        const val ACTION_STOP_PANIC = "com.qapp.app.action.STOP_PANIC"
        const val ACTION_GO_ONLINE = "com.qapp.app.action.GO_ONLINE"
        const val ACTION_GO_OFFLINE = "com.qapp.app.action.GO_OFFLINE"
        const val EXTRA_REASON = "extra_reason"

        fun triggerPanic(context: Context, reason: String) {
            if (!canStartLocationFgs(context)) {
                android.util.Log.w(
                    LogTags.LOCATION,
                    "Panic trigger blocked: missing permission or not eligible for location FGS"
                )
                return
            }
            val intent = Intent(context, CoreSecurityService::class.java).apply {
                action = ACTION_START_PANIC
                putExtra(EXTRA_REASON, reason)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun goOnline(context: Context) {
            if (!canStartLocationFgs(context)) {
                android.util.Log.w(
                    LogTags.LOCATION,
                    "Go online blocked: missing permission or not eligible for location FGS"
                )
                return
            }
            val intent = Intent(context, CoreSecurityService::class.java).apply {
                action = ACTION_GO_ONLINE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun goOffline(context: Context) {
            val intent = Intent(context, CoreSecurityService::class.java).apply {
                action = ACTION_GO_OFFLINE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopPanic(context: Context) {
            val intent = Intent(context, CoreSecurityService::class.java).apply {
                action = ACTION_STOP_PANIC
            }
            ContextCompat.startForegroundService(context, intent)
        }

        private fun canStartLocationFgs(context: Context): Boolean {
            if (!hasLocationPermission(context)) {
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !hasBackgroundLocationPermission(context) &&
                !isAppInForeground(context)
            ) {
                return false
            }
            return true
        }

        private fun hasLocationPermission(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }

        private fun hasBackgroundLocationPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return true
            }
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        private fun isAppInForeground(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pid = Process.myPid()
            val process = manager.runningAppProcesses?.firstOrNull { it.pid == pid } ?: return false
            return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }
    }
}
