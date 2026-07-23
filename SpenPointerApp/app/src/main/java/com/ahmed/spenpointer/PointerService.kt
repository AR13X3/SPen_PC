package com.ahmed.spenpointer

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that owns the S Pen connection and the WebSocket, so the
 * pointer keeps working while the app is in the background. The Activity binds
 * to this only to drive the UI; all event processing lives here.
 */
class PointerService : Service(), SpenRemoteManager.Callback, EventSocketClient.Listener {

    inner class LocalBinder : Binder() {
        val service: PointerService get() = this@PointerService
    }

    /** The Activity implements this to reflect service state in the UI. */
    interface UiListener {
        fun onStateChanged()
        fun onLastEvent(text: String)
    }

    companion object {
        const val ACTION_START = "com.ahmed.spenpointer.START"
        const val ACTION_STOP = "com.ahmed.spenpointer.STOP"

        private const val CHANNEL_ID = "spen_pointer"
        private const val NOTIF_ID = 1

        private const val SENSITIVITY_GAIN = 10f
        private const val DOUBLE_PRESS_WINDOW_MS = 400L
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var spenManager: SpenRemoteManager
    private lateinit var socketClient: EventSocketClient

    // --- Tunables pushed from the Activity's sliders ---
    @Volatile private var sensitivity = 250f      // effective multiplier
    @Volatile private var smoothAlpha = 0.42f

    // --- Event-processing state ---
    private var lastMotionUiUpdate = 0L
    private var lastButtonDownTime = 0L
    private var suppressClickUp = false

    // --- Observable state (read by the Activity) ---
    @Volatile var spenConnected = false; private set
    @Volatile var pcConnected = false; private set
    var spenStatusText = "Disconnected"; private set
    var pcStatusText = "Disconnected"; private set
    var buttonSupported = false; private set
    var airMotionSupported = false; private set
    var lastEventText = "–"; private set

    private var uiListener: UiListener? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        spenManager = SpenRemoteManager(this, this)
        socketClient = EventSocketClient(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            else -> ensureForeground()
        }
        return START_STICKY
    }

    // ===== Controls called by the Activity =====

    /** Caller must already hold BLUETOOTH_CONNECT and pass an Activity (SDK requires it). */
    fun connectSpen(activity: Activity) {
        ensureForeground()
        spenManager.connect(activity)
    }

    fun disconnectSpen() {
        spenManager.disconnect()
        spenConnected = false
        spenStatusText = "Disconnected"
        pushState()
        maybeStopIfIdle()
    }

    fun connectPc(host: String, port: Int) {
        ensureForeground()
        pcStatusText = "Connecting…"
        pushState()
        socketClient.connect(host, port)
    }

    fun disconnectPc() {
        socketClient.disconnect()
    }

    fun setSensitivity(sliderPos: Float) {
        sensitivity = sliderPos * SENSITIVITY_GAIN
    }

    fun setSmoothing(alpha: Float) {
        smoothAlpha = alpha
        if (socketClient.isConnected) socketClient.sendSmoothing(alpha)
    }

    fun centerCursor(): Boolean {
        if (!socketClient.isConnected) return false
        socketClient.sendCenterEvent()
        return true
    }

    fun registerUiListener(listener: UiListener) {
        uiListener = listener
    }

    fun unregisterUiListener(listener: UiListener) {
        if (uiListener === listener) uiListener = null
    }

    private fun stopEverything() {
        spenManager.disconnect()
        socketClient.disconnect()
        spenConnected = false
        pcConnected = false
        spenStatusText = "Disconnected"
        pcStatusText = "Disconnected"
        mainHandler.post { uiListener?.onStateChanged() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    /** If nothing is connected anymore, drop out of the foreground. */
    private fun maybeStopIfIdle() {
        if (!spenConnected && !pcConnected) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
        }
    }

    // ===== SpenRemoteManager.Callback =====

    override fun onSpenConnected(buttonSupported: Boolean, airMotionSupported: Boolean) {
        spenConnected = true
        this.buttonSupported = buttonSupported
        this.airMotionSupported = airMotionSupported
        spenStatusText = "Connected"
        pushState()
    }

    override fun onSpenConnectFailed(errorCode: Int) {
        spenConnected = false
        spenStatusText = "Connect failed (error $errorCode)"
        pushState()
        maybeStopIfIdle()
    }

    override fun onSpenDisconnected(state: Int) {
        spenConnected = false
        spenStatusText = "Disconnected (state $state)"
        pushState()
    }

    override fun onButtonEvent(pressed: Boolean) {
        if (pressed) {
            val now = System.currentTimeMillis()
            if (now - lastButtonDownTime <= DOUBLE_PRESS_WINDOW_MS) {
                socketClient.sendCenterEvent()
                suppressClickUp = true
                lastButtonDownTime = 0L
                pushLastEvent("BUTTON: double-press → center")
                return
            }
            lastButtonDownTime = now
            pushLastEvent("BUTTON DOWN")
            socketClient.sendButtonEvent(true)
        } else {
            if (suppressClickUp) {
                suppressClickUp = false
                return
            }
            pushLastEvent("BUTTON UP")
            socketClient.sendButtonEvent(false)
        }
    }

    override fun onAirMotionEvent(deltaX: Float, deltaY: Float) {
        socketClient.queueMotion(deltaX * sensitivity, -deltaY * sensitivity)
        val now = System.currentTimeMillis()
        if (now - lastMotionUiUpdate >= 100) {
            lastMotionUiUpdate = now
            pushLastEvent("MOTION dx=%.3f dy=%.3f".format(deltaX, deltaY))
        }
    }

    // ===== EventSocketClient.Listener =====

    override fun onSocketConnected() {
        pcConnected = true
        pcStatusText = "Connected"
        socketClient.sendSmoothing(smoothAlpha)
        pushState()
    }

    override fun onSocketDisconnected(reason: String) {
        pcConnected = false
        pcStatusText = "Disconnected ($reason)"
        pushState()
        maybeStopIfIdle()
    }

    // ===== State plumbing =====

    private fun pushState() {
        mainHandler.post {
            updateNotification()
            uiListener?.onStateChanged()
        }
    }

    private fun pushLastEvent(text: String) {
        lastEventText = text
        mainHandler.post { uiListener?.onLastEvent(text) }
    }

    // ===== Foreground / notification =====

    private fun ensureForeground() {
        if (isForeground) return
        // FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE exists since API 29; minSdk is 31.
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        isForeground = true
    }

    private fun updateNotification() {
        if (!isForeground) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PointerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("S Pen Pointer")
            .setContentText("S Pen: ${if (spenConnected) "on" else "off"} · PC: ${if (pcConnected) "on" else "off"}")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "S Pen Pointer", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps the S Pen pointer running in the background" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
