package com.qapp.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BufferedLocation(
    @SerialName("lat")
    val lat: Double,
    @SerialName("lng")
    val lng: Double,
    @SerialName("timestamp")
    val timestamp: Long,
    @SerialName("accuracy")
    val accuracy: Float
)
