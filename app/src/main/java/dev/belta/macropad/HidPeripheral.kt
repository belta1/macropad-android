package dev.belta.macropad

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Makes the tablet a Bluetooth LE keyboard (HID over GATT profile).
 *
 * Fire OS ships with the classic HID Device profile disabled, but BLE peripheral mode works,
 * and every desktop OS supports BLE keyboards natively — no software on the computer.
 */
@SuppressLint("MissingPermission")
class HidPeripheral(private val context: Context) {

    enum class State { UNAVAILABLE, OFF, STARTING, ADVERTISING, CONNECTED }

    fun interface Listener {
        fun onStateChanged(state: State, detail: String)
    }

    var listener: Listener? = null

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("hid-sender").apply { start() }
    private val sender = Handler(worker.looper)
    private val notificationSent = Semaphore(0)

    private var server: BluetoothGattServer? = null
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private var advertising = false

    @Volatile private var host: BluetoothDevice? = null
    @Volatile var state = State.OFF
        private set
    private var detail = ""

    private lateinit var keyboardInput: BluetoothGattCharacteristic
    private lateinit var consumerInput: BluetoothGattCharacteristic
    private lateinit var batteryLevel: BluetoothGattCharacteristic

    // Per-host CCCD values. Bonded hosts (Windows in particular) expect them to survive reconnects.
    private val cccdStore = context.getSharedPreferences("hid_cccd", Context.MODE_PRIVATE)
    private val knownHosts = context.getSharedPreferences("hid_hosts", Context.MODE_PRIVATE)
    private var reenableBluetooth = false
    private var receiverRegistered = false

