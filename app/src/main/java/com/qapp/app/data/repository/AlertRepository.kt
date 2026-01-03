package com.qapp.app.data.repository

import com.qapp.app.core.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class AlertRepository {

    private val client = SupabaseClientProvider.client

    suspend fun getDriverProfile(driverId: String): DriverProfile? {
        val result = client.postgrest["drivers"].select {
            filter { eq("id", driverId) }
            limit(1)
        }
        return result.decodeList<DriverProfile>().firstOrNull()
    }

    suspend fun getVehicleByDriverId(driverId: String): VehicleInfo? {
        val result = client.postgrest["vehicles"].select {
            filter { eq("driver_id", driverId) }
            filter { eq("is_active", true) }
            limit(1)
        }
        return result.decodeList<VehicleInfo>().firstOrNull()
    }
}

@Serializable
data class PanicEventRecord(
    val id: String,
    @SerialName("driver_id")
    val driverId: String,
    @SerialName("driver_name")
    val driverName: String? = null,
    @SerialName("vehicle_id")
    val vehicleId: String? = null,
    @SerialName("is_active")
    val isActive: Boolean = false,
    @SerialName("started_at")
    val startedAt: String? = null,
    @SerialName("ended_at")
    val endedAt: String? = null,
    @SerialName("lat")
    val lat: Double? = null,
    @SerialName("lng")
    val lng: Double? = null,
    @SerialName("vehicle_make")
    val vehicleMake: String? = null,
    @SerialName("vehicle_model")
    val vehicleModel: String? = null,
    @SerialName("vehicle_color")
    val vehicleColor: String? = null,
    @SerialName("vehicle_plate")
    val vehiclePlate: String? = null
) {
    val isResolved: Boolean
        get() = !isActive || endedAt != null
}

@Serializable
data class DriverProfile(
    val name: String,
    val phone: String? = null
)

@Serializable
data class VehicleInfo(
    @SerialName("brand")
    val make: String? = null,
    val model: String? = null,
    val plate: String? = null,
    val color: String? = null
)

@Serializable
data class DriverLocation(
    @SerialName("driver_id")
    val driverId: String,
    @SerialName("lat")
    val latitude: Double,
    @SerialName("lng")
    val longitude: Double,
    @SerialName("heading")
    val heading: Double? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

