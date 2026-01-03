package com.qapp.app.core

class PanicAntiSpamManager {

    data class PanicEventMeta(
        val eventId: String,
        val driverId: String,
        val lat: Double,
        val lng: Double,
        val timestamp: Long
    )

    enum class Decision {
        ACCEPT,
        GLOBAL_LIMIT,
        DRIVER_LIMIT,
        SPATIAL_DUPLICATE
    }

    private var lastAlertTimestampGlobal: Long = 0L
    private val lastAlertPerDriver = LinkedHashMap<String, Long>()
    private val recentEvents = ArrayList<PanicEventMeta>()

    fun checkAndRecord(meta: PanicEventMeta): Decision {
        prune(meta.timestamp)
        if (lastAlertTimestampGlobal > 0L &&
            meta.timestamp - lastAlertTimestampGlobal < GLOBAL_MIN_INTERVAL_MS
        ) {
            return Decision.GLOBAL_LIMIT
        }
        val lastDriver = lastAlertPerDriver[meta.driverId]
        if (lastDriver != null &&
            meta.timestamp - lastDriver < DRIVER_MIN_INTERVAL_MS
        ) {
            return Decision.DRIVER_LIMIT
        }
        if (isSpatialDuplicate(meta)) {
            return Decision.SPATIAL_DUPLICATE
        }
        lastAlertTimestampGlobal = meta.timestamp
        lastAlertPerDriver[meta.driverId] = meta.timestamp
        recentEvents.add(meta)
        return Decision.ACCEPT
    }

    private fun isSpatialDuplicate(meta: PanicEventMeta): Boolean {
        for (event in recentEvents) {
            val elapsed = meta.timestamp - event.timestamp
            if (elapsed < 0L || elapsed > SPATIAL_WINDOW_MS) {
                continue
            }
            val distanceMeters = PanicMath.distanceKm(
                event.lat,
                event.lng,
                meta.lat,
                meta.lng
            ) * 1000.0
            if (distanceMeters <= SPATIAL_DISTANCE_METERS) {
                return true
            }
        }
        return false
    }

    private fun prune(nowMs: Long) {
        val iterator = recentEvents.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (nowMs - event.timestamp > RECENT_TTL_MS) {
                iterator.remove()
            }
        }
        val driverIterator = lastAlertPerDriver.entries.iterator()
        while (driverIterator.hasNext()) {
            val entry = driverIterator.next()
            if (nowMs - entry.value > RECENT_TTL_MS) {
                driverIterator.remove()
            }
        }
    }

    companion object {
        private const val GLOBAL_MIN_INTERVAL_MS = 15_000L
        private const val DRIVER_MIN_INTERVAL_MS = 60_000L
        private const val SPATIAL_WINDOW_MS = 30_000L
        private const val SPATIAL_DISTANCE_METERS = 200.0
        private const val RECENT_TTL_MS = 2 * 60 * 1000L
    }
}
