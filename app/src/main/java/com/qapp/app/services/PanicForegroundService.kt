package com.qapp.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qapp.app.R
import com.qapp.app.core.CoreConfig
import com.qapp.app.core.OnlineStateManager
import com.qapp.app.data.repository.AuthRepository

class PanicForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        OnlineStateManager.init(applicationContext)
        createNotificationChannel()
        if (!OnlineStateManager.isOnline()) {
            stopSelf()
            return
        }
        startForegroundWithType()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                if (!OnlineStateManager.isOnline()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val panicIntent = Intent(this, PanicActionReceiver::class.java).apply {
            action = PanicActionReceiver.ACTION_PANIC
        }
        val offlineIntent = Intent(this, PanicActionReceiver::class.java).apply {
            action = PanicActionReceiver.ACTION_OFFLINE
        }
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            android.app.PendingIntent.FLAG_IMMUTABLE
        val panicPendingIntent = android.app.PendingIntent.getBroadcast(
            this,
            0,
            panicIntent,
            flags
        )
        val offlinePendingIntent = android.app.PendingIntent.getBroadcast(
            this,
            1,
            offlineIntent,
            flags
        )

        return NotificationCompat.Builder(this, CoreConfig.PANIC_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("QAPP ativo")
            .setContentText("Acesso rapido ao panico")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action(
                    0,
                    "PANICO",
                    panicPendingIntent
                )
            )
            .addAction(
                NotificationCompat.Action(
                    0,
                    "Ficar offline",
                    offlinePendingIntent
                )
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.util.Log.d(
                "PANIC_SERVICE",
                "Starting foreground service with type LOCATION"
            )
            startForeground(
                CoreConfig.PANIC_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            android.util.Log.d(
                "PANIC_SERVICE",
                "Starting foreground service without type (legacy)"
            )
            startForeground(CoreConfig.PANIC_NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CoreConfig.PANIC_CHANNEL_ID,
                "QAPP Pânico",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Acesso rapido ao panico via notificacao."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val ACTION_STOP = "com.qapp.app.action.PANIC_NOTIFICATION_STOP"

        fun startIfOnline(context: Context) {
            OnlineStateManager.init(context)
            if (!AuthRepository().isAuthenticated()) {
                android.util.Log.d(
                    "PANIC_SERVICE",
                    "Not starting foreground service: user not authenticated"
                )
                return
            }
            if (!OnlineStateManager.isOnline()) return
            val intent = Intent(context, PanicForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PanicForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
