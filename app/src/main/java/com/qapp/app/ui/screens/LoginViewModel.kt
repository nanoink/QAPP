package com.qapp.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qapp.app.data.repository.AuthRepository
import com.qapp.app.data.repository.DriverRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authRepository: AuthRepository = AuthRepository(),
    private val driverRepository: DriverRepository = DriverRepository()
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _infoMessage = MutableStateFlow<String?>(null)
    val infoMessage: StateFlow<String?> = _infoMessage.asStateFlow()

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess.asStateFlow()

    fun signIn(email: String, password: String) {
        _isLoading.value = true
        _errorMessage.value = null
        _infoMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = authRepository.signIn(email, password)
            if (result.isSuccess) {
                driverRepository.ensureDriverExists()
                _loginSuccess.value = true
            } else {
                _loginSuccess.value = false
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Falha no login"
            }
            _isLoading.value = false
        }
    }

    fun requestPasswordReset(email: String) {
        _isLoading.value = true
        _errorMessage.value = null
        _infoMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = authRepository.resetPassword(email)
            _infoMessage.value =
                result.exceptionOrNull()?.message ?: "Se este email existir, voce recebera instrucoes."
            _isLoading.value = false
        }
    }

    fun clearLoginSuccess() {
        _loginSuccess.value = false
    }
}
