package com.qapp.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.qapp.app.core.PermissionStateManager
import com.qapp.app.core.PanicAlertPendingStore
import com.qapp.app.core.SecurityOnboardingStore
import com.qapp.app.navigation.AppNavGraph
import com.qapp.app.navigation.AppRoute
import com.qapp.app.ui.AppViewModel
import com.qapp.app.ui.screens.LoginViewModel
import com.qapp.app.ui.screens.SecurityOnboardingScreen
import kotlinx.coroutines.launch

@Composable
fun QAppApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val appViewModel: AppViewModel = viewModel<AppViewModel>()
    val loginViewModel: LoginViewModel = viewModel<LoginViewModel>()
    var hasSeenOnboarding by remember {
        mutableStateOf(SecurityOnboardingStore.hasSeen(context))
    }
    var hasCriticalPermissions by remember {
        mutableStateOf(PermissionStateManager.check(context).areAllCriticalPermissionsGranted)
    }
    val isAuthenticated by appViewModel.isAuthenticated.collectAsStateWithLifecycle()
    val alertState by appViewModel.alertState.collectAsStateWithLifecycle()
    val vehicleGateMessage by appViewModel.vehicleGateMessage.collectAsStateWithLifecycle()
    val pendingAlert by PanicAlertPendingStore.pending.collectAsStateWithLifecycle()
    val startDestination = when {
        !hasCriticalPermissions -> AppRoute.SecurityOnboarding.route
        !hasSeenOnboarding -> AppRoute.SecurityOnboarding.route
        isAuthenticated -> AppRoute.Home.route
        else -> AppRoute.Login.route
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCriticalPermissions =
                    PermissionStateManager.check(context).areAllCriticalPermissionsGranted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        PanicAlertPendingStore.init(context)
        appViewModel.refreshSession()
    }

    LaunchedEffect(isAuthenticated, hasSeenOnboarding, hasCriticalPermissions) {
        if (!hasCriticalPermissions) {
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute != AppRoute.SecurityOnboarding.route) {
                val startId = runCatching { navController.graph.startDestinationId }.getOrNull()
                navController.navigate(AppRoute.SecurityOnboarding.route) {
                    if (startId != null) {
                        popUpTo(startId) { inclusive = true }
                    }
                    launchSingleTop = true
                }
            }
            return@LaunchedEffect
        }
        if (!hasSeenOnboarding) {
            return@LaunchedEffect
        }
        if (isAuthenticated) {
            navController.navigate(AppRoute.Home.route) {
                popUpTo(AppRoute.Login.route) { inclusive = true }
            }
        } else {
            navController.navigate(AppRoute.Login.route) {
                popUpTo(AppRoute.Home.route) { inclusive = true }
            }
        }
    }

    LaunchedEffect(alertState.isVisible) {
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        if (alertState.isVisible) {
            if (currentRoute != AppRoute.IncomingPanicAlert.route) {
                navController.navigate(AppRoute.IncomingPanicAlert.route) {
                    launchSingleTop = true
                }
            }
        } else if (currentRoute == AppRoute.IncomingPanicAlert.route) {
            navController.popBackStack()
        }
    }

    LaunchedEffect(pendingAlert?.eventId) {
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        if (pendingAlert != null && currentRoute != AppRoute.IncomingPanicAlert.route) {
            navController.navigate(AppRoute.IncomingPanicAlert.route) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(
        vehicleGateMessage,
        isAuthenticated,
        hasSeenOnboarding,
        hasCriticalPermissions
    ) {
        if (!hasCriticalPermissions || !hasSeenOnboarding) return@LaunchedEffect
        if (isAuthenticated && !vehicleGateMessage.isNullOrBlank()) {
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute != AppRoute.Vehicles.route) {
                navController.navigate(AppRoute.Vehicles.route) {
                    launchSingleTop = true
                }
            }
        }
    }

    AppNavGraph(
        navController = navController,
        appViewModel = appViewModel,
        loginViewModel = loginViewModel,
        startDestination = startDestination,
        onLogout = {
            scope.launch { appViewModel.logout() }
        },
        onOnboardingCompleted = {
            hasSeenOnboarding = true
            hasCriticalPermissions =
                PermissionStateManager.check(context).areAllCriticalPermissionsGranted
            val next = if (isAuthenticated) AppRoute.Home.route else AppRoute.Login.route
            navController.navigate(next) {
                popUpTo(AppRoute.SecurityOnboarding.route) { inclusive = true }
            }
        }
    )
}
