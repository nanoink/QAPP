package com.qapp.app.data.repository

import android.location.Location
import android.util.Log
import com.qapp.app.core.DefensiveModeManager
import com.qapp.app.core.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

interface PanicDataSource {
    suspend fun findActiveEventId(driverId: String): String?
    suspend fun createPanicEvent(location: Location, vehicle: VehicleRecord): Result<UUID>
    suspend fun updatePanicLocation(eventId: String, lat: Double, lng: Double): Result<Unit>
    suspend fun resolvePanicEvent(eventId: UUID): Result<Int>
    suspend fun isPanicEventEnded(eventId: UUID): Boolean
}

class PanicRepository : PanicDataSource {

    private val client = SupabaseClientProvider.client
    private val logTag = "QAPP_PANIC"

    override suspend fun findActiveEventId(driverId: String): String? {
        val result = client.postgrest["panic_events"].select {
            filter {
                eq("driver_id", driverId)
                eq("is_active", true)
            }
            limit(1)
        }
        return result.decodeList<ActivePanicEvent>().firstOrNull()?.id
    }

    override suspend fun createPanicEvent(
        location: Location,
        vehicle: VehicleRecord
    ): Result<UUID> {
        val userId = client.auth.currentUserOrNull()?.id
        if (userId.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Missing user id"))
        }
        Log.i(logTag, "PANIC_INSERT_ATTEMPT")
        val startedAt = formatUtcTimestamp(System.currentTimeMillis())
        val locationPoint = formatPoint(location.longitude, location.latitude)
        val groupId = getDriverGroupId(userId)
        val basePayload = PanicEventInsertBase(
            driverId = userId,
            isActive = true,
            startedAt = startedAt,
            location = locationPoint
        )
        val result = if (!groupId.isNullOrBlank()) {
            insertPayload(
                PanicEventInsertWithGroup(
                    driverId = userId,
                    groupId = groupId,
                    isActive = true,
                    startedAt = startedAt,
                    location = locationPoint
                )
            )
        } else {
            insertPayload(basePayload)
        }
        result.onSuccess { id ->
            Log.i(logTag, "PANIC_INSERT_SUCCESS event_id=$id")
        }.onFailure { error ->
            Log.e(logTag, "PANIC_INSERT_FAILED reason=${error.message}", error)
        }
        return result
    }

    override suspend fun resolvePanicEvent(eventId: UUID): Result<Int> {
        val update = PanicEventResolveUpdate(
            isActive = false,
            endedAt = formatUtcTimestamp(System.currentTimeMillis())
        )
        return try {
            val result = client.postgrest["panic_events"].update(update) {
                filter {
                    eq("id", eventId.toString())
                    eq("is_active", true)
                }
                count(Count.EXACT)
            }
            val updated = result.countOrNull()?.toInt() ?: result.decodeList<ActivePanicEvent>().size
            Result.success(updated)
        } catch (e: Exception) {
            if (e is SerializationException) {
                DefensiveModeManager.recordSerializationError()
            }
            Log.e(logTag, "PANIC_RESOLVE_ERROR: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updatePanicLocation(
        eventId: String,
        lat: Double,
        lng: Double
    ): Result<Unit> {
        val update = PanicEventLocationUpdate(
            location = formatGeographyPoint(lng, lat)
        )
        return try {
            client.postgrest["panic_events"].update(update) {
                filter { eq("id", eventId) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "PANIC_LOCATION_UPDATE_FAILED error=${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun isPanicEventEnded(eventId: UUID): Boolean {
        return try {
            val result = client.postgrest["panic_events"].select {
                filter { eq("id", eventId.toString()) }
                limit(1)
            }
            val record = result.decodeList<PanicEventStatus>().firstOrNull()
            record?.endedAt != null || record?.isActive == false
        } catch (e: Exception) {
            Log.e(logTag, "PANIC_STATUS_ERROR: ${e.message}", e)
            false
        }
    }

    private fun formatUtcTimestamp(epochMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMillis))
    }

    private fun formatPoint(lng: Double, lat: Double): String {
        return "POINT($lng $lat)"
    }

    private fun formatGeographyPoint(lng: Double, lat: Double): String {
        return "SRID=4326;POINT($lng $lat)"
    }

    private suspend fun insertPayload(payload: PanicEventInsertBase): Result<UUID> {
        return try {
            val result = client.postgrest["panic_events"].insert(payload) {
                select(columns = Columns.list("id"))
            }
            val id = result.decodeList<ActivePanicEvent>().firstOrNull()?.id
            if (id.isNullOrBlank()) {
                Result.failure(IllegalStateException("Missing panic event id"))
            } else {
                Result.success(UUID.fromString(id))
            }
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "PANIC_INSERT_ERROR: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun insertPayload(payload: PanicEventInsertWithGroup): Result<UUID> {
        return try {
            val result = client.postgrest["panic_events"].insert(payload) {
                select(columns = Columns.list("id"))
            }
            val id = result.decodeList<ActivePanicEvent>().firstOrNull()?.id
            if (id.isNullOrBlank()) {
                Result.failure(IllegalStateException("Missing panic event id"))
            } else {
                Result.success(UUID.fromString(id))
            }
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "PANIC_INSERT_ERROR: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun getDriverGroupId(driverId: String): String? {
        return try {
            val result = client.postgrest["drivers"].select {
                filter { eq("id", driverId) }
                limit(1)
            }
            result.decodeList<DriverGroupRecord>()
                .firstOrNull()
                ?.groupId
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "Failed to load driver group_id", e)
            null
        }
    }

    private fun recordSerializationFailure(exception: Exception) {
        if (exception is SerializationException) {
            DefensiveModeManager.recordSerializationError()
        }
    }
}

@Serializable
data class PanicEventInsertBase(
    @SerialName("driver_id")
    val driverId: String,
    @SerialName("is_active")
    val isActive: Boolean,
    @SerialName("started_at")
    val startedAt: String,
    val location: String
)

@Serializable
data class PanicEventInsertWithGroup(
    @SerialName("driver_id")
    val driverId: String,
    @SerialName("group_id")
    val groupId: String,
    @SerialName("is_active")
    val isActive: Boolean,
    @SerialName("started_at")
    val startedAt: String,
    val location: String
)

@Serializable
data class PanicEventResolveUpdate(
    @SerialName("is_active")
    val isActive: Boolean,
    @SerialName("ended_at")
    val endedAt: String
)

@Serializable
data class PanicEventLocationUpdate(
    val location: String
)

@Serializable
data class ActivePanicEvent(
    val id: String
)

@Serializable
data class PanicEventStatus(
    val id: String,
    @SerialName("ended_at")
    val endedAt: String? = null,
    @SerialName("is_active")
    val isActive: Boolean? = null
)

@Serializable
data class DriverGroupRecord(
    @SerialName("group_id")
    val groupId: String? = null
)
