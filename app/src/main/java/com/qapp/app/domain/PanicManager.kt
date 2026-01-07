package com.qapp.app.domain

import android.location.Location
import android.util.Log
import com.qapp.app.core.PanicStateManager
import com.qapp.app.data.repository.PanicDataSource
import com.qapp.app.data.repository.PanicRepository
import com.qapp.app.core.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.logging.Logger

/**
 * Gerencia o estado global do modo panico sem depender de UI ou Android Framework.
 * Nao persiste estado; apenas mantem em memoria.
 */
class PanicManager(
    private val repository: PanicDataSource = PanicRepository(),
    private val userIdProvider: () -> String? = {
        SupabaseClientProvider.client.auth.currentSessionOrNull()?.user?.id
            ?: SupabaseClientProvider.client.auth.currentUserOrNull()?.id
    },
    private val panicStarter: ((String) -> Unit)? = null
) {

    private val logger = Logger.getLogger(PanicManager::class.java.name)
    private val createMutex = Mutex()
    @Volatile
    private var activeEventId: UUID? = null
    @Volatile
    private var lastNotifiedDrivers: Set<String> = emptySet()

    /**
     * Estado do modo panico acompanha o armazenamento persistente.
     */
    fun startPanic() {
        if (PanicStateManager.isPanicActive()) {
            logger.info("PanicManager: start ignorado (ja estava ativo)")
        } else {
            logger.info("PanicManager: modo panico ativado")
        }
    }

    fun startPanic(source: String) {
        require(source.isNotBlank()) { "panic source required" }
        startPanic()
    }

    fun startFromVoice() {
        Log.i("QAPP_PANIC", "PANIC_START_FROM_VOICE_CALLED")
        startPanic("VOICE")
        Log.i("QAPP_PANIC", "PANIC_ACTIVATED_FROM_VOICE")
        panicStarter?.invoke("VOICE")
    }

    fun stopPanic() {
        if (PanicStateManager.isPanicActive() || !PanicStateManager.getActiveEventId().isNullOrBlank()) {
            logger.info("PanicManager: solicitacao de parada recebida")
        } else {
            logger.info("PanicManager: stop ignorado (ja estava inativo)")
        }
    }

    fun isPanicActive(): Boolean = PanicStateManager.isPanicActive()

    suspend fun createEventIfNeeded(
        source: String,
        location: Location?,
        vehicle: com.qapp.app.data.repository.VehicleRecord
    ): Result<PanicCreateResult> {
        return createMutex.withLock {
            val userId = userIdProvider()
            if (userId.isNullOrBlank()) {
                return@withLock Result.failure(PanicUserMissingException())
            }
            if (location == null) {
                val error = IllegalStateException("Missing location")
                Log.w("QAPP_PANIC", "PANIC_INSERT_ERROR: ${error.message}")
                return@withLock Result.failure(error)
            }
            val current = activeEventId ?: PanicStateManager.getActiveEventId()?.let { stored ->
                runCatching { UUID.fromString(stored) }.getOrNull()
            }
            if (current != null) {
                activeEventId = current
                return@withLock Result.success(PanicCreateResult(current, created = false))
            }
            val existingId = repository.findActiveEventId(userId)
            if (!existingId.isNullOrBlank()) {
                val parsed = runCatching { UUID.fromString(existingId) }.getOrNull()
                if (parsed != null) {
                    activeEventId = parsed
                    Log.i("QAPP_PANIC", "Panic event already active id=$existingId")
                    return@withLock Result.success(PanicCreateResult(parsed, created = false))
                }
            }
            val normalizedSource = if (source.lowercase().contains("voice")) {
                "voice"
            } else {
                "button"
            }
            Log.i("QAPP_PANIC", "Panic trigger detected ($normalizedSource)")
            val result = repository.createPanicEvent(location, vehicle)
            result.fold(
                onSuccess = { id ->
                    activeEventId = id
                    Log.i("QAPP_PANIC", "PANIC_EVENT_CREATED id=$id")
                },
                onFailure = { }
            )
            result.map { PanicCreateResult(it, created = true) }
        }
    }

    suspend fun resolveActiveEvent(): PanicResolveOutcome {
        val userId = userIdProvider()
        if (userId.isNullOrBlank()) {
            Log.w("QAPP_PANIC", "Panic resolve skipped: missing user")
            return PanicResolveOutcome.FAILED
        }
        val eventId = activeEventId ?: PanicStateManager.getActiveEventId()?.let { stored ->
            runCatching { UUID.fromString(stored) }.getOrNull()
        }
        if (eventId == null) {
            clearActiveEvent()
            return PanicResolveOutcome.MISSING_ID
        }
        val result = repository.resolvePanicEvent(eventId)
        val updated = result.getOrElse { 0 }
        if (updated > 0) {
            Log.i("QAPP_PANIC", "PANIC_FINALIZED_SUCCESS id=$eventId")
            clearActiveEvent()
            return PanicResolveOutcome.RESOLVED
        }
        val alreadyEnded = repository.isPanicEventEnded(eventId)
        if (alreadyEnded) {
            Log.i("QAPP_PANIC", "PANIC_FINALIZED_ALREADY_ENDED id=$eventId")
            clearActiveEvent()
            return PanicResolveOutcome.ALREADY_ENDED
        }
        Log.w("QAPP_PANIC", "PANIC_FINALIZE_FAILED id=$eventId")
        return PanicResolveOutcome.FAILED
    }

    fun getActiveEventId(): String? =
        activeEventId?.toString() ?: PanicStateManager.getActiveEventId()

    fun isPending(): Boolean = PanicStateManager.isPending()

    fun setLastNotifiedDrivers(drivers: Set<String>) {
        lastNotifiedDrivers = drivers
    }

    fun getLastNotifiedDrivers(): Set<String> = lastNotifiedDrivers

    private fun clearActiveEvent() {
        activeEventId = null
        PanicStateManager.setActiveEventId(null)
        PanicStateManager.setPending(false)
        PanicStateManager.deactivatePanic()
        lastNotifiedDrivers = emptySet()
    }
}

data class PanicCreateResult(
    val eventId: UUID,
    val created: Boolean
)

enum class PanicResolveOutcome {
    RESOLVED,
    ALREADY_ENDED,
    FAILED,
    MISSING_ID
}

class PanicUserMissingException : Exception("user_id_unavailable")
