package com.qapp.app.data.repository

import android.util.Log
import com.qapp.app.core.ActiveVehicleStore
import com.qapp.app.core.DefensiveModeManager
import com.qapp.app.core.PanicStateManager
import com.qapp.app.core.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Count
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

class VehicleRepository(
    private val client: io.github.jan.supabase.SupabaseClient = SupabaseClientProvider.client
) {

    private val logTag = "QAPP_VEHICLES"

    suspend fun getVehicles(): List<VehicleRecord> {
        val userId = currentUserId() ?: return emptyList()
        return try {
            val result = client.postgrest["vehicles"].select {
                filter { eq("driver_id", userId) }
            }
            result.decodeList<VehicleRecord>()
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "Failed to load vehicles", e)
            emptyList()
        }
    }

    suspend fun getActiveVehicle(): VehicleRecord? {
        val userId = currentUserId() ?: return null
        return try {
            val result = client.postgrest["vehicles"].select {
                filter {
                    eq("driver_id", userId)
                    eq("is_active", true)
                }
                limit(1)
            }
            result.decodeList<VehicleRecord>().firstOrNull()
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "Failed to load active vehicle", e)
            null
        }
    }

    suspend fun getActiveVehicleStatus(): ActiveVehicleStatus {
        val userId = currentUserId()
        if (userId.isNullOrBlank()) {
            return ActiveVehicleStatus(null, hasAny = false)
        }
        val vehicles = getVehicles()
        val active = vehicles.firstOrNull { it.isActive == true }
        com.qapp.app.core.VehicleSelectionStore.set(active?.id)
        ActiveVehicleStore.set(active)
        return ActiveVehicleStatus(active, hasAny = vehicles.isNotEmpty())
    }

    suspend fun createVehicle(
        input: VehicleUpsert,
        setActive: Boolean
    ): Result<VehicleRecord> {
        val userId = currentUserId()
        if (userId.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Missing user id"))
        }
        val normalized = input.normalized()
        val isFirst = hasAnyVehicle(userId).not()
        val shouldActivate = if (isFirst) true else setActive
        if (shouldActivate && PanicStateManager.isPanicActive()) {
            return Result.failure(IllegalStateException("panic_active"))
        }
        return try {
            if (shouldActivate) {
                deactivateAllVehicles(userId)
            }
            val payload = VehicleInsert(
                driverId = userId,
                make = normalized.make,
                model = normalized.model,
                color = normalized.color,
                plate = normalized.plate,
                isActive = shouldActivate
            )
            val result = client.postgrest["vehicles"].insert(payload) {
                select()
            }
            val created = result.decodeList<VehicleRecord>().firstOrNull()
            if (created == null) {
                Result.failure(IllegalStateException("Vehicle insert failed"))
            } else {
                if (shouldActivate) {
                    Log.i(logTag, "ACTIVE_VEHICLE_SELECTED id=${created.id}")
                    com.qapp.app.core.VehicleSelectionStore.set(created.id)
                    ActiveVehicleStore.set(created)
                }
                Result.success(created)
            }
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "Failed to create vehicle", e)
            Result.failure(e)
        }
    }

    suspend fun updateVehicle(
        vehicleId: String,
        input: VehicleUpsert,
        setActive: Boolean
    ): Result<Unit> {
        val userId = currentUserId()
        if (userId.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Missing user id"))
        }
        val normalized = input.normalized()
        if (setActive && PanicStateManager.isPanicActive()) {
            return Result.failure(IllegalStateException("panic_active"))
        }
        return try {
            if (setActive) {
                deactivateAllVehicles(userId)
            }
            val payload = VehicleUpdate(
                make = normalized.make,
                model = normalized.model,
                color = normalized.color,
                plate = normalized.plate,
                isActive = setActive
            )
            client.postgrest["vehicles"].update(payload) {
                filter {
                    eq("id", vehicleId)
                    eq("driver_id", userId)
                }
            }
            if (setActive) {
                Log.i(logTag, "ACTIVE_VEHICLE_SELECTED id=$vehicleId")
                com.qapp.app.core.VehicleSelectionStore.set(vehicleId)
                ActiveVehicleStore.set(
                    VehicleRecord(
                        id = vehicleId,
                        driverId = userId,
                        make = normalized.make,
                        model = normalized.model,
                        color = normalized.color,
                        plate = normalized.plate,
                        isActive = true
                    )
                )
            } else {
                val current = ActiveVehicleStore.get()
                if (current?.id == vehicleId) {
                    ActiveVehicleStore.clear()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "Failed to update vehicle", e)
            Result.failure(e)
        }
    }

    suspend fun setActiveVehicle(vehicleId: String): Boolean {
        val userId = currentUserId()
        if (userId.isNullOrBlank()) return false
        if (PanicStateManager.isPanicActive()) {
            return false
        }
        return try {
            deactivateAllVehicles(userId)
            val result = client.postgrest["vehicles"].update(
                VehicleActiveUpdate(isActive = true)
            ) {
                filter {
                    eq("id", vehicleId)
                    eq("driver_id", userId)
                }
                count(Count.EXACT)
            }
            val updatedRows = result.countOrNull()?.toInt() ?: 0
            if (updatedRows > 0) {
                Log.i(logTag, "ACTIVE_VEHICLE_SELECTED id=$vehicleId")
                com.qapp.app.core.VehicleSelectionStore.set(vehicleId)
                val active = getVehicles().firstOrNull { it.id == vehicleId }
                ActiveVehicleStore.set(active)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "Failed to set active vehicle", e)
            false
        }
    }

    private suspend fun deactivateAllVehicles(userId: String) {
        client.postgrest["vehicles"].update(
            VehicleActiveUpdate(isActive = false)
        ) {
            filter { eq("driver_id", userId) }
        }
    }

    private suspend fun hasAnyVehicle(userId: String): Boolean {
        return try {
            val result = client.postgrest["vehicles"].select {
                filter { eq("driver_id", userId) }
                limit(1)
            }
            result.decodeList<VehicleRecord>().isNotEmpty()
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "Failed to check vehicles", e)
            false
        }
    }

    private fun currentUserId(): String? {
        val session = client.auth.currentSessionOrNull()
        return session?.user?.id ?: client.auth.currentUserOrNull()?.id
    }

    private fun recordSerializationFailure(exception: Exception) {
        if (exception is SerializationException) {
            DefensiveModeManager.recordSerializationError()
        }
    }
}

