package dev.belta.macropad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.belta.macropad.hid.HidKeys
import dev.belta.macropad.hid.DeviceQuirks
import dev.belta.macropad.hid.HidPeripheral
import dev.belta.macropad.model.AppSettings
import dev.belta.macropad.model.DefaultLayout
import dev.belta.macropad.model.Layout
import dev.belta.macropad.model.PageUsage
import dev.belta.macropad.model.LiveLabels
import dev.belta.macropad.model.Macro
import dev.belta.macropad.model.MacroButton
import dev.belta.macropad.model.MacroException
import dev.belta.macropad.model.MacroStore
import dev.belta.macropad.model.Page
import dev.belta.macropad.model.PageKind
import dev.belta.macropad.model.Palette
import dev.belta.macropad.model.SecretStore
import dev.belta.macropad.model.Step
import dev.belta.macropad.model.TextVariables
import dev.belta.macropad.net.NetActions
import dev.belta.macropad.net.Updater
import dev.belta.macropad.pc.LinkScript
import dev.belta.macropad.pc.PcLink
import dev.belta.macropad.pc.PcStatus
import dev.belta.macropad.pc.ScreenViewport
import dev.belta.macropad.ui.AppIcons
import dev.belta.macropad.ui.Dialogs
import dev.belta.macropad.ui.Icons
import dev.belta.macropad.ui.KeyEditor
import dev.belta.macropad.ui.KeyViews
import dev.belta.macropad.ui.MacropadHost
import dev.belta.macropad.ui.HomeAssistantView
import dev.belta.macropad.ui.PresenterView
import dev.belta.macropad.ui.MixerView
import dev.belta.macropad.ui.PcStatusView
import dev.belta.macropad.ui.Recorder
import dev.belta.macropad.ui.RecordingEditor
import dev.belta.macropad.ui.ScreenView
import dev.belta.macropad.ui.SliderView
import dev.belta.macropad.ui.TrackpadView
import dev.belta.macropad.ui.WindowSwitcherView
import dev.belta.macropad.ui.Ui
import dev.belta.macropad.ui.Ui.Companion.AMBER
import dev.belta.macropad.ui.Ui.Companion.BLUE
import dev.belta.macropad.ui.Ui.Companion.CASE
import dev.belta.macropad.ui.Ui.Companion.FAINT
import dev.belta.macropad.ui.Ui.Companion.GREEN
import dev.belta.macropad.ui.Ui.Companion.HAIRLINE
import dev.belta.macropad.ui.Ui.Companion.HAIRLINE_STRONG
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.PLATE
import dev.belta.macropad.ui.Ui.Companion.RAISED
import dev.belta.macropad.ui.Ui.Companion.RED
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.lighten
import dev.belta.macropad.ui.Ui.Companion.withAlpha
import java.io.IOException
import java.util.concurrent.Executors

/** The macropad screen. The Bluetooth keyboard itself lives in [MacropadService]. */
class MainActivity : Activity(), MacropadHost, KeyViews.Listener {

    override lateinit var ui: Ui
    override lateinit var store: MacroStore
    override lateinit var settings: AppSettings
    override lateinit var layout: Layout
    override var service: MacropadService? = null
    override var pageIndex = 0

    private lateinit var dialogs: Dialogs
    private lateinit var keyViews: KeyViews
    private lateinit var recorder: Recorder

    private lateinit var tabs: LinearLayout
    private lateinit var tabScroller: HorizontalScrollView
    private lateinit var offlineBanner: LinearLayout
    private lateinit var offlineIcon: ImageView
    private lateinit var offlineTitle: TextView
    private lateinit var offlineText: TextView
    private lateinit var typingBanner: TextView
    private lateinit var mailBanner: TextView
    /** The new mail last shown, so each shows once. */
    private var shownMail: dev.belta.macropad.pc.PcMail? = null
    private val hideMail = Runnable { mailBanner.visibility = View.GONE }
    /** Long texts being typed (the stop banner shows while > 0). */
    private var typingRuns = 0
    private lateinit var content: FrameLayout
    private lateinit var statusChip: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    /** The status's second line: the computer, or what the macropad is waiting for. */
    private lateinit var statusText: TextView
    private lateinit var lockBadges: LinearLayout
    private lateinit var capsBadge: TextView
    private lateinit var numBadge: TextView
    private lateinit var switchButton: ImageView
    private lateinit var editButton: ImageView
    private lateinit var recordButton: ImageView
    private lateinit var recordBar: LinearLayout
    private lateinit var recordStatus: TextView
    private lateinit var dockRow: LinearLayout
    private lateinit var sideKeys: LinearLayout
    private lateinit var sidebarView: LinearLayout
    override val secrets by lazy { SecretStore(this) }

    private var editing = false
    private var pendingResetPairing = false

    // Keys currently held, by touched view or hardware key code.
    private val heldSteps = HashMap<Any, Step>()
    private val handler = Handler(Looper.getMainLooper())

    // On/off state of toggle keys ("page#index"); kept for this session.
    private val toggledOn = HashSet<String>()

    // Computer whose page selection is shown; kept while it briefly disconnects.
    private var profileAddress: String? = null

    private val page get() = layout.pages[pageIndex.coerceIn(0, layout.pages.lastIndex)]
    private val hid get() = service?.hid
    /** What the PC link reports; null while no computer has it open. */
    private val pcStatus get() = service?.pc?.status
    private val connected get() = hid?.state == HidPeripheral.State.CONNECTED

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // A macropad should be usable without unlocking the tablet first.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        ui = Ui(this)
        store = MacroStore(this)
        settings = AppSettings(this)
        layout = store.load()
        pageIndex = settings.lastPage.coerceIn(0, layout.pages.lastIndex)
        dialogs = Dialogs(this)
        keyViews = KeyViews(ui, this)
        recorder = Recorder(ui, send = { action ->
            val hid = hid
            if (connected && hid != null) {
                pauseCapture()
                hid.run(Macro.parse(action)) { runOnUiThread { resumeCapture() } }
            }
        }) {
            // Stopped or discarded: the computer stops recording its keyboard too.
            if (!recorder.isRecording && capturing) {
                capturing = false
                service?.pc?.capture(on = false)
            }
            renderChrome()
        }

        idleOverlay = buildIdleOverlay()
        setContentView(FrameLayout(this).apply {
            addView(buildUi().also { screen = it })
            addView(idleOverlay)
        })
        // Re-hide the status bar if something (e.g. the notification shade) brought it back.
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { flags ->
            if (flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) window.decorView.postDelayed({ hideSystemUi() }, 2000)
        }
        renderPage()

