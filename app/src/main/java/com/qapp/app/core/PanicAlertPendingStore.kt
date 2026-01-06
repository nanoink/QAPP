package com.qapp.app.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.panicAlertDataStore by preferencesDataStore("qapp_panic_alert_pending")

data class PendingAlert(
    val eventId: String,
    val emitterId: String,
    val timestamp: Long
)

object PanicAlertPendingStore {

    private val keyEventId = stringPreferencesKey("event_id")
    private val keyEmitterId = stringPreferencesKey("emitter_id")
    private val keyTimestamp = longPreferencesKey("timestamp")

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _pending = MutableStateFlow<PendingAlert?>(null)
    val pending: StateFlow<PendingAlert?> = _pending.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            initialized = true
            val initialPrefs = runBlocking { appContext.panicAlertDataStore.data.first() }
            _pending.value = parsePending(initialPrefs)
            scope.launch {
                appContext.panicAlertDataStore.data.collectLatest { prefs ->
                    _pending.value = parsePending(prefs)
                }
            }
        }
    }

    suspend fun save(eventId: String, emitterId: String, timestamp: Long) {
        if (!initialized) return
        appContext.panicAlertDataStore.edit { prefs ->
            prefs[keyEventId] = eventId
            prefs[keyEmitterId] = emitterId
            prefs[keyTimestamp] = timestamp
        }
    }

    suspend fun clear() {
        if (!initialized) return
        appContext.panicAlertDataStore.edit { prefs ->
            prefs.remove(keyEventId)
            prefs.remove(keyEmitterId)
            prefs.remove(keyTimestamp)
        }
    }

    suspend fun clearIfMatches(eventId: String) {
        if (!initialized) return
        val current = _pending.value
        if (current?.eventId == eventId) {
            clear()
        }
    }

    fun current(): PendingAlert? = _pending.value

    private fun parsePending(prefs: androidx.datastore.preferences.core.Preferences): PendingAlert? {
        val eventId = prefs[keyEventId] ?: return null
        val emitterId = prefs[keyEmitterId] ?: return null
        val timestamp = prefs[keyTimestamp] ?: return null
        return PendingAlert(eventId = eventId, emitterId = emitterId, timestamp = timestamp)
    }
}
