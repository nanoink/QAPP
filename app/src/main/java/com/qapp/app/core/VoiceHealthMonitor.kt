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
        if (errorCode == ERROR_NO_MATCH) {
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
        val shouldDegrade = errorSpike || (longSession && errorCode == ERROR_NO_MATCH)
        if (!shouldDegrade) {
            return RecoveryResult(
                degraded = false,
                degradedReason = null,
                plan = null,
                abortReason = null
            )
        }
        val reason = if (errorSpike) "error_spike" else "continuous_listen"
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

    companion object {
        private const val ERROR_NO_MATCH = 7
        private const val ERROR_THRESHOLD = 3
        private const val ERROR_WINDOW_MS = 60_000L
        private const val CONTINUOUS_LISTEN_MS = 15 * 60_000L
        private const val MAX_RECOVERIES = 3
        private const val RECOVERY_WINDOW_MS = 10 * 60_000L
        private const val RECOVERY_BASE_DELAY_MS = 3_000L
        private const val RECOVERY_MAX_DELAY_MS = 5_000L
    }
}
