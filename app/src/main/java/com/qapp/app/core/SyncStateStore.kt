package com.qapp.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SyncStateStore {

    private val _lastSyncAt = MutableStateFlow<Long?>(null)
    val lastSyncAt: StateFlow<Long?> = _lastSyncAt.asStateFlow()

    fun updateSyncTime(timestamp: Long) {
        _lastSyncAt.value = timestamp
    }
}
