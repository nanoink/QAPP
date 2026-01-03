package com.qapp.app.core

import android.content.Context

object LocationStateStore {

    private const val PREFS_NAME = "qapp_location_state"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val KEY_TIME = "timestamp"

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
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
    }

    fun get(): CurrentLocation? {
        val prefs = getPrefs()
        if (!prefs.contains(KEY_TIME)) return null
        val time = prefs.getLong(KEY_TIME, 0L)
        val lat = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0L))
        val lng = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LNG, 0L))
        return CurrentLocation(lat = lat, lng = lng, timestamp = time)
    }

    private fun getPrefs() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

data class CurrentLocation(
    val lat: Double,
    val lng: Double,
    val timestamp: Long
)
