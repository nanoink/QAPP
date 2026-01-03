package com.qapp.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object DefensiveModeManager {

    private const val TAG = "DEFENSIVE_MODE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val failures = ArrayDeque<Long>()
    private var lastFailureAt: Long = 0L
    private var initialized = false
    private lateinit var appContext: Context

    private val _state = MutableStateFlow(DefensiveModeState(enabled = false, lastChangedAt = 0L))
    val state: StateFlow<DefensiveModeState> = _state.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            initialized = true
            scope.launch {
                val stored = DefensiveModeStore.getState(appContext)
                _state.value = stored
            }
        }
    }

    fun isEnabled(): Boolean = _state.value.enabled

    fun recordServiceFailure(label: String) {
        recordFailure("service_failure:$label", critical = false)
    }

    fun recordRealtimeCrash() {
        recordFailure("realtime_crash", critical = true)
    }

    fun recordSerializationError() {
        recordFailure("serialization_error", critical = true)
    }

    fun onHealthStatus(status: ServiceHealthStatus) {
        if (status.isUnstable) {
            recordFailure("service_unstable", critical = false)
        } else {
            evaluateRecovery()
        }
    }

    fun onRealtimeConnected() {
        evaluateRecovery()
    }

    private fun recordFailure(reason: String, critical: Boolean) {
        if (!initialized) return
        val now = System.currentTimeMillis()
        lastFailureAt = now
        if (critical) {
            enable(reason, now)
            return
        }
        failures.addLast(now)
        pruneFailures(now)
        if (failures.size >= FAILURE_THRESHOLD) {
            enable(reason, now)
        }
    }

    private fun enable(reason: String, now: Long) {
        val current = _state.value
        if (current.enabled) {
            return
        }
        val next = DefensiveModeState(enabled = true, lastChangedAt = now, lastReason = reason)
        _state.value = next
        scope.launch {
            DefensiveModeStore.setState(appContext, next)
        }
        Log.e(TAG, "Defensive mode enabled reason=$reason")
    }

    private fun disable(reason: String, now: Long) {
        val current = _state.value
        if (!current.enabled) {
            return
        }
        val next = DefensiveModeState(enabled = false, lastChangedAt = now, lastReason = reason)
        _state.value = next
        scope.launch {
            DefensiveModeStore.setState(appContext, next)
        }
        Log.i(TAG, "Defensive mode disabled reason=$reason")
    }

    private fun evaluateRecovery() {
        if (!initialized) return
        val now = System.currentTimeMillis()
        if (!_state.value.enabled) return
        val stableForMs = now - lastFailureAt
        val realtimeOk = RealtimeStateStore.state.value.isConnected
        if (stableForMs >= RECOVERY_STABLE_MS && realtimeOk) {
            disable(reason = "stable", now = now)
        }
    }

    private fun pruneFailures(now: Long) {
        while (failures.isNotEmpty() && now - failures.first() > FAILURE_WINDOW_MS) {
            failures.removeFirst()
        }
    }

    private const val FAILURE_THRESHOLD = 3
    private const val FAILURE_WINDOW_MS = 10 * 60 * 1000L
    private const val RECOVERY_STABLE_MS = 5 * 60 * 1000L
}
