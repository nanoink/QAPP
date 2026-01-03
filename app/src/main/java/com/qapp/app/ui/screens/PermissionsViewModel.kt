package com.qapp.app.ui.screens

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qapp.app.core.CriticalPermissionState
import com.qapp.app.core.OverlayPolicyState
import com.qapp.app.core.OverlayPolicyStore
import com.qapp.app.core.PermissionStateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PermissionsViewModel : ViewModel() {
    private val _permissionState = MutableStateFlow(
        CriticalPermissionState(
            locationForeground = false,
            locationBackground = false,
            batteryOptimizationIgnored = false,
            overlay = false,
            microphone = false
        )
    )
    val permissionState: StateFlow<CriticalPermissionState> = _permissionState.asStateFlow()

    private val _overlayPolicyState = MutableStateFlow(
        OverlayPolicyState(
            attemptsCount = 0,
            lastAttemptAt = 0L,
            blockedByPolicy = false
        )
    )
    val overlayPolicyState: StateFlow<OverlayPolicyState> = _overlayPolicyState.asStateFlow()

    fun refreshPermissions(context: Context): CriticalPermissionState {
        val updated = PermissionStateManager.check(context)
        _permissionState.value = updated
        viewModelScope.launch {
            val policy = OverlayPolicyStore.updateBlockedIfNeeded(context, updated.overlay)
            _overlayPolicyState.value = policy
            Log.d("PERMISSIONS", "OVERLAY blockedByPolicy = ${policy.blockedByPolicy}")
        }
        Log.d(
            "PERMISSIONS",
            "Granted: locationForeground=${updated.locationForeground}, " +
                "locationBackground=${updated.locationBackground}, " +
                "batteryIgnored=${updated.batteryOptimizationIgnored}, " +
                "overlay=${updated.overlay}, microphone=${updated.microphone}"
        )
        Log.d("PERMISSIONS", "Overlay granted = ${updated.overlay}")
        Log.d("PERMISSIONS", "Next step: ${PermissionStateManager.getNextPendingPermission(context)}")
        return updated
    }
}
