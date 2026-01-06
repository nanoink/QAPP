package com.qapp.app.core

import kotlin.math.min

class VoiceHealthMonitor(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    enum class VoiceHealthState {
        OK,
        DEGRADED,
        RECOVERING
    }

    data class RecoveryPlan(
        val delayMs: Long,
        val reason: String
    )

    data class RecoveryResult(
        val degraded: Boolean,
        val degradedReason: String?,
        val plan: RecoveryPlan?,
        val abortReason: String?
    )

    private val errorTimestamps = ArrayDeque<Long>()
    private val recoveryTimestamps = ArrayDeque<Long>()
    private var listeningStartAt: Long? = null
    private var state: VoiceHealthState = VoiceHealthState.OK
    private var suppressedUntilMs: Long = 0L

    fun onListeningStarted(now: Long = clock()) {
        if (listeningStartAt == null) {
            listeningStartAt = now
        }
    }

    fun onListeningStopped() {
        listeningStartAt = null
    }

    fun onRecognizerError(errorCode: Int, now: Long = clock()): RecoveryResult {
        pruneErrors(now)
        if (errorCode in TRACKED_ERRORS) {
            errorTimestamps.addLast(now)
        }
        if (state == VoiceHealthState.RECOVERING) {
            return RecoveryResult(
                degraded = false,
                degradedReason = null,
                plan = null,
                abortReason = null
            )
        }
        val listeningDurationMs = listeningStartAt?.let { now - it } ?: 0L
        val errorSpike = errorTimestamps.size >= ERROR_THRESHOLD
        val longSession = listeningDurationMs >= CONTINUOUS_LISTEN_MS
        val immediateRecover = errorCode in IMMEDIATE_RECOVERY_ERRORS
        val shouldDegrade = errorSpike || immediateRecover || (longSession && errorCode == ERROR_NO_MATCH)
        if (!shouldDegrade) {
            return RecoveryResult(
                degraded = false,
                degradedReason = null,
                plan = null,
                abortReason = null
            )
        }
        val reason = when {
            immediateRecover -> errorReason(errorCode)
            errorSpike -> "error_spike"
            else -> "continuous_listen"
        }
        val degradedNow = state != VoiceHealthState.DEGRADED
        state = VoiceHealthState.DEGRADED
        if (now < suppressedUntilMs) {
            return RecoveryResult(
                degraded = degradedNow,
                degradedReason = reason,
                plan = null,
                abortReason = "suppressed"
            )
        }
        pruneRecoveries(now)
        if (recoveryTimestamps.size >= MAX_RECOVERIES) {
            suppressedUntilMs = now + RECOVERY_WINDOW_MS
            return RecoveryResult(
                degraded = degradedNow,
                degradedReason = reason,
                plan = null,
                abortReason = "max_recoveries"
            )
        }
        val delayMs = nextRecoveryDelayMs(recoveryTimestamps.size + 1)
        return RecoveryResult(
            degraded = degradedNow,
            degradedReason = reason,
            plan = RecoveryPlan(delayMs = delayMs, reason = reason),
            abortReason = null
        )
    }

    fun onRecoveryStarted(now: Long = clock()) {
        pruneRecoveries(now)
        recoveryTimestamps.addLast(now)
        state = VoiceHealthState.RECOVERING
    }

    fun onRecoverySucceeded(now: Long = clock()) {
        state = VoiceHealthState.OK
        errorTimestamps.clear()
        listeningStartAt = now
    }

    fun onRecoveryAborted() {
        state = VoiceHealthState.DEGRADED
    }

    fun currentState(): VoiceHealthState = state

    private fun pruneErrors(now: Long) {
        while (errorTimestamps.isNotEmpty() && now - errorTimestamps.first() > ERROR_WINDOW_MS) {
            errorTimestamps.removeFirst()
        }
    }

    private fun pruneRecoveries(now: Long) {
        while (recoveryTimestamps.isNotEmpty() && now - recoveryTimestamps.first() > RECOVERY_WINDOW_MS) {
            recoveryTimestamps.removeFirst()
        }
    }

    private fun nextRecoveryDelayMs(attempt: Int): Long {
        val cappedAttempt = if (attempt < 1) 1 else attempt
        val baseDelay = RECOVERY_BASE_DELAY_MS
        val delay = baseDelay * (1L shl (cappedAttempt - 1))
        return min(delay, RECOVERY_MAX_DELAY_MS)
    }

    private fun errorReason(code: Int): String {
        return when (code) {
            ERROR_SERVER_DISCONNECTED -> "server_disconnected"
            ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
            ERROR_CLIENT -> "client_error"
            ERROR_TOO_MANY_REQUESTS -> "too_many_requests"
            else -> "recognizer_error"
        }
    }

    companion object {
        private const val ERROR_NO_MATCH = 7
        private const val ERROR_RECOGNIZER_BUSY = 8
        private const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        private const val ERROR_TOO_MANY_REQUESTS = 10
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_CLIENT = 5
        private const val ERROR_THRESHOLD = 3
        private const val ERROR_WINDOW_MS = 60_000L
        private const val CONTINUOUS_LISTEN_MS = 15 * 60_000L
        private const val MAX_RECOVERIES = 3
        private const val RECOVERY_WINDOW_MS = 10 * 60_000L
        private const val RECOVERY_BASE_DELAY_MS = 3_000L
        private const val RECOVERY_MAX_DELAY_MS = 5_000L

        private val TRACKED_ERRORS = setOf(
            ERROR_NO_MATCH,
            ERROR_RECOGNIZER_BUSY,
            ERROR_TOO_MANY_REQUESTS,
            ERROR_SERVER_DISCONNECTED,
            ERROR_CLIENT
        )

        private val IMMEDIATE_RECOVERY_ERRORS = setOf(
            ERROR_RECOGNIZER_BUSY,
            ERROR_TOO_MANY_REQUESTS,
            ERROR_SERVER_DISCONNECTED,
            ERROR_CLIENT
        )
    }
}
