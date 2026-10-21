package dev.belta.macropad

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import dev.belta.macropad.hid.HidPeripheral
import dev.belta.macropad.model.AppSettings

/**
 * Owns the Bluetooth keyboard so the connection survives the UI being recreated, backgrounded
 * or killed. Runs as a foreground service with an ongoing notification.
 */
class MacropadService : Service() {

    lateinit var hid: HidPeripheral
        private set

    private lateinit var settings: AppSettings
    private val listeners = LinkedHashSet<HidPeripheral.Listener>()
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: MacropadService get() = this@MacropadService
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        hid = HidPeripheral(applicationContext)
        hid.keyboardLayout = settings.keyboardLayout
        hid.keyTiming = settings.keyTiming
        hid.listener = HidPeripheral.Listener { state, detail ->
            updateNotification(state, detail)
            listeners.toList().forEach { it.onStateChanged(state, detail) }
        }
        createChannel()
        startInForeground(notification("Starting…"))
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        // Without the runtime permissions (Android 12+) the UI asks for them and then starts Bluetooth.
        if (hasBluetoothPermissions(this)) hid.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            sendBroadcast(Intent(ACTION_QUIT).setPackage(packageName)) // closes the UI too
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        unregisterReceiver(bluetoothReceiver)
        unregisterReceiver(powerReceiver)
        hid.listener = null
        hid.stop()
        super.onDestroy()
    }

    fun addListener(listener: HidPeripheral.Listener) {
        listeners += listener
        hid.refreshListener()
    }

    fun removeListener(listener: HidPeripheral.Listener) {
        listeners -= listener
    }

    fun applySettings() {
        hid.keyboardLayout = settings.keyboardLayout
        hid.keyTiming = settings.keyTiming
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> hid.start()
                BluetoothAdapter.STATE_TURNING_OFF -> hid.stop()
                BluetoothAdapter.STATE_OFF -> hid.onBluetoothOff()
            }
        }
    }

    /** Charger plugged in: bring the macropad to the front (it is usually used while charging). */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (settings.openOnCharge) MainActivity.bringToFront(context)
        }
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, "Macropad connection", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: HidPeripheral.State, detail: String) {
        val text = when (state) {
            HidPeripheral.State.ADVERTISING -> "Ready — $detail"
            else -> detail
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val quit = PendingIntent.getService(this, 1, Intent(this, MacropadService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Macropad")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Quit", quit).build())
            .build()
    }

    companion object {
        const val ACTION_QUIT = "dev.belta.macropad.QUIT"
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1

        fun hasBluetoothPermissions(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE).all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }

        fun start(context: Context) {
            val intent = Intent(context, MacropadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
