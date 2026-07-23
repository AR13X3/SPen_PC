package com.ahmed.spenpointer

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * WebSocket client to the PC-side Python listener. The phone is the client.
 *
 * Motion handling is *coalesced*: instead of sending one WebSocket frame per
 * air-motion event (which floods the TCP send queue and makes the cursor lag
 * behind your hand), we accumulate deltas and flush the sum on a fixed timer.
 * This bounds the message rate so the queue can't back up, and it disables
 * Nagle's algorithm (tcpNoDelay) so each frame goes out immediately.
 */
class EventSocketClient(private val listener: Listener) {

    interface Listener {
        fun onSocketConnected()
        fun onSocketDisconnected(reason: String)
    }

    companion object {
        // ~83 flushes/sec. Small enough to feel instant, capped so we never
        // send faster than the link can drain.
        private const val FLUSH_INTERVAL_MS = 12L
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .socketFactory(NoDelaySocketFactory())
        .build()

    private var webSocket: WebSocket? = null

    // Accumulated, un-sent motion. Guarded by [motionLock].
    private val motionLock = Any()
    private var pendingDx = 0f
    private var pendingDy = 0f

    private val flusher = Executors.newSingleThreadScheduledExecutor()
    private var flushTask: ScheduledFuture<*>? = null

    val isConnected: Boolean
        get() = webSocket != null

    fun connect(host: String, port: Int) {
        disconnect()

        val request = Request.Builder()
            .url("ws://$host:$port")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onSocketConnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@EventSocketClient.webSocket = null
                listener.onSocketDisconnected(t.message ?: "connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@EventSocketClient.webSocket = null
                listener.onSocketDisconnected("closed ($code): $reason")
            }
        })

        flushTask = flusher.scheduleAtFixedRate(
            { flushMotion() }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    fun disconnect() {
        flushTask?.cancel(false)
        flushTask = null
        synchronized(motionLock) {
            pendingDx = 0f
            pendingDy = 0f
        }
        webSocket?.close(1000, "client closing")
        webSocket = null
    }

    /** Accumulate a motion delta; it is actually sent by the flush timer. */
    fun queueMotion(deltaX: Float, deltaY: Float) {
        synchronized(motionLock) {
            pendingDx += deltaX
            pendingDy += deltaY
        }
    }

    private fun flushMotion() {
        val dx: Float
        val dy: Float
        synchronized(motionLock) {
            dx = pendingDx
            dy = pendingDy
            pendingDx = 0f
            pendingDy = 0f
        }
        if (dx == 0f && dy == 0f) return
        val json = JSONObject()
            .put("type", "motion")
            .put("dx", dx)
            .put("dy", dy)
        webSocket?.send(json.toString())
    }

    /** Tell the PC how strongly to ease cursor motion (alpha 0.05 = smoothest .. 1.0 = snappy). */
    fun sendSmoothing(alpha: Float) {
        val json = JSONObject().put("type", "config").put("smooth_alpha", alpha)
        webSocket?.send(json.toString())
    }

    /** Warp the PC cursor to screen center. Clears queued motion so it doesn't fight the recenter. */
    fun sendCenterEvent() {
        synchronized(motionLock) {
            pendingDx = 0f
            pendingDy = 0f
        }
        val json = JSONObject().put("type", "center")
        webSocket?.send(json.toString())
    }

    /** Buttons are rare and important, so send them immediately (not coalesced). */
    fun sendButtonEvent(pressed: Boolean) {
        val json = JSONObject()
            .put("type", "button")
            .put("action", if (pressed) "down" else "up")
        webSocket?.send(json.toString())
    }

    /** SocketFactory that disables Nagle's algorithm on every socket OkHttp creates. */
    private class NoDelaySocketFactory : SocketFactory() {
        private val delegate = getDefault()

        private fun tuned(socket: Socket): Socket {
            socket.tcpNoDelay = true
            return socket
        }

        override fun createSocket(): Socket = tuned(delegate.createSocket())

        override fun createSocket(host: String?, port: Int): Socket =
            tuned(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            tuned(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            tuned(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            tuned(delegate.createSocket(address, port, localAddress, localPort))
    }
}
