package com.qapp.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DriverIdentity(
    val name: String
)

object DriverIdentityStore {
    private val _state = MutableStateFlow<DriverIdentity?>(null)
    val state: StateFlow<DriverIdentity?> = _state.asStateFlow()

    fun setName(name: String?) {
        _state.value = if (name.isNullOrBlank()) null else DriverIdentity(name)
    }

    fun getName(): String? = _state.value?.name

    fun clear() {
        _state.value = null
    }
}
