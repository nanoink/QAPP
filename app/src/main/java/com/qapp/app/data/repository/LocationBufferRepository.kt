package com.qapp.app.data.repository

import android.content.Context
import com.qapp.app.domain.model.BufferedLocation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocationBufferRepository(
    context: Context,
    private val maxSize: Int = 50
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun add(location: BufferedLocation) {
        synchronized(lock) {
            val list = getAllInternal().toMutableList()
            list.add(location)
            val trimmed = if (list.size > maxSize) {
                list.takeLast(maxSize)
            } else {
                list
            }
            saveInternal(trimmed)
        }
    }

    fun getAll(): List<BufferedLocation> {
        synchronized(lock) {
            return getAllInternal()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return getAllInternal().size
        }
    }

    fun removeFirst(count: Int) {
        synchronized(lock) {
            val list = getAllInternal()
            if (count >= list.size) {
                clear()
                return
            }
            saveInternal(list.drop(count))
        }
    }

    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY_BUFFER).apply()
        }
    }

    private fun getAllInternal(): List<BufferedLocation> {
        val raw = prefs.getString(KEY_BUFFER, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<BufferedLocation>>(raw)
        } catch (_: Exception) {
            prefs.edit().remove(KEY_BUFFER).apply()
            emptyList()
        }
    }

    private fun saveInternal(list: List<BufferedLocation>) {
        val raw = json.encodeToString(list)
        prefs.edit().putString(KEY_BUFFER, raw).apply()
    }

    private companion object {
        private const val PREFS_NAME = "qapp_location_buffer"
        private const val KEY_BUFFER = "pending_locations"
    }
}
