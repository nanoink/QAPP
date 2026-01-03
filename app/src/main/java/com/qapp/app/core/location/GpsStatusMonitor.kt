package com.qapp.app.core.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.qapp.app.core.LogTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class GpsStatus(
    val isLocationAvailable: Boolean,
    val lastFixAt: Long?
)

object GpsStatusMonitor {

    private val initialized = AtomicBoolean(false)
    private lateinit var appContext: Context
    private lateinit var locationManager: LocationManager
    private val _status = MutableStateFlow(GpsStatus(isLocationAvailable = false, lastFixAt = null))
    val status: StateFlow<GpsStatus> = _status.asStateFlow()

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        updateAvailability()
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        appContext.registerReceiver(providerReceiver, filter)
    }

    fun updateFix(location: Location) {
        if (!hasPermission()) return
        val available = isProviderEnabled()
        _status.value = _status.value.copy(
            isLocationAvailable = available,
            lastFixAt = System.currentTimeMillis()
        )
        Log.d(LogTags.GPS, "GPS fix received")
    }

    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateAvailability()
        }
    }

    private fun updateAvailability() {
        val available = hasPermission() && isProviderEnabled()
        val current = _status.value
        if (current.isLocationAvailable != available) {
            _status.value = current.copy(isLocationAvailable = available)
            if (available) {
                Log.i(LogTags.GPS, "GPS available")
            } else {
                Log.w(LogTags.GPS, "GPS unavailable")
            }
        }
    }

    private fun isProviderEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }
}
