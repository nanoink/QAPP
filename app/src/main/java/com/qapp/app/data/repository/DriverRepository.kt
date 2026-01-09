package com.qapp.app.data.repository

import android.util.Log
import com.qapp.app.core.DefensiveModeManager
import com.qapp.app.core.SupabaseClientProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.user.UserInfo
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Repositorio para acesso a tabela drivers via PostgREST.
 * O app nao cria registros; apenas consulta o driver associado ao auth.users.id.
 */
interface DriverLocationUpdater {
    fun hasValidSession(): Boolean
    suspend fun updateLocation(lat: Double, lng: Double): Boolean
}

class DriverRepository(
    private val supabase: SupabaseClient = SupabaseClientProvider.client
) : DriverLocationUpdater {

    private val logTag = "QAPP_DRIVERS"

    suspend fun ensureDriverExists(): Boolean {
        val session = supabase.auth.currentSessionOrNull()
        val user = supabase.auth.currentUserOrNull()
        val userId = session?.user?.id ?: user?.id
        if (userId.isNullOrBlank()) {
            Log.w(logTag, "Missing session; cannot ensure driver record")
            return false
        }
        Log.d(logTag, "auth.uid=$userId")
        return try {
            val result = supabase.postgrest["drivers"].select {
                filter { eq("id", userId) }
                limit(1)
            }
            val existing = result.decodeList<DriverRecord>()
            if (existing.isNotEmpty()) {
                Log.d(logTag, "Driver record exists")
                true
            } else {
                val email = user?.email
                val name = extractUserName(user) ?: email ?: "Motorista"
                supabase.postgrest["drivers"].insert(
                    DriverInsert(
                        id = userId,
                        name = name,
                        email = email,
                        isOnline = false
                    )
                )
                Log.d(logTag, "Driver record created")
                true
            }
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "Failed to ensure driver record", e)
            false
        }
    }

    /**
     * Retorna o registro do motorista ou null quando nao existe.
     */
    suspend fun getDriverByUserId(userId: String): DriverRecord? {
        val response = supabase.postgrest["drivers"]
            .select {
                filter { eq("id", userId) }
            }

        val drivers = response.decodeList<DriverRecord>()
        return drivers.firstOrNull()
    }

    override fun hasValidSession(): Boolean {
        return supabase.auth.currentSessionOrNull() != null
    }

    suspend fun setOnline(): Boolean = setOnlineState(true)

    suspend fun setOffline(): Boolean = setOnlineState(false)

    suspend fun goOnline(): Boolean = setOnline()

    suspend fun goOffline(): Boolean = setOffline()

    override suspend fun updateLocation(lat: Double, lng: Double): Boolean {
        val session = supabase.auth.currentSessionOrNull()
        val userId = session?.user?.id ?: supabase.auth.currentUserOrNull()?.id
        if (userId.isNullOrBlank()) {
            Log.w(logTag, "Session missing, update skipped")
            return false
        }
        return try {
            supabase.postgrest["drivers"].update(
                DriverLocationUpdate(
                    location = formatPoint(lat, lng),
                    lat = lat,
                    lng = lng,
                    lastSeen = formatUtcTimestamp(System.currentTimeMillis())
                )
            ) {
                filter { eq("id", userId) }
            }
            true
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "Location update failed", e)
            false
        }
    }

    suspend fun getNearbyDrivers(lat: Double, lng: Double, radiusKm: Double): List<NearbyDriver> {
        return try {
            val result = supabase.postgrest.rpc(
                "get_nearby_drivers",
                NearbyDriversParams(
                    lat = lat,
                    lng = lng,
                    radiusKm = radiusKm
                )
            )
            result.decodeList<NearbyDriver>()
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "Failed to fetch nearby drivers", e)
            emptyList()
        }
    }

    private suspend fun setOnlineState(isOnline: Boolean): Boolean {
        val session = supabase.auth.currentSessionOrNull()
        val userId = session?.user?.id ?: supabase.auth.currentUserOrNull()?.id
        if (userId.isNullOrBlank()) {
            Log.w(logTag, "Missing session; cannot update driver online status")
            return false
        }
        Log.d(logTag, "auth.uid=$userId")
        Log.d(logTag, "Updating online status = $isOnline")
        return try {
            val updateResult = supabase.postgrest["drivers"].update(
                DriverOnlineUpdate(
                    isOnline = isOnline,
                    lastSeen = formatUtcTimestamp(System.currentTimeMillis())
                )
            ) {
                filter { eq("id", userId) }
                count(Count.EXACT)
            }
            val updatedRows = updateResult.countOrNull()?.toInt()
                ?: updateResult.decodeList<DriverRecord>().size
            Log.d(logTag, "Update success rows=$updatedRows")
            if (updatedRows <= 0) {
                return false
            }
            true
        } catch (e: Exception) {
            recordSerializationFailure(e)
            Log.e(logTag, "Update failed", e)
            false
        }
    }

    private fun extractUserName(user: UserInfo?): String? {
        val metadata = user?.userMetadata ?: return null
        val keys = listOf("name", "full_name", "username")
        for (key in keys) {
            val value = metadata[key]?.jsonPrimitive?.contentOrNull
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    private fun formatUtcTimestamp(epochMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMillis))
    }

    private fun formatPoint(lat: Double, lng: Double): String {
        return "SRID=4326;POINT($lng $lat)"
    }

    private fun recordSerializationFailure(exception: Exception) {
        if (exception is SerializationException) {
            DefensiveModeManager.recordSerializationError()
        }
    }
}

@Serializable
data class DriverRecord(
    val id: String,
    val name: String? = null,
    @SerialName("is_online")
    val isOnline: Boolean? = null,
    val location: String? = null,
    val city: String? = null,
    @SerialName("group_id")
    val groupId: String? = null,
    @SerialName("owner_id")
    val ownerId: String? = null,
    @SerialName("subscription_status")
    val subscriptionStatus: String? = null,
    val active: Boolean? = null
)

@Serializable
data class DriverOnlineUpdate(
    @SerialName("is_online")
    val isOnline: Boolean,
    @SerialName("last_seen")
    val lastSeen: String
)

@Serializable
data class DriverInsert(
    val id: String,
    val name: String,
    val email: String? = null,
    @SerialName("is_online")
    val isOnline: Boolean
)

@Serializable
data class DriverLocationUpdate(
    val location: String,
    val lat: Double,
    val lng: Double,
    @SerialName("last_seen")
    val lastSeen: String
)

@Serializable
data class NearbyDriver(
    @SerialName("driver_id")
    val driverId: String? = null
)

@Serializable
data class NearbyDriversParams(
    val lat: Double,
    val lng: Double,
    @SerialName("radius_km")
    val radiusKm: Double
)
