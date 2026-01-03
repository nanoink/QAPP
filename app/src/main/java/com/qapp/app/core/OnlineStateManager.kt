package com.qapp.app.core

import android.content.Context

object OnlineStateManager {

    fun init(context: Context) {
        SecurityStateStore.init(context)
    }

    fun isOnline(): Boolean {
        return SecurityStateStore.isOnline()
    }

    fun setOnline(isOnline: Boolean) {
        val current = SecurityStateStore.getState()
        val next = if (isOnline) {
            if (current == SecurityState.PANIC) current else SecurityState.ONLINE
        } else {
            SecurityState.OFFLINE
        }
        SecurityStateStore.setState(next)
    }
}
