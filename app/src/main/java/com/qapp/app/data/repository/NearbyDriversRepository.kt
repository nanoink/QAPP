package com.qapp.app.data.repository

import com.qapp.app.core.SupabaseClientProvider
import com.qapp.app.nearby.DriverLocation
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.Serializable

class NearbyDriversRepository(
    private val client: SupabaseClient = SupabaseClientProvider.client
) {

    suspend fun fetchOnlineDrivers(): List<DriverLocation> {
        val result = client.postgrest["drivers"].select(columns = Columns.list("id,name,lat,lng")) {
            filter {
                eq("is_online", true)
                filterNot("lat", FilterOperator.IS, null)
                filterNot("lng", FilterOperator.IS, null)
            }
        }
        return result.decodeList<OnlineDriverRow>().mapNotNull { row ->
            val lat = row.lat ?: return@mapNotNull null
            val lng = row.lng ?: return@mapNotNull null
            DriverLocation(
                id = row.id,
                name = row.name ?: row.id,
                lat = lat,
                lng = lng
            )
        }
    }
}

@Serializable
private data class OnlineDriverRow(
    val id: String,
    val name: String? = null,
    val lat: Double? = null,
    val lng: Double? = null
)
