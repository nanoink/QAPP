package com.qapp.app.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PanicAntiSpamManagerTest {

    @Test
    fun globalRateLimitBlocksFrequentEvents() {
        val manager = PanicAntiSpamManager()
        val first = PanicAntiSpamManager.PanicEventMeta(
            eventId = "e1",
            driverId = "d1",
            lat = 0.0,
            lng = 0.0,
            timestamp = 100_000L
        )
        val second = PanicAntiSpamManager.PanicEventMeta(
            eventId = "e2",
            driverId = "d2",
            lat = 0.1,
            lng = 0.1,
            timestamp = 105_000L
        )
        assertThat(manager.checkAndRecord(first)).isEqualTo(PanicAntiSpamManager.Decision.ACCEPT)
        assertThat(manager.checkAndRecord(second)).isEqualTo(PanicAntiSpamManager.Decision.GLOBAL_LIMIT)
    }

    @Test
    fun driverRateLimitBlocksSameDriver() {
        val manager = PanicAntiSpamManager()
        val first = PanicAntiSpamManager.PanicEventMeta(
            eventId = "e1",
            driverId = "driver",
            lat = 0.0,
            lng = 0.0,
            timestamp = 200_000L
        )
        val second = PanicAntiSpamManager.PanicEventMeta(
            eventId = "e2",
            driverId = "driver",
            lat = 0.2,
            lng = 0.2,
            timestamp = 230_000L
        )
        assertThat(manager.checkAndRecord(first)).isEqualTo(PanicAntiSpamManager.Decision.ACCEPT)
        assertThat(manager.checkAndRecord(second)).isEqualTo(PanicAntiSpamManager.Decision.DRIVER_LIMIT)
    }

    @Test
    fun spatialDuplicateBlocksNearbyEvents() {
        val manager = PanicAntiSpamManager()
        val first = PanicAntiSpamManager.PanicEventMeta(
            eventId = "e1",
            driverId = "d1",
            lat = -23.0,
            lng = -43.0,
            timestamp = 300_000L
        )
        val second = PanicAntiSpamManager.PanicEventMeta(
            eventId = "e2",
            driverId = "d2",
            lat = -23.0,
            lng = -43.001,
            timestamp = 316_000L
        )
        assertThat(manager.checkAndRecord(first)).isEqualTo(PanicAntiSpamManager.Decision.ACCEPT)
        assertThat(manager.checkAndRecord(second)).isEqualTo(PanicAntiSpamManager.Decision.SPATIAL_DUPLICATE)
    }
}
