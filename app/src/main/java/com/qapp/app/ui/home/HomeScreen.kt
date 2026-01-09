package com.qapp.app.ui.home

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.qapp.app.R
import com.qapp.app.core.CriticalPermissionState
import com.qapp.app.core.PermissionStateManager
import com.qapp.app.core.PanicStatus
import com.qapp.app.data.repository.VehicleRecord
import com.qapp.app.ui.AlertUiState
import com.qapp.app.ui.AppViewModel
import com.qapp.app.ui.components.PanicAlertBottomSheet
import kotlinx.coroutines.delay
import java.util.Locale

private val DisplayFont = FontFamily.Serif
private val MonoFont = FontFamily.Monospace

@Composable
fun HomeScreen(
    onOpenVehicles: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenDriversNearby: () -> Unit,
    onOpenMap: () -> Unit,
    onChangePassword: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppViewModel = viewModel<AppViewModel>()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    LaunchedEffect(Unit) {
        viewModel.initializeIfNeeded()
    }

    val panicStatus by viewModel.panicStatus.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val isNetworkOnline by viewModel.isNetworkOnline.collectAsStateWithLifecycle()
    val isGpsAvailable by viewModel.isGpsAvailable.collectAsStateWithLifecycle()
    val isRealtimeConnected by viewModel.isRealtimeConnected.collectAsStateWithLifecycle()
    val lastSyncAt by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val isBatteryOptimizationIgnored by viewModel.isBatteryOptimizationIgnored.collectAsStateWithLifecycle()
    val manufacturerHint by viewModel.manufacturerHint.collectAsStateWithLifecycle()
    val isAuthenticated by viewModel.isAuthenticated.collectAsStateWithLifecycle()
    val serviceHealthStatus by viewModel.serviceHealthStatus.collectAsStateWithLifecycle()
    val alertState by viewModel.alertState.collectAsStateWithLifecycle()
    val driverName by viewModel.driverName.collectAsStateWithLifecycle()
    val isOverlayAllowed by viewModel.isOverlayAllowed.collectAsStateWithLifecycle()
    val vehicleGateMessage by viewModel.vehicleGateMessage.collectAsStateWithLifecycle()
    val activeVehicle by viewModel.activeVehicle.collectAsStateWithLifecycle()

    var permissionState by remember {
        mutableStateOf(PermissionStateManager.check(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionState = PermissionStateManager.check(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(panicStatus.isActive) {
        view.keepScreenOn = panicStatus.isActive
    }

    var showVoiceBanner by remember { mutableStateOf(false) }
    var lastVoiceAt by remember { mutableStateOf(0L) }
    LaunchedEffect(panicStatus.isActive, panicStatus.source, panicStatus.activatedAt) {
        if (panicStatus.isActive &&
            panicStatus.source == "voice" &&
            panicStatus.activatedAt != null &&
            panicStatus.activatedAt != lastVoiceAt
        ) {
            lastVoiceAt = panicStatus.activatedAt ?: 0L
            showVoiceBanner = true
            vibrateOnce(context)
            playConfirmTone()
            delay(3500L)
            showVoiceBanner = false
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp)
            ) {
                DrawerContent(
                    name = driverName ?: "Motorista",
                    onVehiclesClick = {
                        scope.launch { drawerState.close() }
                        viewModel.clearVehicleGateMessage()
                        onOpenVehicles()
                    },
                    onAccountClick = {
                        scope.launch { drawerState.close() }
                        onOpenAccount()
                    },
                    onDriversNearbyClick = {
                        scope.launch { drawerState.close() }
                        onOpenDriversNearby()
                    },
                    onLogoutClick = {
                        scope.launch { drawerState.close() }
                        onLogout()
                    }
                )
            }
        }
    ) {
        HomeScreenContent(
            panicStatus = panicStatus,
            isOnline = isOnline,
            isNetworkOnline = isNetworkOnline,
            isGpsAvailable = isGpsAvailable,
            isRealtimeConnected = isRealtimeConnected,
            lastSyncAt = lastSyncAt,
            isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
            manufacturerHint = manufacturerHint,
            isAuthenticated = isAuthenticated,
            permissionState = permissionState,
            overlayAllowed = isOverlayAllowed,
            serviceHealthUnstable = serviceHealthStatus.isUnstable,
            vehicleGateMessage = vehicleGateMessage,
            activeVehicle = activeVehicle,
            onStartPanic = viewModel::onStartPanic,
            onStopPanic = viewModel::onStopPanic,
            onGoOnline = viewModel::goOnline,
            onGoOffline = viewModel::goOffline,
            onRequestBatteryOptimization = {
                viewModel.refreshBatteryOptimization()
                context.startActivity(com.qapp.app.core.BatteryOptimizationHelper.buildRequestIntent(context))
            },
            onOpenVehicles = {
                viewModel.clearVehicleGateMessage()
                onOpenVehicles()
            },
            onOpenDrawer = { scope.launch { drawerState.open() } },
            alertState = alertState,
            onOpenMap = onOpenMap,
            onSilenceAlert = viewModel::toggleAlertMute,
            onCloseAlert = viewModel::closeAlertSheet,
            onChangePassword = onChangePassword,
            showVoiceBanner = showVoiceBanner,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreenContent(
    panicStatus: PanicStatus,
    isOnline: Boolean,
    isNetworkOnline: Boolean,
    isGpsAvailable: Boolean,
    isRealtimeConnected: Boolean,
    lastSyncAt: Long?,
    isBatteryOptimizationIgnored: Boolean,
    manufacturerHint: String?,
    isAuthenticated: Boolean,
    permissionState: CriticalPermissionState,
    overlayAllowed: Boolean,
    serviceHealthUnstable: Boolean,
    vehicleGateMessage: String?,
    activeVehicle: VehicleRecord?,
    onStartPanic: () -> Unit,
    onStopPanic: () -> Unit,
    onGoOnline: () -> Unit,
    onGoOffline: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onOpenVehicles: () -> Unit,
    onOpenDrawer: () -> Unit,
    alertState: AlertUiState,
    onOpenMap: () -> Unit,
    onSilenceAlert: () -> Unit,
    onCloseAlert: () -> Unit,
    onChangePassword: () -> Unit,
    showVoiceBanner: Boolean,
    modifier: Modifier = Modifier
) {
    val background = Brush.verticalGradient(
        listOf(Color(0xFF0C1016), Color(0xFF1A1F27), Color(0xFF2A1214))
    )
    val cardColor = Color(0xFFFFFFFF)
    val isPanicActive = panicStatus.isActive
    val elapsedLabel = rememberElapsedLabel(panicStatus.activatedAt, isPanicActive)
    val statusText = if (isPanicActive) "PANICO ATIVO" else if (isOnline) "ONLINE" else "OFFLINE"
    val statusColor = if (isPanicActive) Color(0xFFE53935) else if (isOnline) Color(0xFF2E7D32) else Color(0xFF9E9E9E)

    Scaffold(
        modifier = modifier
            .statusBarsPadding()
            .background(background),
        containerColor = Color.Transparent
    ) { paddingValues ->
        PanicAlertBottomSheet(
            state = alertState,
            onDismiss = onCloseAlert,
            onOpenMap = onOpenMap,
            onSilence = onSilenceAlert,
            onClose = onCloseAlert
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                HomeTopBar(
                    statusText = statusText,
                    statusColor = statusColor,
                    isPanicActive = isPanicActive,
                    onOpenDrawer = onOpenDrawer
                )

                PermissionBadges(
                    permissionState = permissionState,
                    overlayAllowed = overlayAllowed
                )

                StatusRow(
                    isNetworkOnline = isNetworkOnline,
                    isGpsAvailable = isGpsAvailable,
                    isRealtimeConnected = isRealtimeConnected,
                    lastSyncAt = lastSyncAt
                )

                PanicButtonSection(
                    isOnline = isOnline,
                    isPanicActive = isPanicActive,
                    elapsedLabel = elapsedLabel,
                    onStartPanic = onStartPanic,
                    onStopPanic = onStopPanic
                )

                OnlineControls(
                    isOnline = isOnline,
                    isAuthenticated = isAuthenticated,
                    isPanicActive = isPanicActive,
                    onGoOnline = onGoOnline,
                    onGoOffline = onGoOffline
                )

                if (!vehicleGateMessage.isNullOrBlank()) {
                    VehicleGateWarning(
                        message = vehicleGateMessage,
                        onOpenVehicles = onOpenVehicles,
                        backgroundColor = cardColor
                    )
                } else if (activeVehicle != null) {
                    ActiveVehicleCard(
                        vehicle = activeVehicle,
                        backgroundColor = cardColor
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onChangePassword) {
                        Text(text = "Alterar senha", color = Color(0xFF546E7A))
                    }
                }

                if (!isBatteryOptimizationIgnored) {
                    BatteryWarning(
                        manufacturerHint = manufacturerHint,
                        onConfigure = onRequestBatteryOptimization
                    )
                }

                if (serviceHealthUnstable) {
                    Text(
                        text = stringResource(id = R.string.service_health_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF616161)
                    )
                }
            }

            VoicePanicBanner(visible = showVoiceBanner)
        }
    }
}

@Composable
private fun HomeTopBar(
    statusText: String,
    statusColor: Color,
    isPanicActive: Boolean,
    onOpenDrawer: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Menu",
                tint = Color(0xFF263238)
            )
        }

        StatusChip(
            text = statusText,
            color = statusColor,
            pulse = isPanicActive
        )
    }
}

@Composable
private fun DrawerContent(
    name: String,
    onVehiclesClick: () -> Unit,
    onAccountClick: () -> Unit,
    onDriversNearbyClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color(0xFFECEFF1), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.take(1).uppercase(Locale.US),
                fontWeight = FontWeight.Bold,
                color = Color(0xFF546E7A)
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Divider(color = Color(0xFFE0E0E0))
        DrawerItem(
            icon = Icons.Default.Menu,
            label = "Veiculos",
            onClick = onVehiclesClick
        )
        DrawerItem(
            icon = Icons.Default.Person,
            label = "Conta",
            onClick = onAccountClick
        )
        DrawerItem(
            icon = Icons.Default.Menu,
            label = "Motoristas proximos",
            onClick = onDriversNearbyClick
        )
        Spacer(modifier = Modifier.weight(1f))
        Divider(color = Color(0xFFE0E0E0))
        DrawerItem(
            icon = Icons.Default.ExitToApp,
            label = "Sair",
            onClick = onLogoutClick
        )
    }
}

