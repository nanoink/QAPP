package com.qapp.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.qapp.app.R
import com.qapp.app.ui.AlertLocationStatus
import com.qapp.app.ui.AlertSystemStatus
import com.qapp.app.ui.AlertUiState
import com.qapp.app.ui.AlertVisualState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanicAlertBottomSheet(
    state: AlertUiState,
    onDismiss: () -> Unit,
    onOpenMap: () -> Unit,
    onSilence: () -> Unit,
    onClose: () -> Unit
) {
    if (!state.isVisible) return

    val driverName = state.driver?.name ?: stringResource(id = R.string.alert_not_informed)
    val vehicleMake = state.vehicle?.make ?: ""
    val vehicleModel = state.vehicle?.model ?: ""
    val vehiclePlate = state.vehicle?.plate ?: ""
    val vehicleLabel = listOf(vehicleMake, vehicleModel).filter { it.isNotBlank() }.joinToString(" ")
    val vehicleText = if (vehicleLabel.isBlank()) stringResource(id = R.string.alert_not_informed) else vehicleLabel
    val plateText = if (vehiclePlate.isBlank()) stringResource(id = R.string.alert_not_informed) else vehiclePlate
    val statusText = when (state.visualState) {
        AlertVisualState.IDLE -> stringResource(id = R.string.alert_status_idle)
        AlertVisualState.ALERT_RECEIVED -> stringResource(id = R.string.alert_status_received)
        AlertVisualState.ALERT_ACTIVE -> stringResource(id = R.string.alert_status_active)
        AlertVisualState.ALERT_SILENCED -> stringResource(id = R.string.alert_status_silenced)
        AlertVisualState.ALERT_CLOSED -> stringResource(id = R.string.alert_status_closed)
    }
    val systemStatusText = when (state.systemStatus) {
        AlertSystemStatus.OK -> ""
        AlertSystemStatus.DISCONNECTED -> stringResource(id = R.string.alert_status_disconnected)
    }
    val locationStatusText = when (state.locationStatus) {
        AlertLocationStatus.WAITING -> stringResource(id = R.string.alert_status_location_updating)
        AlertLocationStatus.UPDATING -> stringResource(id = R.string.alert_status_location_updating)
        AlertLocationStatus.UNAVAILABLE -> stringResource(id = R.string.alert_status_location_unavailable)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val highlightScale by animateFloatAsState(
        targetValue = if (state.location != null) 1.02f else 1.0f,
        label = "alert_scale"
    )
    val statusColor = when (state.visualState) {
        AlertVisualState.ALERT_ACTIVE -> MaterialTheme.colorScheme.error
        AlertVisualState.ALERT_RECEIVED -> MaterialTheme.colorScheme.tertiary
        AlertVisualState.ALERT_SILENCED -> MaterialTheme.colorScheme.onSurfaceVariant
        AlertVisualState.ALERT_CLOSED -> MaterialTheme.colorScheme.onSurfaceVariant
        AlertVisualState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(text = stringResource(id = R.string.alert_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(id = R.string.alert_driver, driverName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = stringResource(id = R.string.alert_vehicle, vehicleText))
            Text(text = stringResource(id = R.string.alert_plate, plateText))
            Text(
                text = statusText,
                color = statusColor,
                style = MaterialTheme.typography.titleMedium
            )
            if (state.silentBadgeVisible) {
                Text(
                    text = stringResource(id = R.string.alert_silent_mode_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("silent_badge")
                )
            }
            if (systemStatusText.isNotBlank()) {
                Text(text = systemStatusText, color = MaterialTheme.colorScheme.error)
            }
            Text(text = locationStatusText, color = MaterialTheme.colorScheme.onSurfaceVariant)

            PanicMapPreview(
                location = state.location,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp)
                    .graphicsLayer(scaleX = highlightScale, scaleY = highlightScale)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onOpenMap, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(id = R.string.alert_button_open_map))
                }
                Button(onClick = onSilence, modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.isMuted) {
                            stringResource(id = R.string.alert_button_unsilence)
                        } else {
                            stringResource(id = R.string.alert_button_silence)
                        }
                    )
                }
                Button(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(id = R.string.alert_button_close))
                }
            }
        }
    }
}

@Composable
private fun PanicMapPreview(
    location: com.qapp.app.ui.AlertLocation?,
    modifier: Modifier = Modifier
) {
    if (location == null) return

    val cameraState: CameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(location.lat, location.lng),
            16f
        )
    }

    LaunchedEffect(location) {
        cameraState.position = CameraPosition.fromLatLngZoom(
            LatLng(location.lat, location.lng),
            16f
        )
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraState
    ) {
        Marker(state = MarkerState(position = LatLng(location.lat, location.lng)))
    }
}
