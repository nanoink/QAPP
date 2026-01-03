package com.qapp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qapp.app.data.repository.VehicleRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehiclesScreen(
    onBack: () -> Unit,
    onAddVehicle: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VehiclesViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Meus veiculos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onAddVehicle,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Adicionar veiculo")
            }

            if (!uiState.message.isNullOrBlank()) {
                Text(
                    text = uiState.message.orEmpty(),
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (uiState.vehicles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF1F1F1), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Text(text = "Nenhum veiculo cadastrado")
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.vehicles, key = { it.id }) { vehicle ->
                        VehicleCard(
                            vehicle = vehicle,
                            onEdit = { viewModel.openEdit(vehicle) },
                            onActivate = { viewModel.setActive(vehicle.id) }
                        )
                    }
                }
            }
        }
    }

    val form = uiState.form
    if (form != null) {
        VehicleFormDialog(
            form = form,
            onDismiss = viewModel::dismissForm,
            onSave = viewModel::saveVehicle,
            onUpdate = viewModel::updateForm
        )
    }
}

@Composable
private fun VehicleCard(
    vehicle: VehicleRecord,
    onEdit: () -> Unit,
    onActivate: () -> Unit
) {
    val statusLabel = if (vehicle.isActive == true) {
        "Em uso agora"
    } else {
        "Inativo"
    }
    val statusColor = if (vehicle.isActive == true) Color(0xFF2E7D32) else Color(0xFF9E9E9E)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${vehicle.make} ${vehicle.model}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "${vehicle.color} • ${vehicle.plate}")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusLabel,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
                if (vehicle.isActive != true) {
                    Button(onClick = onActivate) {
                        Text(text = "Usar agora")
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleFormDialog(
    form: VehicleFormState,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onUpdate: (VehicleFormState) -> Unit
) {
    val confirmLabel = if (form.useNow) "Salvar e usar agora" else "Salvar"
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onSave) { Text(text = confirmLabel) }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text(text = "Cancelar") }
        },
        title = { Text(text = if (form.id == null) "Novo veiculo" else "Editar veiculo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = form.make,
                    onValueChange = { onUpdate(form.copy(make = it, error = null)) },
                    label = { Text(text = "Marca") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.model,
                    onValueChange = { onUpdate(form.copy(model = it, error = null)) },
                    label = { Text(text = "Modelo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.color,
                    onValueChange = { onUpdate(form.copy(color = it, error = null)) },
                    label = { Text(text = "Cor") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.plate,
                    onValueChange = { onUpdate(form.copy(plate = it, error = null)) },
                    label = { Text(text = "Placa") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Usar como veiculo ativo")
                    Switch(
                        checked = form.useNow,
                        onCheckedChange = { onUpdate(form.copy(useNow = it)) }
                    )
                }
                if (!form.error.isNullOrBlank()) {
                    Text(text = form.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    )
}
