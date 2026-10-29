package dev.belta.macropad.pc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The PC link: a Bluetooth serial port (SPP over RFCOMM) next to the Classic keyboard. Windows
 * shows it as a COM port. tools/macropad-link.ps1 keeps it open and reports Teams, the app in
 * front and the next meeting, and runs the tablet's `pc:` steps ([PcProtocol], docs/pc-link.md).
 *
 * One computer at a time; when it goes away the port waits for the next one.
 */
@SuppressLint("MissingPermission")
class PcLink(private val adapter: BluetoothAdapter, private val appVersion: String, private val log: (String) -> Unit) {

    interface Listener {
        /** Null when no computer has the port open. */
        fun onPcStatus(status: PcStatus?)
        /** The computer's answer to [command] / [run] number [id]. */
        fun onPcResult(id: Int, ok: Boolean, error: String?)
    }

    var listener: Listener? = null

    /** What the computer reported; null while no computer has the port open. */
    @Volatile var status: PcStatus? = null
        private set
    val connected get() = status != null

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val ids = AtomicInteger()
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    /** Bumped by start / stop, so an old accept thread still winding down quits. */
    @Volatile private var generation = 0

    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        Thread({ acceptLoop(gen) }, "pc-link").start()
    }

    fun stop() {
        running = false
        generation++
        runCatching { server?.close() }
        runCatching { socket?.close() }
        main.removeCallbacks(ping)
    }

    /** Sends a Teams command; returns its id, or null when no computer is connected. */
    fun teams(action: String, param: String?): Int? = send { PcProtocol.teams(action, param, it) }

    /** Runs a named command from the computer's commands.json; null when no computer is connected. */
    fun run(name: String): Int? = send { PcProtocol.run(name, it) }

    private fun send(line: (Int) -> String): Int? {
        if (socket == null) return null
        val id = ids.incrementAndGet()
        write(line(id))
        return id
    }

    private fun write(line: String) = writer.execute {
        val out = socket?.outputStream ?: return@execute
        try {
            out.write(line.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: IOException) {
            runCatching { socket?.close() } // the reader sees it and goes back to waiting
        }
    }

    private fun acceptLoop(gen: Int) {
        while (gen == generation) {
            val listening = try {
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            } catch (e: Exception) {
                log("PC link: can't open the serial port: $e")
                return stopped(gen)
            }
            server = listening
            log("PC link listening")
            val client = try {
                listening.accept()
            } catch (e: IOException) {
                runCatching { listening.close() }
                if (gen == generation) runCatching { Thread.sleep(RETRY_MS) }
                continue
            }
            // One computer at a time: stop listening while it's connected.
            runCatching { listening.close() }
            if (gen != generation) { runCatching { client.close() }; break }
            serve(client)
        }
        stopped(gen)
    }

    private fun stopped(gen: Int) {
        if (gen != generation) return
        running = false
        server = null
    }

    private fun serve(client: BluetoothSocket) {
        socket = client
        val name = runCatching { client.remoteDevice.name }.getOrNull() ?: client.remoteDevice.address
        log("PC link: $name opened the port")
        var current = PcStatus(host = name)
        publish(current)
        write(PcProtocol.hello(appVersion))
        main.postDelayed(ping, PING_MS)
        try {
            client.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                when (val message = PcProtocol.parse(line) ?: return@forEachLine) {
                    is PcMessage.Result -> main.post { listener?.onPcResult(message.id, message.ok, message.error) }
                    else -> {
                        if (message is PcMessage.Hello) log("PC link: script on ${message.host} (Teams ${message.teams}, Outlook ${message.outlook})")
                        current = PcProtocol.apply(current, message)
                        publish(current)
                    }
                }
            }
        } catch (e: IOException) {
            // closed by the computer, out of range, or stop()
        }
        runCatching { client.close() }
        socket = null
        main.removeCallbacks(ping)
        log("PC link: $name closed the port")
        publish(null)
    }

    private fun publish(newStatus: PcStatus?) {
        status = newStatus
        main.post { listener?.onPcStatus(newStatus) }
    }

    /** Keeps the script's silence timeout from firing while nothing else is said. */
    private val ping = object : Runnable {
        override fun run() {
            if (socket == null) return
            write(PcProtocol.ping())
            main.postDelayed(this, PING_MS)
        }
    }

    private companion object {
        const val SERVICE_NAME = "Macropad PC link"
        /** The standard Serial Port Profile UUID: Windows makes a COM port for it. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val PING_MS = 30_000L
        const val RETRY_MS = 5_000L
    }
}
