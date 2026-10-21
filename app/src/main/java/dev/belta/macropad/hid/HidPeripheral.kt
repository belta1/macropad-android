package dev.belta.macropad.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
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
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import dev.belta.macropad.BuildConfig
import dev.belta.macropad.model.Step
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Makes the tablet a Bluetooth LE keyboard + mouse (HID over GATT profile).
 *
 * Fire OS ships with the classic HID Device profile disabled, but BLE peripheral mode works,
 * and every desktop OS supports BLE keyboards natively — no software on the computer.
 * See docs/bluetooth-troubleshooting.md for the Fire OS / Windows workarounds in here.
 */
@SuppressLint("MissingPermission")
class HidPeripheral(private val context: Context) {

    enum class State { UNAVAILABLE, OFF, STARTING, ADVERTISING, CONNECTED }

    fun interface Listener {
        fun onStateChanged(state: State, detail: String)
    }

    data class Host(val address: String, val name: String)

    data class Diagnostics(
        val state: State,
        val detail: String,
        val host: Host?,
        val bonded: Boolean?,
        val preferredHost: Host?,
        val classicConnectable: Boolean?,
        val secureSettingsGranted: Boolean,
        val audioProfilesBlocked: Boolean?,
        val events: List<String>,
    )

    var listener: Listener? = null

    /** Layout of the computer's keyboard; used for `text:` steps. */
    @Volatile var keyboardLayout = KeyboardLayout.US

    /** Hold time and spacing of tapped keys. */
    @Volatile var keyTiming = KeyTiming.NORMAL

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
    private lateinit var mouseInput: BluetoothGattCharacteristic

    // Per-host CCCD values. Bonded hosts (Windows in particular) expect them to survive reconnects.
    private val cccdStore = context.getSharedPreferences("hid_cccd", Context.MODE_PRIVATE)
    // Computers served so far: address -> Bluetooth name.
    private val knownHosts = context.getSharedPreferences("hid_hosts", Context.MODE_PRIVATE)
    private val hostSettings = context.getSharedPreferences("hid_settings", Context.MODE_PRIVATE)
    private var reenableBluetooth = false
    private var receiverRegistered = false

    // Keys / mouse buttons currently held down by touched keys (worker thread only).
    private val held = LinkedHashMap<Any, Step.Combo>()
    private var mouseButtons = 0

    // Mouse motion accumulated between reports.
    private val pendingDx = AtomicInteger()
    private val pendingDy = AtomicInteger()
    private val pendingWheel = AtomicInteger()
    private val pendingPan = AtomicInteger()
    private val mouseFlushQueued = AtomicBoolean(false)

    private val events = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

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

    fun restart() {
        event("Restarting Bluetooth server")
        stop()
        main.postDelayed({ start() }, 300)
    }

    private fun closeServer() {
        stopAdvertising()
        server?.let { s -> host?.let { s.cancelConnection(it) }; s.close() }
        server = null
        host = null
        sender.post { held.clear(); mouseButtons = 0 }
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
            adapter?.javaClass?.getMethod("setScanMode", Int::class.javaPrimitiveType)?.invoke(adapter, mode)
            debug("classic scan mode -> $mode")
        } catch (e: Exception) {
            event("Could not change Classic Bluetooth mode: ${e.cause ?: e}")
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
            adapter.bondedDevices.filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
        for (device in computers.distinct()) removeBond(device)
        knownHosts.edit().clear().apply()
        cccdStore.edit().clear().apply()
        hostSettings.edit().remove(PREFERRED_HOST).apply()
        event("Reset pairing: forgot ${computers.distinct().size} computer(s), restarting Bluetooth")
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

    private fun removeBond(device: BluetoothDevice) {
        try {
            device.javaClass.getMethod("removeBond").invoke(device)
            event("Unpaired ${device.address}")
        } catch (e: Exception) {
            event("Could not unpair ${device.address}: ${e.cause ?: e}")
        }
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
            debug("advertising started")
            showAdvertising()
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            event("Advertising failed (error $errorCode)")
            setState(State.UNAVAILABLE, "Advertising failed (error $errorCode)")
        }
    }

    private fun setState(newState: State, newDetail: String) {
        state = newState
        detail = newDetail
        main.post { listener?.onStateChanged(newState, newDetail) }
    }

    fun refreshListener() = main.post { listener?.onStateChanged(state, detail) }

    // ---------------------------------------------------------------- computers

