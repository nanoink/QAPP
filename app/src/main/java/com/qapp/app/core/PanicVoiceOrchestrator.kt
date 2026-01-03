package com.qapp.app.core

import android.util.Log
import com.qapp.app.data.repository.VehicleRepository
import com.qapp.app.domain.PanicManager
import io.github.jan.supabase.gotrue.auth

interface SessionManager {
    fun isAuthenticated(): Boolean
}

class SupabaseSessionManager : SessionManager {
    override fun isAuthenticated(): Boolean {
        return SupabaseClientProvider.client.auth.currentSessionOrNull() != null
    }
}

class StoredSessionManager : SessionManager {
    override fun isAuthenticated(): Boolean {
        return SecuritySessionStore.isAuthenticated()
    }
}

interface PanicStateStore {
    fun isOnline(): Boolean
    fun isPanicActive(): Boolean
}

class SecurityPanicStateStore : PanicStateStore {
    override fun isOnline(): Boolean = SecurityStateStore.isOnline()
    override fun isPanicActive(): Boolean = PanicStateManager.isPanicActive()
}

class PanicVoiceOrchestrator(
    private val panicManager: PanicManager,
    private val sessionManager: SessionManager,
    private val panicStateStore: PanicStateStore,
    private val vehicleRepository: VehicleRepository = VehicleRepository(),
    private val onTriggered: (() -> Unit)? = null
) {

    suspend fun onKeywordDetected(keyword: String) {
        Log.i(TAG, "VOICE_KEYWORD_DISPATCH keyword=$keyword")
        val isAuthed = sessionManager.isAuthenticated()
        Log.i(TAG, "AUTH_STATE=${if (isAuthed) "AUTHENTICATED" else "NOT_AUTHENTICATED"}")
        if (!isAuthed) {
            Log.w(TAG, "VOICE_PANIC_BLOCKED reason=not_authenticated")
            return
        }
        if (!panicStateStore.isOnline()) {
            Log.w(TAG, "VOICE_PANIC_BLOCKED reason=offline")
            return
        }
        if (panicStateStore.isPanicActive()) {
            Log.w(TAG, "VOICE_PANIC_BLOCKED reason=panic_already_active")
            return
        }
        val vehicleStatus = vehicleRepository.getActiveVehicleStatus()
        if (!vehicleStatus.hasAny) {
            Log.w(TAG, "NO_VEHICLE_REGISTERED")
            Log.w(TAG, "PANIC_BLOCKED_NO_ACTIVE_VEHICLE")
            return
        }
        if (vehicleStatus.vehicle == null) {
            Log.w(TAG, "NO_ACTIVE_VEHICLE")
            Log.w(TAG, "PANIC_BLOCKED_NO_ACTIVE_VEHICLE")
            return
        }
        Log.i(TAG, "VOICE_PANIC_TRIGGER dispatching panic start")
        panicManager.startFromVoice()
        onTriggered?.invoke()
    }

    companion object {
        private const val TAG = "PANIC_VOICE"
    }
}
