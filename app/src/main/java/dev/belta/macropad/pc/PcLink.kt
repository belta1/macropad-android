package dev.belta.macropad.pc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.belta.macropad.model.Step
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The PC link: a Bluetooth serial port (SPP over RFCOMM) next to the Classic keyboard. Windows
 * shows it as a COM port. tools/macropad-link.ps1 keeps it open and reports Teams, the app in
 * front, the next meeting, the clipboard, open windows, volume, what's playing and the terminal's
 * venv, runs the tablet's `pc:` steps and switches windows / sets volumes ([PcProtocol],
 * docs/pc-link.md).
 *
 * One computer at a time; when it goes away the port waits for the next one. Only computers
 * allowed on the tablet ([PcComputers]) get the link; a new one waits while the tablet asks.
 */
@SuppressLint("MissingPermission")
class PcLink(
    private val adapter: BluetoothAdapter,
    private val appVersion: String,
    private val computers: PcComputers,
    private val log: (String) -> Unit,
) {

    interface Listener {
        /** Null when no computer has the port open. */
        fun onPcStatus(status: PcStatus?)
        /** The computer's answer to [command] / [run] number [id]. */
        fun onPcResult(id: Int, ok: Boolean, error: String?)
        /** A new picture of the mirrored monitor is in [mirror]. */
        fun onScreen() {}
        /** A step recorded from the computer's own keyboard ([capture]). */
        fun onRecorded(step: String) {}
        /** A picture of a slide arrived in [slideImages]. */
        fun onSlideImage() {}
        /** The file asked for with [typeFile] request [id]. */
        fun onFileText(id: Int, name: String, text: String) {}
        /** A computer not asked about yet opened the port and waits ([decide]); null once it no longer does. */
        fun onPcApproval(computer: PcComputer?) {}
    }

    /** The computer waiting to be allowed or refused, if any. */
    @Volatile var pending: PcComputer? = null
        private set

    /** Allows or refuses [computer] for good (⚙ → Connection → PC link computers changes it). */
    fun decide(computer: PcComputer, allow: Boolean) {
        computers.set(computer.address, computer.name, allow)
        recheck()
    }

    /** The decisions changed: a connected computer no longer allowed loses the link. */
    fun recheck() {
        val client = socket ?: return
        if (computers.allowed(client.remoteDevice.address) != true) runCatching { client.close() }
    }

    /** Pictures of PowerPoint's slides by number (1-based), for the presentation and version in [PcStatus.slides]. */
    val slideImages = ConcurrentHashMap<Int, Bitmap>()
    @Volatile private var slidesKey: String? = null

    /** Drops the pictures when the presentation (or its [PcSlides.version]) changes. */
    private fun slidesFor(file: String, version: Int) {
        val key = "$file#$version"
        if (key != slidesKey) {
            slidesKey = key
            slideImages.clear()
        }
    }

    /**
     * The mirrored monitor (the Screen page): the picture so far, which the reader thread draws the
     * computer's changes into. Lock it while reading [bitmap].
     */
    class ScreenMirror {
        var bitmap: Bitmap? = null
            private set
        /** The part of the monitor [bitmap] shows. */
        var viewport = ScreenViewport()
            private set
        /** The request whose pictures are wanted, its viewport, and the whole picture being drawn for it. */
        private var view = 0
        private var wanted = ScreenViewport()
        private var building: Bitmap? = null
        /** The mouse pointer, 0–1 across the picture; -1 when it is on another monitor. */
        var cursorX = -1f
            private set
        var cursorY = -1f
            private set
        /** Pictures and kilobytes per second over the last second, for the page's status line. */
        @Volatile var fps = 0
            private set
        @Volatile var kbps = 0
            private set
        private var windowStart = 0L
        private var frames = 0
        private var bytes = 0L

        @Synchronized fun clear() {
            bitmap = null
            building = null
            cursorX = -1f
            cursorY = -1f
        }

        /**
         * A new request ([view]) for [viewport]: its first, whole picture is drawn aside and replaces
         * [bitmap] once complete, so zooming shows the old picture (scaled) until then.
         */
        @Synchronized internal fun expect(view: Int, viewport: ScreenViewport) {
            this.view = view
            wanted = viewport
            building = null
        }

        @Synchronized internal fun apply(frame: PcMessage.Frame, size: Int) {
            // Still on its way for an earlier request (scripts before zooming send no view: 0).
            if (frame.view != 0 && frame.view != view) return
            val current = bitmap
            val fresh = current == null || viewport != wanted || current.width != frame.width || current.height != frame.height
            var picture = if (fresh) building else current
            if (picture == null || picture.width != frame.width || picture.height != frame.height) {
                picture = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                if (fresh) building = picture else bitmap = picture
            }
            val canvas = Canvas(picture)
            for (tile in frame.tiles) {
                val image = BitmapFactory.decodeByteArray(tile.jpeg, 0, tile.jpeg.size) ?: continue
                canvas.drawBitmap(image, tile.x.toFloat(), tile.y.toFloat(), null)
                image.recycle()
            }
            bytes += size
            if (frame.end) {
                frames++
                cursorX = frame.cursorX
                cursorY = frame.cursorY
                if (fresh) {
                    bitmap = picture
                    viewport = wanted
                    building = null
                }
            }
            val now = SystemClock.uptimeMillis()
            if (now - windowStart >= 1000) {
                fps = frames
                kbps = (bytes / 1024).toInt()
                frames = 0
                bytes = 0
                windowStart = now
            }
        }
    }

    val mirror = ScreenMirror()

    var listener: Listener? = null

    /** What the computer reported; null while no computer has the port open. */
    @Volatile var status: PcStatus? = null
        private set
    val connected get() = status != null

    /** App icons the computer sent, by [PcWindow.exe]; kept across connections. */
    val icons: MutableMap<String, Bitmap> = ConcurrentHashMap()
    /** The computer's clipboard history, kept across connections (in memory only). */
    @Volatile private var clips = emptyList<String>()

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

    /** Sends a classic Outlook command; returns its id, or null when no computer is connected. */
    fun outlook(action: String): Int? = send { PcProtocol.outlook(action, it) }

    /** Runs a named command from the computer's commands.json; null when no computer is connected. */
    fun run(name: String): Int? = send { PcProtocol.run(name, it) }

    /** Brings a window ([PcWindow.id]) to the front; null when no computer is connected. */
    fun focus(window: Long): Int? = send { PcProtocol.focus(window, it) }

    /** Sets the speakers' (or [app]'s) volume and / or mute; null when no computer is connected. */
    fun volume(app: String?, level: Int? = null, mute: Boolean? = null): Int? = send { PcProtocol.volume(app, level, mute, it) }

    private var screenViews = 0

    /**
     * Starts mirroring [viewport] of [monitor] into [mirror], fitted to [width] x [height];
     * [keep] keeps the picture on screen until the new one arrives (zooming).
     */
    fun startScreen(monitor: Int, width: Int, height: Int, viewport: ScreenViewport = ScreenViewport(), keep: Boolean = false) {
        if (!keep) mirror.clear()
        val view = ++screenViews
        mirror.expect(view, viewport)
        if (socket != null) write(PcProtocol.screen(monitor, width, height, viewport, view))
    }

    /** Records the computer's own keyboard ([on]), or holds it ([pause]) while the tablet's keys go out. */
    fun capture(on: Boolean? = null, pause: Boolean? = null) {
        if (socket != null) write(PcProtocol.capture(on, pause))
    }

    /** `pc:focus` / `pc:wait`; their result comes when done. Null when no computer is connected. */
    fun activate(app: String): Int? = send { PcProtocol.activate(app, it) }
    fun waitFor(text: String): Int? = send { PcProtocol.waitFor(text, it) }
    fun click(command: String, app: String, point: Step.PcPoint): Int? = send { PcProtocol.click(command, app, point, it) }

    /** Asks for the file selected in File Explorer; null when no computer is connected. */
    fun typeFile(): Int? = send { PcProtocol.typeFile(it) }

    /** Sends [script] to replace the computer's copy; null when no computer is connected. */
    fun update(script: LinkScript): Int? = send { PcProtocol.update(script, it) }

    /** A PowerPoint command ([PcProtocol.slides]); null when no computer is connected. */
    fun slides(action: String, slide: Int = 0): Int? = send { PcProtocol.slides(action, it, slide) }

    /** The laser pointer at ([x], [y]) of the slide (0–1); [x] < 0 puts it away. Not answered. */
    fun laser(x: Float, y: Float) {
        if (socket != null) write(if (x < 0) PcProtocol.slides("point-end", null) else PcProtocol.slides("point", null, x = x, y = y))
    }

    /** Opens the next meeting's online meeting on the computer; null when no computer is connected. */
    fun join(): Int? = send { PcProtocol.join(it) }

    fun stopScreen() {
        if (socket != null) write(PcProtocol.screenStop())
    }

    /** A click, drag or scroll on the mirrored [monitor] (see [PcProtocol.pointer]). */
    fun pointer(monitor: Int, x: Float, y: Float, action: String, amount: Int = 0) {
        if (socket != null) write(PcProtocol.pointer(monitor, x, y, action, amount))
    }

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
            if (admit(client, gen)) serve(client) else runCatching { client.close() }
        }
        stopped(gen)
    }

    /**
     * Whether [client] gets the link: allowed before, or allowed on the tablet within [ASK_MS]. Until
     * then nothing is sent to it and what it sends waits unread; the port stays taken meanwhile.
     */
    private fun admit(client: BluetoothSocket, gen: Int): Boolean {
        val device = client.remoteDevice
        val name = runCatching { device.name }.getOrNull() ?: device.address
        when (computers.allowed(device.address)) {
            true -> return true
            false -> { log("PC link: turned $name away (refused on the tablet)"); return false }
            null -> {}
        }
        log("PC link: $name opened the port; asking on the tablet whether to allow it")
        val computer = PcComputer(device.address, name)
        pending = computer
        main.post { listener?.onPcApproval(computer) }
        val until = SystemClock.uptimeMillis() + ASK_MS
        while (gen == generation && computers.allowed(device.address) == null && SystemClock.uptimeMillis() < until) {
            runCatching { Thread.sleep(250) }
        }
        pending = null
        main.post { listener?.onPcApproval(null) }
        val allowed = gen == generation && computers.allowed(device.address) == true
        if (!allowed) log("PC link: $name not allowed; closed")
        return allowed
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
        var current = PcStatus(host = name, clips = clips)
        publish(current)
        write(PcProtocol.hello(appVersion))
        main.postDelayed(ping, PING_MS)
        try {
            client.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                when (val message = PcProtocol.parse(line) ?: return@forEachLine) {
                    is PcMessage.Result -> {
                        if (!message.ok) log("PC link: request ${message.id} failed: ${message.error}")
                        main.post { listener?.onPcResult(message.id, message.ok, message.error) }
                    }
                    // Shown with the next windows / volume message, which the script sends after it.
                    is PcMessage.Icon -> BitmapFactory.decodeByteArray(message.png, 0, message.png.size)?.let { icons[message.exe] = it }
                    is PcMessage.Recorded -> main.post { listener?.onRecorded(message.step) }
                    is PcMessage.FileText -> main.post { listener?.onFileText(message.id, message.name, message.text) }
                    is PcMessage.SlideImage -> {
                        slidesFor(message.file, message.version)
                        BitmapFactory.decodeByteArray(message.jpeg, 0, message.jpeg.size)?.let {
                            slideImages[message.slide] = it
                            main.post { listener?.onSlideImage() }
                        }
                    }
                    // Drawn here, off the main thread; acknowledged once whole, so the computer
                    // never sends faster than Bluetooth carries it.
                    is PcMessage.Frame -> {
                        mirror.apply(message, line.length)
                        if (message.end) {
                            write(PcProtocol.screenAck(message.seq))
                            main.post { listener?.onScreen() }
                        }
                    }
                    else -> {
                        if (message is PcMessage.Hello) log("PC link: script on ${message.host} (Teams ${message.teams}, Outlook ${message.outlook})")
                        if (message is PcMessage.Slides) message.slides?.let { slidesFor(it.file, it.version) }
                        current = PcProtocol.apply(current, message)
                        clips = current.clips
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
        /** How long a new computer waits to be allowed; under the script's 90 s silence timeout. */
        const val ASK_MS = 80_000L
    }
}