    fun knownHosts(): List<Host> =
        knownHosts.all.keys.map { Host(it, hostName(it)) }.sortedBy { it.name }

    /** The connected computer, if any. */
    fun currentHost(): Host? = host?.let { Host(it.address, hostName(it.address)) }

    /** Names the user gave computers (address -> name or null). */
    @Volatile var customName: (String) -> String? = { null }

    /** The user's name for a computer, else its Bluetooth name, else its address. */
    fun hostName(address: String): String =
        customName(address) ?: knownHosts.getString(address, null) ?: address

    /** Bluetooth name of a computer as last seen, if known. */
    fun bluetoothName(address: String): String? = knownHosts.getString(address, null)

    /** Call after [customName] results changed, so the status text shows the new names. */
    fun namesChanged() {
        host?.let { if (state == State.CONNECTED) setState(State.CONNECTED, "Connected to ${hostName(it.address)}") }
        refreshAdvertisingState()
    }

    /**
     * Hands the macropad over to [address]: the current computer is disconnected and ignored from
     * now on, and the other one reconnects as soon as it sees the macropad advertising again.
     */
    fun switchTo(address: String) = setPreferredHost(address)

    fun preferredHost(): Host? = hostSettings.getString(PREFERRED_HOST, null)?.let { Host(it, hostName(it)) }

    /**
     * Restricts the macropad to one computer (null = whichever connects first). A BLE peripheral
     * can't choose whom to connect to, so other computers are disconnected as soon as they connect.
     */
    fun setPreferredHost(address: String?) {
        hostSettings.edit().putString(PREFERRED_HOST, address).apply()
        event("Computer selection: ${address?.let { hostName(it) } ?: "any"}")
        val current = host
        if (current != null && address != null && current.address != address) {
            server?.cancelConnection(current)
        } else if (current == null) {
            refreshAdvertisingState()
        }
    }

    fun forgetHost(address: String) {
        adapter?.getRemoteDevice(address)?.let { removeBond(it) }
        knownHosts.edit().remove(address).apply()
        val editor = cccdStore.edit()
        cccdStore.all.keys.filter { it.startsWith("$address|") }.forEach { editor.remove(it) }
        editor.apply()
        if (hostSettings.getString(PREFERRED_HOST, null) == address) hostSettings.edit().remove(PREFERRED_HOST).apply()
        host?.let { if (it.address == address) server?.cancelConnection(it) }
    }

    private fun refreshAdvertisingState() {
        if (state == State.ADVERTISING) showAdvertising()
    }

    private fun showAdvertising() {
        val waitingFor = preferredHost()?.let { "Waiting for ${it.name}" }
            ?: "Waiting for a computer — pair with \"$DEVICE_NAME\""
        setState(State.ADVERTISING, waitingFor)
    }

    // ---------------------------------------------------------------- diagnostics

