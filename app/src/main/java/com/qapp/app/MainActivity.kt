package com.qapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.qapp.app.ui.theme.QAPPTheme
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.qapp.app.alerts.InAppAlertBus
import com.qapp.app.alerts.PanicAlert
import com.qapp.app.core.PanicAlertMessage
import com.qapp.app.core.RealtimeManager
import com.qapp.app.core.SupabaseClientProvider
import com.qapp.app.nearby.NearbyDriversRegistry
import com.qapp.app.ui.components.InAppPanicBottomSheet
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val inAppAlert = mutableStateOf<PanicAlert?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            InAppAlertBus.alerts.collect { showPanicBottomSheet(it) }
        }

        lifecycleScope.launch {
            RealtimeManager.connect()
            RealtimeManager.alerts.collect { message ->
                val panic = message as? PanicAlertMessage.Panic ?: return@collect
                val payload = panic.payload
                val currentUserId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
                if (!currentUserId.isNullOrBlank() && currentUserId == payload.driverId) {
                    return@collect
                }
                val receivers = NearbyDriversRegistry.get()
                if (receivers.isEmpty()) return@collect
                if (receivers.none { it.id == payload.driverId }) return@collect
                receivers.forEach {
                    InAppAlertBus.emit(
                        PanicAlert(
                            eventId = payload.panicEventId,
                            emitterId = payload.driverId,
                            lat = payload.latitude,
                            lng = payload.longitude
                        )
                    )
                }
            }
        }

        setContent {
            QAPPTheme {
                QAppApp()
                InAppPanicBottomSheet(
                    alert = inAppAlert.value,
                    onDismiss = { inAppAlert.value = null }
                )
            }
        }
    }

    private fun showPanicBottomSheet(alert: PanicAlert) {
        val current = inAppAlert.value
        if (current?.eventId == alert.eventId) {
            return
        }
        inAppAlert.value = alert
    }
}
