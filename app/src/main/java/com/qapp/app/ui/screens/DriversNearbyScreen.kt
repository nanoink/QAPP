package com.qapp.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.qapp.app.core.LocationStateStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriversNearbyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DriversNearbyViewModel = viewModel()
) {
    val context = LocalContext.current
    LocationStateStore.init(context)

    val drivers by viewModel.drivers.collectAsStateWithLifecycle()
    val userLocation by viewModel.userLocation.collectAsStateWithLifecycle()

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f)
    }
    var hasCentered by remember { mutableStateOf(false) }

    LaunchedEffect(userLocation) {
        val location = userLocation ?: return@LaunchedEffect
        if (!hasCentered) {
            cameraState.position = CameraPosition.fromLatLngZoom(
                LatLng(location.lat, location.lng),
                14f
            )
            hasCentered = true
        }
    }

    DisposableEffect(Unit) {
        viewModel.start()
        onDispose {
            viewModel.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Motoristas proximos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraState
            ) {
                val current = userLocation
                if (current != null) {
                    Marker(
                        state = MarkerState(position = LatLng(current.lat, current.lng)),
                        title = "Voce"
                    )
                }
                drivers.forEach { driver ->
                    Marker(
                        state = MarkerState(position = LatLng(driver.lat, driver.lng)),
                        title = driver.name
                    )
                }
            }
        }
    }
}