@Composable
private fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF455A64))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatusChip(text: String, color: Color, pulse: Boolean) {
    val transition = rememberInfiniteTransition(label = "status_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status_alpha"
    )
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .background(color.copy(alpha = if (pulse) alpha else 1.0f), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = MonoFont
        )
    }
}

@Composable
private fun PermissionBadges(
    permissionState: CriticalPermissionState,
    overlayAllowed: Boolean
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PermissionBadge(
            label = "GPS",
            stateColor = if (permissionState.locationForeground && permissionState.locationBackground) {
                Color(0xFF43A047)
            } else {
                Color(0xFFD32F2F)
            }
        )
        PermissionBadge(
            label = "MIC",
            stateColor = if (permissionState.microphone) {
                Color(0xFF43A047)
            } else {
                Color(0xFFD32F2F)
            }
        )
        PermissionBadge(
            label = "OVR",
            stateColor = if (overlayAllowed) {
                Color(0xFF43A047)
            } else {
                Color(0xFFFFA000)
            }
        )
    }
}

@Composable
private fun PermissionBadge(label: String, stateColor: Color) {
    Box(
        modifier = Modifier
            .background(stateColor.copy(alpha = 0.18f), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = stateColor,
            fontSize = 11.sp,
            fontFamily = MonoFont,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StatusRow(
    isNetworkOnline: Boolean,
    isGpsAvailable: Boolean,
    isRealtimeConnected: Boolean,
    lastSyncAt: Long?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(label = "Rede", ok = isNetworkOnline)
            StatusPill(label = "GPS", ok = isGpsAvailable)
            StatusPill(label = "Realtime", ok = isRealtimeConnected)
        }
        if (lastSyncAt != null) {
            Text(
                text = "Ultimo sync: $lastSyncAt",
                color = Color(0xFF757575),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun StatusPill(label: String, ok: Boolean) {
    val color = if (ok) Color(0xFF2E7D32) else Color(0xFFD32F2F)
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PanicButtonSection(
    isOnline: Boolean,
    isPanicActive: Boolean,
    elapsedLabel: String,
    onStartPanic: () -> Unit,
    onStopPanic: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PanicButton(
            enabled = isOnline && !isPanicActive,
            isActive = isPanicActive,
            onPanicTriggered = onStartPanic,
            size = 200.dp
        )
        if (isPanicActive) {
            Text(
                text = "Ajuda sendo solicitada",
                color = Color(0xFF424242),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Tempo: $elapsedLabel",
                color = Color(0xFF757575),
                style = MaterialTheme.typography.labelMedium
            )
            Button(onClick = onStopPanic) {
                Text(text = "Encerrar panico")
            }
        }
    }
}

@Composable
private fun PanicButton(
    enabled: Boolean,
    isActive: Boolean,
    onPanicTriggered: () -> Unit,
    size: Dp = 220.dp
) {
    var isPressing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    val pulseTransition = rememberInfiniteTransition(label = "panic_button_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "panic_scale"
    )
    val ringProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(120),
        label = "panic_progress"
    )

    LaunchedEffect(isPressing, enabled, isActive) {
        if (!enabled || isActive) {
            isPressing = false
            progress = 0f
            return@LaunchedEffect
        }
        if (isPressing) {
            val start = System.currentTimeMillis()
            while (isPressing) {
                val elapsed = System.currentTimeMillis() - start
                progress = (elapsed / 3000f).coerceIn(0f, 1f)
                if (progress >= 1f) {
                    onPanicTriggered()
                    isPressing = false
                    progress = 0f
                    break
                }
                delay(16L)
            }
        } else {
            progress = 0f
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .scale(if (isActive) pulseScale else 1.0f)
            .pointerInput(enabled, isActive) {
                if (enabled && !isActive) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.any { it.changedToDown() }
                            val up = event.changes.any { it.previousPressed && !it.pressed }
                            if (down) {
                                isPressing = true
                            }
                            if (up) {
                                isPressing = false
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = 10.dp.toPx()
            drawCircle(
                color = Color(0xFFB71C1C),
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = Color(0xFFFFCDD2),
                startAngle = -90f,
                sweepAngle = 360f * ringProgress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Box(
            modifier = Modifier
                .size(size - 30.dp)
                .background(Color(0xFFD32F2F), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isActive) "PANICO ATIVO" else "SEGURAR PARA PANICO",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    text = if (isActive) "AJUDA SENDO SOLICITADA" else "3s",
                    color = Color(0xFFFFCDD2),
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun OnlineControls(
    isOnline: Boolean,
    isAuthenticated: Boolean,
    isPanicActive: Boolean,
    onGoOnline: () -> Unit,
    onGoOffline: () -> Unit
) {
    var sliderValue by remember { mutableStateOf(0f) }
    var showConfirm by remember { mutableStateOf(false) }
    val enabled = isAuthenticated && !isPanicActive

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isOnline) {
            OutlinedButton(
                onClick = { showConfirm = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Ficar offline")
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFFFFF), shape = MaterialTheme.shapes.large)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = { if (enabled) sliderValue = it },
                    onValueChangeFinished = {
                        if (sliderValue > 0.92f && enabled) {
                            onGoOnline()
                        }
                        sliderValue = 0f
                    },
                    enabled = enabled,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF2E7D32),
                        activeTrackColor = Color(0xFF66BB6A),
                        inactiveTrackColor = Color(0xFFE0E0E0)
                    )
                )
                Text(
                    text = if (enabled) "Deslize para ficar online" else "Bloqueado durante panico",
                    color = Color(0xFF757575),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        if (showConfirm) {
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConfirm = false
                            onGoOffline()
                        }
                    ) { Text(text = "Confirmar") }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirm = false }) { Text(text = "Cancelar") }
                },
                title = { Text(text = "Ficar offline") },
                text = { Text(text = "Tem certeza que deseja ficar offline?") }
            )
        }
    }
}

@Composable
private fun BatteryWarning(
    manufacturerHint: String?,
    onConfigure: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF8E1), shape = MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Risco de desligamento pelo sistema",
            color = Color(0xFFF57C00),
            fontWeight = FontWeight.SemiBold
        )
        if (!manufacturerHint.isNullOrBlank()) {
            Text(
                text = manufacturerHint,
                color = Color(0xFF6D4C41),
                fontSize = 12.sp
            )
        }
        Button(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Configurar agora")
        }
    }
}

