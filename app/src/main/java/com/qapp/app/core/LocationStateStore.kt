package com.qapp.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LocationStateStore {

    private const val PREFS_NAME = "qapp_location_state"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val KEY_TIME = "timestamp"

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context
    private val _state = MutableStateFlow<CurrentLocation?>(null)
    val state: StateFlow<CurrentLocation?> = _state.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            _state.value = readFromPrefs()
            initialized = true
        }
    }

    fun update(lat: Double, lng: Double, timestamp: Long) {
        getPrefs()
            .edit()
            .putLong(KEY_TIME, timestamp)
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LNG, java.lang.Double.doubleToRawLongBits(lng))
            .apply()
        _state.value = CurrentLocation(lat = lat, lng = lng, timestamp = timestamp)
    }

    fun get(): CurrentLocation? {
        return _state.value ?: readFromPrefs().also { _state.value = it }
    }

    fun get(maxAgeMs: Long): CurrentLocation? {
        val current = get() ?: return null
        val now = System.currentTimeMillis()
        if (now - current.timestamp > maxAgeMs) {
            clear()
            return null
        }
        return current
    }

    fun clear() {
        getPrefs()
            .edit()
            .remove(KEY_TIME)
            .remove(KEY_LAT)
            .remove(KEY_LNG)
            .apply()
        _state.value = null
    }

    private fun getPrefs() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readFromPrefs(): CurrentLocation? {
        val prefs = getPrefs()
        if (!prefs.contains(KEY_TIME)) return null
        val time = prefs.getLong(KEY_TIME, 0L)
        val lat = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0L))
        val lng = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LNG, 0L))
        return CurrentLocation(lat = lat, lng = lng, timestamp = time)
    }
}

data class CurrentLocation(
    val lat: Double,
    val lng: Double,
    val timestamp: Long
)
