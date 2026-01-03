package com.qapp.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.qapp.app.ui.AppViewModel
import com.qapp.app.ui.home.HomeScreen
import com.qapp.app.ui.screens.AccountScreen
import com.qapp.app.ui.screens.ChangePasswordScreen
import com.qapp.app.ui.screens.ForgotPasswordScreen
import com.qapp.app.ui.screens.IncomingPanicAlertScreen
import com.qapp.app.ui.screens.LoginScreen
import com.qapp.app.ui.screens.LoginViewModel
import com.qapp.app.ui.screens.MapFullScreenScreen
import com.qapp.app.ui.screens.SecurityOnboardingScreen
import com.qapp.app.ui.screens.VehicleFormScreen
import com.qapp.app.ui.screens.VehiclesScreen

sealed class AppRoute(val route: String) {
    object Home : AppRoute("home")
    object Vehicles : AppRoute("vehicles")
    object VehicleForm : AppRoute("vehicle_form")
    object Account : AppRoute("account")

    object SecurityOnboarding : AppRoute("security_onboarding")
    object Login : AppRoute("login")
    object ForgotPassword : AppRoute("forgot_password")
    object ChangePassword : AppRoute("change_password")
    object MapFullScreen : AppRoute("map_full_screen")
    object IncomingPanicAlert : AppRoute("incoming_panic_alert")
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    appViewModel: AppViewModel,
    loginViewModel: LoginViewModel,
    startDestination: String,
    onLogout: () -> Unit,
    onOnboardingCompleted: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(AppRoute.SecurityOnboarding.route) {
            SecurityOnboardingScreen(
                onCompleted = onOnboardingCompleted
            )
        }
        composable(AppRoute.Login.route) {
            LoginScreen(
                viewModel = loginViewModel,
                onLoginSuccess = {
                    appViewModel.refreshSession()
                    navController.navigate(AppRoute.Home.route) {
                        popUpTo(AppRoute.Login.route) { inclusive = true }
                    }
                },
                onForgotPassword = { navController.navigate(AppRoute.ForgotPassword.route) }
            )
        }
        composable(AppRoute.ForgotPassword.route) {
            ForgotPasswordScreen(
                viewModel = loginViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(AppRoute.ChangePassword.route) {
            ChangePasswordScreen(
                viewModel = appViewModel,
                onDone = { navController.popBackStack() }
            )
        }
        composable(AppRoute.Home.route) {
            HomeScreen(
                viewModel = appViewModel,
                onOpenVehicles = { navController.navigate(AppRoute.Vehicles.route) },
                onOpenAccount = { navController.navigate(AppRoute.Account.route) },
                onOpenMap = { navController.navigate(AppRoute.MapFullScreen.route) },
                onChangePassword = { navController.navigate(AppRoute.ChangePassword.route) },
                onLogout = onLogout
            )
        }
        composable(AppRoute.Vehicles.route) {
            VehiclesScreen(
                onBack = { navController.popBackStack() },
                onAddVehicle = { navController.navigate(AppRoute.VehicleForm.route) }
            )
        }
        composable(AppRoute.VehicleForm.route) {
            VehicleFormScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(AppRoute.Account.route) {
            AccountScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(AppRoute.IncomingPanicAlert.route) {
            IncomingPanicAlertScreen(
                onDismissAlert = {
                    appViewModel.closeAlertSheet()
                    navController.popBackStack()
                },
                onToggleSound = { appViewModel.toggleAlertMute() }
            )
        }
        composable(AppRoute.MapFullScreen.route) {
            LaunchedEffect(Unit) {
                appViewModel.initializeIfNeeded()
            }
            val alertState = appViewModel.alertState.collectAsStateWithLifecycle()
            MapFullScreenScreen(
                location = alertState.value.location,
                title = "Alerta de panico",
                onBack = { navController.popBackStack() }
            )
        }
    }
}
