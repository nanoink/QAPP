package com.qapp.app.nearby

import android.location.Location

fun distanceKm(
    lat1: Double,
    lng1: Double,
    lat2: Double,
    lng2: Double
): Double {
    val results = FloatArray(1)
    Location.distanceBetween(lat1, lng1, lat2, lng2, results)
    return results[0].toDouble() / 1000.0
}
