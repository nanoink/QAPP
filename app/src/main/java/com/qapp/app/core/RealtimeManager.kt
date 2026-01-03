package com.qapp.app.core

import android.util.Log
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object RealtimeManager {

    private const val TAG = "QAPP_REALTIME"
    private const val EVENT_PANIC = "panic"
    private const val EVENT_PANIC_RESOLVED = "panic_resolved"
    private const val EVENT_PANIC_LOCATION = "panic_location"
    private const val CHANNEL_NAME = "panic_events"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = SupabaseClientProvider.client
    private val _alerts = MutableSharedFlow<PanicAlertMessage>(extraBufferCapacity = 32)
    val alerts: SharedFlow<PanicAlertMessage> = _alerts

    @Volatile
    private var permanentlyDisconnected = false
    @Volatile
    private var connected = false

    private var channel: RealtimeChannel? = null
    private var panicJob: Job? = null
    private var resolvedJob: Job? = null
    private var locationJob: Job? = null

    fun connect() {
        permanentlyDisconnected = false
        if (connected) return
        val session = client.auth.currentSessionOrNull()
        if (session == null) {
            Log.w(TAG, "Realtime not connected: missing session")
            return
        }
        RealtimeStateStore.updateState(RealtimeState.CONNECTING)
        scope.launch {
            try {
                if (channel?.topic != "realtime:$CHANNEL_NAME") {
                    panicJob?.cancel()
                    resolvedJob?.cancel()
                    channel?.unsubscribe()
                    channel = client.realtime.channel(CHANNEL_NAME)
                }
                val activeChannel = channel ?: return@launch
                if (panicJob?.isActive != true) {
                    panicJob = scope.launch {
                        activeChannel.broadcastFlow<PanicAlertPayload>(EVENT_PANIC).collect { payload ->
                            _alerts.emit(PanicAlertMessage.Panic(payload))
                        }
                    }
                }
                if (resolvedJob?.isActive != true) {
                    resolvedJob = scope.launch {
                        activeChannel.broadcastFlow<PanicResolvedPayload>(EVENT_PANIC_RESOLVED).collect { payload ->
                            _alerts.emit(PanicAlertMessage.Resolved(payload))
                        }
                    }
                }
                if (locationJob?.isActive != true) {
                    locationJob = scope.launch {
                        activeChannel.broadcastFlow<PanicLocationPayload>(EVENT_PANIC_LOCATION).collect { payload ->
                            _alerts.emit(PanicAlertMessage.Location(payload))
                        }
                    }
                }
                activeChannel.subscribe()
                connected = true
                RealtimeStateStore.updateState(RealtimeState.CONNECTED)
                DefensiveModeManager.onRealtimeConnected()
                Log.d(TAG, "Realtime connected channel=$CHANNEL_NAME")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connected = false
                RealtimeStateStore.updateState(RealtimeState.DISCONNECTED)
                DefensiveModeManager.recordRealtimeCrash()
                Log.e(TAG, "Realtime crash", e)
            }
        }
    }

    fun disconnect(permanent: Boolean) {
        if (permanent) {
            permanentlyDisconnected = true
        }
        connected = false
        scope.launch {
            panicJob?.cancel()
            resolvedJob?.cancel()
            locationJob?.cancel()
            channel?.unsubscribe()
            channel = null
            client.realtime.disconnect()
            RealtimeStateStore.updateState(RealtimeState.DISCONNECTED)
            Log.d(TAG, "Realtime disconnected permanent=$permanent")
        }
    }

    suspend fun sendPanicAlert(
        panicId: String,
        driverId: String,
        driverName: String?,
        lat: Double,
        lng: Double,
        vehicle: com.qapp.app.data.repository.VehicleRecord,
        createdAt: String
    ) {
        val payload = PanicAlertPayload(
            panicEventId = panicId,
            driverId = driverId,
            driverName = driverName,
            latitude = lat,
            longitude = lng,
            vehicleId = vehicle.id,
            vehicleBrand = vehicle.make,
            vehicleMake = vehicle.make,
            vehicleModel = vehicle.model,
            vehicleColor = vehicle.color,
            vehiclePlate = vehicle.plate,
            createdAt = createdAt,
            status = "active",
            isActive = true
        )
        val channel = client.realtime.channel(CHANNEL_NAME)
        channel.broadcast(EVENT_PANIC, payload)
        client.realtime.removeChannel(channel)
    }

    suspend fun sendPanicResolved(
        panicId: String,
        driverId: String
    ) {
        val payload = PanicResolvedPayload(
            panicEventId = panicId,
            driverId = driverId
        )
        val channel = client.realtime.channel(CHANNEL_NAME)
        channel.broadcast(EVENT_PANIC_RESOLVED, payload)
        client.realtime.removeChannel(channel)
    }

    suspend fun sendPanicLocationUpdate(
        panicId: String,
        driverId: String,
        lat: Double,
        lng: Double,
        heading: Double?,
        updatedAt: String
    ) {
        val payload = PanicLocationPayload(
            panicEventId = panicId,
            driverId = driverId,
            latitude = lat,
            longitude = lng,
            heading = heading,
            updatedAt = updatedAt
        )
        val channel = client.realtime.channel(CHANNEL_NAME)
        channel.broadcast(EVENT_PANIC_LOCATION, payload)
        client.realtime.removeChannel(channel)
    }

    internal suspend fun emitTestAlert(message: PanicAlertMessage) {
        _alerts.emit(message)
    }

    fun shutdown() {
        permanentlyDisconnected = true
        connected = false
        scope.cancel()
    }

}

sealed class PanicAlertMessage {
    data class Panic(val payload: PanicAlertPayload) : PanicAlertMessage()
    data class Resolved(val payload: PanicResolvedPayload) : PanicAlertMessage()
    data class Location(val payload: PanicLocationPayload) : PanicAlertMessage()
}

@Serializable
data class PanicAlertPayload(
    @SerialName("panic_event_id")
    val panicEventId: String,
    @SerialName("driver_id")
    val driverId: String,
    @SerialName("driver_name")
    val driverName: String? = null,
    @SerialName("latitude")
    val latitude: Double,
    @SerialName("longitude")
    val longitude: Double,
    @SerialName("vehicle_id")
    val vehicleId: String? = null,
    @SerialName("vehicle_brand")
    val vehicleBrand: String? = null,
    @SerialName("vehicle_make")
    val vehicleMake: String? = null,
    @SerialName("vehicle_model")
    val vehicleModel: String? = null,
    @SerialName("vehicle_color")
    val vehicleColor: String? = null,
    @SerialName("vehicle_plate")
    val vehiclePlate: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("status")
    val status: String? = null,
    @SerialName("is_active")
    val isActive: Boolean = true
)

@Serializable
data class PanicResolvedPayload(
    @SerialName("panic_event_id")
    val panicEventId: String,
    @SerialName("driver_id")
    val driverId: String
)

@Serializable
data class PanicLocationPayload(
    @SerialName("panic_event_id")
    val panicEventId: String,
    @SerialName("driver_id")
    val driverId: String,
    @SerialName("latitude")
    val latitude: Double,
    @SerialName("longitude")
    val longitude: Double,
    @SerialName("heading")
    val heading: Double? = null,
    @SerialName("updated_at")
    val updatedAt: String
)
