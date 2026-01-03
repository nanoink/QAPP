package com.qapp.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qapp.app.core.SecurityState
import com.qapp.app.core.SecurityStateStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LocationRepositoryTest {

    @Test
    fun sendLocationUpdatesDriverWhenOnline() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecurityStateStore.init(context)
        SecurityStateStore.setState(SecurityState.ONLINE)
        val fakeDriver = FakeDriverUpdater()
        val repository = LocationRepository(
            context = context,
            driverRepository = fakeDriver,
            connectivityChecker = { true }
        )
        repository.sendLocation(10.0, 20.0, 5f)
        assertThat(fakeDriver.updateCalls).isEqualTo(1)
    }

    @Test
    fun sendLocationSkippedWhenOffline() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecurityStateStore.init(context)
        SecurityStateStore.setState(SecurityState.OFFLINE)
        val fakeDriver = FakeDriverUpdater()
        val repository = LocationRepository(
            context = context,
            driverRepository = fakeDriver,
            connectivityChecker = { true }
        )
        repository.sendLocation(10.0, 20.0, 5f)
        assertThat(fakeDriver.updateCalls).isEqualTo(0)
    }

    private class FakeDriverUpdater : DriverLocationUpdater {
        var updateCalls = 0

        override fun hasValidSession(): Boolean = true

        override suspend fun updateLocation(lat: Double, lng: Double): Boolean {
            updateCalls++
            return true
        }
    }
}
