package com.qapp.app.domain

import android.location.Location
import com.google.common.truth.Truth.assertThat
import com.qapp.app.data.repository.PanicDataSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID

class PanicManagerTest {

    @Test
    fun createEventPersistsWhenNoActive() = runTest {
        val fake = FakePanicDataSource()
        val manager = PanicManager(
            repository = fake,
            userIdProvider = { "driver-1" }
        )
        val location = Location("test").apply {
            latitude = 1.0
            longitude = 2.0
        }
        val result = manager.createEventIfNeeded("button", location)
        assertThat(result.isSuccess).isTrue()
        assertThat(fake.createdEvents.size).isEqualTo(1)
        assertThat(fake.createdEvents.first().driverId).isEqualTo("driver-1")
    }

    @Test
    fun resolveEventUpdatesActiveRow() = runTest {
        val fake = FakePanicDataSource()
        val manager = PanicManager(
            repository = fake,
            userIdProvider = { "driver-1" }
        )
        val location = Location("test").apply {
            latitude = 1.0
            longitude = 2.0
        }
        manager.createEventIfNeeded("button", location)
        val outcome = manager.resolveActiveEvent()
        assertThat(outcome).isEqualTo(PanicResolveOutcome.RESOLVED)
        assertThat(fake.resolveCalls).isEqualTo(1)
    }

    private class FakePanicDataSource : PanicDataSource {
        data class CreatedEvent(val driverId: String, val lat: Double, val lng: Double)

        val createdEvents = mutableListOf<CreatedEvent>()
        var activeEventId: String? = null
        var resolveCalls = 0

        override suspend fun findActiveEventId(driverId: String): String? {
            return activeEventId
        }

        override suspend fun createPanicEvent(location: Location): Result<UUID> {
            val id = UUID.randomUUID()
            createdEvents.add(CreatedEvent("driver-1", location.latitude, location.longitude))
            activeEventId = id.toString()
            return Result.success(id)
        }

        override suspend fun resolvePanicEvent(eventId: UUID): Result<Int> {
            resolveCalls++
            activeEventId = null
            return Result.success(1)
        }

        override suspend fun isPanicEventEnded(eventId: UUID): Boolean {
            return false
        }
    }
}
