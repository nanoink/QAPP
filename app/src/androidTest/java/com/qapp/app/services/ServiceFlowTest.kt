package com.qapp.app.services

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ServiceScenario
import com.google.common.truth.Truth.assertThat
import com.qapp.app.core.SecurityState
import com.qapp.app.core.SecurityStateStore
import org.junit.Test

class ServiceFlowTest {

    @Test
    fun locationServiceStartsWhenOnline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecurityStateStore.init(context)
        SecurityStateStore.setState(SecurityState.ONLINE)
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_START
        }
        ServiceScenario.launch<LocationService>(intent).use {
            assertThat(LocationService.getLastHeartbeatAt()).isGreaterThan(0L)
        }
    }

    @Test
    fun voiceTriggerServiceStartsWhenOnline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecurityStateStore.init(context)
        SecurityStateStore.setState(SecurityState.ONLINE)
        val intent = Intent(context, VoiceTriggerService::class.java).apply {
            action = VoiceTriggerService.ACTION_START
        }
        ServiceScenario.launch<VoiceTriggerService>(intent).use {
            assertThat(VoiceTriggerService.getLastHeartbeatAt()).isGreaterThan(0L)
        }
    }

    @Test
    fun servicesStopWhenOffline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecurityStateStore.init(context)
        SecurityStateStore.setState(SecurityState.OFFLINE)
        val locationIntent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP
        }
        val voiceIntent = Intent(context, VoiceTriggerService::class.java).apply {
            action = VoiceTriggerService.ACTION_STOP
        }
        ServiceScenario.launch<LocationService>(locationIntent).close()
        ServiceScenario.launch<VoiceTriggerService>(voiceIntent).close()
        assertThat(SecurityStateStore.isOnline()).isFalse()
    }
}
