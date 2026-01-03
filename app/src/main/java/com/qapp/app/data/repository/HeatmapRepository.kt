package com.qapp.app.data.repository

import android.util.Log
import com.qapp.app.core.DefensiveModeManager
import com.qapp.app.core.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.round

class HeatmapRepository {

    private val client = SupabaseClientProvider.client
    private val logTag = "QAPP_HEATMAP"

    private var cache: CacheEntry? = null

    suspend fun loadHeatmap(
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double
    ): List<HeatmapPoint> {
        if (DefensiveModeManager.isEnabled()) {
            Log.w(logTag, "[HEATMAP] disabled due to defensive mode")
            return emptyList()
        }
        val now = System.currentTimeMillis()
        val key = CacheKey(
            lat = roundTo(centerLat, 3),
            lng = roundTo(centerLng, 3),
            radiusKm = radiusKm
        )
        val cached = cache
        if (cached != null &&
            cached.key == key &&
            now - cached.fetchedAt <= CACHE_TTL_MS
        ) {
            Log.i(logTag, "[HEATMAP] cache_hit=true")
            return cached.points
        }
        val params = HeatmapParams(
            lat = centerLat,
            lng = centerLng,
            radiusKm = radiusKm,
            limitCount = MAX_POINTS
        )
        val result = client.postgrest.rpc("get_panic_heatmap", params)
        val points = result.decodeList<HeatmapPoint>()
        val grouped = aggregatePoints(points).take(MAX_POINTS)
        cache = CacheEntry(key, grouped, now)
        Log.i(logTag, "[HEATMAP] loaded_points=${grouped.size} radius=${radiusKm}km")
        return grouped
    }

    private fun aggregatePoints(points: List<HeatmapPoint>): List<HeatmapPoint> {
        if (points.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, HeatmapPoint>()
        for (point in points) {
            val lat = roundTo(point.lat, 4)
            val lng = roundTo(point.lng, 4)
            val key = "$lat:$lng"
            val existing = grouped[key]
            if (existing == null) {
                grouped[key] = point.copy(lat = lat, lng = lng)
            } else {
                grouped[key] = existing.copy(weight = existing.weight + point.weight)
            }
        }
        return grouped.values.toList()
    }

    private fun roundTo(value: Double, decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return round(value * factor) / factor
    }

    private data class CacheKey(
        val lat: Double,
        val lng: Double,
        val radiusKm: Double
    )

    private data class CacheEntry(
        val key: CacheKey,
        val points: List<HeatmapPoint>,
        val fetchedAt: Long
    )

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val MAX_POINTS = 500
    }
}

@Serializable
data class HeatmapParams(
    val lat: Double,
    val lng: Double,
    @SerialName("radius_km")
    val radiusKm: Double,
    @SerialName("limit_count")
    val limitCount: Int
)

@Serializable
data class HeatmapPoint(
    val lat: Double,
    val lng: Double,
    val weight: Int
)
