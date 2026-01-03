package com.qapp.app.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import com.qapp.app.ui.AlertUiState
import com.qapp.app.ui.AlertVisualState
import com.qapp.app.ui.AlertSystemStatus
import com.qapp.app.ui.AlertLocationStatus
import com.qapp.app.ui.components.PanicAlertBottomSheet
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class PanicHoldButtonTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pressLessThanThreeSecondsDoesNotTrigger() {
        val triggered = AtomicBoolean(false)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PanicHoldButton(
                enabled = true,
                onPanicTriggered = { triggered.set(true) }
            )
        }
        composeRule.onNodeWithTag("panic_hold_button").performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(2000)
        composeRule.onNodeWithTag("panic_hold_button").performTouchInput { up() }
        composeRule.waitForIdle()
        assertThat(triggered.get()).isFalse()
    }

    @Test
    fun pressThreeSecondsTriggers() {
        val triggered = AtomicBoolean(false)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PanicHoldButton(
                enabled = true,
                onPanicTriggered = { triggered.set(true) }
            )
        }
        composeRule.onNodeWithTag("panic_hold_button").performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(3100)
        composeRule.onNodeWithTag("panic_hold_button").performTouchInput { up() }
        composeRule.waitForIdle()
        assertThat(triggered.get()).isTrue()
    }

    @Test
    fun silentBadgeIsVisibleWhenMutedByPolicy() {
        composeRule.setContent {
            PanicAlertBottomSheet(
                state = AlertUiState(
                    isVisible = true,
                    isMuted = true,
                    silentBadgeVisible = true,
                    visualState = AlertVisualState.ALERT_SILENCED,
                    systemStatus = AlertSystemStatus.OK,
                    locationStatus = AlertLocationStatus.WAITING
                ),
                onDismiss = {},
                onOpenMap = {},
                onSilence = {},
                onClose = {}
            )
        }
        composeRule.onNodeWithTag("silent_badge").assertIsDisplayed()
    }

    @Test
    fun onlineOfflineStatusReflectsState() {
        composeRule.setContent {
            HomeScreenContent(
                isPanicActive = false,
                isOnline = false,
                isNetworkOnline = true,
                isGpsAvailable = true,
                isRealtimeConnected = false,
                lastSyncAt = null,
                isBatteryOptimizationIgnored = true,
                manufacturerHint = null,
                isAuthenticated = true,
                serviceHealthUnstable = false,
                onStartPanic = {},
                onStopPanic = {},
                onGoOnline = {},
                onGoOffline = {},
                onRequestBatteryOptimization = {},
                alertState = AlertUiState(),
                onOpenMap = {},
                onSilenceAlert = {},
                onCloseAlert = {},
                onChangePassword = {},
                onLogout = {}
            )
        }
        composeRule.onNodeWithText("OFFLINE").assertIsDisplayed()
    }
}
