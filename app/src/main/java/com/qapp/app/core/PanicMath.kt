package com.qapp.app.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object PanicMath {

    fun distanceKm(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    fun priorityForDistance(distanceKm: Double): PanicEventPriority {
        return when {
            distanceKm <= 0.5 -> PanicEventPriority.CRITICAL
            distanceKm <= 2.0 -> PanicEventPriority.HIGH
            distanceKm <= 5.0 -> PanicEventPriority.NORMAL
            else -> PanicEventPriority.LOW
        }
    }

    fun priorityRank(priority: PanicEventPriority): Int {
        return when (priority) {
            PanicEventPriority.CRITICAL -> 3
            PanicEventPriority.HIGH -> 2
            PanicEventPriority.NORMAL -> 1
            PanicEventPriority.LOW -> 0
        }
    }

    private const val EARTH_RADIUS_KM = 6371.0
}
