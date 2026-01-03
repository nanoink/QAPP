package com.qapp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleFormScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VehiclesViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val form = uiState.form
    var wasOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (uiState.form == null) {
            viewModel.openCreate()
        }
    }

    LaunchedEffect(form) {
        if (form != null) {
            wasOpen = true
        } else if (wasOpen) {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Cadastro de veiculo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        if (form == null) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = form.make,
                onValueChange = { viewModel.updateForm(form.copy(make = it, error = null)) },
                label = { Text(text = "Marca") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.model,
                onValueChange = { viewModel.updateForm(form.copy(model = it, error = null)) },
                label = { Text(text = "Modelo") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.color,
                onValueChange = { viewModel.updateForm(form.copy(color = it, error = null)) },
                label = { Text(text = "Cor") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.plate,
                onValueChange = { viewModel.updateForm(form.copy(plate = it, error = null)) },
                label = { Text(text = "Placa") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Usar como veiculo ativo")
                Switch(
                    checked = form.useNow,
                    onCheckedChange = { viewModel.updateForm(form.copy(useNow = it)) }
                )
            }
            if (!form.error.isNullOrBlank()) {
                Text(text = form.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    viewModel.saveVehicle()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (form.useNow) "Salvar e usar agora" else "Salvar")
            }
        }
    }
}
