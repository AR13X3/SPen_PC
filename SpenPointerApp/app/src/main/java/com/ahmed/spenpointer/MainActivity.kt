package com.ahmed.spenpointer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ahmed.spenpointer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), PointerService.UiListener {

    private lateinit var binding: ActivityMainBinding

    private var service: PointerService? = null
    private var bound = false

    companion object {
        private const val ALPHA_SNAPPY = 1.0f      // slider = 1
        private const val ALPHA_SMOOTHEST = 0.15f  // slider = 100
    }

    private fun smoothnessToAlpha(pos: Float): Float {
        val t = (pos - 1f) / 99f
        return ALPHA_SNAPPY - t * (ALPHA_SNAPPY - ALPHA_SMOOTHEST)
    }

    // ===== Service binding =====

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, ibinder: IBinder?) {
            service = (ibinder as PointerService.LocalBinder).service
            bound = true
            service?.registerUiListener(this@MainActivity)
            service?.setSensitivity(binding.sliderSensitivity.value)
            service?.setSmoothing(smoothnessToAlpha(binding.sliderSmoothness.value))
            updateUiFromState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    // ===== Permissions =====

    private val requestBluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startAndConnectSpen()
        } else {
            Toast.makeText(this, "BLUETOOTH_CONNECT permission is required to use the S Pen remote", Toast.LENGTH_LONG).show()
            binding.switchSpen.isChecked = false
        }
    }

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        animateEntrance()

        binding.editHost.setText(PrefsHelper.getHost(this))
        binding.editPort.setText(PrefsHelper.getPort(this).toString())

        val sliderPos = Math.round(PrefsHelper.getSensitivity(this)).toFloat().coerceIn(1f, 100f)
        binding.sliderSensitivity.value = sliderPos
        binding.textSensitivityValue.text = sliderPos.toInt().toString()
        binding.sliderSensitivity.addOnChangeListener { _, value, _ ->
            binding.textSensitivityValue.text = value.toInt().toString()
            PrefsHelper.saveSensitivity(this, value)
            service?.setSensitivity(value)
        }

        val smoothPos = Math.round(PrefsHelper.getSmoothness(this)).toFloat().coerceIn(1f, 100f)
        binding.sliderSmoothness.value = smoothPos
        binding.textSmoothnessValue.text = smoothPos.toInt().toString()
        binding.sliderSmoothness.addOnChangeListener { _, value, _ ->
            binding.textSmoothnessValue.text = value.toInt().toString()
            PrefsHelper.saveSmoothness(this, value)
            service?.setSmoothing(smoothnessToAlpha(value))
        }

        binding.rowCenterCursor.setOnClickListener {
            if (service?.centerCursor() != true) {
                Toast.makeText(this, "Connect to PC first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchSpen.setOnClickListener {
            val wantOn = binding.switchSpen.isChecked
            val svc = service
            if (svc == null) {
                binding.switchSpen.isChecked = false
                return@setOnClickListener
            }
            if (wantOn) connectSpenWithPermissionCheck() else svc.disconnectSpen()
        }

        binding.switchPc.setOnClickListener {
            val wantOn = binding.switchPc.isChecked
            val svc = service
            if (svc == null) {
                binding.switchPc.isChecked = false
                return@setOnClickListener
            }
            if (wantOn) {
                val host = binding.editHost.text.toString().trim()
                val port = binding.editPort.text.toString().trim().toIntOrNull()
                if (host.isEmpty() || port == null) {
                    Toast.makeText(this, "Enter a valid IP address and port", Toast.LENGTH_SHORT).show()
                    binding.switchPc.isChecked = false
                    return@setOnClickListener
                }
                PrefsHelper.saveTarget(this, host, port)
                maybeRequestNotifPermission()
                startForegroundService(Intent(this, PointerService::class.java).setAction(PointerService.ACTION_START))
                svc.connectPc(host, port)
            } else {
                svc.disconnectPc()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PointerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        service?.unregisterUiListener(this)
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun connectSpenWithPermissionCheck() {
        maybeRequestNotifPermission()
        val permission = Manifest.permission.BLUETOOTH_CONNECT
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startAndConnectSpen()
        } else {
            requestBluetoothPermission.launch(permission)
        }
    }

    private fun startAndConnectSpen() {
        startForegroundService(Intent(this, PointerService::class.java).setAction(PointerService.ACTION_START))
        service?.connectSpen(this)
    }

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ===== PointerService.UiListener =====

    override fun onStateChanged() {
        updateUiFromState()
    }

    override fun onLastEvent(text: String) {
        binding.textLastEvent.text = text
        animateEventPop()
    }

    private fun updateUiFromState() {
        val svc = service ?: return

        binding.switchSpen.isChecked = svc.spenConnected
        applyStatus(binding.textSpenStatus, svc.spenStatusText, svc.spenConnected)
        binding.textSpenFeatures.text = getString(
            R.string.spen_features_format,
            if (svc.buttonSupported) "yes" else "no",
            if (svc.airMotionSupported) "yes" else "no"
        )

        binding.switchPc.isChecked = svc.pcConnected
        applyStatus(binding.textWsStatus, svc.pcStatusText, svc.pcConnected)

        binding.textLastEvent.text = svc.lastEventText
    }

    private fun applyStatus(view: TextView, text: String, connected: Boolean) {
        view.text = text
        view.setTextColor(
            ContextCompat.getColor(this, if (connected) R.color.status_connected else R.color.status_disconnected)
        )
    }

    /** Staggered fade + rise for the preference groups on launch. */
    private fun animateEntrance() {
        val groups = listOf(binding.groupConnection, binding.groupAddress, binding.groupCursor, binding.groupStatus)
        groups.forEachIndexed { i, group ->
            group.alpha = 0f
            group.translationY = 40f
            group.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(70L * i)
                .setDuration(340)
                .start()
        }
    }

    /** Small pop when a new S Pen event arrives. */
    private fun animateEventPop() {
        binding.textLastEvent.animate().cancel()
        binding.textLastEvent.scaleX = 1.05f
        binding.textLastEvent.scaleY = 1.05f
        binding.textLastEvent.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
    }
}