        registerReceiver(pairingReceiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST))
        // Sent by the service only; Android 14 requires saying so for app broadcasts.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(quitReceiver, IntentFilter(MacropadService.ACTION_QUIT), RECEIVER_NOT_EXPORTED)
        else registerReceiver(quitReceiver, IntentFilter(MacropadService.ACTION_QUIT))
        if (!MacropadService.hasBluetoothPermissions(this)) requestPermissions()
        MacropadService.start(this)
        bindService(Intent(this, MacropadService::class.java), connection, Context.BIND_AUTO_CREATE)
        handleIntent(intent)
        checkForUpdate()
    }

    /** Once a day, says when GitHub has a newer version; installing is up to ⚙ → Updates. */
    private fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheck < 24 * 3_600_000L) return
        settings.lastUpdateCheck = now
        network.execute {
            val release = runCatching { Updater.latest() }.getOrNull() ?: return@execute
            if (Updater.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                runOnUiThread { ui.toast("Version ${release.version} is available: ⚙ → Updates") }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * `adb shell am start -n dev.belta.macropad/.MainActivity --ez reset_pairing true`; debug builds
     * also take `--ez debug_disconnect true` (drops the link to test reconnecting).
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("debug_disconnect", false) == true) hid?.debugDisconnect()
        if (intent?.getBooleanExtra("reset_pairing", false) != true) return
        hid?.resetPairing() ?: run { pendingResetPairing = true }
    }

    override fun onDestroy() {
        (heldSteps.keys + pendingPresses.keys).toList().forEach { releaseKey(it) }
        service?.removeListener(stateListener)
        service?.removePcListener(pcListener)
        unbindService(connection)
        unregisterReceiver(pairingReceiver)
        unregisterReceiver(quitReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        resetIdleTimer()
        screenShiftChanged()
        updateScreenStream()
        handler.postDelayed(liveTick, 30_000)
        handler.postDelayed(usageTick, 60_000)
    }

    override fun onPause() {
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(shiftScreen)
        handler.removeCallbacks(liveTick)
        handler.removeCallbacks(usageTick)
        resumed = false
        updateScreenStream()
        super.onPause()
    }

    override fun onStop() {
        unlocked = false // ask for the PIN again next time
        super.onStop()
    }

    // ---------------------------------------------------------------- idle clock

    private lateinit var idleOverlay: FrameLayout
    private lateinit var idleClock: LinearLayout
    private lateinit var idleTime: TextView
    private lateinit var idleDate: TextView
    private lateinit var idleStatus: TextView
    private lateinit var idleEvent: TextView
    private lateinit var idleMail: TextView
    /** The Home Assistant calendar's next event (⚙ → Saved values → ha_calendar), refreshed while dimmed. */
    @Volatile private var nextEvent: NetActions.CalendarEvent? = null
    private var eventFetchedAt = 0L
    private var dimmed = false
    private var swallowGesture = false
    private val dimRunnable = Runnable { dim() }
    private val clockTick = object : Runnable {
        override fun run() {
            updateIdleClock()
            handler.postDelayed(this, 20_000)
        }
    }

    private fun buildIdleOverlay() = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        idleClock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        idleTime = TextView(context).apply {
            typeface = ui.fontHint
            setTextColor(FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 128f)
        }
        idleDate = TextView(context).apply {
            typeface = ui.fontLabel
            setTextColor(withAlpha(FAINT, 0.8f))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        }
        idleEvent = TextView(context).apply {
            typeface = ui.fontLabel
            setTextColor(withAlpha(FAINT, 0.8f))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, ui.dp(14), 0, 0)
            visibility = View.GONE
        }
        idleStatus = TextView(context).apply {
            typeface = ui.fontHint
            setTextColor(withAlpha(FAINT, 0.6f))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, ui.dp(18), 0, 0)
        }
        // Who wrote, not what: anyone passing by can read the clock.
        idleMail = TextView(context).apply {
            typeface = ui.fontLabel
            setTextColor(withAlpha(FAINT, 0.8f))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, ui.dp(10), 0, 0)
            visibility = View.GONE
        }
        for (line in listOf(idleTime, idleDate, idleEvent, idleMail, idleStatus)) {
            idleClock.addView(line, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        addView(idleClock, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(dimRunnable)
        val minutes = settings.idleDimMinutes
        if (minutes > 0 && !dimmed) handler.postDelayed(dimRunnable, minutes * 60_000L)
    }

    /** Dims the screen to a clock (saves the screen and a weak charger); the next touch only wakes it. */
    private fun dim() {
        if (dimmed || editing || recorder.isRecording || heldSteps.isNotEmpty() || typingRuns > 0) return resetIdleTimer()
        dimmed = true
        unlocked = false
        updateScreenStream()
        updateIdleClock()
        idleOverlay.visibility = View.VISIBLE
        window.attributes = window.attributes.apply { screenBrightness = 0.02f }
        handler.post(clockTick)
    }

    private fun wake() {
        dimmed = false
        idleOverlay.visibility = View.GONE
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        handler.removeCallbacks(clockTick)
        resetIdleTimer()
        updateScreenStream()
    }

    private fun updateIdleClock() {
        val now = java.util.Date()
        idleTime.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now)
        idleDate.text = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(now)
        idleStatus.text = if (connected) "Connected to ${statusText.text}" else "Not connected"
        // The computer's Outlook (PC link) when it reads it, else the Home Assistant calendar.
        val pc = pcStatus
        val event = describeEvent(if (pc?.hasOutlook == true) pc.meeting else nextEvent, now)
        idleEvent.text = event
        idleEvent.visibility = if (event == null) View.GONE else View.VISIBLE
        val mail = shownMail?.takeIf { settings.mailNotices && now.time - it.at < MAIL_ON_CLOCK_MS }
        idleMail.text = mail?.let { "New mail from ${it.from}" + if (it.count > 1) " (+${it.count - 1})" else "" }
        idleMail.visibility = if (mail == null) View.GONE else View.VISIBLE
        refreshNextEvent()
        // Drift a little so nothing stays lit on the same pixels.
        idleClock.translationX = ((Math.random() - 0.5) * ui.dp(160)).toFloat()
        idleClock.translationY = ((Math.random() - 0.5) * ui.dp(120)).toFloat()
    }

    /** Fetches the next calendar event every few minutes; the clock shows it on its next tick. */
    private fun refreshNextEvent() {
        val calendar = secrets[NetActions.HA_CALENDAR]?.trim()
        if (calendar.isNullOrEmpty()) { nextEvent = null; return }
        if (System.currentTimeMillis() - eventFetchedAt < EVENT_REFRESH_MS) return
        eventFetchedAt = System.currentTimeMillis()
        network.execute {
            val event = try {
                NetActions.nextEvent({ secrets[it] }, calendar)
            } catch (e: Exception) {
                Log.w(TAG, "calendar: $e")
                return@execute // keep the last one we had
            }
            runOnUiThread { nextEvent = event; if (dimmed) updateIdleClock() }
        }
    }

    /** "Now: Standup · until 10:45", "Next: Standup · 10:30 (in 4 min)", "Tomorrow 09:00 · Standup". */
    private fun describeEvent(event: NetActions.CalendarEvent?, now: java.util.Date): String? {
        event ?: return null
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        if (event.now) return "Now: ${event.title}" + (event.end?.let { " · until ${time.format(it)}" } ?: "")
        val minutes = (event.start.time - now.time) / 60_000
        if (minutes < 0) return null
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val tomorrow = java.util.Date(now.time + 24 * 3_600_000L)
        return when {
            day.format(event.start) == day.format(now) ->
                "Next: ${event.title} · ${time.format(event.start)}" + if (minutes < 60) " (in $minutes min)" else ""
            day.format(event.start) == day.format(tomorrow) -> "Tomorrow ${time.format(event.start)} · ${event.title}"
            else -> null
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowGesture = dimmed
            if (dimmed) wake()
        }
        if (swallowGesture) return true // the touch that wakes the screen never presses a key
        resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------------------------------------------- PIN

    private var unlocked = false

    /** Runs [action] if no PIN is set or it was entered since the screen last dimmed / was left. */
    override fun withUnlock(action: () -> Unit) {
        val hash = settings.pinHash
        if (hash == null || unlocked) return action()
        dialogs.askPin("Enter PIN") { pin ->
            if (AppSettings.hashPin(pin) == hash) {
                unlocked = true
                action()
            } else {
                ui.toast("Wrong PIN")
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as MacropadService.LocalBinder).service
            service = s
            profileAddress = s.hid.currentHost()?.address ?: s.hid.preferredHost()?.address
            s.addListener(stateListener)
            s.addPcListener(pcListener)
            renderPage()
            if (pendingResetPairing) {
                pendingResetPairing = false
                s.hid.resetPairing()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val stateListener = HidPeripheral.Listener { state, detail -> showState(state, detail) }

    // ---------------------------------------------------------------- PC link

    /** The app the PC link last reported in front, so only changes switch pages. */
    private var pcApp: String? = null
    /** Whether the computer was locked at its last report, so only changes dim / wake the tablet. */
    private var pcLocked: Boolean? = null
    /** The computer locked but the tablet couldn't dim yet (see [pcListener]). */
    private var lockDimPending = false
    /** Counts on the tabs (unread mail…), by app, as last drawn. */
    private var tabBadges = emptyMap<String, Int>()

    /** Live keys that depend on the time too (the join key lights 5 minutes before a meeting). */
    /** How much each page is used, for sorting the tabs by use (pages menu). */
    override val usage by lazy { PageUsage(settings.pageUsage) }

    /** A key pressed on a page (not the dock or sidebar), or a minute it showed. */
    private fun noteUse(page: Page) {
        if (page !in layout.pages) return
        usage.use(page.name, System.currentTimeMillis())
        settings.pageUsage = usage.toJson()
    }

    private val usageTick = object : Runnable {
        override fun run() {
            if (!dimmed && !editing) noteUse(page)
            handler.postDelayed(this, 60_000)
        }
    }

    private val liveTick = object : Runnable {
        override fun run() {
            refreshLiveKeys()
            handler.postDelayed(this, 30_000)
        }
    }
    /** Labels of keys whose PC command is waiting for its result, by request id. */
    private val pcRequests = HashMap<Int, String>()

    private val pcListener = object : PcLink.Listener {
        override fun onPcStatus(status: PcStatus?) {
            val app = status?.app
            if (app != null && app != pcApp) followPcApp(app)
            // While recording, an app switch on the computer becomes a pc:focus step (once it stays).
            if (app != null && app != pcApp && recorder.isRecording) {
                recordedApp = app
                handler.removeCallbacks(recordApp)
                handler.postDelayed(recordApp, 1000)
            }
            pcApp = app
            if (app != null && app != POWERPOINT) demoApp = app
            if (status == null) {
                pcRequests.clear()
                pcWaits.clear() // macros waiting on the computer stop
                fileRequests.clear()
                streamingMonitor = null // the computer stopped with the link
            }
            // The tablet dims while the computer is locked and wakes when it is unlocked. Dimming
            // waits while a key is held, a text is being typed or keys are edited: then it's
            // tried again with the next report, as long as the computer is still locked.
            val locked = status?.locked
            if (locked != null && locked != pcLocked) {
                hid?.event(if (locked) "PC link: the computer locked, dimming" else "PC link: the computer unlocked")
                lockDimPending = locked
                if (!locked && dimmed) wake()
            }
            if (lockDimPending && locked == true) { dim(); lockDimPending = !dimmed }
            if (locked != true) lockDimPending = false
            pcLocked = locked
            if (status?.badges.orEmpty() != tabBadges) { tabBadges = status?.badges.orEmpty(); renderTabs() }
            status?.mail?.takeIf { it != shownMail }?.let { mail ->
                shownMail = mail
                if (settings.mailNotices) showMail(mail)
            }
            if (status == null) capturing = false // the script stops recording with the link
            offerScriptUpdate(status)
            val call = status?.teams?.meeting == true
            if (call != inCall) {
                inCall = call
                followCall(call)
                handler.removeCallbacks(callTick)
                if (call) handler.post(callTick)
            }
            val show = status?.slides?.show == true
            if (show != slideShow) {
                slideShow = show
                followSlideShow(show)
            }
            refreshLiveKeys()
            (content.getChildAt(0) as? PcStatusView)?.update(status)
            updateScreenStream()
            if (dimmed) updateIdleClock()
        }

        override fun onScreen() {
            (content.getChildAt(0) as? ScreenView)?.refresh()
        }

        override fun onRecorded(step: String) {
            if (capturing && recorder.isRecording) recorder.record(step)
        }

        override fun onSlideImage() {
            (content.getChildAt(0) as? PresenterView)?.refreshImages()
        }

        override fun onFileText(id: Int, name: String, text: String) = typeFile(id, name, text)

        override fun onPcResult(id: Int, ok: Boolean, error: String?) {
            val then = pcWaits.remove(id)
            val label = pcRequests.remove(id)
            if (!ok && label != null) ui.toast("$label: ${error ?: "the computer couldn't do it"}")
            then?.invoke(ok)
            fileRequests.remove(id) // a pc:typefile that failed (no file selected…)
        }
    }

    /**
     * Shows the page that lists [app] (page menu → Show for apps), if any and not hidden, unless the
     * page showing stays put (page menu → Stay on this page).
     */
    private fun followPcApp(app: String) {
        if (!settings.followPcApp || editing || recorder.isRecording) return
        if (layout.pages.getOrNull(pageIndex)?.stay == true) return
        // During a slide show the Presenter page stays, even while a demo is in front; during a
        // call, the Teams page (while sharing another app, say).
        if (presenting() || (inCall && settings.followCalls && pageIndex == callPage())) return
        val hidden = hiddenPages()
        val index = layout.pages.indexOfFirst { it.name !in hidden && app in it.appNames }
        if (index < 0 || index == pageIndex) return
        selectPage(index)
        renderPage()
    }

    /** A slide show runs on the computer (PowerPoint, PC link); the time it started, for the Presenter page's timer. */
    private var slideShow = false
    private var showStartedAt = 0L
    /** The page shown before the slide show, to go back to when it ends. */
    private var pageBeforeShow: String? = null

    /**
     * A slide show started: the Presenter page comes up (waking the tablet); it ended: the page
     * shown before comes back, if the Presenter page is still showing.
     */
    private fun followSlideShow(show: Boolean) {
        if (show) showStartedAt = System.currentTimeMillis()
        if (editing || recorder.isRecording) return
        val hidden = hiddenPages()
        val presenter = layout.pages.indexOfFirst { it.kind == PageKind.SLIDES && it.name !in hidden }
        if (presenter < 0) return
        if (show) {
            if (dimmed) wake()
            if (pageIndex == presenter) return
            pageBeforeShow = page.name
            selectPage(presenter)
            renderPage()
        } else {
            val back = pageBeforeShow?.let { name -> layout.pages.indexOfFirst { it.name == name && it.name !in hidden } }
            pageBeforeShow = null
            if (back == null || back < 0 || pageIndex != presenter) return
            selectPage(back)
            renderPage()
        }
    }

    /** The PC link script this app carries; null if the build has none. */
    private val linkScript by lazy { LinkScript.load(this) }
    /** Copies of the computer's script (SHA-256) already offered an update this session. */
    private val offeredScripts = HashSet<String>()

    /**
     * The computer runs an older copy of the PC link script than this app carries: offer to send
     * this one, once per copy and session, while the tablet is in use.
     */
    private fun offerScriptUpdate(status: PcStatus?) {
        val script = linkScript ?: return
        val sha = status?.scriptSha256 ?: return
        if (!status.canUpdate || !script.newerThan(status.scriptVersion, sha) || sha in offeredScripts) return
        if (!resumed || dimmed || editing || recorder.isRecording) return // asked when the tablet is next used
        offeredScripts += sha
        confirmScriptUpdate(status, script)
    }

    private fun confirmScriptUpdate(status: PcStatus, script: LinkScript) {
        val same = status.scriptVersion == script.version
        ui.dialog()
            .setTitle("Update the PC link script?")
            .setMessage("${status.host ?: "The computer"} runs version ${status.scriptVersion} of tools/macropad-link.ps1; " +
                "this app carries ${script.version}${if (same) " (a newer copy)" else ""}.\n\nIt goes over Bluetooth. The " +
                "computer checks it arrived whole, keeps the old one as macropad-link.ps1.bak and restarts the script, " +
                "which reconnects in a few seconds.")
            .setPositiveButton("Update") { _, _ ->
                val id = pcLinkFor("Updating the script")?.update(script) ?: return@setPositiveButton
                pcRequests[id] = "Updating the PC link script"
                pcWaits[id] = { ok -> if (ok) ui.toast("PC link script updated; it restarts and reconnects") }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun pcScriptState(): String? {
        val status = pcStatus ?: return null
        val script = linkScript
        return when {
            status.scriptSha256 == null -> "too old to update itself (copy tools/macropad-link.ps1 once)"
            script != null && script.newerThan(status.scriptVersion, status.scriptSha256) ->
                "${status.scriptVersion}, update to ${script.version}" + if (status.canUpdate) "" else " (started with -NoUpdate)"
            else -> "${status.scriptVersion}, up to date"
        }
    }

    override fun updatePcScript() {
        val status = pcStatus ?: return ui.toast("No computer is connected over the PC link")
        val script = linkScript ?: return ui.toast("This build doesn't carry the script")
        when {
            status.scriptSha256 == null -> ui.toast("That script can't update itself: copy tools/macropad-link.ps1 to the computer once")
            !status.canUpdate -> ui.toast("The script was started with -NoUpdate")
            !script.newerThan(status.scriptVersion, status.scriptSha256) -> ui.toast("The computer's script is up to date")
            else -> confirmScriptUpdate(status, script)
        }
    }

    /** A Teams call is under way on the computer (PC link). */
    private var inCall = false
    /** The page shown before the call, to go back to after it. */
    private var pageBeforeCall: String? = null

    /** The Teams page (the one shown for Teams), not hidden; -1 if none. */
    private fun callPage(): Int {
        val hidden = hiddenPages()
        return layout.pages.indexOfFirst { it.kind == PageKind.KEYS && it.name !in hidden && "ms-teams" in it.appNames }
    }

    /**
     * A call started: the Teams page comes up (waking the tablet), unless a slide show is on; it
     * ended: the page shown before comes back, if the Teams page is still showing.
     */
    private fun followCall(call: Boolean) {
        if (!settings.followCalls || editing || recorder.isRecording) return
        val teams = callPage()
        if (teams < 0) return
        if (call) {
            if (presenting()) return // the Presenter page has its own Mute
            if (dimmed) wake()
            if (pageIndex == teams) return
            pageBeforeCall = page.name
            selectPage(teams)
            renderPage()
        } else {
            val back = pageBeforeCall?.let { name -> layout.pages.indexOfFirst { it.name == name } }
            pageBeforeCall = null
            if (back == null || back < 0 || pageIndex != teams || layout.pages[back].name in hiddenPages()) return
            selectPage(back)
            renderPage()
        }
    }

    /** Keys showing the call's time ({call}) tick every second during a call. */
    private val callTick = object : Runnable {
        override fun run() {
            if (!inCall) return
            if (!dimmed) refreshLiveKeys(ticking = true)
            handler.postDelayed(this, 1000)
        }
    }

    /** A PowerPoint command over the PC link (the Presenter page, the volume buttons). */
    private fun slideCommand(action: String, slide: Int = 0) {
        val id = pcLinkFor("PowerPoint")?.slides(action, slide) ?: return
        pcRequests[id] = "PowerPoint"
    }

    /** Sends a `pc:` step over the PC link; its result comes back to [pcListener]. */
    private fun sendPc(label: String, link: PcLink, step: Step.Pc) {
        val id = when (step.command) {
            "teams" -> link.teams(step.name, step.param)
            "outlook" -> link.outlook(step.name)
            "join" -> link.join()
            "typefile" -> link.typeFile()?.also { fileRequests += it }
            else -> link.run(step.name)
        }
        if (id != null) pcRequests[id] = label
    }

    /** `pc:typefile` requests waiting for their file. */
    private val fileRequests = HashSet<Int>()

    /**
     * The file selected on the computer arrived: after a look at what it is (a long text typed into
     * the wrong window is hard to undo), it's typed into the window in front, as written.
     */
    private fun typeFile(id: Int, name: String, text: String) {
        if (!fileRequests.remove(id)) return
        pcRequests.remove(id)
        if (text.isEmpty()) return ui.toast("$name is empty")
        val lines = text.trimEnd('\n').count { it == '\n' } + 1
        ui.dialog()
            .setTitle("Type $name?")
            .setMessage("$lines line${if (lines == 1) "" else "s"}, ${"%,d".format(text.length)} characters, into the " +
                "window in front on the computer (${pcStatus?.app ?: "?"}). Long texts show a bar to stop them.\n\n" +
                "Editors that indent new lines by themselves (VS Code, IDLE…) add their indent to the file's: type into " +
                "Notepad or an empty editor without auto-indent.")
            .setPositiveButton("Type") { _, _ ->
                val hid = hid
                if (hid == null || !connected) return@setPositiveButton flashOfflineBanner()
                pauseCapture()
                runSteps(hid, listOf(Step.Text(text))) { runOnUiThread { resumeCapture() } }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The monitor the Screen page is mirroring; null while nothing is. */
    private var streamingMonitor: Int? = null
    /** The part of that monitor the Screen page shows (zoom), kept while it shows. */
    private var screenViewport = ScreenViewport()
    private var resumed = false

    /** The Screen page's monitor: the one chosen there, else the computer's main one; null without monitors. */
    private fun screenMonitor(): Int? {
        val monitors = pcStatus?.monitors?.takeIf { it.isNotEmpty() } ?: return null
        return monitors.firstOrNull { it.number == settings.screenMonitor }?.number
            ?: (monitors.firstOrNull { it.primary } ?: monitors[0]).number
    }

    /** Mirrors the computer's screen only while the Screen page shows: the link has little to spare. */
    private fun updateScreenStream() {
        val link = service?.pc?.takeIf { it.connected }
        val monitor = screenMonitor().takeIf { link != null && resumed && !dimmed && page.kind == PageKind.SCREEN }
        if (monitor == streamingMonitor) return
        if (monitor != null) link!!.startScreen(monitor, SCREEN_WIDTH, SCREEN_HEIGHT, screenViewport) else link?.stopScreen()
        streamingMonitor = monitor
    }

    /** "✉ Ana Pérez — Budget (+2)" over the dock for a few seconds. */
    private fun showMail(mail: dev.belta.macropad.pc.PcMail) {
        mailBanner.text = "${mail.from} — ${mail.subject}" + if (mail.count > 1) "  (+${mail.count - 1})" else ""
        mailBanner.contentDescription = "New mail from ${mail.from}: ${mail.subject}. Tap to open Outlook"
        mailBanner.visibility = View.VISIBLE
        handler.removeCallbacks(hideMail)
        handler.postDelayed(hideMail, MAIL_SHOWN_MS)
        if (dimmed) updateIdleClock()
    }

    /** Recording also captures the computer's own keyboard (experimental, ⚙ → Connection). */
    private var capturing = false

    /** Holds the capture while the tablet's own keys go out (the recorder has them already). */
    private fun pauseCapture() { if (capturing) service?.pc?.capture(pause = true) }
    private fun resumeCapture() { if (capturing) service?.pc?.capture(pause = false) }

    /** The PC link while a computer has it open; otherwise says what [what] needs and returns null. */
    private fun pcLinkFor(what: String): PcLink? {
        val link = service?.pc?.takeIf { it.connected }
        if (link == null) ui.toast("$what needs the PC link (docs/pc-link.md)")
        return link
    }

    /** When a level last went out while a slider was dragged. */
    private var levelSentAt = 0L

    /**
     * Sets the computer's volume ([app] null) or an app's over the PC link while a slider is
     * dragged: at most every [LEVEL_MS], and always the last level ([done]).
     */
    private fun sendVolume(app: String?, level: Int, done: Boolean) {
        val link = service?.pc?.takeIf { it.connected } ?: return
        val now = SystemClock.uptimeMillis()
        if (!done && now - levelSentAt < LEVEL_MS) return
        levelSentAt = now
        link.volume(app, level = level)
    }

    /**
     * What a `pc:` step sends as keys without the PC link, Teams or classic Outlook (their
     * shortcuts); empty if nothing.
     */
    private fun pcFallback(label: String, step: Step.Pc): List<Step> {
        val shortcut = when (step.command) {
            "teams" -> Macro.TEAMS_FALLBACK[step.name]
            "outlook" -> Macro.OUTLOOK_FALLBACK[step.name]
            else -> null
        }
        if (shortcut == null) {
            ui.toast(if (service?.pc?.connected == true) "$label: the PC link can't reach Teams"
                else "$label: needs the PC link (docs/pc-link.md)")
            return emptyList()
        }
        return Macro.parse(shortcut)
    }

    private val quitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finishAndRemoveTask()
    }

    private fun requestPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_PERMISSIONS && MacropadService.hasBluetoothPermissions(this)) hid?.start()
    }

    // ---------------------------------------------------------------- tablet volume buttons

    private fun hardwareSlot(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> Layout.HW_VOLUME_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> Layout.HW_VOLUME_DOWN
        else -> null
    }

    /** On the Presenter page during a slide show, the tablet's volume buttons change slides (up = next). */
    private fun presenting() = page.kind == PageKind.SLIDES && pcStatus?.slides?.show == true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (hardwareSlot(keyCode) != null && presenting()) {
            if (event.repeatCount == 0) slideCommand(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) "next" else "prev")
            return true
        }
        val slot = hardwareSlot(keyCode) ?: return super.onKeyDown(keyCode, event)
        val button = layout.button(layout.hardware, slot) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) pressKey(keyCode, layout.hardware, slot, button, null)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (hardwareSlot(keyCode) != null && presenting()) return true
        val slot = hardwareSlot(keyCode) ?: return super.onKeyUp(keyCode, event)
        layout.button(layout.hardware, slot) ?: return super.onKeyUp(keyCode, event)
        releaseKey(keyCode, tapped = true)
        return true
    }

    // ---------------------------------------------------------------- UI construction

    private fun buildUi(): View {
        // Main column (tabs, keys, dock) and, on the right, the sidebar shown on every page: status,
        // app controls and the sidebar's action keys. Everything sits flat on the case except the
        // keys, which are keycaps.
        // The dock runs along the whole bottom, under both.
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CASE)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        outer.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        top.addView(root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isMotionEventSplittingEnabled = true
        }
        sidebarView = sidebar
        top.addView(sidebar)
        placeSidebar()

        // Top bar: page tabs
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
            // The strip narrows when the switch button appears; keep the selected tab in view.
            addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) scrollToActiveTab() }
        }
        bar.addView(tabScroller, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Status: a light, the state and, under it, the computer or what it's waiting for. Every line
        // always takes its room, so the sidebar under it keeps its size as the status changes; the
        // full text is in Diagnostics (tap the status).
        statusChip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(10), ui.dp(8), ui.dp(8), ui.dp(8))
            background = ui.pressable(Color.TRANSPARENT, ui.dp(12).toFloat())
            setOnClickListener { onStatusTapped() }
        }
        statusDot = View(this)
        statusTitle = TextView(this).apply {
            setTextColor(TEXT)
            typeface = ui.fontLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = "Starting"
        }
        statusChip.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { marginEnd = ui.dp(8) })
            addView(statusTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        statusText = TextView(this).apply {
            setTextColor(MUTED)
            typeface = ui.fontHint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(16), 0, 0, 0)
        }
        statusChip.addView(statusText)
        // The computer's Caps / Num Lock as indicator lights, like a keyboard's. Always there (dark
        // while off or disconnected), so nothing moves when they light.
        fun lockBadge(text: String) = TextView(this).apply {
            this.text = text
            typeface = ui.fontHint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = ui.dp(6)
            maxLines = 1
        }
        capsBadge = lockBadge("Caps")
        numBadge = lockBadge("Num")
        lockBadges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(16), ui.dp(6), 0, 0)
            addView(capsBadge)
            addView(numBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(12) })
        }
        statusChip.addView(lockBadges)
        sidebar.addView(statusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(4) })

        // App controls, two per row: record, edit / settings, switch computer
        switchButton = ui.iconButton("swap", "Switch computer") { switchComputer() }.apply { visibility = View.GONE }
        recordButton = ui.iconButton("record", "Record a macro") {
            if (recorder.isRecording) stopRecording() else withUnlock { startRecording() }
        }
        editButton = ui.iconButton("edit", "Edit keys") { if (editing) toggleEdit() else withUnlock { toggleEdit() } }
        val settingsButton = ui.iconButton("settings", "Settings") { withUnlock { dialogs.settings() } }
        for (pair in listOf(listOf(recordButton, editButton), listOf(settingsButton, switchButton))) {
            sidebar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                for (button in pair) addView(button, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply {
                    setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
                })
            })
        }

        // The sidebar's keys (Away, Lock PC, …), sharing the remaining height
        sideKeys = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isMotionEventSplittingEnabled = true
        }
        sidebar.addView(sideKeys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = ui.dp(8)
        })
        root.addView(bar)

        // Takes the media dock's place while no computer is connected (the dock does nothing then),
        // so key presses that go nowhere are obvious without moving the keys.
        offlineIcon = ui.icon("bluetooth", AMBER)
        offlineTitle = TextView(this).apply {
            setTextColor(TEXT)
            typeface = ui.fontLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            maxLines = 1
        }
        offlineText = TextView(this).apply {
            setTextColor(MUTED)
            typeface = ui.fontBody
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        offlineBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(20), 0, ui.dp(16), 0)
            visibility = View.GONE
            background = ui.roundRect(PLATE, ui.dp(12).toFloat()).apply { setStroke(ui.dp(1), HAIRLINE) }
            addView(offlineIcon, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(16) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(offlineTitle)
                addView(offlineText)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.icon("chevron_right", MUTED), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
            setOnClickListener { onStatusTapped() }
        }

        // New mail on the computer (PC link), over the dock for a few seconds; tapping it opens Outlook.
        mailBanner = TextView(this).apply {
            setTextColor(TEXT)
            typeface = ui.fontLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(20), 0, ui.dp(16), 0)
            visibility = View.GONE
            background = ui.pressable(RAISED, ui.dp(12).toFloat())
            setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(Icons.get("mail")!!)!!.mutate().apply { setTint(BLUE) }, null, null, null)
            compoundDrawablePadding = ui.dp(14)
            setOnClickListener {
                visibility = View.GONE
                service?.pc?.takeIf { it.connected }?.outlook("open")
            }
        }

        // Shown over the dock while a long text is being typed; tapping it stops the typing.
        typingBanner = TextView(this).apply {
            setTextColor(TEXT)
            typeface = ui.fontLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            visibility = View.GONE
            background = ui.pressable(RAISED, ui.dp(12).toFloat())
            setOnClickListener {
                hid?.stopTyping()
                text = "Stopping…"
            }
        }

        content = FrameLayout(this).apply { clipChildren = false }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = ui.dp(8)
        })

        recordBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(ui.dp(16), ui.dp(6), ui.dp(6), ui.dp(6))
            background = ui.roundRect(PLATE, ui.dp(12).toFloat()).apply {
                setStroke(ui.dp(1), withAlpha(RED, 0.5f))
            }
        }
        recordStatus = TextView(this).apply {
            setTextColor(lighten(RED, 0.3f))
            typeface = ui.fontLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        recordBar.addView(recordStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        recordBar.addView(ui.chip("Type text", "keyboard") { recorder.typeTextDialog() })
        recordBar.addView(ui.chip("Key combo", "command") { recorder.keyComboDialog() })
        recordBar.addView(ui.chip("Undo", "undo") { recorder.undo() })
        recordBar.addView(ui.chip("Discard", "delete") { recorder.discard() })
        recordBar.addView(ui.chip("Save", "check", RED) { stopRecording() })
        root.addView(recordBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(ui.dp(5), ui.dp(8), ui.dp(5), 0) })

        // Dock, shared by every page, the whole width
        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isMotionEventSplittingEnabled = true
        }
        outer.addView(FrameLayout(this).apply {
            addView(dockRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(offlineBanner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(mailBanner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(typingBanner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.sdp(72)).apply {
            setMargins(ui.dp(5), ui.dp(8), ui.dp(5), 0)
        })
        return outer
    }

    /** Puts the sidebar on the side chosen in ⚙ (right by default). */
    override fun placeSidebar() {
        val outer = sidebarView.parent as? LinearLayout ?: return
        outer.removeView(sidebarView)
        val left = settings.sidebarLeft
        outer.addView(sidebarView, if (left) 0 else outer.childCount,
            LinearLayout.LayoutParams(ui.sdp(SIDEBAR_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                if (left) marginEnd = ui.dp(10) else marginStart = ui.dp(10)
            })
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        val hidden = hiddenPages()
        layout.pages.forEachIndexed { i, p ->
            if (p.name in hidden) return@forEachIndexed
            val active = i == pageIndex
            // App icon on every tab; the selected one also shows its name, on a raised patch of case.
            tabs.addView(LinearLayout(this).apply {
                tag = p.name
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = p.name
                isSelected = active
                setPadding(ui.dp(if (active) 10 else 9), 0, ui.dp(if (active) 16 else 9), 0)
                // The icon, with the count the computer reports for the page's apps (unread mail).
                val count = p.appNames.sumOf { tabBadges[it] ?: 0 }
                addView(FrameLayout(context).apply {
                    clipChildren = false
                    addView(ImageView(context).apply {
                        setImageDrawable(AppIcons.drawable(ui, p.icon ?: DefaultLayout.iconFor(p.name), p.name))
                        alpha = if (active) 1f else 0.6f
                    }, FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER))
                    if (count > 0) addView(TextView(context).apply {
                        text = if (count > 99) "99+" else count.toString()
                        setTextColor(Color.WHITE)
                        typeface = ui.fontLabel
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                        gravity = Gravity.CENTER
                        minWidth = ui.dp(17)
                        setPadding(ui.dp(4), 0, ui.dp(4), 0)
                        background = ui.roundRect(RED, ui.dp(9).toFloat()).apply { setStroke(ui.dp(2), CASE) }
                        contentDescription = "$count unread"
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(17), Gravity.TOP or Gravity.END))
                }, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)))
                if (active) addView(TextView(context).apply {
                    text = p.name
                    typeface = ui.fontTitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(TEXT)
                    maxLines = 1
                    setPadding(ui.dp(10), 0, 0, 0)
                })
                background = ui.pressable(if (active) RAISED else Color.TRANSPARENT, ui.dp(12).toFloat())
                setOnClickListener { selectPage(i); renderPage() }
                setOnLongClickListener { withUnlock { dialogs.pageMenu(i) }; true }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)).apply {
                marginEnd = ui.dp(2)
            })
        }
        // Keep the selected tab fully visible (once the new tabs are laid out).
        tabs.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this)
                scrollToActiveTab()
            }
        })
        tabs.requestLayout()
    }

    private fun scrollToActiveTab() {
        tabScroller.post {
            val active = (0 until tabs.childCount).map { tabs.getChildAt(it) }
                .firstOrNull { it.tag == page.name } ?: return@post
            val left = active.left - ui.dp(8)
            val right = active.right + ui.dp(8) - tabScroller.width
            when {
                tabScroller.scrollX > left -> tabScroller.smoothScrollTo(left, 0)
                tabScroller.scrollX < right -> tabScroller.smoothScrollTo(right, 0)
            }
        }
    }

    /** Edit/record buttons and the recorder bar. */
    private fun renderChrome() {
        ui.setIconButton(editButton, if (editing) "check" else "edit", if (editing) TEXT else null)
        editButton.contentDescription = if (editing) "Done editing" else "Edit keys"
        ui.setIconButton(recordButton, if (recorder.isRecording) "stop" else "record",
            if (recorder.isRecording) RED else null, tint = RED)
        recordButton.contentDescription = if (recorder.isRecording) "Stop recording" else "Record a macro"
        recordBar.visibility = if (recorder.isRecording) View.VISIBLE else View.GONE
        recordStatus.text = recorder.status()
    }

    /** Pages hidden for the current computer (never all of them). */
    private fun hiddenPages(): Set<String> {
        val hidden = profileAddress?.let { service?.profiles?.get(it)?.hiddenPages } ?: return emptySet()
        return if (layout.pages.all { it.name in hidden }) emptySet() else hidden
    }

    private fun renderPage() {
        if (page.name in hiddenPages()) {
            selectPage(layout.pages.indexOfFirst { it.name !in hiddenPages() })
        }
        renderTabs()
        renderChrome()
        (heldSteps.keys + pendingPresses.keys).toList().forEach { releaseKey(it) }

        content.removeAllViews()
        content.addView(when (page.kind) {
            PageKind.TRACKPAD -> buildTrackpad(page)
            PageKind.WINDOWS -> buildSwitcher()
            PageKind.MIXER -> buildMixer()
            PageKind.SCREEN -> buildScreen()
            PageKind.HOME -> HomeAssistantView(ui, layout.cols, settings, secret = { secrets[it] }, active = { resumed && !dimmed })
            PageKind.SLIDES -> buildPresenter()
            PageKind.KEYS -> buildGrid(page)
        })
        updateScreenStream()

        renderSidebar()

        // Icon-only keys take one share of the width, labelled keys two, sliders three; a divider
        // separates the media keys from the rest.
        dockRow.removeAllViews()
        var previousMedia: Boolean? = null
        for (index in 0 until Layout.DOCK_SIZE) {
            val button = layout.button(layout.dock, index)
            // Empty slots only take room while editing (to add a key there), as in the sidebar.
            if (!editing && button == null) continue
            val iconOnly = dockIconOnly(button)
            val media = button == null || isMediaKey(button)
            if (previousMedia != null && media != previousMedia) {
                dockRow.addView(View(this).apply { setBackgroundColor(HAIRLINE_STRONG) },
                    LinearLayout.LayoutParams(ui.dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        setMargins(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12))
                    })
            }
            previousMedia = media
            // A slider is exactly three icon keys wide (three shares plus the two gaps between
            // them), a card showing what the computer reports (the media card) six.
            val slider = button != null && Macro.slider(button.action) != null
            val card = button != null && LiveLabels.isLive(button.label)
            val weight = when {
                card -> 6
                slider -> 3
                iconOnly -> 1
                else -> 2
            }
            val gaps = if (slider || card) weight - 1 else 0
            dockRow.addView(keyView(layout.dock, index, compact = true),
                LinearLayout.LayoutParams(gaps * ui.dp(DOCK_GAP_DP), ViewGroup.LayoutParams.MATCH_PARENT, weight.toFloat()).apply {
                    setMargins(ui.dp(DOCK_GAP_DP) / 2, 0, ui.dp(DOCK_GAP_DP) / 2, 0)
                })
        }
    }

    private fun renderSidebar() {
        sideKeys.removeAllViews()
        for (index in 0 until Layout.SIDEBAR_SIZE) {
            // Empty slots only take room while editing (to add a key there).
            if (!editing && layout.button(layout.sidebar, index) == null) continue
            sideKeys.addView(keyView(layout.sidebar, index, compact = false),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    setMargins(0, ui.dp(3), 0, ui.dp(3))
                })
        }
    }

    /** Dock keys with an icon show just the icon (the label is in edit mode). Empty slots count as icon-only. */
    private fun dockIconOnly(button: MacroButton?) = button == null || Icons.get(button.icon) != null

    private fun isMediaKey(button: MacroButton) =
        when (val step = runCatching { Macro.parse(button.action) }.getOrNull()?.singleOrNull()) {
            is Step.Media -> true
            is Step.Slide -> step.up is Step.Media // e.g. a volume slider
            else -> false
        }

    private fun buildGrid(page: Page) = GridLayout(this).apply {
        isMotionEventSplittingEnabled = true
        rowCount = layout.rows
        columnCount = layout.cols
        for (index in 0 until layout.cells) {
            addView(keyView(page, index, compact = false), GridLayout.LayoutParams(
                GridLayout.spec(index / layout.cols, 1f), GridLayout.spec(index % layout.cols, 1f),
            ).apply {
                width = 0
                height = 0
                setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
            })
        }
    }

    /** Touchpad surface plus the page's keys as a row underneath. */
    private fun buildTrackpad(page: Page) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isMotionEventSplittingEnabled = true
        addView(TrackpadView(ui, object : TrackpadView.Listener {
            override fun onMove(dx: Int, dy: Int) { if (connected) hid?.moveMouse(dx, dy) }
            override fun onScroll(vertical: Int, horizontal: Int) { if (connected) hid?.scroll(vertical, horizontal) }
            override fun onButton(button: Int, down: Boolean) { if (connected) hid?.setMouseButton(button, down) }
        }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isMotionEventSplittingEnabled = true
        }
        for (index in 0 until TRACKPAD_KEYS) {
            row.addView(keyView(page, index, compact = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
                })
        }
        addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(72)))
    }

    /** The computer's open windows (PC link); tapping one brings it to the front. */
    private fun buildSwitcher() = WindowSwitcherView(ui, layout.cols, icon = { service?.pc?.icons?.get(it) }) { window ->
        val id = pcLinkFor("Switching windows")?.focus(window.id) ?: return@WindowSwitcherView
        pcRequests[id] = window.title.take(40)
    }.apply { update(pcStatus) }

    /** The computer's volume per app (PC link). */
    private fun buildMixer() = MixerView(ui, icon = { service?.pc?.icons?.get(it) },
        onLevel = { app, level, done -> sendVolume(app, level, done) },
        onMute = { app, mute -> pcLinkFor("The volume mixer")?.volume(app, mute = mute) },
        onStep = { app, level, up -> pcLinkFor("The volume mixer")?.volume(app, level = level + if (up) MIXER_STEP else -MIXER_STEP) },
    ).apply { update(pcStatus) }

    /** A monitor of the computer, touchable (PC link). */
    /** PowerPoint's notes, slides and controls (PC link); its keys without it. */
    private fun buildPresenter() = PresenterView(ui, settings,
        images = { service?.pc?.slideImages },
        startedAt = { showStartedAt },
        onResetTimer = { showStartedAt = System.currentTimeMillis() },
        onCommand = ::presenterCommand,
        onLaser = { x, y -> service?.pc?.takeIf { it.connected }?.laser(x, y) },
        typeTarget = { demoTarget()?.let(::appName) },
        onPickTarget = ::pickDemoApp,
        onType = ::typeNotesCommand,
    ).apply { update(pcStatus) }

    /** The last app in front on the computer other than PowerPoint: where a demo's commands go. */
    private var demoApp: String? = null
    /** The app chosen for them instead (Presenter page → Types in). */
    private var demoPicked: String? = null

    private fun demoTarget() = demoPicked ?: demoApp

    private fun appName(exe: String) = pcStatus?.windows?.firstOrNull { it.exe == exe }?.app ?: exe

    private fun pickDemoApp() {
        val apps = pcStatus?.windows.orEmpty().map { it.exe }.distinct().filter { it != POWERPOINT }
        val items = listOf("The last app I used" + (demoApp?.let { " (now ${appName(it)})" } ?: "")) + apps.map(::appName)
        ui.dialog()
            .setTitle("Type the notes' commands in")
            .setItems(items.toTypedArray()) { _, which ->
                demoPicked = if (which == 0) null else apps[which - 1]
                (content.getChildAt(0) as? PresenterView)?.update(pcStatus)
            }
            .show()
    }

    /**
     * A `>` command from the slide's notes: the demo's app comes to the front (PC link), then the
     * command is typed as written (no text variables: code has braces) and Enter if [enter].
     */
    private fun typeNotesCommand(command: String, enter: Boolean) {
        val text = Step.Text(command + if (enter) "\n" else "")
        val target = demoTarget()
        if (target != null && service?.pc?.connected == true) {
            pauseCapture()
            return runInOrder("Notes", listOf(Step.Pc("focus", target), text), onEnd = ::resumeCapture)
        }
        val hid = hid
        if (hid == null || !connected) return flashOfflineBanner()
        runSteps(hid, listOf(text))
    }

    /** Over the PC link while it reaches PowerPoint; else the slide show's own keys. */
    private fun presenterCommand(action: String, slide: Int) {
        if (action == "mute") {
            val id = pcLinkFor("Teams")?.teams("mute", null) ?: return
            pcRequests[id] = "Mute"
            return
        }
        if (pcStatus?.slides != null) return slideCommand(action, slide)
        val keys = when (action) {
            "next" -> "right"
            "prev" -> "left"
            "black" -> "b"
            "white" -> "w"
            "start" -> "f5"
            "end" -> "esc"
            "goto" -> "text:$slide\\n" // a slide show goes to the slide whose number is typed
            else -> return
        }
        val hid = hid
        if (hid == null || !connected) return flashOfflineBanner()
        runSteps(hid, Macro.parse(keys))
    }

    private fun buildScreen() = ScreenView(ui, mirror = { service?.pc?.mirror }, selected = ::screenMonitor,
        viewport = screenViewport,
        onSelect = { number ->
            settings.screenMonitor = number
            screenViewport = ScreenViewport() // a new monitor: all of it
            renderPage()
        },
        onViewport = { viewport ->
            screenViewport = viewport
            // The computer sends that part sharp; the picture so far stays until it arrives.
            streamingMonitor?.let { service?.pc?.startScreen(it, SCREEN_WIDTH, SCREEN_HEIGHT, viewport, keep = true) }
        },
        onPointer = { x, y, action, amount ->
            val monitor = streamingMonitor
            if (!editing && monitor != null) service?.pc?.pointer(monitor, x, y, action, amount)
        },
        onType = { text -> typeOnComputer(listOf(Step.Text(text))) },
        onKey = { key -> typeOnComputer(Macro.parse(key)) },
    ).apply { update(pcStatus) }

    /** The Screen page's keyboard: typed on the computer like a key's text. */
    private fun typeOnComputer(steps: List<Step>) {
        val hid = hid
        if (hid == null || !connected) return flashOfflineBanner()
        runSteps(hid, steps)
    }

    private fun keyView(page: Page, index: Int, compact: Boolean): View {
        val stored = layout.button(page, index)
        val lock = liveState(stored)
        // Labels showing what the PC link reports, filled in (edit mode shows them as written). The
        // media card's ⏯ shows what a tap does.
        val live = !editing && stored != null && LiveLabels.isLive(stored.label)
        val button = if (!live) stored else stored!!.copy(
            label = LiveLabels.expand(stored.label, pcStatus),
            icon = if (!KeyViews.isMediaCard(stored)) stored.icon else if (pcStatus?.media?.playing == true) "pause" else "play",
        )
        val small = page === layout.dock
        // The volume slider shows (and sets) the computer's real volume over the PC link.
        val volume = pcStatus?.volume?.takeIf { isVolumeSlider(stored) }
        return keyViews.build(
            page, index, button, compact, editing,
            toggledOn = lock ?: (toggleKey(page, index) in toggledOn),
            emphasize = small && live && KeyViews.isMediaCard(stored),
            iconOnly = small && !live && dockIconOnly(button),
            showHint = page !== layout.sidebar,
            small = page === layout.sidebar,
            stateful = lock != null || button?.toggle == true,
            sliderLevel = volume?.level, sliderMuted = volume?.muted == true,
            card = small && live,
        ).apply {
            if (lock != null || isTeamsKey(stored) || live || isVolumeSlider(stored)) tag = LiveKey(page, index, compact)
        }
    }

    private fun isVolumeSlider(button: MacroButton?) = button != null && Macro.slider(button.action)?.preset == "volume"

    /**
     * Tag of a live key's view (lock or Teams key, a label showing what the PC link reports, the
     * volume slider), so [refreshLiveKeys] can find and redraw it.
     */
    private class LiveKey(val page: Page, val index: Int, val compact: Boolean)

    /**
     * A key that shows the computer's real state, or null for any other key: Caps / Num / Scroll
     * Lock (from its keyboard LEDs, off while disconnected), Teams' mute, camera, hand and blur
     * while the PC link reports Teams (otherwise those are ordinary toggle keys), and the join key
     * (lit while a meeting is near).
     */
    private fun liveState(button: MacroButton?): Boolean? {
        val step = button?.let { runCatching { Macro.parse(it.action) }.getOrNull()?.singleOrNull() }
        // The join key lights from 5 minutes before the next meeting until it ends.
        if (step is Step.Pc && step.command == "join") return LiveLabels.meetingSoon(pcStatus)
        if (step is Step.Pc) {
            val teams = pcStatus?.teams?.takeIf { step.command == "teams" } ?: return null
            return when (step.name) {
                "mute" -> teams.muted
                "camera" -> teams.camera
                "hand" -> teams.hand
                "blur" -> teams.blur
                else -> null
            }
        }
        val combo = step as? Step.Combo
        val key = combo?.keys?.singleOrNull()?.takeIf { combo.mods == 0 && it in HidPeripheral.LOCK_LEDS } ?: return null
        return connected && hid?.lockOn(key) == true
    }

    /** A `pc:teams` key: redrawn when Teams' state comes and goes. */
    private fun isTeamsKey(button: MacroButton?) = button != null && button.action.trim().lowercase().startsWith("pc:teams")

    /**
     * Redraws the live keys on screen ([ticking]: just the ones showing the call's time); one being
     * touched (or a button on it) redraws when it is released.
     */
    private fun refreshLiveKeys(ticking: Boolean = false) {
        val volume = pcStatus?.volume
        fun touched(view: View): Boolean =
            view.isPressed || (view is ViewGroup && (0 until view.childCount).any { touched(view.getChildAt(it)) })
        fun visit(group: ViewGroup) {
            for (i in group.childCount - 1 downTo 0) {
                val child = group.getChildAt(i)
                val lock = (child.tag as? LiveKey)?.takeIf {
                    !ticking || layout.button(it.page, it.index)?.label?.let(LiveLabels::isTicking) == true
                }
                when {
                    ticking && lock == null && child.tag is LiveKey -> {}
                    // An absolute volume slider just moves (no new view while the volume changes).
                    lock != null && child is SliderView && child.absolute && volume != null -> child.setLevel(volume.level, volume.muted)
                    lock != null -> if (!touched(child)) keyViews.replace(child, keyView(lock.page, lock.index, lock.compact))
                    child is ViewGroup -> visit(child)
                }
            }
        }
        for (root in listOf(content, dockRow, sideKeys)) visit(root)
    }

    private fun toggleKey(page: Page, index: Int) = "${page.name}#$index"

    private fun flipToggle(page: Page, index: Int) {
        val key = toggleKey(page, index)
        if (!toggledOn.remove(key)) toggledOn += key
    }

    override val toggledKeys: Int get() = toggledOn.size

    override fun resetToggles() {
        toggledOn.clear()
        renderPage()
    }

    // ---------------------------------------------------------------- pressing

    override fun onKeyPressed(view: View, page: Page, index: Int, button: MacroButton) =
        pressKey(view, page, index, button, view)

    override fun onKeyReleased(view: View, page: Page, index: Int, button: MacroButton, cancelled: Boolean) {
        releaseKey(view, tapped = !cancelled)
        // Toggle keys flip on press, live keys follow the computer; redraw them once the finger lifts.
        if (button.toggle || liveState(button) != null) {
            keyViews.replace(view, keyView(page, index, compact = page === layout.dock || page.kind == PageKind.TRACKPAD))
        }
    }

    override fun onEditKey(page: Page, index: Int) = editKey(page, index)

    /** One tick of a slider key: its up or down step, sent like a tap. */
    override fun onSlide(page: Page, index: Int, button: MacroButton, up: Boolean) {
        val slide = Macro.slider(button.action) ?: return
        val hid = hid
        if (hid == null || !connected) return flashOfflineBanner()
        hid.run(listOf(if (up) slide.up else slide.down))
    }

    /** The volume slider, absolute over the PC link, was dragged. */
    override fun onCardButton(action: String) {
        val hid = hid
        if (hid == null || !connected) return flashOfflineBanner()
        hid.run(Macro.parse(action))
    }

    override fun onSlideLevel(page: Page, index: Int, button: MacroButton, level: Int, done: Boolean) =
        sendVolume(null, level, done)

    /** The slider's icon was tapped: its tap step (Mute for the volume slider), sent like a tap. */
    override fun onSlideTap(page: Page, index: Int, button: MacroButton) {
        val tap = Macro.slider(button.action)?.tap ?: return
        val hid = hid
        if (hid == null || !connected) return flashOfflineBanner()
        hid.run(listOf(tap))
    }

    private fun flashOfflineBanner() {
        offlineBanner.animate().alpha(0.3f).setDuration(120)
            .withEndAction { offlineBanner.animate().alpha(1f).setDuration(120) }
    }

    /** A key with a long-press action, waiting to see whether it is tapped or held. */
    private class PendingPress(val page: Page, val index: Int, val button: MacroButton, val longPress: Runnable)

    private val pendingPresses = HashMap<Any, PendingPress>()

    /**
     * [token] identifies the physical source (touched view or hardware key) for press/release pairing.
     * Keys without a long-press action fire at once; the others wait [LONG_PRESS_MS] to decide.
     */
    private fun pressKey(token: Any, page: Page, index: Int, button: MacroButton, view: View?) {
        val longAction = button.longAction?.takeIf { it.isNotBlank() }
            ?: return runAction(token, page, index, button, button.action, view, hold = true)
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val longPress = Runnable {
            pendingPresses.remove(token)
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            runAction(token, page, index, button, longAction, null, hold = true, primary = false)
        }
        pendingPresses[token] = PendingPress(page, index, button, longPress)
        handler.postDelayed(longPress, LONG_PRESS_MS)
    }

    /** [tapped] = the finger lifted normally, so a key still waiting for a long press runs its tap action. */
    private fun releaseKey(token: Any, tapped: Boolean = false) {
        pendingPresses.remove(token)?.let { pending ->
            handler.removeCallbacks(pending.longPress)
            if (tapped) runAction(token, pending.page, pending.index, pending.button, pending.button.action, null, hold = false)
        }
        heldSteps.remove(token)?.let { hid?.release(token, it); resumeCapture() }
    }

    /**
     * Runs [action] of [button]: page steps on the tablet, the rest on the computer. With [hold] a
     * single combo / media key / click stays down until [releaseKey]. [primary] = the tap action
     * (flips toggle keys), not the long-press one.
     */
    private fun runAction(
        token: Any, page: Page, index: Int, button: MacroButton, action: String, view: View?,
        hold: Boolean, primary: Boolean = true,
    ) {
        val steps = try {
            Macro.parse(action)
        } catch (e: MacroException) {
            ui.toast("${button.label}: ${e.message}")
            return
        }
        // A slider assigned somewhere it can't be dragged (the tablet's volume buttons).
        if (steps.any { it is Step.Slide }) return ui.toast("${button.label}: drag the slider")
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val missing = steps.flatMap { step ->
            when (step) {
                is Step.Text -> TextVariables.secretNames(step.text)
                is Step.Http -> TextVariables.secretNames(step.url + step.body.orEmpty())
                else -> NetActions.requiredSecrets(step)
            }
        }.distinct().filter { secrets[it] == null }
        if (missing.isNotEmpty()) return ui.toast("${button.label}: set ${missing.joinToString()} in ⚙ → Saved values")
        recorder.record(action)
        if (primary) noteUse(page)
        steps.filterIsInstance<Step.Page>().lastOrNull()?.let { goToPage(it.target) }
        // Dim after the keys have gone out (e.g. "lock PC, then dim"), so the idle overlay doesn't
        // block them.
        if (steps.any { it is Step.Tablet && it.command == "dim" }) handler.postDelayed({ dim() }, 600)
        steps.filter { it.isNetwork }.takeIf { it.isNotEmpty() }?.let { runNetwork(button.label, it) }
        // PC steps go over the PC link; Teams / Outlook steps only while it reaches Teams / classic
        // Outlook. Otherwise they become their shortcut, in place. pc:focus / pc:wait stay in
        // place: the keys after them wait for them (runInOrder).
        val link = service?.pc?.takeIf { it.connected }
        fun overLink(step: Step.Pc) = link != null && when (step.command) {
            "teams" -> link.status?.teams != null
            "outlook" -> link.status?.hasOutlook == true // classic Outlook open on the computer
            else -> true
        }
        val sent = steps.filterIsInstance<Step.Pc>().filter { !it.waits && overLink(it) }
        sent.forEach { sendPc(button.label, link!!, it) }
        val remote = steps.flatMap { step ->
            if (step is Step.Pc && !step.waits && !overLink(step)) pcFallback(button.label, step) else listOf(step)
        }.filterNot { it is Step.Page || it is Step.Tablet || (it is Step.Pc && !it.waits) || it.isNetwork }.map { step ->
            if (step is Step.Text) Step.Text(expandText(step.text)) else step
        }
        if (remote.any { it is Step.Pc }) {
            // Without the link, the keys after a pc:focus could land in the wrong window: none go.
            if (link == null) return ui.toast("${button.label}: needs the PC link to find its window (docs/pc-link.md)")
            if (primary && button.toggle) flipToggle(page, index)
            pauseCapture()
            return runInOrder(button.label, remote, onEnd = ::resumeCapture)
        }
        if (remote.isEmpty()) {
            if (primary && button.toggle && sent.isNotEmpty()) flipToggle(page, index)
            return
        }
        if (action.contains("{clipboard}") && clipboardText().isNullOrEmpty()) {
            return ui.toast("The tablet's clipboard is empty — copy something on the tablet first")
        }
        TextVariables.pcClipboardSlots(action).firstOrNull { pcStatus?.clips?.getOrNull(it - 1) == null }?.let { slot ->
            return ui.toast(if (pcStatus == null) "The computer's clipboard needs the PC link (docs/pc-link.md)"
                else "The computer's clipboard has no copy #$slot yet")
        }
        val hid = hid
        if (hid == null || !connected) {
            if (!recorder.isRecording) flashOfflineBanner()
            return
        }
        if (primary && button.toggle) flipToggle(page, index)
        pauseCapture()
        if (hold && Macro.isHoldable(remote) && steps.none { it is Step.Tablet }) {
            heldSteps[token] = remote[0]
            hid.press(token, remote[0])
        } else {
            runSteps(hid, remote) { runOnUiThread { resumeCapture() } }
        }
    }

    private val network = Executors.newCachedThreadPool()

    /** Web, Wake-on-LAN and Home Assistant steps, in order, off the main thread; a failure stops the rest. */
    private fun runNetwork(label: String, steps: List<Step>) {
        network.execute {
            try {
                for (step in steps) NetActions.run(step, secret = { secrets[it] }) { expandText(it) }
            } catch (e: Exception) {
                Log.w(TAG, "network step failed: $e")
                runOnUiThread { ui.toast("$label: ${(e as? IOException)?.message ?: "network error"}") }
            }
        }
    }

    private fun expandText(text: String) = TextVariables.expand(text, secret = { secrets[it] }, clipboard = ::clipboardText,
        pcClipboard = { pcStatus?.clips?.getOrNull(it - 1) })

    /**
     * Runs a macro; long texts show a banner (tap to stop) and say which characters can't be typed.
     * [onDone] (on the sender thread) gets whether every step went out.
     */
    private fun runSteps(hid: HidPeripheral, steps: List<Step>, onDone: ((Boolean) -> Unit)? = null) {
        val text = steps.filterIsInstance<Step.Text>().joinToString("") { it.text }
        val skipped = hid.keyboardLayout.unsupported(text)
        if (skipped.isNotEmpty()) {
            ui.toast("Can't type ${skipped.joinToString(" ").take(40)} with the ${hid.keyboardLayout.label} layout; skipped")
        }
        if (text.length < LONG_TEXT) return hid.run(steps, onDone)
        typingRuns++
        typingBanner.text = "Typing ${text.length} characters… tap here to stop"
        typingBanner.visibility = View.VISIBLE
        hid.run(steps) { finished ->
            runOnUiThread {
                typingRuns--
                if (typingRuns == 0) typingBanner.visibility = View.GONE
            }
            onDone?.invoke(finished)
        }
    }

    /**
     * Runs [steps] in order, holding at each `pc:focus` / `pc:wait` until the computer says it's
     * done; if it can't (or doesn't answer), the rest doesn't run.
     */
    private fun runInOrder(label: String, steps: List<Step>, onEnd: () -> Unit = {}) {
        val at = steps.indexOfFirst { it is Step.Pc }
        val keys = if (at < 0) steps else steps.take(at)
        val next = {
            if (at < 0) onEnd()
            else awaitPc(label, steps[at] as Step.Pc) { ok -> if (ok) runInOrder(label, steps.drop(at + 1), onEnd) else onEnd() }
        }
        if (keys.isEmpty()) return next()
        val hid = hid
        if (hid == null || !connected) { flashOfflineBanner(); return onEnd() }
        runSteps(hid, keys) { finished -> runOnUiThread { if (finished) next() else onEnd() } }
    }

    /** What to do with the computer's answer to a pc:focus / pc:wait, by request id. */
    private val pcWaits = HashMap<Int, (Boolean) -> Unit>()

    private fun awaitPc(label: String, step: Step.Pc, then: (Boolean) -> Unit) {
        val link = service?.pc?.takeIf { it.connected }
        val id = when {
            step.point != null -> link?.click(step.command, step.name, step.point)
            step.command == "focus" -> link?.activate(step.name)
            else -> link?.waitFor(step.name)
        } ?: return ui.toast("$label: needs the PC link (docs/pc-link.md)")
        pcRequests[id] = label
        pcWaits[id] = then
        // The computer answers within 10 s; a script from before 1.8 never does.
        handler.postDelayed({
            pcWaits.remove(id)?.let {
                pcRequests.remove(id)
                ui.toast("$label: the computer didn't answer — update the PC link script")
                it(false)
            }
        }, PC_WAIT_MS)
    }

    /** "next" / "prev" (among the pages shown for this computer) or a page name. */
    private fun goToPage(target: String) {
        val shown = layout.pages.indices.filter { layout.pages[it].name !in hiddenPages() }
        val at = shown.indexOf(pageIndex)
        val index = when (target.lowercase()) {
            "next" -> shown[(at + 1).mod(shown.size)]
            "prev", "previous" -> shown[(at - 1).mod(shown.size)]
            else -> layout.pages.indexOfFirst { it.name.equals(target, ignoreCase = true) }
        }
        if (index < 0) return ui.toast("No page named \"$target\"")
        selectPage(index)
        renderPage()
    }

    private fun clipboardText(): String? =
        getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            // Windows / old Mac line endings: one Enter per line.
            ?.replace("\r\n", "\n")?.replace('\r', '\n')

    // ---------------------------------------------------------------- status

    private fun showState(state: HidPeripheral.State, detail: String) {
        val color = when (state) {
            HidPeripheral.State.CONNECTED -> GREEN
            HidPeripheral.State.ADVERTISING -> BLUE
            HidPeripheral.State.STARTING -> AMBER
            HidPeripheral.State.OFF, HidPeripheral.State.UNAVAILABLE -> RED
        }
        statusDot.background = ui.roundRect(color, ui.dp(4).toFloat())
        val offline = state != HidPeripheral.State.CONNECTED
        offlineBanner.visibility = if (offline) View.VISIBLE else View.GONE
        dockRow.visibility = if (offline) View.INVISIBLE else View.VISIBLE
        offlineIcon.imageTintList = android.content.res.ColorStateList.valueOf(color)
        offlineTitle.text = if (state == HidPeripheral.State.OFF) "Bluetooth is off" else "Not connected"
        offlineText.text = when (state) {
            HidPeripheral.State.ADVERTISING -> "Keys won't reach a computer. " +
                (hid?.preferredHost()?.let { "Waiting for ${it.name}." } ?: "Waiting for any paired computer.")
            HidPeripheral.State.OFF -> "Tap to turn it on."
            else -> detail
        }
        val waitingFor = hid?.preferredHost()
        statusTitle.text = when (state) {
            HidPeripheral.State.CONNECTED -> "Connected"
            HidPeripheral.State.ADVERTISING -> if (waitingFor != null) "Waiting" else "Ready to pair"
            HidPeripheral.State.STARTING -> "Starting"
            HidPeripheral.State.OFF -> "Bluetooth off"
            HidPeripheral.State.UNAVAILABLE -> "Unavailable"
        }
        statusText.text = when (state) {
            HidPeripheral.State.CONNECTED -> detail.removePrefix("Connected to ")
            HidPeripheral.State.ADVERTISING -> waitingFor?.name ?: "Tap for steps"
            HidPeripheral.State.OFF -> "Tap to turn on"
            else -> detail
        }
        switchButton.visibility = if ((hid?.knownHosts()?.size ?: 0) >= 2) View.VISIBLE else View.GONE
        showLocks()
        if (state == HidPeripheral.State.CONNECTED) hid?.currentHost()?.let { onComputerConnected(it.address) }
        if (state != HidPeripheral.State.CONNECTED && toggledOn.isNotEmpty()) {
            toggledOn.clear() // the computer's state is unknown after a reconnect
            renderPage()
        }
    }

    /** The status's Caps / Num lights (lit while on) and the lock keys on screen. */
    private fun showLocks() {
        for ((badge, key, accent) in listOf(Triple(capsBadge, "capslock", AMBER), Triple(numBadge, "numlock", BLUE))) {
            val on = connected && hid?.lockOn(HidKeys.keys.getValue(key)) == true
            badge.setTextColor(if (on) TEXT else FAINT)
            badge.setCompoundDrawablesRelativeWithIntrinsicBounds(
                ui.roundRect(if (on) accent else RAISED, ui.dp(4).toFloat()).apply { setSize(ui.dp(8), ui.dp(8)) },
                null, null, null)
            badge.contentDescription = "${badge.text} Lock ${if (on) "on" else "off"}"
        }
        refreshLiveKeys()
    }

    /** Shows the pages chosen for [address], opening its start page when a different computer connects. */
    private fun onComputerConnected(address: String) {
        if (address == profileAddress) return
        profileAddress = address
        service?.profiles?.get(address)?.startPage
            ?.let { name -> layout.pages.indexOfFirst { it.name == name } }
            ?.takeIf { it >= 0 }
            ?.let { selectPage(it) }
        renderPage()
    }

    /** Hands the macropad to the other computer (or lets the user pick when there are several). */
    private fun switchComputer() {
        val hid = hid ?: return
        val current = hid.currentHost()?.address ?: hid.preferredHost()?.address
        val others = hid.knownHosts().filter { it.address != current }
        when (others.size) {
            0 -> ui.toast("Pair another computer first")
            1 -> {
                hid.switchTo(others[0].address)
                ui.toast("Switching to ${others[0].name}…")
            }
            else -> dialogs.computers()
        }
    }

    // ---------------------------------------------------------------- screen protection

    private lateinit var screen: View

    /** Moves the whole screen a couple of pixels every few minutes, so OLED pixels don't keep one image. */
    private val shiftScreen = object : Runnable {
        override fun run() {
            val reach = ui.dp(2)
            screen.translationX = (-reach..reach).random().toFloat()
            screen.translationY = (-reach..reach).random().toFloat()
            handler.postDelayed(this, SHIFT_EVERY_MS)
        }
    }

    override fun screenShiftChanged() {
        handler.removeCallbacks(shiftScreen)
        if (settings.screenShift) handler.postDelayed(shiftScreen, SHIFT_EVERY_MS)
        else { screen.translationX = 0f; screen.translationY = 0f }
    }

    override fun profilesChanged() {
        store.save(layout) // the backup file includes computer settings
        service?.applySettings()
        hid?.namesChanged()
        renderPage()
    }

    @SuppressLint("MissingPermission")
    private fun onStatusTapped() {
        if (hid?.state == HidPeripheral.State.OFF) startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        else dialogs.connectionInfo()
    }

    /**
     * Fire OS usually puts the "confirm pairing code" prompt in a notification while another app is
     * in front, which is easy to miss — and the computer gives up if the tablet never confirms.
     * Show the code ourselves and pull the notification shade down so its Pair button is visible.
     */
    private val pairingReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val code = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
            ui.dialog()
                .setTitle("Pairing with ${device.name ?: device.address}")
                .setMessage((if (code >= 0) "Code: ${"%06d".format(code)}\n\n" else "") +
                    "Check the computer shows the same code and accept it there, then tap PAIR in the " +
                    "tablet's Bluetooth notification (the notification shade has been opened for you).")
                .setPositiveButton("OK", null)
                .show()
            expandNotificationShade()
        }
    }

    @SuppressLint("WrongConstant")
    private fun expandNotificationShade() {
        try {
            val statusBar = getSystemService("statusbar")
            statusBar.javaClass.getMethod("expandNotificationsPanel").invoke(statusBar)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "could not open notification shade: $e")
        }
    }

    // ---------------------------------------------------------------- MacropadHost

    override fun saveAndRender() {
        store.save(layout)
        settings.lastPage = pageIndex
        renderPage()
    }

    override fun selectPage(index: Int) {
        pageIndex = index.coerceIn(0, layout.pages.lastIndex)
        settings.lastPage = pageIndex
    }

    override fun editKey(page: Page, index: Int, title: String?) {
        val existing = layout.button(page, index)
        KeyEditor.show(ui, title ?: if (existing == null) "New key" else "Edit key", existing, existing != null,
            onSave = { layout.setButton(page, index, it); saveAndRender() },
            onDelete = { layout.setButton(page, index, null); saveAndRender() })
    }

    override fun exportLayout() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "macropad-layout.json")
        try {
            startActivityForResult(intent, REQUEST_EXPORT)
        } catch (e: ActivityNotFoundException) {
            ui.toast("No file picker; the backup is at ${store.backupFile?.absolutePath}")
        }
    }

    override fun importLayout() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            startActivityForResult(intent, REQUEST_IMPORT)
        } catch (e: ActivityNotFoundException) {
            ui.toast("No file picker available on this tablet")
        }
    }

    @Deprecated("Activity result API needs AndroidX; this app uses the platform API only")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQUEST_EXPORT -> {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(store.export(layout).toByteArray()) }
                    ui.toast("Layout exported")
                }
                REQUEST_IMPORT -> {
                    val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    val imported = store.import(text)
                    dialogs.confirm("Import layout?",
                        "Replaces all pages and keys with the ${imported.pages.size} pages in the file.") {
                        layout = imported
                        store.importComputers(text)
                        selectPage(0)
                        saveAndRender()
                        profilesChanged()
                    }
                }
            }
        } catch (e: Exception) {
            ui.toast("Failed: ${e.message}")
        }
    }

    override fun quit() {
        stopService(Intent(this, MacropadService::class.java))
        finishAndRemoveTask()
    }

    // ---------------------------------------------------------------- editing & recording

    private fun toggleEdit() {
        editing = !editing
        renderPage()
        if (editing) ui.toast("Tap a key to edit it · long-press a tab to rename it")
    }

    private fun startRecording() {
        editing = false
        recorder.start(pcStatus?.app)
        renderPage()
        // Experimental (⚙ → Connection): the computer's own keyboard too.
        val link = service?.pc?.takeIf { it.connected }
        when {
            !settings.recordPcKeyboard || link == null -> ui.toast("Recording — keys you press are sent live and captured")
            link.status?.canCapture != true ->
                ui.toast("Recording the tablet's keys only: start the PC link script with -AllowRecording to record the computer's keyboard and mouse")
            else -> {
                capturing = true
                link.capture(on = true)
                ui.toast("Recording — the tablet's keys and the computer's keyboard and mouse (5 minutes at most)")
            }
        }
    }

    /** An app that came to the front on the computer while recording, recorded once it stays a second. */
    private var recordedApp: String? = null
    private val recordApp = Runnable {
        val app = recordedApp
        if (app != null && pcStatus?.app == app) recorder.appChanged(app)
    }

    private fun stopRecording() {
        val recording = recorder.finish() ?: return ui.toast("Nothing recorded")
        handler.removeCallbacks(recordApp)
        val app = recording.app
        val appName = app?.let { a -> pcStatus?.windows?.firstOrNull { it.exe == a }?.app } ?: app
        RecordingEditor(ui).show(recording, appName,
            onTry = { macro -> runAction(Any(), page, -1, MacroButton("Recording", macro, 0, null), macro, null, hold = false) },
            onSave = { macro -> saveRecording(macro, app) })
    }

    /**
     * Opens the key editor on a free slot of the page for the app it was recorded in (with a free
     * slot), else of the Custom page.
     */
    private fun saveRecording(macro: String, app: String?) {
        fun freeSlot(p: Page) = (0 until layout.cells).firstOrNull { layout.button(p, it) == null }
        val target = layout.pages.firstOrNull {
            app != null && it.kind == PageKind.KEYS && app in it.appNames && it.name !in hiddenPages() && freeSlot(it) != null
        } ?: layout.pages.firstOrNull { it.name == DefaultLayout.CUSTOM_PAGE }
            ?: Page(DefaultLayout.CUSTOM_PAGE, mutableListOf()).also { layout.pages += it }
        selectPage(layout.pages.indexOf(target))
        renderPage()
        val slot = freeSlot(target) ?: return ui.toast("${target.name} page is full — make the grid bigger or delete a key")
        val count = target.buttons.count { it != null } + 1
        KeyEditor.show(ui, "Save recording", MacroButton("Recording $count", macro, Palette.colors[4], "bolt"), false,
            onSave = { layout.setButton(target, slot, it); saveAndRender() },
            onDelete = {})
    }

    companion object {
        private const val TAG = "Macropad"
        private const val EVENT_REFRESH_MS = 5 * 60_000L
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_EXPORT = 2
        private const val REQUEST_IMPORT = 3
        private const val TRACKPAD_KEYS = 5
        private const val SIDEBAR_WIDTH_DP = 140
        /** Space between two dock keys. */
        private const val DOCK_GAP_DP = 6
        /** Texts at least this long show the "tap to stop" banner. */
        private const val LONG_TEXT = 60
        private const val LONG_PRESS_MS = 450L
        /** At most one volume level per this long while a slider is dragged (the PC link is slow). */
        private const val LEVEL_MS = 80L
        /** What − / + change in the volume mixer. */
        private const val MIXER_STEP = 5
        /** A pc:focus / pc:wait not answered by then stops its macro (the computer gives up at 10 s). */
        private const val PC_WAIT_MS = 15_000L
        /** PowerPoint's process name: never the demo's app. */
        private const val POWERPOINT = "powerpnt"
        private const val MAIL_SHOWN_MS = 8_000L
        /** The idle clock says "New mail from …" for this long after it came. */
        private const val MAIL_ON_CLOCK_MS = 30 * 60_000L
        /** The mirrored screen is scaled to fit this: sharp enough, and a whole picture is ~200 KB. */
        private const val SCREEN_WIDTH = 1600
        private const val SCREEN_HEIGHT = 1000

        private const val SHIFT_EVERY_MS = 3 * 60_000L

        /**
         * Opens the macropad from the background (boot, charger). Android 10+ only allows that with
         * "Display over other apps"; without it, a notification offers to open it.
         */
        fun bringToFront(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (DeviceQuirks.needsOverlayToOpenFromBackground && !android.provider.Settings.canDrawOverlays(context)) {
                MacropadService.offerToOpen(context, intent)
            } else {
                context.startActivity(intent)
            }
        }
    }
}
