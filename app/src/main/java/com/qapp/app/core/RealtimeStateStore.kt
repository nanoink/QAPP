package com.qapp.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RealtimeState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED_FATAL
}

data class RealtimeConnectionState(
    val state: RealtimeState,
    val lastChangedAt: Long
) {
    val isConnected: Boolean
        get() = state == RealtimeState.CONNECTED
}

object RealtimeStateStore {

    private val _state =
        MutableStateFlow(RealtimeConnectionState(state = RealtimeState.DISCONNECTED, lastChangedAt = 0L))
    val state: StateFlow<RealtimeConnectionState> = _state.asStateFlow()

    fun updateState(state: RealtimeState) {
        val current = _state.value
        if (current.state == state) return
        _state.value =
            RealtimeConnectionState(state = state, lastChangedAt = System.currentTimeMillis())
    }

    fun updateConnected(connected: Boolean) {
        val next = if (connected) RealtimeState.CONNECTED else RealtimeState.DISCONNECTED
        updateState(next)
    }
}