@Composable
private fun VehicleGateWarning(
    message: String,
    onOpenVehicles: () -> Unit,
    backgroundColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, shape = MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = message,
            color = Color(0xFFD32F2F),
            fontWeight = FontWeight.SemiBold
        )
        Button(onClick = onOpenVehicles, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Meus veiculos")
        }
    }
}

@Composable
private fun ActiveVehicleCard(
    vehicle: VehicleRecord,
    backgroundColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Veiculo ativo",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF757575)
            )
            Text(
                text = "${vehicle.make} ${vehicle.model}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Placa: ${vehicle.plate}  Cor: ${vehicle.color}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF616161)
            )
        }
    }
}

@Composable
private fun VoicePanicBanner(visible: Boolean) {
    AnimatedVisibility(visible = visible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .background(Color(0xFF1E1E1E), shape = MaterialTheme.shapes.large)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "PANICO ATIVADO",
                    color = Color(0xFFE53935),
                    fontFamily = DisplayFont,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Sua localizacao esta sendo compartilhada com motoristas proximos",
                    color = Color(0xFFECEFF1),
                    fontFamily = MonoFont,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun rememberElapsedLabel(startedAt: Long?, active: Boolean): String {
    var elapsed by remember { mutableStateOf("00:00") }
    LaunchedEffect(startedAt, active) {
        if (!active || startedAt == null) {
            elapsed = "00:00"
            return@LaunchedEffect
        }
        while (active) {
            val delta = System.currentTimeMillis() - startedAt
            val seconds = (delta / 1000).coerceAtLeast(0)
            val minutes = seconds / 60
            val remaining = seconds % 60
            elapsed = String.format(Locale.US, "%02d:%02d", minutes, remaining)
            delay(1000L)
        }
    }
    return elapsed
}

private fun vibrateOnce(context: android.content.Context) {
    val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(160L, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(160L)
    }
}

private fun playConfirmTone() {
    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
    tone.startTone(ToneGenerator.TONE_PROP_ACK, 160)
    tone.release()
}
