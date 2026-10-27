package dev.belta.macropad.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/**
 * The keyboard and mouse over Classic Bluetooth (the HID Device profile, Android 9+), for tablets
 * that ship the profile (Samsung does; Fire OS doesn't). Unlike BLE, the tablet keeps one public
 * address and can connect to the computer itself, so it reconnects on its own: while no computer
 * is connected it calls the one it served last (or the one chosen with "switch computer") every
 * few seconds, and at once when Bluetooth comes back, the computer shows up or the screen wakes.
 *
 * Same report descriptor and report payloads as the BLE keyboard ([HidReports]).
 */
@SuppressLint("MissingPermission")
class ClassicHidLink(private val context: Context, private val adapter: BluetoothAdapter, private val callbacks: Callbacks) {

    interface Callbacks {
        /** The profile is ready (true), or this tablet can't be a Classic keyboard (false). */
        fun onReady(ok: Boolean, detail: String)
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected(device: BluetoothDevice)
        /** The computer wrote the keyboard's LED output report (Num / Caps / Scroll Lock). */
        fun onLeds(value: Int)
        /** Address to reconnect to: the chosen computer, else the last one served; null = wait to be paired. */
        fun reconnectTarget(): String?
        fun event(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { main.post(it) }
    private var proxy: BluetoothHidDevice? = null
    private var registered = false
    private var started = false
    @Volatile var host: BluetoothDevice? = null
        private set
    private var attemptsSince = 0L
    private var receiverRegistered = false

    /** Binds the HID Device profile and registers the keyboard; false if the profile doesn't exist here. */
    fun start(name: String): Boolean {
        if (started) return true
        started = true
        val bound = try {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                    proxy = p as BluetoothHidDevice
                    register(name)
                }

                override fun onServiceDisconnected(profile: Int) {
                    proxy = null
                    registered = false
                }
            }, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            false
        }
        if (!bound) {
            started = false
            return false
        }
        // A tablet without the profile may never answer; give up after a while.
        main.postDelayed(proxyTimeout, PROXY_TIMEOUT_MS)
        if (!receiverRegistered) {
            context.registerReceiver(nudges, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED).apply {
                addAction(Intent.ACTION_SCREEN_ON)
            })
            receiverRegistered = true
        }
        return true
    }

    private val proxyTimeout = Runnable {
        if (proxy == null) callbacks.onReady(false, "Classic Bluetooth keyboard isn't available on this tablet")
    }

    private fun register(name: String) {
        main.removeCallbacks(proxyTimeout)
        val hid = proxy ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(name, "Keyboard and mouse", "Macropad",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidReports.DESCRIPTOR)
        val ok = try {
            hid.registerApp(sdp, null, null, executor, callback)
        } catch (e: Exception) {
            callbacks.event("Classic keyboard registration failed: $e")
            false
        }
        if (!ok) callbacks.onReady(false, "Could not register the Classic Bluetooth keyboard")
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            context.unregisterReceiver(nudges)
            receiverRegistered = false
        }
        proxy?.let { hid ->
            host?.let { runCatching { hid.disconnect(it) } }
            runCatching { hid.unregisterApp() }
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        }
        proxy = null
        registered = false
        started = false
        host = null
    }

    /** Sends one input report; [payload] excludes the report ID, as for BLE. */
    fun send(reportId: Byte, payload: ByteArray): Boolean {
        val hid = proxy ?: return false
        val device = host ?: return false
        return try {
            hid.sendReport(device, reportId.toInt(), payload)
        } catch (e: Exception) {
            false
        }
    }

    fun disconnect(device: BluetoothDevice) {
        proxy?.let { runCatching { it.disconnect(device) } }
    }

    /** Try to reach the computer now (e.g. after "switch computer"), then keep trying. */
    fun reconnectNow() {
        attemptsSince = System.currentTimeMillis()
        main.removeCallbacks(reconnect)
        main.post(reconnect)
    }

    private val reconnect = object : Runnable {
        override fun run() {
            val hid = proxy ?: return
            if (!registered || host != null) return
            val target = callbacks.reconnectTarget()?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
            if (target != null && target.bondState == BluetoothDevice.BOND_BONDED) {
                runCatching { hid.connect(target) }
            }
            // Quickly at first (the computer is probably just back), then gently.
            val fast = System.currentTimeMillis() - attemptsSince < FAST_RETRY_FOR_MS
            main.postDelayed(this, if (fast) FAST_RETRY_MS else SLOW_RETRY_MS)
        }
    }

    /** A known computer showed up (any link) or the tablet woke: try at once. */
    private val nudges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (host != null || !registered) return
            if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (device.address != callbacks.reconnectTarget()) return
            }
            reconnectNow()
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@ClassicHidLink.registered = registered
            if (registered) {
                callbacks.onReady(true, "Classic Bluetooth keyboard ready")
                reconnectNow()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    main.removeCallbacks(reconnect)
                    callbacks.onConnected(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (device == host || host == null) {
                    host = null
                    callbacks.onDisconnected(device)
                    reconnectNow()
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            // Nothing is held between reports, so the current state is always "nothing pressed".
            val empty = when (id) {
                HidReports.ID_KEYBOARD -> if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) ByteArray(1) else ByteArray(8)
                HidReports.ID_CONSUMER -> ByteArray(2)
                HidReports.ID_MOUSE -> ByteArray(5)
                else -> null
            }
            runCatching {
                if (empty != null) proxy?.replyReport(device, type, id, empty)
                else proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id == HidReports.ID_KEYBOARD) leds(data)
            runCatching { proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray?) {
            if (reportId == HidReports.ID_KEYBOARD) leds(data)
        }
    }

    private fun leds(data: ByteArray?) {
        data?.firstOrNull()?.toInt()?.let { callbacks.onLeds(it and 0xFF) }
    }

    private companion object {
        const val PROXY_TIMEOUT_MS = 4_000L
        const val FAST_RETRY_MS = 5_000L
        const val FAST_RETRY_FOR_MS = 2 * 60_000L
        const val SLOW_RETRY_MS = 30_000L
    }
}