    // Keys currently held down by touched buttons (worker thread only).
    private val held = LinkedHashMap<Any, Step.Combo>()

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        val adapter = adapter
        when {
            adapter == null -> return setState(State.UNAVAILABLE, "This device has no Bluetooth")
            !adapter.isEnabled -> return setState(State.OFF, "Bluetooth is off — tap to turn on")
            adapter.bluetoothLeAdvertiser == null || !adapter.isMultipleAdvertisementSupported ->
                return setState(State.UNAVAILABLE, "This tablet can't act as a BLE peripheral")
        }
        if (server != null) return
        if (adapter!!.name != DEVICE_NAME) adapter.name = DEVICE_NAME
        setClassicConnectable(false)
        setState(State.STARTING, "Starting…")
        if (!receiverRegistered) {
            context.registerReceiver(hostProfilesReceiver, IntentFilter(BluetoothDevice.ACTION_UUID).apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            })
            receiverRegistered = true
        }
        server = manager.openGattServer(context, gattCallback)
        if (server == null) return setState(State.UNAVAILABLE, "Could not open GATT server")
        pendingServices.clear()
        pendingServices += hidService()
        pendingServices += batteryService()
        pendingServices += deviceInfoService()
        addNextService()
    }

    fun stop() {
        if (receiverRegistered) {
            context.unregisterReceiver(hostProfilesReceiver)
            receiverRegistered = false
        }
        closeServer()
        setClassicConnectable(true)
        setState(State.OFF, "Bluetooth is off — tap to turn on")
    }

    private fun closeServer() {
        stopAdvertising()
        server?.let { s -> host?.let { s.cancelConnection(it) }; s.close() }
        server = null
        host = null
        sender.post { held.clear() }
    }

    /**
     * Windows only reconnects to a bonded BLE keyboard when the tablet's GATT server and advertising
     * start completely fresh (same as an app restart), so rebuild both after every disconnect.
     */
    private fun rebuildAfterDisconnect() {
        setState(State.STARTING, "Waiting for the computer to reconnect…")
        main.postDelayed({
            closeServer()
            start()
        }, 500)
    }

    /**
     * The tablet's Classic Bluetooth side has the same name as the macropad. If a PC reaches it
     * (e.g. Windows remembering an old Classic pairing), Fire OS deletes the keyboard's bond, so
     * keep Classic non-connectable while the macropad runs. Needs WRITE_SECURE_SETTINGS (granted via adb).
     */
    private fun setClassicConnectable(connectable: Boolean) {
        val mode = if (connectable) SCAN_MODE_CONNECTABLE else SCAN_MODE_NONE
        try {
            val ok = adapter?.javaClass?.getMethod("setScanMode", Int::class.javaPrimitiveType)?.invoke(adapter, mode)
            Log.i(TAG, "classic scan mode -> $mode ($ok)")
        } catch (e: Exception) {
            Log.w(TAG, "could not change classic scan mode: $e")
        }
    }

    /**
     * Forgets (on the tablet) every computer this macropad has served, plus any bonded computer,
     * and restarts Bluetooth. The restart also clears Fire OS's cached "dual-mode" info about them;
     * with that cached, Fire OS keeps opening Classic connections to the PC that tear down the
     * keyboard link. The computers must then remove and re-add "Fire Macropad".
     */
    @Suppress("DEPRECATION")
    fun resetPairing() {
        val adapter = adapter ?: return
        val computers = knownHosts.all.keys.map { adapter.getRemoteDevice(it) } +
            adapter.bondedDevices.filter {
                it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER
            }
        for (device in computers.distinct()) {
            try {
                val removed = device.javaClass.getMethod("removeBond").invoke(device)
                Log.i(TAG, "removeBond ${device.address}: $removed")
            } catch (e: Exception) {
                Log.w(TAG, "removeBond ${device.address} failed: $e")
            }
        }
        knownHosts.edit().clear().apply()
        cccdStore.edit().clear().apply()
        stop()
        setState(State.STARTING, "Restarting Bluetooth…")
        reenableBluetooth = true
        main.postDelayed({ adapter.disable() }, 1000)
    }

    /** Called when the adapter has turned off; completes a [resetPairing] restart. */
    @Suppress("DEPRECATION")
    fun onBluetoothOff() {
        stop()
        if (reenableBluetooth) {
            reenableBluetooth = false
            setState(State.STARTING, "Restarting Bluetooth…")
            main.postDelayed({ adapter?.enable() }, 1000)
        }
    }

    fun restart() {
        stop()
        main.postDelayed({ start() }, 300)
    }

    private fun addNextService() {
        val next = pendingServices.removeFirstOrNull()
        if (next != null) {
            if (server?.addService(next) != true) setState(State.UNAVAILABLE, "Could not add GATT service")
        } else {
            startAdvertising()
        }
    }

    private fun startAdvertising() {
        if (advertising || host != null) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(HID_SERVICE))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        advertising = true
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising", e)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising started")
            setState(State.ADVERTISING, "Waiting for a computer — pair with \"$DEVICE_NAME\"")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            setState(State.UNAVAILABLE, "Advertising failed (error $errorCode)")
        }
    }

    private fun setState(newState: State, newDetail: String) {
        state = newState
        detail = newDetail
        main.post { listener?.onStateChanged(newState, newDetail) }
    }

    fun refreshListener() = main.post { listener?.onStateChanged(state, detail) }

    // ---------------------------------------------------------------- sending

    /** Button touched: hold the combo/media key down until [release]. */
    fun press(token: Any, step: Step) = sender.post {
        when (step) {
            is Step.Combo -> { held[token] = step; sendKeyboard(currentKeys()) }
            is Step.Media -> sendConsumer(step.usage)
            else -> {}
        }
    }

    fun release(token: Any, step: Step) = sender.post {
        when (step) {
            is Step.Combo -> { held.remove(token); sendKeyboard(currentKeys()) }
            is Step.Media -> sendConsumer(0)
            else -> {}
        }
    }

    /** Runs a multi-step macro (taps every combo). */
    fun run(steps: List<Step>) = sender.post {
        for (step in steps) {
            if (host == null) break
            when (step) {
                is Step.Combo -> tap(step.mods, step.keys)
                is Step.Media -> { sendConsumer(step.usage); sendConsumer(0) }
                is Step.Delay -> Thread.sleep(step.ms)
                is Step.Text -> for (c in step.text) {
                    val (mods, key) = HidKeys.charToKey(c) ?: continue
                    tap(mods, listOf(key))
                }
            }
        }
    }

    private fun tap(mods: Int, keys: List<Int>) {
        val base = currentKeys()
        sendKeyboard(Step.Combo(base.mods or mods, (base.keys + keys).distinct()))
        sendKeyboard(base)
    }

    private fun currentKeys(): Step.Combo {
        var mods = 0
        val keys = mutableListOf<Int>()
        for (c in held.values) {
            mods = mods or c.mods
            keys += c.keys
        }
        return Step.Combo(mods, keys.distinct())
    }

    private fun sendKeyboard(combo: Step.Combo) {
        val report = ByteArray(8)
        report[0] = combo.mods.toByte()
        combo.keys.take(6).forEachIndexed { i, k -> report[2 + i] = k.toByte() }
        notify(keyboardInput, report)
    }

    private fun sendConsumer(usage: Int) =
        notify(consumerInput, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8 and 0xFF).toByte()))

    /** Sends one notification and waits until the stack has queued it (worker thread only). */
    private fun notify(characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val s = server ?: return false
        val device = host ?: return false
        notificationSent.drainPermits()
        characteristic.value = value
        val ok = try {
            s.notifyCharacteristicChanged(device, characteristic, false)
        } catch (e: Exception) {
            Log.w(TAG, "notify", e); false
        }
        if (ok) notificationSent.tryAcquire(250, TimeUnit.MILLISECONDS)
        return ok
    }

    // ---------------------------------------------------------------- GATT server

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setState(State.UNAVAILABLE, "Adding service ${service.uuid} failed ($status)")
                return
            }
            addNextService()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "connection ${device.address} status=$status state=$newState bond=${device.bondState}")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                host = device
                knownHosts.edit().putBoolean(device.address, true).apply()
                stopAdvertising()
                // Keeps the link up instead of letting Android drop an "idle" peripheral connection.
                server?.connect(device, false)
                disableAudioProfiles(device)
                setState(State.CONNECTED, "Connected to ${device.name ?: device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && device == host) {
                rebuildAfterDisconnect()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "mtu $mtu")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notificationSent.release()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            Log.i(TAG, "read ${name(characteristic)} offset=$offset")
            if (characteristic.uuid == BATTERY_LEVEL) characteristic.value = byteArrayOf(batteryPercent().toByte())
            respondRead(device, requestId, offset, characteristic.value ?: ByteArray(0))
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            Log.i(TAG, "read descriptor ${descriptor.uuid.short()} of ${name(descriptor.characteristic)} offset=$offset")
            val value = if (descriptor.uuid == CCCD) loadCccd(device, descriptor) else descriptor.value
            respondRead(device, requestId, offset, value ?: byteArrayOf(0, 0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            Log.i(TAG, "write ${name(characteristic)} value=${value?.hex()} response=$responseNeeded")
            // Output report = keyboard LEDs (caps/num lock); protocol mode / control point = suspend etc.
            if (characteristic.uuid != HID_CONTROL_POINT) characteristic.value = value
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            Log.i(TAG, "write descriptor ${descriptor.uuid.short()} of ${name(descriptor.characteristic)} value=${value?.hex()}")
            if (descriptor.uuid == CCCD) saveCccd(device, descriptor, value) else descriptor.value = value
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun cccdKey(device: BluetoothDevice, descriptor: BluetoothGattDescriptor) =
        "${device.address}|${name(descriptor.characteristic)}"

    private fun loadCccd(device: BluetoothDevice, descriptor: BluetoothGattDescriptor): ByteArray =
        when (cccdStore.getInt(cccdKey(device, descriptor), 0)) {
            1 -> byteArrayOf(1, 0)
            2 -> byteArrayOf(2, 0)
            else -> byteArrayOf(0, 0)
        }

    private fun saveCccd(device: BluetoothDevice, descriptor: BluetoothGattDescriptor, value: ByteArray?) {
        cccdStore.edit().putInt(cccdKey(device, descriptor), value?.firstOrNull()?.toInt() ?: 0).apply()
    }

    /**
     * Fire OS treats any bonded computer that advertises audio profiles as headphones and starts
     * connecting A2DP / Hands-Free to it right after pairing; Windows then drops the keyboard link.
     * Turning those profiles off for the host keeps the connection a plain keyboard.
     */
    private val hostProfilesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (device != host) return
            // Runs after Fire OS's own handler has set the profile priorities.
            main.postDelayed({ disableAudioProfiles(device) }, 500)
        }
    }

    private fun disableAudioProfiles(device: BluetoothDevice) {
        // Fire OS reads these per-device priorities from global settings (needs WRITE_SECURE_SETTINGS).
        for (key in listOf("bluetooth_a2dp_sink_priority_", "bluetooth_headset_priority_")) {
            try {
                Settings.Global.putInt(context.contentResolver, key + device.address, 0)
            } catch (e: SecurityException) {
                Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; run: adb shell pm grant ${context.packageName} " +
                    "android.permission.WRITE_SECURE_SETTINGS")
                break
            }
        }
        val adapter = adapter ?: return
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        // Drops an audio connection Fire OS may already have opened (hidden API, best effort).
                        proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(proxy, device)
                        Log.i(TAG, "audio profile $p disconnected for ${device.address}")
                    } catch (e: Exception) {
                        Log.w(TAG, "could not disconnect audio profile $p: $e")
                    } finally {
                        adapter.closeProfileProxy(p, proxy)
                    }
                }

                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }
    }

    private fun name(c: BluetoothGattCharacteristic): String {
        val ref = c.getDescriptor(REPORT_REFERENCE)?.value
        return c.uuid.short() + (ref?.let { " [report id=${it[0]} type=${it[1]}]" } ?: "")
    }

    private fun UUID.short() = toString().substring(4, 8)

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray) {
        val s = server ?: return
        if (offset > value.size) {
            s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
        } else {
            s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }
    }

    private fun batteryPercent(): Int =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100

    // ---------------------------------------------------------------- service definitions

    private fun hidService() = BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(characteristic(HID_INFORMATION, PROPERTY_READ, PERMISSION_READ_ENCRYPTED,
            byteArrayOf(0x11, 0x01, 0x00, 0x02))) // HID 1.11, no country, normally connectable
        addCharacteristic(characteristic(REPORT_MAP, PROPERTY_READ, PERMISSION_READ_ENCRYPTED, REPORT_DESCRIPTOR))
        addCharacteristic(characteristic(HID_CONTROL_POINT, PROPERTY_WRITE_NO_RESPONSE, PERMISSION_WRITE_ENCRYPTED,
            byteArrayOf(0)))
        addCharacteristic(characteristic(PROTOCOL_MODE, PROPERTY_READ or PROPERTY_WRITE_NO_RESPONSE,
            PERMISSION_READ_ENCRYPTED or PERMISSION_WRITE_ENCRYPTED, byteArrayOf(1)))

        keyboardInput = inputReport(REPORT_ID_KEYBOARD, ByteArray(8))
        addCharacteristic(keyboardInput)
        addCharacteristic(
            characteristic(REPORT, PROPERTY_READ or PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE,
                PERMISSION_READ_ENCRYPTED or PERMISSION_WRITE_ENCRYPTED, byteArrayOf(0)).apply {
                addDescriptor(descriptor(REPORT_REFERENCE, PERMISSION_READ_ENCRYPTED,
                    byteArrayOf(REPORT_ID_KEYBOARD, REPORT_TYPE_OUTPUT)))
            })
        consumerInput = inputReport(REPORT_ID_CONSUMER, ByteArray(2))
        addCharacteristic(consumerInput)
    }

    private fun batteryService() = BluetoothGattService(BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        batteryLevel = characteristic(BATTERY_LEVEL, PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ_ENCRYPTED,
            byteArrayOf(100)).apply { addDescriptor(cccd()) }
        addCharacteristic(batteryLevel)
    }

    private fun deviceInfoService() = BluetoothGattService(DEVICE_INFO_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(characteristic(MANUFACTURER_NAME, PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ,
            "Macropad".toByteArray()))
        // PnP ID: USB vendor-id source, pid.codes test VID 0x1209 / PID 0x0001, version 1.0
        addCharacteristic(characteristic(PNP_ID, PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ,
            byteArrayOf(0x02, 0x09, 0x12, 0x01, 0x00, 0x00, 0x01)))
    }

    private fun inputReport(id: Byte, initial: ByteArray) =
        characteristic(REPORT, PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ_ENCRYPTED, initial).apply {
            addDescriptor(cccd())
            addDescriptor(descriptor(REPORT_REFERENCE, PERMISSION_READ_ENCRYPTED, byteArrayOf(id, REPORT_TYPE_INPUT)))
        }

    private fun characteristic(uuid: UUID, properties: Int, permissions: Int, value: ByteArray) =
        BluetoothGattCharacteristic(uuid, properties, permissions).apply { this.value = value }

    private fun descriptor(uuid: UUID, permissions: Int, value: ByteArray) =
        BluetoothGattDescriptor(uuid, permissions).apply { this.value = value }

    private fun cccd() = descriptor(CCCD,
        BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
        byteArrayOf(0, 0))

    companion object {
        private const val TAG = "HidPeripheral"
        const val DEVICE_NAME = "Fire Macropad"
        private const val SCAN_MODE_NONE = 20
        private const val SCAN_MODE_CONNECTABLE = 21

        private fun uuid16(short: Int): UUID =
            UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

        val HID_SERVICE = uuid16(0x1812)
        val BATTERY_SERVICE = uuid16(0x180F)
        val DEVICE_INFO_SERVICE = uuid16(0x180A)
        val HID_INFORMATION = uuid16(0x2A4A)
        val REPORT_MAP = uuid16(0x2A4B)
        val HID_CONTROL_POINT = uuid16(0x2A4C)
        val REPORT = uuid16(0x2A4D)
        val PROTOCOL_MODE = uuid16(0x2A4E)
        val BATTERY_LEVEL = uuid16(0x2A19)
        val MANUFACTURER_NAME = uuid16(0x2A29)
        val PNP_ID = uuid16(0x2A50)
        val CCCD = uuid16(0x2902)
        val REPORT_REFERENCE = uuid16(0x2908)

        const val REPORT_ID_KEYBOARD: Byte = 1
        const val REPORT_ID_CONSUMER: Byte = 2
        const val REPORT_TYPE_INPUT: Byte = 1
        const val REPORT_TYPE_OUTPUT: Byte = 2

        private val REPORT_DESCRIPTOR = intArrayOf(
            // Keyboard, report ID 1: [modifiers, reserved, key1..key6]; output = LEDs
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
            0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x05, 0x07, 0x19, 0x00, 0x29, 0xFF, 0x81, 0x00,
            0xC0,
            // Consumer control, report ID 2: one 16-bit usage
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0,
        ).map { it.toByte() }.toByteArray()
    }
}