data class ActiveVehicleStatus(
    val vehicle: VehicleRecord?,
    val hasAny: Boolean
)

@Serializable
data class VehicleRecord(
    val id: String,
    @SerialName("driver_id")
    val driverId: String,
    @SerialName("brand")
    val make: String,
    val model: String,
    val color: String,
    val plate: String,
    @SerialName("is_active")
    val isActive: Boolean? = null
)

@Serializable
data class VehicleInsert(
    @SerialName("driver_id")
    val driverId: String,
    @SerialName("brand")
    val make: String,
    val model: String,
    val color: String,
    val plate: String,
    @SerialName("is_active")
    val isActive: Boolean
)

@Serializable
data class VehicleUpdate(
    @SerialName("brand")
    val make: String,
    val model: String,
    val color: String,
    val plate: String,
    @SerialName("is_active")
    val isActive: Boolean
)

@Serializable
data class VehicleActiveUpdate(
    @SerialName("is_active")
    val isActive: Boolean
)

data class VehicleUpsert(
    val make: String,
    val model: String,
    val color: String,
    val plate: String
) {
    fun normalized(): VehicleUpsert {
        return VehicleUpsert(
            make = make.trim(),
            model = model.trim(),
            color = color.trim(),
            plate = plate.trim().uppercase()
        )
    }
}
