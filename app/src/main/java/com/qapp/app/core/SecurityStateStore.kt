package com.qapp.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SecurityState {
    OFFLINE,
    ONLINE,
    PANIC
}

object SecurityStateStore {

    private const val PREFS_NAME = "qapp_security_state"
    private const val KEY_STATE = "security_state"
    private const val LEGACY_PREFS_NAME = "qapp_online_state"
    private const val LEGACY_KEY_ONLINE = "is_online"

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context

    private val _state = MutableStateFlow(SecurityState.OFFLINE)
    val state: StateFlow<SecurityState> = _state.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_STATE, null)
            val resolved = when (saved) {
                SecurityState.ONLINE.name -> SecurityState.ONLINE
                SecurityState.PANIC.name -> SecurityState.PANIC
                SecurityState.OFFLINE.name -> SecurityState.OFFLINE
                else -> {
                    val legacyPrefs =
                        appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                    if (legacyPrefs.getBoolean(LEGACY_KEY_ONLINE, false)) {
                        SecurityState.ONLINE
                    } else {
                        SecurityState.OFFLINE
                    }
                }
            }
            _state.value = resolved
            initialized = true
        }
    }

    fun getState(): SecurityState = _state.value

    fun setState(state: SecurityState) {
        _state.value = state
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATE, state.name).apply()
        // Keep legacy online flag in sync for older readers.
        val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit().putBoolean(LEGACY_KEY_ONLINE, state != SecurityState.OFFLINE).apply()
    }

    fun isOnline(): Boolean = _state.value != SecurityState.OFFLINE
}
