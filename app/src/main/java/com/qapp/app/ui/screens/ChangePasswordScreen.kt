package com.qapp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qapp.app.ui.AppViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun ChangePasswordScreen(
    viewModel: AppViewModel,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val authLoading by viewModel.authLoading.collectAsStateWithLifecycle()
    val authError by viewModel.authError.collectAsStateWithLifecycle()
    val authMessage by viewModel.authMessage.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Alterar senha", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Nova senha") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirmar senha") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )
        if (!authError.isNullOrBlank()) {
            Text(text = authError.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        if (!authMessage.isNullOrBlank()) {
            Text(text = authMessage.orEmpty(), color = MaterialTheme.colorScheme.primary)
        }
        Button(
            onClick = {
                scope.launch {
                    val ok = viewModel.updatePassword(password, confirm)
                    if (ok) {
                        onDone()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !authLoading
        ) {
            Text(text = if (authLoading) "Salvando..." else "Salvar")
        }
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Voltar")
        }
    }
}
