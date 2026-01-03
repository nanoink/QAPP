package com.qapp.app.core

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
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

private val Context.panicStateDataStore by preferencesDataStore("qapp_panic_state")

object PanicStateManager {

    private val keyActive = booleanPreferencesKey("panic_active")
    private val keyPending = booleanPreferencesKey("panic_pending")
    private val keyEventId = stringPreferencesKey("panic_event_id")
    private val keySource = stringPreferencesKey("panic_source")
    private val keyActivatedAt = longPreferencesKey("panic_activated_at")
    private val keyState = stringPreferencesKey("panic_state")

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PanicStatus())
    val state: StateFlow<PanicStatus> = _state.asStateFlow()
    private var restoreLogged = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            initialized = true
            val initialPrefs = runBlocking {
                appContext.panicStateDataStore.data.first()
            }
            val initialEventId = initialPrefs[keyEventId]
            val legacyActive = initialPrefs[keyActive] ?: false
            val resolvedState = resolveState(
                saved = initialPrefs[keyState],
                legacyActive = legacyActive,
                eventId = initialEventId
            )
            var initialStatus = PanicStatus(
                state = resolvedState,
                source = initialPrefs[keySource],
                activatedAt = (initialPrefs[keyActivatedAt] ?: 0L).takeIf { it > 0L },
                pending = initialPrefs[keyPending] ?: false,
                eventId = initialEventId
            )
            if ((resolvedState == PanicState.ACTIVE || resolvedState == PanicState.FINALIZING) &&
                initialEventId.isNullOrBlank()
            ) {
                transitionTo(PanicState.ACTIVATING, "missing_event_id")
                initialStatus = initialStatus.copy(state = PanicState.ACTIVATING)
                scope.launch {
                    appContext.panicStateDataStore.edit { prefs ->
                        prefs[keyState] = PanicState.ACTIVATING.name
                    }
                }
            }
            _state.value = initialStatus
            if (initialStatus.isActive || !initialStatus.eventId.isNullOrBlank()) {
                val idLog = _state.value.eventId ?: "missing"
                Log.i(
                    "QAPP_PANIC",
                    "PANIC_STATE_RESTORED id=$idLog " +
                        "state=${_state.value.state} source=${_state.value.source}"
                )
                restoreLogged = true
            }
            scope.launch {
                appContext.panicStateDataStore.data.collectLatest { prefs ->
                    val eventId = prefs[keyEventId]
                    val status = PanicStatus(
                        state = resolveState(
                            saved = prefs[keyState],
                            legacyActive = prefs[keyActive] ?: false,
                            eventId = eventId
                        ),
                        source = prefs[keySource],
                        activatedAt = (prefs[keyActivatedAt] ?: 0L).takeIf { it > 0L },
                        pending = prefs[keyPending] ?: false,
                        eventId = eventId
                    )
                    _state.value = status
                    if (!restoreLogged &&
                        (status.isActive || !status.eventId.isNullOrBlank())
                    ) {
                        val idLog = status.eventId ?: "missing"
                        Log.i(
                            "QAPP_PANIC",
                            "PANIC_STATE_RESTORED id=$idLog " +
                                "state=${status.state} source=${status.source}"
                        )
                        restoreLogged = true
                    }
                }
            }
        }
    }

    fun isPanicActive(): Boolean = _state.value.isActive

    fun getState(): PanicState = _state.value.state

    fun activatePanic(reason: String) {
        if (!initialized) return
        val normalized = if (reason.lowercase().contains("voice")) "voice" else "button"
        val now = System.currentTimeMillis()
        transitionTo(PanicState.ACTIVATING, "activate")
        _state.value = _state.value.copy(
            state = PanicState.ACTIVATING,
            source = normalized,
            activatedAt = now,
            pending = false,
            eventId = null
        )
        scope.launch {
            appContext.panicStateDataStore.edit { prefs ->
                prefs[keyActive] = true
                prefs[keyState] = PanicState.ACTIVATING.name
                prefs[keySource] = normalized
                prefs[keyActivatedAt] = now
                prefs[keyPending] = false
                prefs.remove(keyEventId)
            }
        }
    }

    fun deactivatePanic() {
        if (!initialized) return
        transitionTo(PanicState.IDLE, "deactivate")
        _state.value = PanicStatus()
        scope.launch {
            appContext.panicStateDataStore.edit { prefs ->
                prefs[keyActive] = false
                prefs[keyPending] = false
                prefs[keyState] = PanicState.IDLE.name
                prefs.remove(keyEventId)
                prefs.remove(keySource)
                prefs.remove(keyActivatedAt)
            }
        }
    }

    fun setPending(pending: Boolean) {
        if (!initialized) return
        val current = _state.value
        val nextState = if (pending && current.state == PanicState.IDLE) {
            PanicState.ACTIVATING
        } else {
            current.state
        }
        if (nextState != current.state) {
            transitionTo(nextState, "pending")
        }
        _state.value = current.copy(pending = pending, state = nextState)
        scope.launch {
            appContext.panicStateDataStore.edit { prefs ->
                prefs[keyPending] = pending
                if (nextState != current.state) {
                    prefs[keyState] = nextState.name
                }
            }
        }
    }

    fun isPending(): Boolean = _state.value.pending

    fun setActiveEventId(eventId: String?) {
        if (!initialized) return
        val nextState = if (eventId.isNullOrBlank()) {
            _state.value.state
        } else {
            PanicState.ACTIVE
        }
        if (nextState != _state.value.state) {
            transitionTo(nextState, "event_id")
        }
        val updated = _state.value.copy(
            state = nextState,
            eventId = eventId,
            pending = if (eventId.isNullOrBlank()) _state.value.pending else false
        )
        _state.value = updated
        scope.launch {
            appContext.panicStateDataStore.edit { prefs ->
                if (eventId.isNullOrBlank()) {
                    prefs.remove(keyEventId)
                } else {
                    prefs[keyEventId] = eventId
                    prefs[keyPending] = false
                }
                prefs[keyState] = updated.state.name
                prefs[keyActive] = updated.isActive
            }
        }
    }

    fun getActiveEventId(): String? = _state.value.eventId

    fun markFinalizing(reason: String) {
        if (!initialized) return
        transitionTo(PanicState.FINALIZING, reason)
        _state.value = _state.value.copy(state = PanicState.FINALIZING)
        scope.launch {
            appContext.panicStateDataStore.edit { prefs ->
                prefs[keyState] = PanicState.FINALIZING.name
            }
        }
    }

    private fun transitionTo(state: PanicState, reason: String) {
        val from = _state.value.state
        if (from == state) return
        Log.i("QAPP_PANIC", "PANIC_STATE_TRANSITION from=$from to=$state reason=$reason")
    }

    private fun resolveState(saved: String?, legacyActive: Boolean, eventId: String?): PanicState {
        val parsed = runCatching { PanicState.valueOf(saved ?: "") }.getOrNull()
        if (parsed != null) return parsed
        return if (legacyActive || !eventId.isNullOrBlank()) {
            if (eventId.isNullOrBlank()) PanicState.ACTIVATING else PanicState.ACTIVE
        } else {
            PanicState.IDLE
        }
    }
}

enum class PanicState {
    IDLE,
    ACTIVATING,
    ACTIVE,
    FINALIZING
}

data class PanicStatus(
    val state: PanicState = PanicState.IDLE,
    val source: String? = null,
    val activatedAt: Long? = null,
    val pending: Boolean = false,
    val eventId: String? = null
) {
    val isActive: Boolean
        get() = state != PanicState.IDLE
}
