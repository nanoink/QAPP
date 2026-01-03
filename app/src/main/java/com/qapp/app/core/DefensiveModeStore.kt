package com.qapp.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

data class DefensiveModeState(
    val enabled: Boolean,
    val lastChangedAt: Long,
    val lastReason: String? = null
)

private val Context.defensiveModeDataStore by preferencesDataStore("defensive_mode")

object DefensiveModeStore {
    private val keyEnabled = booleanPreferencesKey("defensive_enabled")
    private val keyLastChangedAt = longPreferencesKey("defensive_last_changed_at")
    private val keyLastReason = stringPreferencesKey("defensive_last_reason")

    suspend fun getState(context: Context): DefensiveModeState {
        val prefs = context.defensiveModeDataStore.data.first()
        return DefensiveModeState(
            enabled = prefs[keyEnabled] ?: false,
            lastChangedAt = prefs[keyLastChangedAt] ?: 0L,
            lastReason = prefs[keyLastReason]
        )
    }

    suspend fun setState(context: Context, state: DefensiveModeState) {
        context.defensiveModeDataStore.edit { prefs ->
            prefs[keyEnabled] = state.enabled
            prefs[keyLastChangedAt] = state.lastChangedAt
            if (state.lastReason.isNullOrBlank()) {
                prefs.remove(keyLastReason)
            } else {
                prefs[keyLastReason] = state.lastReason
            }
        }
    }
}
