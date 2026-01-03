package com.qapp.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

data class OverlayPolicyState(
    val attemptsCount: Int,
    val lastAttemptAt: Long,
    val blockedByPolicy: Boolean
)

private val Context.overlayPolicyDataStore by preferencesDataStore("overlay_policy")

object OverlayPolicyStore {
    private val keyAttempts = intPreferencesKey("overlay_attempts_count")
    private val keyLastAttempt = longPreferencesKey("last_overlay_attempt_at")
    private val keyBlocked = booleanPreferencesKey("overlay_blocked_by_policy")

    suspend fun getState(context: Context): OverlayPolicyState {
        val prefs = context.overlayPolicyDataStore.data.first()
        return OverlayPolicyState(
            attemptsCount = prefs[keyAttempts] ?: 0,
            lastAttemptAt = prefs[keyLastAttempt] ?: 0L,
            blockedByPolicy = prefs[keyBlocked] ?: false
        )
    }

    suspend fun recordAttempt(context: Context): OverlayPolicyState {
        val now = System.currentTimeMillis()
        context.overlayPolicyDataStore.edit { prefs ->
            val next = (prefs[keyAttempts] ?: 0) + 1
            prefs[keyAttempts] = next
            prefs[keyLastAttempt] = now
        }
        return getState(context)
    }

    suspend fun updateBlockedIfNeeded(
        context: Context,
        overlayGranted: Boolean
    ): OverlayPolicyState {
        val current = getState(context)
        val shouldBlock = !overlayGranted && current.attemptsCount >= 2
        if (current.blockedByPolicy != shouldBlock) {
            context.overlayPolicyDataStore.edit { prefs ->
                prefs[keyBlocked] = shouldBlock
            }
        }
        if (overlayGranted && current.blockedByPolicy) {
            context.overlayPolicyDataStore.edit { prefs ->
                prefs[keyBlocked] = false
            }
        }
        return getState(context)
    }
}
