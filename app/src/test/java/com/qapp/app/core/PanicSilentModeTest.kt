package com.qapp.app.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PanicSilentModeTest {

    @Test
    fun criticalAlwaysPlaysSound() {
        val lastState = SilentAlertState(
            lastSoundAt = 10_000L,
            lastPriority = PanicEventPriority.LOW
        )
        val result = shouldPlaySound(
            priority = PanicEventPriority.CRITICAL,
            lastState = lastState,
            now = 15_000L
        )
        assertThat(result).isTrue()
    }

    @Test
    fun normalRespectsSilentWindow() {
        val lastState = SilentAlertState(
            lastSoundAt = 100_000L,
            lastPriority = PanicEventPriority.NORMAL
        )
        val result = shouldPlaySound(
            priority = PanicEventPriority.NORMAL,
            lastState = lastState,
            now = 160_000L
        )
        assertThat(result).isFalse()
    }

    @Test
    fun priorityIncreaseBypassesSilence() {
        val lastState = SilentAlertState(
            lastSoundAt = 100_000L,
            lastPriority = PanicEventPriority.NORMAL
        )
        val result = shouldPlaySound(
            priority = PanicEventPriority.HIGH,
            lastState = lastState,
            now = 110_000L
        )
        assertThat(result).isTrue()
    }
}
