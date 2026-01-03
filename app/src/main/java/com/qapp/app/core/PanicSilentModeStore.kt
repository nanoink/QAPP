package com.qapp.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

enum class PanicEventPriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW
}

data class SilentAlertState(
    val lastSoundAt: Long,
    val lastPriority: PanicEventPriority
)

private val Context.panicSilentDataStore by preferencesDataStore("panic_silent_state")

object PanicSilentModeStore {
    private val keyLastSoundAt = longPreferencesKey("last_sound_at")
    private val keyLastPriority = stringPreferencesKey("last_priority")
    private val keySilentEnabled = booleanPreferencesKey("silent_mode_enabled")

    suspend fun getState(context: Context): SilentAlertState? {
        val prefs = context.panicSilentDataStore.data.first()
        val lastSoundAt = prefs[keyLastSoundAt] ?: 0L
        val lastPriorityName = prefs[keyLastPriority]
        if (lastSoundAt <= 0L || lastPriorityName.isNullOrBlank()) {
            return null
        }
        val priority = runCatching {
            PanicEventPriority.valueOf(lastPriorityName)
        }.getOrNull() ?: return null
        return SilentAlertState(
            lastSoundAt = lastSoundAt,
            lastPriority = priority
        )
    }

    suspend fun updateState(context: Context, state: SilentAlertState) {
        context.panicSilentDataStore.edit { prefs ->
            prefs[keyLastSoundAt] = state.lastSoundAt
            prefs[keyLastPriority] = state.lastPriority.name
        }
    }

    suspend fun isSilentModeEnabled(context: Context): Boolean {
        val prefs = context.panicSilentDataStore.data.first()
        return prefs[keySilentEnabled] ?: true
    }

    suspend fun setSilentModeEnabled(context: Context, enabled: Boolean) {
        context.panicSilentDataStore.edit { prefs ->
            prefs[keySilentEnabled] = enabled
        }
    }
}

fun shouldPlaySound(
    priority: PanicEventPriority,
    lastState: SilentAlertState?,
    now: Long
): Boolean {
    if (priority == PanicEventPriority.CRITICAL) {
        return true
    }
    if (lastState == null) {
        return true
    }
    if (priorityRank(priority) > priorityRank(lastState.lastPriority)) {
        return true
    }
    val window = silentWindowMs(priority)
    return now - lastState.lastSoundAt >= window
}

private fun silentWindowMs(priority: PanicEventPriority): Long {
    return when (priority) {
        PanicEventPriority.CRITICAL -> 0L
        PanicEventPriority.HIGH -> 30_000L
        PanicEventPriority.NORMAL -> 2 * 60_000L
        PanicEventPriority.LOW -> 5 * 60_000L
    }
}

private fun priorityRank(priority: PanicEventPriority): Int {
    return when (priority) {
        PanicEventPriority.CRITICAL -> 3
        PanicEventPriority.HIGH -> 2
        PanicEventPriority.NORMAL -> 1
        PanicEventPriority.LOW -> 0
    }
}
