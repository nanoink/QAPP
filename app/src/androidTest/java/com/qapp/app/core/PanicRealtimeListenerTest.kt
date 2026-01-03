package com.qapp.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qapp.app.data.repository.DriverLocation
import com.qapp.app.data.repository.PanicEventRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PanicRealtimeListenerTest {

    private val scope = CoroutineScope(Dispatchers.Default)

    @After
    fun tearDown() {
        SecurityStateStore.setState(SecurityState.OFFLINE)
    }

    @Test
    fun eventWithinRadiusTriggersAlert() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecurityStateStore.init(context)
        SecurityStateStore.setState(SecurityState.ONLINE)
        LocationStateStore.init(context)
        LocationStateStore.update(0.0, 0.0, System.currentTimeMillis())

        val listener = PanicRealtimeListener(
            context = context,
            locationStore = LocationStateStore,
            scope = scope,
            radiusKm = 10.0
        )
        val latch = CountDownLatch(1)
        listener.start(
            onAlertStarted = { _: PanicEventRecord, _, _, _ -> latch.countDown() },
            onAlertLocation = { _: DriverLocation -> },
            onAlertEnded = {},
            onSystemStatus = {}
        )
        runBlocking {
            RealtimeManager.emitTestAlert(
                PanicAlertMessage.Panic(
                    PanicAlertPayload(
                        panicEventId = "event-1",
                        driverId = "driver-2",
                        latitude = 0.01,
                        longitude = 0.01
                    )
                )
            )
        }
        val received = latch.await(2, TimeUnit.SECONDS)
        listener.stop()
        assertThat(received).isTrue()
    }

    @Test
    fun eventOutsideRadiusIsIgnored() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecurityStateStore.init(context)
        SecurityStateStore.setState(SecurityState.ONLINE)
        LocationStateStore.init(context)
        LocationStateStore.update(0.0, 0.0, System.currentTimeMillis())

        val listener = PanicRealtimeListener(
            context = context,
            locationStore = LocationStateStore,
            scope = scope,
            radiusKm = 10.0
        )
        val latch = CountDownLatch(1)
        listener.start(
            onAlertStarted = { _: PanicEventRecord, _, _, _ -> latch.countDown() },
            onAlertLocation = { _: DriverLocation -> },
            onAlertEnded = {},
            onSystemStatus = {}
        )
        runBlocking {
            RealtimeManager.emitTestAlert(
                PanicAlertMessage.Panic(
                    PanicAlertPayload(
                        panicEventId = "event-2",
                        driverId = "driver-3",
                        latitude = 1.0,
                        longitude = 1.0
                    )
                )
            )
        }
        val received = latch.await(1, TimeUnit.SECONDS)
        listener.stop()
        assertThat(received).isFalse()
    }

    @Test
    fun criticalEventIsAlwaysShown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecurityStateStore.init(context)
        SecurityStateStore.setState(SecurityState.ONLINE)
        LocationStateStore.init(context)
        LocationStateStore.update(0.0, 0.0, System.currentTimeMillis())

        val listener = PanicRealtimeListener(
            context = context,
            locationStore = LocationStateStore,
            scope = scope,
            radiusKm = 10.0
        )
        val latch = CountDownLatch(1)
        listener.start(
            onAlertStarted = { _: PanicEventRecord, _, _, _ -> latch.countDown() },
            onAlertLocation = { _: DriverLocation -> },
            onAlertEnded = {},
            onSystemStatus = {}
        )
        runBlocking {
            RealtimeManager.emitTestAlert(
                PanicAlertMessage.Panic(
                    PanicAlertPayload(
                        panicEventId = "event-3",
                        driverId = "driver-4",
                        latitude = 0.002,
                        longitude = 0.002
                    )
                )
            )
        }
        val received = latch.await(2, TimeUnit.SECONDS)
        listener.stop()
        assertThat(received).isTrue()
    }
}
