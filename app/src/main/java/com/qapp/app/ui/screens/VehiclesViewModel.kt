package com.qapp.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qapp.app.core.PanicStateManager
import com.qapp.app.data.repository.VehicleRecord
import com.qapp.app.data.repository.VehicleRepository
import com.qapp.app.data.repository.VehicleUpsert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VehicleFormState(
    val id: String? = null,
    val make: String = "",
    val model: String = "",
    val color: String = "",
    val plate: String = "",
    val useNow: Boolean = true,
    val error: String? = null
)

data class VehiclesUiState(
    val isLoading: Boolean = false,
    val vehicles: List<VehicleRecord> = emptyList(),
    val message: String? = null,
    val form: VehicleFormState? = null
)

class VehiclesViewModel(
    private val repository: VehicleRepository = VehicleRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(VehiclesUiState())
    val uiState: StateFlow<VehiclesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            val vehicles = repository.getVehicles()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                vehicles = vehicles
            )
        }
    }

    fun openCreate() {
        _uiState.value = _uiState.value.copy(form = VehicleFormState())
    }

    fun openEdit(vehicle: VehicleRecord) {
        _uiState.value = _uiState.value.copy(
            form = VehicleFormState(
                id = vehicle.id,
                make = vehicle.make,
                model = vehicle.model,
                color = vehicle.color,
                plate = vehicle.plate,
                useNow = vehicle.isActive == true
            )
        )
    }

    fun dismissForm() {
        _uiState.value = _uiState.value.copy(form = null)
    }

    fun updateForm(updated: VehicleFormState) {
        _uiState.value = _uiState.value.copy(form = updated)
    }

    fun saveVehicle() {
        val current = _uiState.value.form ?: return
        val input = VehicleUpsert(
            make = current.make,
            model = current.model,
            color = current.color,
            plate = current.plate
        )
        if (input.make.isBlank() || input.model.isBlank() || input.plate.isBlank()) {
            updateForm(current.copy(error = "Preencha marca, modelo e placa"))
            return
        }
        if (current.useNow && PanicStateManager.isPanicActive()) {
            _uiState.value = _uiState.value.copy(
                message = "Nao e possivel trocar de veiculo durante um alerta ativo"
            )
            return
        }
        viewModelScope.launch {
            if (current.id == null) {
                val result = repository.createVehicle(input, current.useNow)
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    val message = if (error?.message == "panic_active") {
                        "Nao e possivel trocar de veiculo durante um alerta ativo"
                    } else {
                        "Falha ao salvar veiculo"
                    }
                    _uiState.value = _uiState.value.copy(message = message)
                }
            } else {
                val result = repository.updateVehicle(current.id, input, current.useNow)
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    val message = if (error?.message == "panic_active") {
                        "Nao e possivel trocar de veiculo durante um alerta ativo"
                    } else {
                        "Falha ao atualizar veiculo"
                    }
                    _uiState.value = _uiState.value.copy(message = message)
                }
            }
            dismissForm()
            refresh()
        }
    }

    fun setActive(vehicleId: String) {
        if (PanicStateManager.isPanicActive()) {
            _uiState.value = _uiState.value.copy(
                message = "Nao e possivel trocar de veiculo durante um alerta ativo"
            )
            return
        }
        viewModelScope.launch {
            val ok = repository.setActiveVehicle(vehicleId)
            if (!ok) {
                _uiState.value = _uiState.value.copy(message = "Nao foi possivel ativar")
            }
            refresh()
        }
    }
}
