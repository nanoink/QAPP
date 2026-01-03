package com.qapp.app.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import com.qapp.app.services.CoreSecurityService
import com.qapp.app.R
import kotlin.math.abs

class PanicOverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var progressBar: ProgressBar? = null
    private var holdStartMs = 0L
    private var holdTriggered = false
    private val holdDurationMs = 3_000L
    private var touchSlop = 0
    private var downX = 0f
    private var downY = 0f
    private var alertMoved = false

    override fun onCreate() {
        super.onCreate()
        OnlineStateManager.init(applicationContext)
        if (!OverlayController.isPermissionGranted(this)) {
            stopSelf()
            return
        }
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        rootView?.let { windowManager.removeView(it) }
        rootView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createOverlay() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val handle = object : View(this) {
            override fun performClick(): Boolean {
                return super.performClick()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(12)
            )
            setBackgroundColor(0x66FFFFFF)
        }

        val alertButton = object : Button(this) {
            override fun performClick(): Boolean {
                return super.performClick()
            }
        }.apply {
            text = getString(R.string.overlay_alert)
        }

        val openButton = Button(this).apply {
            text = getString(R.string.overlay_open_app)
        }

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = holdDurationMs.toInt()
            progress = 0
        }
        progressBar = progress

        container.addView(handle)
        container.addView(alertButton)
        container.addView(progress)
        container.addView(openButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(120)
        }

        handle.setOnTouchListener(DragTouchListener(params))

        alertButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!OnlineStateManager.isOnline()) return@setOnTouchListener true
                    holdStartMs = System.currentTimeMillis()
                    holdTriggered = false
                    downX = event.rawX
                    downY = event.rawY
                    alertMoved = false
                    progress.progress = 0
                    startHoldProgress()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > touchSlop ||
                        abs(event.rawY - downY) > touchSlop
                    ) {
                        alertMoved = true
                        cancelHold()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelHold()
                    if (!alertMoved && !holdTriggered) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        openButton.setOnClickListener {
            val intent = Intent().setClassName(packageName, "com.qapp.app.MainActivity").apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        }

        windowManager.addView(container, params)
        rootView = container
    }

    private fun startHoldProgress() {
        mainHandler.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - holdStartMs
                progressBar?.progress = elapsed.toInt().coerceAtMost(holdDurationMs.toInt())
                if (elapsed >= holdDurationMs && !holdTriggered) {
                    holdTriggered = true
                    progressBar?.progress = holdDurationMs.toInt()
                    triggerPanic()
                    return
                }
                if (!holdTriggered) {
                    mainHandler.postDelayed(this, 50)
                }
            }
        })
    }

    private fun cancelHold() {
        mainHandler.removeCallbacksAndMessages(null)
        progressBar?.progress = 0
        holdTriggered = false
    }

    private fun triggerPanic() {
        toneBeep()
        vibrate()
        CoreSecurityService.triggerPanic(this, "overlay")
    }

    private fun toneBeep() {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    private fun vibrate() {
        val vibrator = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(120)
        }
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }

    private inner class DragTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {

        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    if (abs(event.rawX - touchX) > touchSlop ||
                        abs(event.rawY - touchY) > touchSlop
                    ) {
                        moved = true
                    }
                    windowManager.updateViewLayout(rootView, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    snapToEdge(params)
                    windowManager.updateViewLayout(rootView, params)
                    if (!moved) {
                        v.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val middle = metrics.widthPixels / 2
        params.x = if (params.x + dp(48) < middle) 0 else metrics.widthPixels - dp(120)
    }
}
