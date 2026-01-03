package com.qapp.app.core

import com.google.common.truth.Truth.assertThat
import com.qapp.app.data.repository.PanicDataSource
import com.qapp.app.domain.PanicManager
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import android.location.Location

class PanicVoiceOrchestratorTest {

    @Test
    fun keywordDetectedTriggersPanic() = runTest {
        val triggered = AtomicBoolean(false)
        val orchestrator = PanicVoiceOrchestrator(
            panicManager = PanicManager(
                repository = FakePanicDataSource(),
                userIdProvider = { "driver-1" },
                panicStarter = { reason ->
                    if (reason == "VOICE") {
                        triggered.set(true)
                    }
                }
            ),
            sessionManager = FakeSessionManager(authenticated = true),
            panicStateStore = FakePanicStateStore(online = true, panicActive = false)
        )

        orchestrator.onKeywordDetected("socorro")

        assertThat(triggered.get()).isTrue()
    }

    @Test
    fun keywordDetectedBlockedWhenOffline() = runTest {
        val triggered = AtomicBoolean(false)
        val orchestrator = PanicVoiceOrchestrator(
            panicManager = PanicManager(
                repository = FakePanicDataSource(),
                userIdProvider = { "driver-1" },
                panicStarter = { triggered.set(true) }
            ),
            sessionManager = FakeSessionManager(authenticated = true),
            panicStateStore = FakePanicStateStore(online = false, panicActive = false)
        )

        orchestrator.onKeywordDetected("socorro")

        assertThat(triggered.get()).isFalse()
    }

    @Test
    fun keywordDetectedBlockedWhenPanicActive() = runTest {
        val triggered = AtomicBoolean(false)
        val orchestrator = PanicVoiceOrchestrator(
            panicManager = PanicManager(
                repository = FakePanicDataSource(),
                userIdProvider = { "driver-1" },
                panicStarter = { triggered.set(true) }
            ),
            sessionManager = FakeSessionManager(authenticated = true),
            panicStateStore = FakePanicStateStore(online = true, panicActive = true)
        )

        orchestrator.onKeywordDetected("socorro")

        assertThat(triggered.get()).isFalse()
    }

    @Test
    fun keywordDetectedBlockedWhenUnauthenticated() = runTest {
        val triggered = AtomicBoolean(false)
        val orchestrator = PanicVoiceOrchestrator(
            panicManager = PanicManager(
                repository = FakePanicDataSource(),
                userIdProvider = { "driver-1" },
                panicStarter = { triggered.set(true) }
            ),
            sessionManager = FakeSessionManager(authenticated = false),
            panicStateStore = FakePanicStateStore(online = true, panicActive = false)
        )

        orchestrator.onKeywordDetected("socorro")

        assertThat(triggered.get()).isFalse()
    }

    private class FakeSessionManager(private val authenticated: Boolean) : SessionManager {
        override fun isAuthenticated(): Boolean = authenticated
    }

    private class FakePanicStateStore(
        private val online: Boolean,
        private val panicActive: Boolean
    ) : PanicStateStore {
        override fun isOnline(): Boolean = online
        override fun isPanicActive(): Boolean = panicActive
    }

    private class FakePanicDataSource : PanicDataSource {
        override suspend fun findActiveEventId(driverId: String): String? = null
        override suspend fun createPanicEvent(location: Location): Result<UUID> =
            Result.success(UUID.randomUUID())
        override suspend fun resolvePanicEvent(eventId: UUID): Result<Int> = Result.success(1)
        override suspend fun isPanicEventEnded(eventId: UUID): Boolean = false
    }
}
