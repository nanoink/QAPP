package com.qapp.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qapp.app.alerts.PanicAlert
import com.qapp.app.nearby.NearbyDriversRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppPanicBottomSheet(
    alert: PanicAlert?,
    onDismiss: () -> Unit
) {
    if (alert == null) return

    val driverName = NearbyDriversRegistry.get()
        .firstOrNull { it.id == alert.emitterId }
        ?.name

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(text = "Alerta de panico")
            Text(text = "Motorista: ${driverName ?: alert.emitterId}")
            Text(text = "Lat: ${alert.lat}")
            Text(text = "Lng: ${alert.lng}")
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text(text = "Fechar")
            }
        }
    }
}

