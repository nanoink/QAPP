package com.qapp.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.qapp.app.core.AlertSoundManager
import com.qapp.app.core.ActiveVehicleStore
import com.qapp.app.core.BatteryOptimizationHelper
import com.qapp.app.core.DefensiveModeManager
import com.qapp.app.core.DefensiveModeState
import com.qapp.app.core.DeviceInfoUtil
import com.qapp.app.core.DriverIdentityStore
import com.qapp.app.core.IncomingPanicAlertStore
import com.qapp.app.core.LogTags
import com.qapp.app.core.LocationStateStore
import com.qapp.app.core.OverlayController
import com.qapp.app.core.PanicMath
import com.qapp.app.core.PanicRealtimeListener
import com.qapp.app.core.PanicStateManager
import com.qapp.app.core.PanicStatus
import com.qapp.app.core.ServiceHealthMonitor
import com.qapp.app.core.ServiceHealthStatus
import com.qapp.app.core.SecurityState
import com.qapp.app.core.SecurityStateStore
import com.qapp.app.core.SecuritySessionState
import com.qapp.app.core.SecuritySessionStore
import com.qapp.app.core.SyncStateStore
import com.qapp.app.core.SupabaseClientProvider
import com.qapp.app.core.VehicleSelectionStore
import com.qapp.app.core.connectivity.ConnectivityMonitor
import com.qapp.app.core.location.GpsStatusMonitor
import com.qapp.app.data.repository.AuthRepository
import com.qapp.app.data.repository.AlertRepository
import com.qapp.app.data.repository.DriverRecord
import com.qapp.app.data.repository.DriverRepository
import com.qapp.app.data.repository.LocationRepository
import com.qapp.app.data.repository.DriverProfile
import com.qapp.app.data.repository.VehicleInfo
import com.qapp.app.data.repository.VehicleRecord
import com.qapp.app.data.repository.VehicleRepository
import com.qapp.app.domain.AccessBlockReason
import com.qapp.app.domain.AccessResult
import com.qapp.app.domain.AccessValidator
import com.qapp.app.domain.Driver
import com.qapp.app.domain.PanicManager
import com.qapp.app.services.CoreSecurityService
import com.qapp.app.services.PanicRealtimeService
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val panicManager = PanicManager()
    private val authRepository: AuthRepository = AuthRepository()
    private val alertRepository: AlertRepository = AlertRepository()
    private val driverRepository: DriverRepository = DriverRepository()
    private val vehicleRepository: VehicleRepository = VehicleRepository()
    private val accessValidator = AccessValidator()

    private val alertSoundManager = AlertSoundManager(getApplication<Application>()).apply {
        setAllowInSilentMode(true)
    }
    private var alertCloseJob: Job? = null
    private val panicRealtimeListener = PanicRealtimeListener(
        context = getApplication<Application>(),
        locationStore = LocationStateStore,
        scope = viewModelScope
    )

    private val _isPanicActive = MutableStateFlow(panicManager.isPanicActive())
    val isPanicActive: StateFlow<Boolean> = _isPanicActive.asStateFlow()
    val panicStatus: StateFlow<PanicStatus> = PanicStateManager.state

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isOverlayAllowed = MutableStateFlow(false)
    val isOverlayAllowed: StateFlow<Boolean> = _isOverlayAllowed.asStateFlow()

    private val _alertState = MutableStateFlow(AlertUiState())
    val alertState: StateFlow<AlertUiState> = _alertState.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    val selectedVehicleId: StateFlow<String?> = VehicleSelectionStore.selectedVehicleId

    private val _activeVehicle = MutableStateFlow<VehicleRecord?>(null)
    val activeVehicle: StateFlow<VehicleRecord?> = _activeVehicle.asStateFlow()

    private val _driverName = MutableStateFlow<String?>(null)
    val driverName: StateFlow<String?> = _driverName.asStateFlow()

    private val _accountEmail = MutableStateFlow<String?>(null)
    val accountEmail: StateFlow<String?> = _accountEmail.asStateFlow()

    private val _accountPhone = MutableStateFlow<String?>(null)
    val accountPhone: StateFlow<String?> = _accountPhone.asStateFlow()

    private val _authLoading = MutableStateFlow(false)
    val authLoading: StateFlow<Boolean> = _authLoading.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _authMessage = MutableStateFlow<String?>(null)
    val authMessage: StateFlow<String?> = _authMessage.asStateFlow()

    private val _isAuthorized = MutableStateFlow(false)
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _vehicleGateMessage = MutableStateFlow<String?>(null)
    val vehicleGateMessage: StateFlow<String?> = _vehicleGateMessage.asStateFlow()

    private var isInitialized = false
    private var onlineSynced = false
    private var vehicleObserverJob: Job? = null
    private var vehicleCacheJob: Job? = null

    val isNetworkOnline: StateFlow<Boolean> = ConnectivityMonitor.status
        .map { it.isOnline }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectivityMonitor.isOnline())

    val isGpsAvailable: StateFlow<Boolean> = GpsStatusMonitor.status
        .map { it.isLocationAvailable }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isBatteryOptimizationIgnored: StateFlow<Boolean> = BatteryOptimizationHelper.status
        .map { it.isIgnoring }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val manufacturerHint: StateFlow<String?> = BatteryOptimizationHelper.status
        .map { it.manufacturerHint }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isAggressiveManufacturer: StateFlow<Boolean> = BatteryOptimizationHelper.status
        .map { it.isAggressiveManufacturer }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DeviceInfoUtil.isAggressiveBatteryDevice())

    val serviceHealthStatus: StateFlow<ServiceHealthStatus> = ServiceHealthMonitor.status
    val defensiveModeState: StateFlow<DefensiveModeState> = DefensiveModeManager.state

    private val _isRealtimeConnected = MutableStateFlow(false)
    val isRealtimeConnected: StateFlow<Boolean> = _isRealtimeConnected.asStateFlow()
    private var lastRealtimeStatus: AlertSystemStatus? = null

    val lastSyncAt: StateFlow<Long?> = SyncStateStore.lastSyncAt

    fun initializeIfNeeded() {
        if (isInitialized) {
            return
        }
        isInitialized = true
        val app = getApplication<Application>()
        SecurityStateStore.init(app)
        SecuritySessionStore.init(app)
        LocationStateStore.init(app)
        ConnectivityMonitor.init(app)
        GpsStatusMonitor.init(app)
        ServiceHealthMonitor.init(app)
        DefensiveModeManager.init(app)
        PanicStateManager.init(app)
        BatteryOptimizationHelper.refresh(app)
        _isOnline.value = SecurityStateStore.isOnline()
        _isPanicActive.value = panicManager.isPanicActive()
        onlineSynced = false
        _isOverlayAllowed.value = OverlayController.isPermissionGranted(app)
        vehicleObserverJob = viewModelScope.launch {
            VehicleSelectionStore.selectedVehicleId.collect { id ->
                if (!id.isNullOrBlank()) {
                    _vehicleGateMessage.value = null
                }
            }
        }
        vehicleCacheJob = viewModelScope.launch {
            ActiveVehicleStore.state.collect { vehicle ->
                _activeVehicle.value = vehicle
            }
        }
        if (_isOnline.value) {
            startAlertListener()
        }
        refreshAccess()
        viewModelScope.launch {
            refreshActiveVehicle()
        }
    }

    fun onStartPanic() {
        activatePanic("BUTTON")
    }

    fun onStopPanic() {
        panicManager.stopPanic()
        CoreSecurityService.stopPanic(getApplication<Application>())
        _isPanicActive.value = panicManager.isPanicActive()
    }

    fun activatePanic(source: String) {
        if (!_isOnline.value) {
            return
        }
        viewModelScope.launch {
            val allowed = ensureActiveVehicleOrWarn()
            if (!allowed) {
                return@launch
            }
            CoreSecurityService.triggerPanic(getApplication<Application>(), source)
        }
    }

    fun refreshSession() {
        Log.d("AUTH_SESSION", "Session: ${SupabaseClientProvider.client.auth.currentSessionOrNull()}")
        val authed = authRepository.isAuthenticated()
        _isAuthenticated.value = authed
        _currentUserId.value = if (authed) {
            SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        } else {
            null
        }
        SecuritySessionStore.setState(
            if (authed) SecuritySessionState.AUTHENTICATED else SecuritySessionState.NOT_AUTHENTICATED
        )
        if (authed) {
            viewModelScope.launch {
                ensureActiveVehicleOrWarn()
                refreshActiveVehicle()
            }
        } else {
            VehicleSelectionStore.set(null)
            _vehicleGateMessage.value = null
            _activeVehicle.value = null
            ActiveVehicleStore.clear()
            DriverIdentityStore.clear()
        }
    }

    fun refreshAccountInfo() {
        viewModelScope.launch {
            val user = SupabaseClientProvider.client.auth.currentUserOrNull()
            _accountEmail.value = user?.email
            val userId = user?.id
            if (!userId.isNullOrBlank()) {
                val profile = alertRepository.getDriverProfile(userId)
                _accountPhone.value = profile?.phone
                if ((_driverName.value.isNullOrBlank()) && !profile?.name.isNullOrBlank()) {
                    _driverName.value = profile?.name
                    DriverIdentityStore.setName(profile?.name)
                }
            } else {
                _accountPhone.value = null
            }
        }
    }

    suspend fun updatePassword(newPassword: String, confirmPassword: String): Boolean {
        if (newPassword.length < 6) {
            _authError.value = "Senha muito curta"
            return false
        }
        if (newPassword != confirmPassword) {
            _authError.value = "As senhas nao conferem"
            return false
        }
        _authLoading.value = true
        _authError.value = null
        _authMessage.value = null
        return try {
            val result = authRepository.updatePassword(newPassword)
            if (result.isSuccess) {
                _authMessage.value = "Senha atualizada"
                true
            } else {
                val message = result.exceptionOrNull()?.message ?: "Falha ao atualizar senha"
                _authError.value = message
                false
            }
        } finally {
            _authLoading.value = false
        }
    }

    suspend fun logout() {
        _authLoading.value = true
        _authError.value = null
        _authMessage.value = null
        try {
            driverRepository.setOffline()
            authRepository.signOut()
        } finally {
            _authLoading.value = false
        }
        SecuritySessionStore.setState(SecuritySessionState.NOT_AUTHENTICATED)
        stopAlertListener()
        _isAuthenticated.value = false
        _currentUserId.value = null
        _activeVehicle.value = null
        ActiveVehicleStore.clear()
        DriverIdentityStore.clear()
        _isAuthorized.value = false
        _errorMessage.value = null
        _isOnline.value = false
        onlineSynced = false
        panicManager.stopPanic()
        _isPanicActive.value = panicManager.isPanicActive()
        CoreSecurityService.goOffline(getApplication<Application>())
    }

    fun goOnline() {
        val repo = driverRepository ?: return
        if (_isOnline.value) {
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                LocationRepository(getApplication<Application>()).clearBuffer()
            }
            val appContext = getApplication<Application>()
            val authed = authRepository.isAuthenticated()
            SecuritySessionStore.setState(
                if (authed) SecuritySessionState.AUTHENTICATED else SecuritySessionState.NOT_AUTHENTICATED
            )
            val allowed = ensureActiveVehicleOrWarn()
            if (!allowed) {
                _isOnline.value = false
                return@launch
            }
            if (!hasLocationPermission(appContext)) {
                Log.w(LogTags.LOCATION, "Cannot go online: location permission missing")
                _isOnline.value = false
                return@launch
            }
            SecurityStateStore.init(appContext)
            val last = LocationStateStore.get()
            if (last != null) {
                repo.updateLocation(last.lat, last.lng)
            }
            val ok = repo.setOnline()
            _isOnline.value = ok
            if (ok) {
                onlineSynced = true
            }
            _isOverlayAllowed.value = OverlayController.isPermissionGranted(getApplication<Application>())
            if (_isOnline.value) {
                SecurityStateStore.setState(SecurityState.ONLINE)
                CoreSecurityService.goOnline(appContext)
                PanicRealtimeService.startIfAllowed(appContext)
                startAlertListener()
            } else {
                SecurityStateStore.setState(SecurityState.OFFLINE)
            }
        }
    }

    fun clearVehicleGateMessage() {
        _vehicleGateMessage.value = null
    }

    private suspend fun ensureActiveVehicleOrWarn(): Boolean {
        val status = vehicleRepository.getActiveVehicleStatus()
        _activeVehicle.value = status.vehicle
        if (!status.hasAny) {
            Log.w("QAPP_PANIC", "NO_VEHICLE_REGISTERED")
            _vehicleGateMessage.value = "Cadastre um veiculo para continuar"
            return false
        }
        if (status.vehicle == null) {
            Log.w("QAPP_PANIC", "NO_ACTIVE_VEHICLE")
            _vehicleGateMessage.value = "Selecione um veiculo ativo para continuar"
            return false
        }
        _vehicleGateMessage.value = null
        return true
    }

    fun goOffline() {
        val repo = driverRepository ?: return
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            SecurityStateStore.init(appContext)
            repo.setOffline()
            SecurityStateStore.setState(SecurityState.OFFLINE)
            stopAlertListener()
            CoreSecurityService.goOffline(appContext)
            _isOnline.value = false
            onlineSynced = false
            _isOverlayAllowed.value = OverlayController.isPermissionGranted(getApplication<Application>())
        }
    }

    fun refreshOverlayStatus() {
        _isOverlayAllowed.value = OverlayController.isPermissionGranted(getApplication<Application>())
    }

    fun refreshBatteryOptimization() {
        BatteryOptimizationHelper.refresh(getApplication<Application>())
    }

    fun silenceAlert() {
        alertSoundManager.setMuted(true)
        _alertState.value = _alertState.value.copy(
            isMuted = true,
            silentBadgeVisible = false,
            visualState = AlertVisualState.ALERT_SILENCED
        )
        IncomingPanicAlertStore.updateMuted(true)
    }

    fun confirmAlertView() {
        if (_alertState.value.isMuted) return
        silenceAlert()
    }

    fun toggleAlertMute() {
        val current = _alertState.value
        val nextMuted = !current.isMuted
        alertSoundManager.setMuted(nextMuted)
        val nextVisual = if (nextMuted) {
            AlertVisualState.ALERT_SILENCED
        } else if (current.isActive) {
            AlertVisualState.ALERT_ACTIVE
        } else {
            AlertVisualState.ALERT_RECEIVED
        }
        _alertState.value = current.copy(
            isMuted = nextMuted,
            silentBadgeVisible = false,
            visualState = nextVisual
        )
        IncomingPanicAlertStore.updateMuted(nextMuted)
    }

    fun closeAlertSheet() {
        _alertState.value = _alertState.value.copy(
            isVisible = false,
            visualState = AlertVisualState.ALERT_CLOSED
        )
        IncomingPanicAlertStore.dismiss()
    }

    fun openAlertSheet() {
        _alertState.value = _alertState.value.copy(isVisible = true)
    }

    private fun refreshAccess() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val isAuthenticated = authRepository.isAuthenticated()
                val userId = if (isAuthenticated) {
                    SupabaseClientProvider.client.auth.currentUserOrNull()?.id
                } else {
                    null
                }
                val driverRecord = userId?.let { driverRepository.getDriverByUserId(it) }
                _driverName.value = driverRecord?.name
                DriverIdentityStore.setName(driverRecord?.name)

                val result = accessValidator.validate(
                    userId = userId,
                    driver = driverRecord?.toDomain()
                )

                when (result) {
                    is AccessResult.Allowed -> {
                        _isAuthorized.value = true
                        _errorMessage.value = null
                    }
                    is AccessResult.Blocked -> {
                        _isAuthorized.value = false
                        _errorMessage.value = mapReason(result.reason)
                    }
                }
            } catch (ex: Exception) {
                _isAuthorized.value = false
                _errorMessage.value = "Falha ao validar acesso"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun startAlertListener() {
        panicRealtimeListener.start(
            onAlertStarted = { event, driver, vehicle, mutedByPolicy ->
                alertCloseJob?.cancel()
                val distanceKm = computeDistanceKm(event.lat, event.lng)
                val intensity = if (distanceKm != null && distanceKm <= 3.0) {
                    1.0f
                } else {
                    0.7f
                }
                alertSoundManager.setIntensity(intensity)
                alertSoundManager.setMuted(mutedByPolicy)
                if (mutedByPolicy) {
                    alertSoundManager.stop()
                } else {
                    alertSoundManager.startLoop(intensity)
                    Log.i("QAPP_PANIC", "PANIC_RECEIVER_SOUND_STARTED")
                }
                val startedAtMs = System.currentTimeMillis()
                _alertState.value = _alertState.value.copy(
                    isVisible = true,
                    isActive = event.isActive,
                    driver = driver,
                    vehicle = vehicle,
                    isMuted = mutedByPolicy,
                    silentBadgeVisible = mutedByPolicy,
                    visualState = AlertVisualState.ALERT_RECEIVED,
                    systemStatus = AlertSystemStatus.OK,
                    locationStatus = AlertLocationStatus.WAITING
                )
                val lat = event.lat
                val lng = event.lng
                if (lat != null && lng != null) {
                    IncomingPanicAlertStore.showAlert(
                        eventId = event.id,
                        driverId = event.driverId,
                        driverName = driver?.name,
                        lat = lat,
                        lng = lng,
                        distanceKm = distanceKm,
                        startedAtMs = startedAtMs,
                        muted = mutedByPolicy,
                        vehicle = vehicle
                    )
                    Log.i("QAPP_PANIC", "PANIC_RECEIVER_UI_SHOWN event_id=${event.id}")
                }
            },
            onAlertLocation = { location ->
                val distanceKm = computeDistanceKm(location.latitude, location.longitude)
                val current = _alertState.value
                val nextVisualState = if (current.isMuted) {
                    AlertVisualState.ALERT_SILENCED
                } else {
                    AlertVisualState.ALERT_ACTIVE
                }
                _alertState.value = _alertState.value.copy(
                    location = AlertLocation(
                        lat = location.latitude,
                        lng = location.longitude,
                        updatedAt = location.updatedAt
                    ),
                    visualState = nextVisualState,
                    locationStatus = AlertLocationStatus.UPDATING
                )
                Log.i(
                    "QAPP_PANIC",
                    "PANIC_RECEIVER_LOCATION_UPDATED lat=${location.latitude} lng=${location.longitude}"
                )
                IncomingPanicAlertStore.updateLocation(
                    lat = location.latitude,
                    lng = location.longitude,
                    distanceKm = distanceKm,
                    heading = location.heading
                )
            },
            onAlertEnded = {
                alertSoundManager.stop()
                alertSoundManager.playEndTone()
                IncomingPanicAlertStore.state.value.eventId?.let { eventId ->
                    Log.i("QAPP_PANIC", "PANIC_RECEIVER_EVENT_ENDED id=$eventId")
                }
                IncomingPanicAlertStore.markEnded()
                _alertState.value = _alertState.value.copy(
                    isActive = false,
                    isVisible = true,
                    visualState = AlertVisualState.ALERT_CLOSED,
                    locationStatus = AlertLocationStatus.UNAVAILABLE
                )
                alertCloseJob?.cancel()
                alertCloseJob = viewModelScope.launch {
                    delay(ALERT_END_DISMISS_DELAY_MS)
                    if (_alertState.value.isActive) {
                        return@launch
                    }
                    _alertState.value = AlertUiState()
                    IncomingPanicAlertStore.clear()
                }
            },
            onSystemStatus = { status ->
                _isRealtimeConnected.value = status == AlertSystemStatus.OK
                _alertState.value = _alertState.value.copy(systemStatus = status)
                val previous = lastRealtimeStatus
                lastRealtimeStatus = status
                if (status == AlertSystemStatus.OK &&
                    previous != AlertSystemStatus.OK &&
                    _isOnline.value &&
                    !onlineSynced
                ) {
                    viewModelScope.launch {
                        if (driverRepository.setOnline()) {
                            onlineSynced = true
                        }
                    }
                }
            }
        )
    }

    private fun stopAlertListener() {
        panicRealtimeListener.stop()
        alertSoundManager.stop()
        alertCloseJob?.cancel()
        alertCloseJob = null
        _alertState.value = AlertUiState()
        IncomingPanicAlertStore.clear()
        _isRealtimeConnected.value = false
    }

    private companion object {
        private const val ALERT_END_DISMISS_DELAY_MS = 2500L
    }

    private suspend fun refreshActiveVehicle() {
        val status = vehicleRepository.getActiveVehicleStatus()
        _activeVehicle.value = status.vehicle
    }

    private fun computeDistanceKm(lat: Double?, lng: Double?): Double? {
        if (lat == null || lng == null) return null
        val current = LocationStateStore.get() ?: return null
        return PanicMath.distanceKm(current.lat, current.lng, lat, lng)
    }

    private fun mapReason(reason: AccessBlockReason): String {
        return when (reason) {
            AccessBlockReason.NOT_AUTHENTICATED -> "Nao autenticado"
            AccessBlockReason.DRIVER_NOT_FOUND -> "Motorista nao encontrado"
            AccessBlockReason.SUBSCRIPTION_INACTIVE -> "Assinatura inativa"
            AccessBlockReason.DRIVER_INACTIVE -> "Motorista inativo"
        }
    }

    private fun hasLocationPermission(context: android.content.Context): Boolean {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun DriverRecord.toDomain(): Driver {
        return Driver(
            id = id,
            active = active ?: false,
            subscriptionStatus = subscriptionStatus ?: "inactive"
        )
    }

    override fun onCleared() {
        super.onCleared()
        vehicleObserverJob?.cancel()
        vehicleCacheJob?.cancel()
        panicRealtimeListener.stop()
        alertSoundManager.stop()
    }
}


data class AlertUiState(
    val isVisible: Boolean = false,
    val isActive: Boolean = false,
    val driver: DriverProfile? = null,
    val vehicle: VehicleInfo? = null,
    val location: AlertLocation? = null,
    val isMuted: Boolean = false,
    val silentBadgeVisible: Boolean = false,
    val visualState: AlertVisualState = AlertVisualState.IDLE,
    val systemStatus: AlertSystemStatus = AlertSystemStatus.OK,
    val locationStatus: AlertLocationStatus = AlertLocationStatus.WAITING
)

data class AlertLocation(
    val lat: Double,
    val lng: Double,
    val updatedAt: String?
)

enum class AlertVisualState {
    IDLE,
    ALERT_RECEIVED,
    ALERT_ACTIVE,
    ALERT_SILENCED,
    ALERT_CLOSED
}

enum class AlertSystemStatus {
    OK,
    DISCONNECTED
}

enum class AlertLocationStatus {
    WAITING,
    UPDATING,
    UNAVAILABLE
}