    private fun event(message: String) {
        Log.i(TAG, message)
        synchronized(events) {
            events.addLast("${timeFormat.format(Date())}  $message")
            while (events.size > 60) events.removeFirst()
        }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun diagnostics(): Diagnostics {
        val current = host
        val secure = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        val audioBlocked = current?.let { d ->
            listOf("bluetooth_a2dp_sink_priority_", "bluetooth_headset_priority_").all {
                Settings.Global.getInt(context.contentResolver, it + d.address, -1) == 0
            }
        }
        return Diagnostics(
            state = state,
            detail = detail,
            host = currentHost(),
            bonded = current?.let { it.bondState == BluetoothDevice.BOND_BONDED },
            preferredHost = preferredHost(),
            classicConnectable = adapter?.let { it.scanMode != BluetoothAdapter.SCAN_MODE_NONE },
            secureSettingsGranted = secure,
            audioProfilesBlocked = audioBlocked,
            events = synchronized(events) { events.toList().asReversed() },
        )
    }

    // ---------------------------------------------------------------- sending

    /** Key touched: hold the combo / media key / mouse button down until [release]. */
    fun press(token: Any, step: Step) = sender.post {
        when (step) {
            is Step.Combo -> { held[token] = step; sendKeyboard(currentKeys()) }
            is Step.Media -> sendConsumer(step.usage)
            is Step.Click -> { mouseButtons = mouseButtons or step.buttons; sendMouse(0, 0) }
            else -> {}
        }
    }

    fun release(token: Any, step: Step) = sender.post {
        when (step) {
            is Step.Combo -> { held.remove(token); sendKeyboard(currentKeys()) }
            is Step.Media -> sendConsumer(0)
            is Step.Click -> { mouseButtons = mouseButtons and step.buttons.inv(); sendMouse(0, 0) }
            else -> {}
        }
    }

    /** Runs a multi-step macro (taps every combo). */
    fun run(steps: List<Step>) = sender.post {
        for (step in steps) {
            if (host == null) break
            when (step) {
                is Step.Combo -> tap(step.mods, step.keys)
                is Step.Media -> { sendConsumer(step.usage); pause(keyTiming.holdMs); sendConsumer(0); pause(keyTiming.gapMs) }
                is Step.Delay -> Thread.sleep(step.ms)
                is Step.Text -> for (c in step.text) {
                    val strokes = keyboardLayout.strokes(c) ?: continue
                    for (s in strokes) tap(s.mods, listOf(s.key))
                }
                is Step.Click -> {
                    sendMouse(0, 0, buttons = mouseButtons or step.buttons)
                    pause(keyTiming.holdMs)
                    sendMouse(0, 0)
                    pause(keyTiming.gapMs)
                }
                is Step.Scroll -> sendMouse(0, 0, wheel = step.amount)
            }
        }
    }

    /** Relative pointer motion from the trackpad; coalesced into as few reports as the link allows. */
    fun moveMouse(dx: Int, dy: Int) {
        pendingDx.addAndGet(dx)
        pendingDy.addAndGet(dy)
        queueMouseFlush()
    }

    fun scroll(vertical: Int, horizontal: Int = 0) {
        pendingWheel.addAndGet(vertical)
        pendingPan.addAndGet(horizontal)
        queueMouseFlush()
    }

    fun setMouseButton(button: Int, down: Boolean) = sender.post {
        mouseButtons = if (down) mouseButtons or button else mouseButtons and button.inv()
        sendMouse(0, 0)
    }

    private fun queueMouseFlush() {
        if (!mouseFlushQueued.compareAndSet(false, true)) return
        sender.post {
            mouseFlushQueued.set(false)
            while (true) {
                val dx = take(pendingDx)
                val dy = take(pendingDy)
                val wheel = take(pendingWheel)
                val pan = take(pendingPan)
                if (dx == 0 && dy == 0 && wheel == 0 && pan == 0) break
                if (!sendMouse(dx, dy, wheel = wheel, pan = pan)) break
            }
        }
    }

    /** Takes up to one report's worth (±127) from [counter], leaving the rest for the next report. */
    private fun take(counter: AtomicInteger): Int {
        val all = counter.getAndSet(0)
        val now = all.coerceIn(-127, 127)
        if (all != now) counter.addAndGet(all - now)
        return now
    }

    /**
     * Presses modifiers first, then the keys, holds them for [KeyTiming.holdMs] and releases in
     * reverse order. Sending everything in one report and releasing it at once is too quick for
     * Teams and Citrix, which then miss the shortcut.
     */
    private fun tap(mods: Int, keys: List<Int>) {
        val timing = keyTiming
        val base = currentKeys()
        val down = base.mods or mods
        val extraMods = down != base.mods
        if (extraMods) { sendKeyboard(Step.Combo(down, base.keys)); pause(timing.gapMs) }
        sendKeyboard(Step.Combo(down, (base.keys + keys).distinct()))
        pause(timing.holdMs)
        if (extraMods) { sendKeyboard(Step.Combo(down, base.keys)); pause(timing.gapMs) }
        sendKeyboard(base)
        pause(timing.gapMs)
    }

    private fun pause(ms: Long) {
        if (ms > 0) Thread.sleep(ms)
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

    private fun sendKeyboard(combo: Step.Combo) = notify(keyboardInput, HidReports.keyboard(combo.mods, combo.keys))

    private fun sendConsumer(usage: Int) = notify(consumerInput, HidReports.consumer(usage))

    private fun sendMouse(dx: Int, dy: Int, buttons: Int = mouseButtons, wheel: Int = 0, pan: Int = 0) =
        notify(mouseInput, HidReports.mouse(buttons, dx, dy, wheel, pan))

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
            device.name?.let { if (newState == BluetoothProfile.STATE_CONNECTED) knownHosts.edit().putString(device.address, it).apply() }
            val name = hostName(device.address)
            debug("connection ${device.address} status=$status state=$newState bond=${device.bondState}")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val preferred = hostSettings.getString(PREFERRED_HOST, null)
                if (preferred != null && preferred != device.address) {
                    event("Ignored $name (macropad is set to another computer)")
                    server?.cancelConnection(device)
                    return
                }
                host = device
                if (!knownHosts.contains(device.address)) knownHosts.edit().putString(device.address, name).apply()
                stopAdvertising()
                // Keeps the link up instead of letting Android drop an "idle" peripheral connection.
                server?.connect(device, false)
                disableAudioProfiles(device)
                event("Connected to $name (bond ${bondName(device.bondState)})")
                setState(State.CONNECTED, "Connected to $name")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (device == host) {
                    event("Disconnected from $name (status $status, bond ${bondName(device.bondState)})")
                    rebuildAfterDisconnect()
                } else if (host == null) {
                    rebuildAfterDisconnect() // advertising stops when anyone connects, even an ignored host
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = debug("mtu $mtu")

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notificationSent.release()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            debug("read ${name(characteristic)} offset=$offset")
            if (characteristic.uuid == BATTERY_LEVEL) characteristic.value = byteArrayOf(batteryPercent().toByte())
            respondRead(device, requestId, offset, characteristic.value ?: ByteArray(0))
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            debug("read descriptor ${descriptor.uuid.short()} of ${name(descriptor.characteristic)} offset=$offset")
            val value = if (descriptor.uuid == CCCD) loadCccd(device, descriptor) else descriptor.value
            respondRead(device, requestId, offset, value ?: byteArrayOf(0, 0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            debug("write ${name(characteristic)} value=${value?.hex()} response=$responseNeeded")
            // Output report = keyboard LEDs (caps/num lock); protocol mode / control point = suspend etc.
            if (characteristic.uuid != HID_CONTROL_POINT) characteristic.value = value
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            debug("write descriptor ${descriptor.uuid.short()} of ${name(descriptor.characteristic)} value=${value?.hex()}")
            if (descriptor.uuid == CCCD) saveCccd(device, descriptor, value) else descriptor.value = value
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "paired"
        BluetoothDevice.BOND_BONDING -> "pairing"
        else -> "none"
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
                event("WRITE_SECURE_SETTINGS not granted — run: adb shell pm grant ${context.packageName} " +
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
                        debug("audio profile $p disconnected for ${device.address}")
                    } catch (e: Exception) {
                        debug("could not disconnect audio profile $p: $e")
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
        addCharacteristic(characteristic(REPORT_MAP, PROPERTY_READ, PERMISSION_READ_ENCRYPTED, HidReports.DESCRIPTOR))
        addCharacteristic(characteristic(HID_CONTROL_POINT, PROPERTY_WRITE_NO_RESPONSE, PERMISSION_WRITE_ENCRYPTED,
            byteArrayOf(0)))
        addCharacteristic(characteristic(PROTOCOL_MODE, PROPERTY_READ or PROPERTY_WRITE_NO_RESPONSE,
            PERMISSION_READ_ENCRYPTED or PERMISSION_WRITE_ENCRYPTED, byteArrayOf(1)))

        keyboardInput = inputReport(HidReports.ID_KEYBOARD, ByteArray(8))
        addCharacteristic(keyboardInput)
        addCharacteristic(
            characteristic(REPORT, PROPERTY_READ or PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE,
                PERMISSION_READ_ENCRYPTED or PERMISSION_WRITE_ENCRYPTED, byteArrayOf(0)).apply {
                addDescriptor(descriptor(REPORT_REFERENCE, PERMISSION_READ_ENCRYPTED,
                    byteArrayOf(HidReports.ID_KEYBOARD, REPORT_TYPE_OUTPUT)))
            })
        consumerInput = inputReport(HidReports.ID_CONSUMER, ByteArray(2))
        addCharacteristic(consumerInput)
        mouseInput = inputReport(HidReports.ID_MOUSE, ByteArray(5))
        addCharacteristic(mouseInput)
    }

    private fun batteryService() = BluetoothGattService(BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(characteristic(BATTERY_LEVEL, PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ_ENCRYPTED,
            byteArrayOf(100)).apply { addDescriptor(cccd()) })
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
        private const val PREFERRED_HOST = "preferred_host"

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

        const val REPORT_TYPE_INPUT: Byte = 1
        const val REPORT_TYPE_OUTPUT: Byte = 2
    }
}
