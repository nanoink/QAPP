package com.qapp.app.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.heatmaps.HeatmapTileProvider
import com.google.maps.android.heatmaps.WeightedLatLng
import com.qapp.app.data.repository.HeatmapPoint
import com.qapp.app.data.repository.HeatmapRepository
import com.qapp.app.ui.AlertLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapFullScreenScreen(
    location: AlertLocation?,
    title: String,
    onBack: () -> Unit
) {
    val heatmapRepository = remember { HeatmapRepository() }
    val scope = rememberCoroutineScope()
    var heatmapEnabled by remember { mutableStateOf(false) }
    var heatmapPoints by remember { mutableStateOf<List<HeatmapPoint>>(emptyList()) }
    var heatmapOverlay by remember { mutableStateOf<TileOverlay?>(null) }
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(location?.lat ?: 0.0, location?.lng ?: 0.0),
            16f
        )
    }
    LaunchedEffect(location) {
        if (location != null) {
            cameraState.position = CameraPosition.fromLatLngZoom(
                LatLng(location.lat, location.lng),
                16f
            )
        }
    }

    LaunchedEffect(heatmapEnabled) {
        if (!heatmapEnabled) {
            heatmapPoints = emptyList()
            return@LaunchedEffect
        }
        val initialTarget = cameraState.position.target
        scope.launch(Dispatchers.IO) {
            val points = heatmapRepository.loadHeatmap(
                centerLat = initialTarget.latitude,
                centerLng = initialTarget.longitude,
                radiusKm = HEATMAP_RADIUS_KM
            )
            withContext(Dispatchers.Main) {
                heatmapPoints = points
            }
        }
        snapshotFlow { cameraState.isMoving }
            .distinctUntilChanged()
            .filter { moving -> !moving }
            .collect {
                val target = cameraState.position.target
                scope.launch(Dispatchers.IO) {
                    val points = heatmapRepository.loadHeatmap(
                        centerLat = target.latitude,
                        centerLng = target.longitude,
                        radiusKm = HEATMAP_RADIUS_KM
                    )
                    withContext(Dispatchers.Main) {
                        heatmapPoints = points
                    }
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                actions = {
                    TextButton(onClick = { heatmapEnabled = !heatmapEnabled }) {
                        Text(text = if (heatmapEnabled) "Heatmap ON" else "Heatmap OFF")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(text = "Voltar")
                    }
                }
            )
        }
    ) { paddingValues ->
        GoogleMap(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            cameraPositionState = cameraState
        ) {
            if (location != null) {
                Marker(state = MarkerState(position = LatLng(location.lat, location.lng)))
            }
        }
        MapEffect(heatmapEnabled, heatmapPoints) { map ->
            heatmapOverlay?.remove()
            heatmapOverlay = null
            if (!heatmapEnabled || heatmapPoints.isEmpty()) {
                return@MapEffect
            }
            val weighted = heatmapPoints.map {
                WeightedLatLng(LatLng(it.lat, it.lng), it.weight.toDouble())
            }
            val provider = HeatmapTileProvider.Builder()
                .weightedData(weighted)
                .build()
            heatmapOverlay = map.addTileOverlay(
                TileOverlayOptions().tileProvider(provider)
            )
        }
    }
}

private const val HEATMAP_RADIUS_KM = 10.0
