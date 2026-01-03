package com.qapp.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.qapp.app.R
import com.qapp.app.core.LocationStateStore
import kotlinx.coroutines.delay

@Composable
fun IncomingPanicAlertScreen(
    onDismissAlert: () -> Unit,
    onToggleSound: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: IncomingPanicAlertViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (!uiState.isVisible) {
        return
    }

    val lat = uiState.lat
    val lng = uiState.lng
    val center = if (lat != null && lng != null) {
        LatLng(lat, lng)
    } else {
        LatLng(0.0, 0.0)
    }
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(center, 16f)
    }

    var endHandled by remember { mutableStateOf(false) }
    var receiverLatLng by remember { mutableStateOf<LatLng?>(null) }
    var hasCentered by remember { mutableStateOf(false) }
    var displayedLatLng by remember { mutableStateOf<LatLng?>(null) }

    LaunchedEffect(uiState.isVisible) {
        if (uiState.isVisible) {
            hasCentered = false
            displayedLatLng = null
        }
    }

    LaunchedEffect(lat, lng) {
        if (lat != null && lng != null) {
            if (!hasCentered) {
                cameraState.position = CameraPosition.fromLatLngZoom(LatLng(lat, lng), 16f)
                hasCentered = true
            }
            val target = LatLng(lat, lng)
            val current = displayedLatLng
            if (current == null) {
                displayedLatLng = target
            } else {
                val steps = 18
                for (step in 1..steps) {
                    val fraction = step.toDouble() / steps.toDouble()
                    val interpolated = LatLng(
                        current.latitude + (target.latitude - current.latitude) * fraction,
                        current.longitude + (target.longitude - current.longitude) * fraction
                    )
                    displayedLatLng = interpolated
                    delay(22L)
                }
            }
        }
    }

    LaunchedEffect(uiState.isVisible) {
        while (uiState.isVisible) {
            val current = LocationStateStore.get()
            receiverLatLng = current?.let { LatLng(it.lat, it.lng) }
            delay(2000L)
        }
    }

    LaunchedEffect(uiState.isActive, uiState.isVisible) {
        if (uiState.isVisible && !uiState.isActive && !endHandled) {
            endHandled = true
            Toast.makeText(
                context,
                context.getString(R.string.incoming_alert_ended),
                Toast.LENGTH_SHORT
            ).show()
            delay(2200L)
            viewModel.clearAlert()
            onDismissAlert()
        } else if (uiState.isActive) {
            endHandled = false
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "panic_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 40f,
        targetValue = 120f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_radius"
    )

        Box(
            modifier = modifier.fillMaxSize()
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraState
            ) {
                if (uiState.pathPoints.size >= 2) {
                    Polyline(
                        points = uiState.pathPoints,
                        color = Color(0xFFE53935),
                        width = 8f
                    )
                }
                if (lat != null && lng != null && uiState.isActive) {
                    val markerPosition = displayedLatLng ?: LatLng(lat, lng)
                    Circle(
                        center = markerPosition,
                        radius = pulseRadius.toDouble(),
                        fillColor = Color(0xFFF44336).copy(alpha = pulseAlpha),
                        strokeColor = Color(0x00FFFFFF)
                    )
                    Marker(
                        state = MarkerState(position = markerPosition),
                        rotation = uiState.heading?.toFloat() ?: 0f,
                        flat = uiState.heading != null,
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                    )
                }
                if (receiverLatLng != null && uiState.isActive) {
                    Marker(
                        state = MarkerState(position = receiverLatLng!!),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                    )
                }
        }

        if (uiState.isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .background(
                        color = Color(0xFFD32F2F),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.incoming_alert_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AlertDriverCard(uiState = uiState)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (lat != null && lng != null) {
                            val uri = android.net.Uri.parse("google.navigation:q=$lat,$lng")
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = lat != null && lng != null
                ) {
                    Text(text = "Abrir navegacao")
                }
                OutlinedButton(
                    onClick = {
                        if (lat != null && lng != null) {
                            val text = "${lat}, ${lng}"
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "Localizacao copiada", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = lat != null && lng != null
                ) {
                    Text(text = "Copiar local")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onToggleSound,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.muted) Color(0xFF2E7D32) else Color(0xFF616161)
                    )
                ) {
                    Text(
                        text = if (uiState.muted) "Ativar som" else "Silenciar som",
                        textAlign = TextAlign.Center
                    )
                }
                OutlinedButton(
                    onClick = onDismissAlert,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Encerrar visualizacao")
                }
            }
        }
    }
}

@Composable
private fun AlertDriverCard(
    uiState: IncomingPanicAlertUiState
) {
    val driverName = uiState.driverName.ifBlank {
        stringResource(id = R.string.alert_not_informed)
    }
    val plateLabel = uiState.vehiclePlate.ifBlank {
        stringResource(id = R.string.alert_not_informed)
    }
    val details = listOf(uiState.vehicleColor, uiState.vehicleMake, uiState.vehicleModel)
        .filter { it.isNotBlank() }
        .joinToString(" | ")
        .ifBlank { stringResource(id = R.string.alert_not_informed) }
    val distanceLabel = uiState.distanceLabel.ifBlank { "--" }
    val elapsedLabel = uiState.elapsedLabel.ifBlank { "--" }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.size(88.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color(0xFFFDECEA), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "CAR",
                            color = Color(0xFFD32F2F),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = driverName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = plateLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.lastUpdateLabel.ifBlank { "--" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(id = R.string.incoming_alert_info),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (uiState.isActive) {
                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFD32F2F), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "EM ALERTA",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(id = R.string.incoming_alert_distance, distanceLabel),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(id = R.string.incoming_alert_elapsed, elapsedLabel),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
