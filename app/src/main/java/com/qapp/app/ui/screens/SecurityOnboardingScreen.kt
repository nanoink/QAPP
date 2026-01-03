package com.qapp.app.ui.screens

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qapp.app.R
import com.qapp.app.core.PermissionStateManager
import com.qapp.app.core.PermissionStateManager.PermissionStep
import com.qapp.app.core.SecurityOnboardingStore

@Composable
fun SecurityOnboardingScreen(
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionsViewModel: PermissionsViewModel = viewModel()
    val permissionState by permissionsViewModel.permissionState.collectAsStateWithLifecycle()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf(false) }

    val onCompletedAction: () -> Unit = {
        if (!completed) {
            completed = true
            SecurityOnboardingStore.markCompleted(context, true)
            onCompleted()
        }
    }

    LaunchedEffect(permissionState) {
        handleRefreshResult(
            updated = permissionState,
            isCompleted = completed,
            setStatusMessage = { statusMessage = it },
            onCompleted = onCompletedAction
        )
    }

    val foregroundLocationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                statusMessage = context.getString(R.string.security_onboarding_retry)
                return@rememberLauncherForActivityResult
            }
            val updated = permissionsViewModel.refreshPermissions(context)
            handleRefreshResult(
                updated = updated,
                isCompleted = completed,
                setStatusMessage = { statusMessage = it },
                onCompleted = onCompletedAction
            )
        }

    val backgroundLocationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                statusMessage = context.getString(R.string.security_onboarding_retry)
                return@rememberLauncherForActivityResult
            }
            val updated = permissionsViewModel.refreshPermissions(context)
            handleRefreshResult(
                updated = updated,
                isCompleted = completed,
                setStatusMessage = { statusMessage = it },
                onCompleted = onCompletedAction
            )
        }

    val microphoneLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                statusMessage = context.getString(R.string.security_onboarding_retry)
                return@rememberLauncherForActivityResult
            }
            val updated = permissionsViewModel.refreshPermissions(context)
            handleRefreshResult(
                updated = updated,
                isCompleted = completed,
                setStatusMessage = { statusMessage = it },
                onCompleted = onCompletedAction
            )
        }

    LaunchedEffect(Unit) {
        val updated = permissionsViewModel.refreshPermissions(context)
        handleRefreshResult(
            updated = updated,
            isCompleted = completed,
            setStatusMessage = { statusMessage = it },
            onCompleted = onCompletedAction
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Log.d("PERMISSIONS", "Permissions refreshed onResume")
                val updated = permissionsViewModel.refreshPermissions(context)
                handleRefreshResult(
                    updated = updated,
                    isCompleted = completed,
                    setStatusMessage = { statusMessage = it },
                    onCompleted = onCompletedAction
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier.statusBarsPadding()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(id = R.string.security_onboarding_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(id = R.string.security_onboarding_intro),
                style = MaterialTheme.typography.bodyMedium
            )
            PermissionItem(
                title = stringResource(id = R.string.security_permission_location_title),
                description = stringResource(id = R.string.security_permission_location_desc),
                granted = permissionState.locationForeground && permissionState.locationBackground
            )
            Text(
                text = "Selecione \"Permitir o tempo todo\" quando o sistema solicitar a localizacao.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PermissionItem(
                title = stringResource(id = R.string.security_permission_microphone_title),
                description = stringResource(id = R.string.security_permission_microphone_desc),
                granted = permissionState.microphone
            )
            Text(
                text = stringResource(id = R.string.security_onboarding_reinforcement),
                style = MaterialTheme.typography.bodyMedium
            )
            if (!statusMessage.isNullOrBlank() && !permissionState.areAllCriticalPermissionsGranted) {
                Text(
                    text = statusMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = {
                    statusMessage = null
                    val updated = permissionsViewModel.refreshPermissions(context)
                    handleNextMissingPermission(
                        context = context,
                        setStatusMessage = { statusMessage = it },
                        foregroundLocationLauncher = foregroundLocationLauncher,
                        backgroundLocationLauncher = backgroundLocationLauncher,
                        microphoneLauncher = microphoneLauncher,
                        onCompleted = onCompletedAction
                    )
                    handleRefreshResult(
                        updated = updated,
                        isCompleted = completed,
                        setStatusMessage = { statusMessage = it },
                        onCompleted = onCompletedAction
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(id = R.string.security_onboarding_button))
            }
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    granted: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .background(
                    if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    CircleShape
                )
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            text = if (granted) "✓" else "—",
            style = MaterialTheme.typography.titleSmall,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun handleNextMissingPermission(
    context: Context,
    setStatusMessage: (String?) -> Unit,
    foregroundLocationLauncher: ActivityResultLauncher<String>,
    backgroundLocationLauncher: ActivityResultLauncher<String>,
    microphoneLauncher: ActivityResultLauncher<String>,
    onCompleted: () -> Unit
) {
    val next = PermissionStateManager.getNextPendingPermission(context)
    Log.d("PERMISSIONS", "Next step: $next")
    when (next) {
        PermissionStep.LOCATION_BACKGROUND -> {
            val state = PermissionStateManager.check(context)
            Log.d(
                "PERMISSIONS",
                "Granted: locationForeground=${state.locationForeground}, locationBackground=${state.locationBackground}"
            )
            if (!state.locationForeground) {
                foregroundLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                return
            }
            setStatusMessage(context.getString(R.string.security_location_background_required))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        PermissionStep.MICROPHONE -> {
            val state = PermissionStateManager.check(context)
            Log.d("PERMISSIONS", "Granted: microphone=${state.microphone}")
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        PermissionStep.COMPLETED -> {
            Log.d("PERMISSIONS", "Granted: all")
            onCompleted()
        }
    }
}

private fun handleRefreshResult(
    updated: com.qapp.app.core.CriticalPermissionState,
    isCompleted: Boolean,
    setStatusMessage: (String?) -> Unit,
    onCompleted: () -> Unit
) {
    if (updated.areAllCriticalPermissionsGranted && !isCompleted) {
        setStatusMessage(null)
        onCompleted()
    } else if (!updated.areAllCriticalPermissionsGranted) {
        setStatusMessage(null)
    }
}
