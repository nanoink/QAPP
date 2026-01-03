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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: () -> Unit,
    onForgotPassword: () -> Unit
) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val loginSuccess by viewModel.loginSuccess.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    if (loginSuccess) {
        viewModel.clearLoginSuccess()
        onLoginSuccess()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Login", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Senha") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )
        if (isLoading) {
            Text(text = "Entrando...", style = MaterialTheme.typography.bodyMedium)
        } else if (!errorMessage.isNullOrBlank()) {
            Text(text = errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
        } else if (!localError.isNullOrBlank()) {
            Text(text = localError.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                localError = null
                if (email.isBlank() || password.isBlank()) {
                    localError = "Preencha email e senha"
                    return@Button
                }
                viewModel.signIn(email.trim(), password)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            Text(text = if (isLoading) "Entrando..." else "Entrar")
        }
        TextButton(
            onClick = onForgotPassword,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Esqueci minha senha")
        }
    }
}
