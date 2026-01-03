package com.qapp.app.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.qapp.app.R

class AlertSoundManager(private val context: Context) {

    private var loopPlayer: MediaPlayer? = null
    private var muted = false
    private var allowInSilentMode = true
    private var loopVolume = 1.0f

    fun startLoop(volume: Float = loopVolume) {
        if (muted) return
        if (loopPlayer?.isPlaying == true) return
        loopVolume = volume
        val player = MediaPlayer.create(context, R.raw.alert_alarm) ?: return
        applyAudioAttributes(player)
        player.isLooping = true
        player.setVolume(loopVolume, loopVolume)
        player.start()
        loopPlayer = player
    }

    fun stop() {
        loopPlayer?.stop()
        loopPlayer?.release()
        loopPlayer = null
    }

    fun playEndTone() {
        val player = MediaPlayer.create(context, R.raw.alert_end) ?: return
        applyAudioAttributes(player)
        player.setOnCompletionListener { it.release() }
        player.start()
    }

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
        if (muted) {
            stop()
        } else {
            startLoop(loopVolume)
        }
    }

    fun setIntensity(volume: Float) {
        loopVolume = volume
        loopPlayer?.setVolume(loopVolume, loopVolume)
    }

    fun setAllowInSilentMode(allowed: Boolean) {
        allowInSilentMode = allowed
    }

    fun isMuted(): Boolean = muted

    private fun applyAudioAttributes(player: MediaPlayer) {
        val usage = if (allowInSilentMode) {
            AudioAttributes.USAGE_ALARM
        } else {
            AudioAttributes.USAGE_NOTIFICATION
        }
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
    }
}
