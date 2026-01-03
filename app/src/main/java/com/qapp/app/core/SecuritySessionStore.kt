package com.qapp.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SecuritySessionState {
    AUTHENTICATED,
    NOT_AUTHENTICATED
}

object SecuritySessionStore {

    private const val PREFS_NAME = "qapp_session_state"
    private const val KEY_STATE = "security_session_state"

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context

    private val _state = MutableStateFlow(SecuritySessionState.NOT_AUTHENTICATED)
    val state: StateFlow<SecuritySessionState> = _state.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_STATE, null)
            val resolved = runCatching {
                SecuritySessionState.valueOf(saved ?: "")
            }.getOrNull() ?: SecuritySessionState.NOT_AUTHENTICATED
            _state.value = resolved
            initialized = true
        }
    }

    fun setState(state: SecuritySessionState) {
        if (!initialized) return
        _state.value = state
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATE, state.name).apply()
    }

    fun isAuthenticated(): Boolean = _state.value == SecuritySessionState.AUTHENTICATED
}
