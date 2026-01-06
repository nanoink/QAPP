package com.qapp.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.qapp.app.core.PanicVoiceOrchestrator
import com.qapp.app.core.CoreConfig
import com.qapp.app.core.PanicStateManager
import com.qapp.app.core.SecurityPanicStateStore
import com.qapp.app.core.SecuritySessionStore
import com.qapp.app.core.SecurityStateStore
import com.qapp.app.core.StoredSessionManager
import com.qapp.app.core.VoiceHealthMonitor
import com.qapp.app.domain.PanicManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import java.util.logging.Logger

/**
 * Servico dedicado a gatilhos de voz (nao-foreground).
 */
class VoiceTriggerService : Service() {

    private val logTag = "PANIC_VOICE"
    private val logger = Logger.getLogger(VoiceTriggerService::class.java.name)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listenIntent: Intent? = null
    private var lastRms = 0f
    private val detections = ArrayDeque<Long>()
    private var shouldListen = false
    private var isListening = false
    private var recoveryInProgress = false
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var panicVoiceOrchestrator: PanicVoiceOrchestrator
    private lateinit var voiceHealthMonitor: VoiceHealthMonitor

    private val keywordRegex = Regex("\\bsocorro\\b", RegexOption.IGNORE_CASE)

    override fun onCreate() {
        super.onCreate()
        touchHeartbeat()
        createNotificationChannel()
        startForegroundWithType()
        SecurityStateStore.init(applicationContext)
        SecuritySessionStore.init(applicationContext)
        PanicStateManager.init(applicationContext)
        panicVoiceOrchestrator = PanicVoiceOrchestrator(
            panicManager = PanicManager(
                panicStarter = { reason ->
                    CoreSecurityService.triggerPanic(this, reason)
                }
            ),
            sessionManager = StoredSessionManager(),
            panicStateStore = SecurityPanicStateStore(),
            onTriggered = {
                vibrateOnce()
                playConfirmationTone()
            }
        )
        voiceHealthMonitor = VoiceHealthMonitor()
        initSpeechRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        touchHeartbeat()
        when (intent?.action) {
            ACTION_START -> {
                if (!SecurityStateStore.isOnline()) {
                    shouldListen = false
                    stopListening()
                    Log.i(logTag, "Voice trigger blocked: offline")
                    stopForeground(true)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!hasAudioPermission()) {
                    shouldListen = false
                    stopListening()
                    Log.w(logTag, "Microphone permission missing; skipping voice trigger")
                    stopForeground(true)
                    stopSelf()
                    return START_NOT_STICKY
                }
                shouldListen = true
                Log.i(logTag, "Voice trigger armed")
                logger.info("VoiceTriggerService: start voice trigger")
                startListening()
            }
            ACTION_TRIGGER -> {
                Log.i(logTag, "VOICE_KEYWORD_DETECTED keyword=socorro")
                logger.info("VoiceTriggerService: voice trigger detected")
                dispatchKeyword("socorro", forced = false)
            }
            ACTION_STOP -> {
                if (PanicStateManager.isPanicActive()) {
                    Log.w(logTag, "VOICE_STOP_SKIPPED panic_active")
                    return START_STICKY
                }
                shouldListen = false
                stopListening()
                Log.i(logTag, "Voice trigger disarmed")
                logger.info("VoiceTriggerService: stop voice trigger")
                stopForeground(true)
                stopSelf()
            }
            else -> logger.info("VoiceTriggerService: action missing or ignored")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        recognizer?.destroy()
        recognizer = null
        voiceScope.coroutineContext.cancel()
        stopForeground(true)
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(logTag, "SpeechRecognizer unavailable")
            Log.w(logTag, "PANIC_BLOCKED_BY_SYSTEM reason=recognizer_unavailable")
            stopSelf()
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                lastRms = rmsdB
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                isListening = false
                val background = !isAppInForeground()
                Log.w(logTag, "VOICE_RECOGNIZER_ERROR code=$error background=$background")
                val recovery = voiceHealthMonitor.onRecognizerError(error)
                if (recovery.degraded) {
                    Log.w(logTag, "VOICE_HEALTH_DEGRADED reason=${recovery.degradedReason}")
                }
                if (recovery.abortReason != null) {
                    Log.w(logTag, "VOICE_HEALTH_RECOVERY_ABORTED reason=${recovery.abortReason}")
                }
                if (recovery.plan != null) {
                    scheduleRecognizerRecovery(recovery.plan)
                    return
                }
                if (shouldListen) {
                    mainHandler.postDelayed({ startListening() }, 500)
                }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                handleMatches(matches.orEmpty())
                if (shouldListen) {
                    mainHandler.postDelayed({ startListening() }, 300)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                handleMatches(matches.orEmpty())
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("pt", "BR").toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
    }

    private fun startListening() {
        if (isListening || !shouldListen) return
        if (!hasAudioPermission()) {
            Log.w(logTag, "Microphone permission missing; cannot start listening")
            shouldListen = false
            stopSelf()
            return
        }
        if (!SecurityStateStore.isOnline()) {
            Log.i(logTag, "Voice listening skipped: offline")
            shouldListen = false
            stopSelf()
            return
        }
        val intent = listenIntent ?: return
        isListening = true
        voiceHealthMonitor.onListeningStarted()
        Log.i(logTag, "VOICE_LISTENING_STARTED")
        recognizer?.startListening(intent)
    }

    private fun stopListening() {
        isListening = false
        recognizer?.stopListening()
        recognizer?.cancel()
        voiceHealthMonitor.onListeningStopped()
    }

    private fun handleMatches(matches: List<String>) {
        touchHeartbeat()
        val keyword = matches.firstOrNull { keywordRegex.containsMatchIn(it) }
        val hit = keyword != null
        if (!hit) return
        val isBackground = !isAppInForeground()
        Log.i(logTag, "VOLUME_CHECK_SKIPPED")
        if (isBackground) {
            Log.i(logTag, "VOICE_KEYWORD_ACCEPTED_BACKGROUND")
        }
        val now = System.currentTimeMillis()
        detections.addLast(now)
        while (detections.isNotEmpty() && detections.first() < now - WINDOW_MS) {
            detections.removeFirst()
        }
        Log.i(logTag, "VOICE_KEYWORD_DETECTED keyword=${keyword ?: "socorro"}")
        if (detections.size >= REQUIRED_DETECTIONS) {
            dispatchKeyword(keyword ?: "socorro", isBackground)
            detections.clear()
        }
    }

    private fun dispatchKeyword(keyword: String, forced: Boolean) {
        if (forced) {
            Log.i(logTag, "PANIC_TRIGGER_FORCED")
        }
        voiceScope.launch {
            panicVoiceOrchestrator.onKeywordDetected(keyword)
        }
    }

    private fun scheduleRecognizerRecovery(plan: VoiceHealthMonitor.RecoveryPlan) {
        if (recoveryInProgress) return
        recoveryInProgress = true
        voiceHealthMonitor.onRecoveryStarted()
        Log.i(
            logTag,
            "VOICE_HEALTH_RECOVERY_STARTED reason=${plan.reason} delayMs=${plan.delayMs}"
        )
        mainHandler.postDelayed(
            {
                performRecognizerRecovery()
            },
            plan.delayMs
        )
    }

    private fun performRecognizerRecovery() {
        if (!shouldListen) {
            voiceHealthMonitor.onRecoveryAborted()
            Log.w(logTag, "VOICE_HEALTH_RECOVERY_ABORTED reason=not_listening")
            recoveryInProgress = false
            return
        }
        val focusHandle = requestTransientAudioFocus()
        try {
            stopListening()
            recognizer?.destroy()
            recognizer = null
            initSpeechRecognizer()
            if (shouldListen) {
                startListening()
            }
            voiceHealthMonitor.onRecoverySucceeded()
            Log.i(logTag, "VOICE_HEALTH_RECOVERY_SUCCESS")
        } catch (e: Exception) {
            voiceHealthMonitor.onRecoveryAborted()
            Log.w(logTag, "VOICE_HEALTH_RECOVERY_ABORTED reason=exception", e)
        } finally {
            abandonTransientAudioFocus(focusHandle)
            recoveryInProgress = false
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CoreConfig.VOICE_CHANNEL_ID)
            .setSmallIcon(com.qapp.app.R.mipmap.ic_launcher)
            .setContentTitle("Protecao ativa")
            .setContentText("Monitoramento por voz em execucao")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CoreConfig.VOICE_CHANNEL_ID,
                "Protecao por voz",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Canal para monitoramento continuo por voz."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                CoreConfig.VOICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(CoreConfig.VOICE_NOTIFICATION_ID, notification)
        }
        Log.i(logTag, "VOICE_FGS_STARTED")
        Log.i(logTag, "MICROPHONE_PRIORITY_GRANTED")
    }

    private fun vibrateOnce() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150L)
        }
    }

    private fun playConfirmationTone() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        mainHandler.postDelayed({ tone.release() }, 200)
    }

    private fun requestTransientAudioFocus(): Any? {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = android.media.AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ).setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(request)
            request
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            null
        }
    }

    private fun abandonTransientAudioFocus(handle: Any?) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = handle as? android.media.AudioFocusRequest ?: return
            audioManager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAppInForeground(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val pid = android.os.Process.myPid()
        val process = manager.runningAppProcesses?.firstOrNull { it.pid == pid } ?: return false
        return process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    companion object {
        const val ACTION_START = "com.qapp.app.action.VOICE_START"
        const val ACTION_TRIGGER = "com.qapp.app.action.VOICE_TRIGGER"
        const val ACTION_STOP = "com.qapp.app.action.VOICE_STOP"
        private const val WINDOW_MS = 5_000L
        private const val REQUIRED_DETECTIONS = 2

        private val lastHeartbeatAt = AtomicLong(0L)

        fun touchHeartbeat() {
            lastHeartbeatAt.set(System.currentTimeMillis())
        }

        fun getLastHeartbeatAt(): Long = lastHeartbeatAt.get()
    }
}
