package com.qapp.app.core.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import android.annotation.SuppressLint
import androidx.core.content.ContextCompat
import com.qapp.app.core.LogTags
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class ConnectivityStatus(
    val isOnline: Boolean,
    val lastChangedAt: Long
)

object ConnectivityMonitor {

    private val initialized = AtomicBoolean(false)
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var appContext: Context
    private val _status = MutableStateFlow(
        ConnectivityStatus(isOnline = false, lastChangedAt = 0L)
    )
    val status: StateFlow<ConnectivityStatus> = _status.asStateFlow()

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        updateStatus(readCurrentStatus())
        registerCallback()
    }

    fun isOnline(): Boolean = _status.value.isOnline

    private fun registerCallback() {
        if (!hasNetworkPermission()) {
            Log.w(LogTags.CONNECTIVITY, "NETWORK_STATE_PERMISSION_MISSING")
            updateStatus(false)
            return
        }
        Log.i(LogTags.CONNECTIVITY, "NETWORK_STATE_PERMISSION_GRANTED")
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateStatus(true)
            }

            override fun onLost(network: Network) {
                updateStatus(readCurrentStatus())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                updateStatus(hasInternet(networkCapabilities))
            }
        }
        try {
            @SuppressLint("MissingPermission")
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            Log.w(LogTags.CONNECTIVITY, "NETWORK_STATE_PERMISSION_MISSING", e)
            updateStatus(false)
        } catch (_: Exception) {
            val request = NetworkRequest.Builder().build()
            try {
                @SuppressLint("MissingPermission")
                connectivityManager.registerNetworkCallback(request, callback)
            } catch (e: SecurityException) {
                Log.w(LogTags.CONNECTIVITY, "NETWORK_STATE_PERMISSION_MISSING", e)
                updateStatus(false)
            } catch (e: Exception) {
                Log.w(LogTags.CONNECTIVITY, "NETWORK_CALLBACK_REGISTER_FAILED", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readCurrentStatus(): Boolean {
        if (!hasNetworkPermission()) {
            Log.w(LogTags.CONNECTIVITY, "NETWORK_STATE_PERMISSION_MISSING")
            return false
        }
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            hasInternet(capabilities)
        } catch (e: SecurityException) {
            Log.w(LogTags.CONNECTIVITY, "NETWORK_STATE_PERMISSION_MISSING", e)
            false
        }
    }

    private fun hasInternet(capabilities: NetworkCapabilities): Boolean {
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun updateStatus(isOnline: Boolean) {
        val current = _status.value
        if (current.isOnline == isOnline) return
        _status.value = ConnectivityStatus(
            isOnline = isOnline,
            lastChangedAt = System.currentTimeMillis()
        )
        if (isOnline) {
            Log.i(LogTags.CONNECTIVITY, "Network connected")
        } else {
            Log.w(LogTags.CONNECTIVITY, "Network disconnected")
        }
    }

    private fun hasNetworkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_NETWORK_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
