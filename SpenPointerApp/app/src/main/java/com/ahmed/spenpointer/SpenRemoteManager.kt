package com.ahmed.spenpointer

import android.app.Activity
import android.content.Context
import android.util.Log
import java.lang.ref.WeakReference
import com.samsung.android.sdk.penremote.AirMotionEvent
import com.samsung.android.sdk.penremote.ButtonEvent
import com.samsung.android.sdk.penremote.SpenEvent
import com.samsung.android.sdk.penremote.SpenEventListener
import com.samsung.android.sdk.penremote.SpenRemote
import com.samsung.android.sdk.penremote.SpenUnit
import com.samsung.android.sdk.penremote.SpenUnitManager

/**
 * Wraps the Samsung S Pen Remote SDK connection + button/air-motion listeners.
 *
 * Quirks worth knowing (from the Samsung sample + docs):
 *  - SpenRemote is a process-wide singleton; only ONE app can hold the connection at a time.
 *    If another app (or Samsung's own Air Command) currently owns it, connect() reports
 *    onFailure rather than throwing.
 *  - connect() requires BLUETOOTH_CONNECT to already be GRANTED on Android 12+, or it fails.
 *    This class assumes the caller has already obtained that permission.
 *  - Not every S Pen supports every feature. Always check isFeatureEnabled(...) before
 *    registering a listener for that unit type — the S23 Ultra's BLE S Pen supports both
 *    FEATURE_TYPE_BUTTON and FEATURE_TYPE_AIR_MOTION, but this check is what future-proofs
 *    the code against other S Pen-enabled devices.
 *  - The SpenUnitManager instance is ONLY valid inside/after a successful onSuccess callback;
 *    calling getUnit()/registerSpenEventListener() before that throws.
 *  - This manager is owned by [PointerService] (a foreground service), so the connection
 *    survives backgrounding. Call disconnect() only when the user actually stops the
 *    pointer, which releases the exclusive S Pen claim for other apps.
 */
class SpenRemoteManager(context: Context, private val callback: Callback) {

    // bindService (inside connect) and unbindService (inside disconnect) MUST use the SAME
    // context, or Android throws "Service not registered" and the SDK never releases the S Pen.
    // So we remember the exact Activity used to connect and hand it back at disconnect.
    // WeakReference so a destroyed Activity can still be GC'd (the framework auto-unbinds then).
    private var connectActivity: WeakReference<Activity>? = null

    interface Callback {
        fun onSpenConnected(buttonSupported: Boolean, airMotionSupported: Boolean)
        fun onSpenConnectFailed(errorCode: Int)
        fun onSpenDisconnected(state: Int)
        fun onButtonEvent(pressed: Boolean)
        fun onAirMotionEvent(deltaX: Float, deltaY: Float)
    }

    companion object {
        private const val TAG = "SpenRemoteManager"
    }

    private val spenRemote: SpenRemote = SpenRemote.getInstance()
    private var unitManager: SpenUnitManager? = null

    init {
        spenRemote.setConnectionStateChangeListener { state ->
            if (state == SpenRemote.State.DISCONNECTED ||
                state == SpenRemote.State.DISCONNECTED_BY_UNKNOWN_REASON
            ) {
                unitManager = null
                callback.onSpenDisconnected(state)
            }
        }
    }

    val isConnected: Boolean
        get() = spenRemote.isConnected

    fun isButtonFeatureAvailable(): Boolean = spenRemote.isFeatureEnabled(SpenRemote.FEATURE_TYPE_BUTTON)

    fun isAirMotionFeatureAvailable(): Boolean = spenRemote.isFeatureEnabled(SpenRemote.FEATURE_TYPE_AIR_MOTION)

    /**
     * Caller must already hold BLUETOOTH_CONNECT, and MUST pass an Activity — the Samsung
     * SDK throws IllegalArgumentException ("Activity context is only allowed") otherwise.
     */
    fun connect(activity: Activity) {
        if (spenRemote.isConnected) {
            Log.d(TAG, "Already connected")
            return
        }

        connectActivity = WeakReference(activity)
        spenRemote.connect(activity, object : SpenRemote.ConnectionResultCallback {
            override fun onSuccess(spenUnitManager: SpenUnitManager) {
                unitManager = spenUnitManager
                registerButtonListener()
                registerAirMotionListener()
                callback.onSpenConnected(isButtonFeatureAvailable(), isAirMotionFeatureAvailable())
            }

            override fun onFailure(errorCode: Int) {
                Log.e(TAG, "connect() failed, error=$errorCode")
                callback.onSpenConnectFailed(errorCode)
            }
        })
    }

    fun disconnect() {
        val activity = connectActivity?.get()
        try {
            if (spenRemote.isConnected && activity != null) {
                // Must be the SAME Activity passed to connect(), or unbind throws.
                spenRemote.disconnect(activity)
            }
        } catch (e: Exception) {
            Log.w(TAG, "disconnect() failed: ${e.message}")
        }
        connectActivity = null
        unitManager = null
    }

    private fun registerButtonListener() {
        if (!isButtonFeatureAvailable()) {
            Log.w(TAG, "Button feature not supported on this S Pen/device")
            return
        }
        val unit = unitManager?.getUnit(SpenUnit.TYPE_BUTTON) ?: return
        unitManager?.registerSpenEventListener(buttonEventListener, unit)
    }

    private fun registerAirMotionListener() {
        if (!isAirMotionFeatureAvailable()) {
            Log.w(TAG, "Air motion feature not supported on this S Pen/device")
            return
        }
        val unit = unitManager?.getUnit(SpenUnit.TYPE_AIR_MOTION) ?: return
        unitManager?.registerSpenEventListener(airMotionEventListener, unit)
    }

    private val buttonEventListener = SpenEventListener { event: SpenEvent ->
        val button = ButtonEvent(event)
        callback.onButtonEvent(button.action == ButtonEvent.ACTION_DOWN)
    }

    private val airMotionEventListener = SpenEventListener { event: SpenEvent ->
        val motion = AirMotionEvent(event)
        callback.onAirMotionEvent(motion.deltaX, motion.deltaY)
    }
}
