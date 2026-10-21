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
import android.text.TextUtils
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
import dev.belta.macropad.hid.HidPeripheral
import dev.belta.macropad.model.AppSettings
import dev.belta.macropad.model.DefaultLayout
import dev.belta.macropad.model.Layout
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
import dev.belta.macropad.ui.AppIcons
import dev.belta.macropad.ui.Dialogs
import dev.belta.macropad.ui.Icons
import dev.belta.macropad.ui.KeyEditor
import dev.belta.macropad.ui.KeyViews
import dev.belta.macropad.ui.MacropadHost
import dev.belta.macropad.ui.Recorder
import dev.belta.macropad.ui.TrackpadView
import dev.belta.macropad.ui.Ui
import dev.belta.macropad.ui.Ui.Companion.BG_BOTTOM
import dev.belta.macropad.ui.Ui.Companion.BG_TOP
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.RED
import dev.belta.macropad.ui.Ui.Companion.STROKE
import dev.belta.macropad.ui.Ui.Companion.SURFACE_HI
import dev.belta.macropad.ui.Ui.Companion.STROKE_STRONG
import dev.belta.macropad.ui.Ui.Companion.SURFACE
import dev.belta.macropad.ui.Ui.Companion.SURFACE_ACTIVE
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.withAlpha

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
    private lateinit var offlineBanner: TextView
    private lateinit var typingBanner: TextView
    /** Long texts being typed (the stop banner shows while > 0). */
    private var typingRuns = 0
    private lateinit var content: FrameLayout
    private lateinit var statusChip: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
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
        recorder = Recorder(ui, send = { action -> if (connected) hid?.run(Macro.parse(action)) }) { renderChrome() }

        idleOverlay = buildIdleOverlay()
        setContentView(FrameLayout(this).apply {
            addView(buildUi())
            addView(idleOverlay)
        })
        // Re-hide the status bar if something (e.g. the notification shade) brought it back.
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { flags ->
            if (flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) window.decorView.postDelayed({ hideSystemUi() }, 2000)
        }
        renderPage()

        registerReceiver(pairingReceiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST))
        registerReceiver(quitReceiver, IntentFilter(MacropadService.ACTION_QUIT))
        if (!MacropadService.hasBluetoothPermissions(this)) requestPermissions()
        MacropadService.start(this)
        bindService(Intent(this, MacropadService::class.java), connection, Context.BIND_AUTO_CREATE)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** `adb shell am start -n dev.belta.macropad/.MainActivity --ez reset_pairing true` */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("reset_pairing", false) != true) return
        hid?.resetPairing() ?: run { pendingResetPairing = true }
    }

    override fun onDestroy() {
        (heldSteps.keys + pendingPresses.keys).toList().forEach { releaseKey(it) }
        service?.removeListener(stateListener)
        unbindService(connection)
        unregisterReceiver(pairingReceiver)
        unregisterReceiver(quitReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resetIdleTimer()
    }

    override fun onPause() {
        handler.removeCallbacks(dimRunnable)
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
            typeface = ui.fontBold
            setTextColor(0xFF8A93A6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
        }
        idleDate = TextView(context).apply {
            typeface = ui.fontMedium
            setTextColor(0xFF5C6476.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }
        idleStatus = TextView(context).apply {
            typeface = ui.fontMedium
            setTextColor(0xFF4A5163.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, ui.dp(18), 0, 0)
        }
        for (line in listOf(idleTime, idleDate, idleStatus)) {
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
    }

    private fun updateIdleClock() {
        val now = java.util.Date()
        idleTime.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now)
        idleDate.text = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(now)
        idleStatus.text = if (connected) "● ${statusText.text}" else "○ Not connected"
        // Drift a little so nothing stays lit on the same pixels.
        idleClock.translationX = ((Math.random() - 0.5) * ui.dp(160)).toFloat()
        idleClock.translationY = ((Math.random() - 0.5) * ui.dp(120)).toFloat()
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val slot = hardwareSlot(keyCode) ?: return super.onKeyDown(keyCode, event)
        val button = layout.button(layout.hardware, slot) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) pressKey(keyCode, layout.hardware, slot, button, null)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val slot = hardwareSlot(keyCode) ?: return super.onKeyUp(keyCode, event)
        layout.button(layout.hardware, slot) ?: return super.onKeyUp(keyCode, event)
        releaseKey(keyCode, tapped = true)
        return true
    }

    // ---------------------------------------------------------------- UI construction

    private fun buildUi(): View {
        // Main column (tabs, keys, dock) and, on the right, the sidebar shown on every page: status,
        // app controls and the sidebar's action keys.
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
            background = ui.roundRect(SURFACE, ui.dp(24).toFloat()).apply { setStroke(ui.dp(1), STROKE) }
            isMotionEventSplittingEnabled = true
        }
        sidebarView = sidebar
        outer.addView(sidebar)
        placeSidebar()

        // Top bar: page tabs
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = ui.roundRect(SURFACE, ui.dp(24).toFloat())
        }
        tabScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
            // The strip narrows when the switch button appears; keep the selected tab in view.
            addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) scrollToActiveTab() }
        }
        bar.addView(tabScroller, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        statusChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(8), ui.dp(10), ui.dp(8))
            background = ui.pressable(SURFACE_HI, ui.dp(18).toFloat())
            setOnClickListener { onStatusTapped() }
        }
        statusDot = View(this)
        statusChip.addView(statusDot, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)))
        statusText = TextView(this).apply {
            setTextColor(TEXT)
            typeface = ui.fontMedium
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(ui.dp(8), 0, 0, 0)
            // Always two lines, so the chip (and the sidebar under it) keeps its size as the status
            // changes; the full text is in Diagnostics (tap the chip).
            setLines(2)
            gravity = Gravity.CENTER_VERTICAL // one-line names sit level with the dot
            ellipsize = TextUtils.TruncateAt.END
            text = "Starting…"
        }
        statusChip.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sidebar.addView(statusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(6) })

        // App controls, two per row: record · edit / settings · switch computer
        switchButton = ui.iconButton("swap") { switchComputer() }.apply { visibility = View.GONE }
        recordButton = ui.iconButton("record") {
            if (recorder.isRecording) stopRecording() else withUnlock { startRecording() }
        }
        editButton = ui.iconButton("edit") { if (editing) toggleEdit() else withUnlock { toggleEdit() } }
        val settingsButton = ui.iconButton("settings") { withUnlock { dialogs.settings() } }
        for (pair in listOf(listOf(recordButton, editButton), listOf(settingsButton, switchButton))) {
            sidebar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                for (button in pair) addView(button, LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply {
                    setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
                })
            })
        }

        // The sidebar's keys (Away, Lock PC, …), sharing the remaining height
        sideKeys = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isMotionEventSplittingEnabled = true
        }
        sidebar.addView(sideKeys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = ui.dp(6)
        })
        root.addView(bar)

        // Takes the media dock's place while no computer is connected (the dock does nothing then),
        // so key presses that go nowhere are obvious without moving the keys.
        offlineBanner = TextView(this).apply {
            setTextColor(0xFFFF8FA3.toInt())
            typeface = ui.fontSemiBold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            visibility = View.GONE
            background = ui.roundRect(0xFF2A1219.toInt(), ui.dp(26).toFloat()).apply {
                setStroke(ui.dp(1), withAlpha(RED, 0.45f))
            }
            setOnClickListener { onStatusTapped() }
        }

        // Shown over the dock while a long text is being typed; tapping it stops the typing.
        typingBanner = TextView(this).apply {
            setTextColor(TEXT)
            typeface = ui.fontSemiBold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            visibility = View.GONE
            background = ui.pressable(0xFF15263F.toInt(), ui.dp(26).toFloat())
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
            background = ui.roundRect(0xFF2A1219.toInt(), ui.dp(24).toFloat()).apply {
                setStroke(ui.dp(1), withAlpha(RED, 0.45f))
            }
        }
        recordStatus = TextView(this).apply {
            setTextColor(0xFFFF8FA3.toInt())
            typeface = ui.fontSemiBold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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

        // Media dock, shared by every page
        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
            background = ui.roundRect(SURFACE, ui.dp(26).toFloat()).apply { setStroke(ui.dp(1), STROKE) }
            isMotionEventSplittingEnabled = true
        }
        root.addView(FrameLayout(this).apply {
            addView(dockRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(offlineBanner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(typingBanner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(76)).apply {
            setMargins(ui.dp(5), ui.dp(10), ui.dp(5), 0)
        })
        return outer
    }

    /** Puts the sidebar on the side chosen in ⚙ (right by default). */
    override fun placeSidebar() {
        val outer = sidebarView.parent as? LinearLayout ?: return
        outer.removeView(sidebarView)
        val left = settings.sidebarLeft
        outer.addView(sidebarView, if (left) 0 else outer.childCount,
            LinearLayout.LayoutParams(ui.dp(SIDEBAR_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                if (left) marginEnd = ui.dp(10) else marginStart = ui.dp(10)
            })
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        val hidden = hiddenPages()
        layout.pages.forEachIndexed { i, p ->
            if (p.name in hidden) return@forEachIndexed
            val active = i == pageIndex
            // App icon on every tab; the selected one also shows its name.
            tabs.addView(LinearLayout(this).apply {
                tag = p.name
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = p.name
                setPadding(ui.dp(if (active) 8 else 9), 0, ui.dp(if (active) 16 else 9), 0)
                addView(ImageView(context).apply {
                    setImageDrawable(AppIcons.drawable(ui, p.icon ?: DefaultLayout.iconFor(p.name), p.name))
                    alpha = if (active) 1f else 0.72f
                }, LinearLayout.LayoutParams(ui.dp(if (active) 28 else 26), ui.dp(if (active) 28 else 26)))
                if (active) addView(TextView(context).apply {
                    text = p.name
                    typeface = ui.fontBold
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    setPadding(ui.dp(9), 0, 0, 0)
                })
                background = if (active) {
                    ui.roundRect(SURFACE_ACTIVE, ui.dp(20).toFloat()).apply { setStroke(ui.dp(1), STROKE_STRONG) }
                } else {
                    ui.pressable(Color.TRANSPARENT, ui.dp(20).toFloat())
                }
                setOnClickListener { selectPage(i); renderPage() }
                setOnLongClickListener { withUnlock { dialogs.pageMenu(i) }; true }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)).apply {
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
        ui.setIconButton(editButton, if (editing) "check" else "edit", if (editing) Palette.colors[1] else null)
        ui.setIconButton(recordButton, if (recorder.isRecording) "stop" else "record", if (recorder.isRecording) RED else null)
        recordButton.imageTintList = android.content.res.ColorStateList.valueOf(if (recorder.isRecording) Color.WHITE else RED)
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
        content.addView(if (page.kind == PageKind.TRACKPAD) buildTrackpad(page) else buildGrid(page))

        renderSidebar()

        // Icon-only keys (media) take one share of the width, labelled keys two; a divider
        // separates the two groups.
        dockRow.removeAllViews()
        var previousIconOnly: Boolean? = null
        for (index in 0 until Layout.DOCK_SIZE) {
            val iconOnly = dockIconOnly(layout.button(layout.dock, index))
            if (previousIconOnly != null && iconOnly != previousIconOnly) {
                dockRow.addView(View(this).apply { setBackgroundColor(STROKE) },
                    LinearLayout.LayoutParams(ui.dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        setMargins(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12))
                    })
            }
            previousIconOnly = iconOnly
            dockRow.addView(keyView(layout.dock, index, compact = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, if (iconOnly) 1f else 2f).apply {
                    setMargins(ui.dp(3), 0, ui.dp(3), 0)
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

    /** Dock keys that only send a media key show just their icon. Empty slots count as icon-only. */
    private fun dockIconOnly(button: MacroButton?) = button == null ||
        (Icons.get(button.icon) != null && runCatching { Macro.parse(button.action) }.getOrNull()?.singleOrNull() is Step.Media)

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

    private fun keyView(page: Page, index: Int, compact: Boolean) = keyViews.build(
        page, index, layout.button(page, index), compact, editing,
        toggledOn = toggleKey(page, index) in toggledOn,
        emphasize = page === layout.dock && index == 1,
        iconOnly = page === layout.dock && dockIconOnly(layout.button(page, index)),
        showHint = page !== layout.sidebar,
        small = page === layout.sidebar,
    )

    private fun toggleKey(page: Page, index: Int) = "${page.name}#$index"

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
        // Toggle keys flip on press; redraw them once the finger lifts.
        if (button.toggle) {
            keyViews.replace(view, keyView(page, index, compact = page === layout.dock || page.kind == PageKind.TRACKPAD))
        }
    }

    override fun onEditKey(page: Page, index: Int) = editKey(page, index)

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
        heldSteps.remove(token)?.let { hid?.release(token, it) }
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
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val missing = steps.filterIsInstance<Step.Text>().flatMap { TextVariables.secretNames(it.text) }
            .distinct().filter { secrets[it] == null }
        if (missing.isNotEmpty()) return ui.toast("${button.label}: set ${missing.joinToString()} in ⚙ → Saved values")
        recorder.record(action)
        steps.filterIsInstance<Step.Page>().lastOrNull()?.let { goToPage(it.target) }
        // Dim after the keys have gone out (e.g. "lock PC, then dim"), so the idle overlay doesn't
        // block them.
        if (steps.any { it is Step.Tablet && it.command == "dim" }) handler.postDelayed({ dim() }, 600)
        val remote = steps.filterNot { it is Step.Page || it is Step.Tablet }.map { step ->
            if (step is Step.Text) Step.Text(TextVariables.expand(step.text, secret = { secrets[it] }, clipboard = ::clipboardText)) else step
        }
        if (remote.isEmpty()) return
        if (action.contains("{clipboard}") && clipboardText().isNullOrEmpty()) {
            return ui.toast("The tablet's clipboard is empty — copy something on the tablet first")
        }
        val hid = hid
        if (hid == null || !connected) {
            if (!recorder.isRecording) offlineBanner.animate().alpha(0.3f).setDuration(120)
                .withEndAction { offlineBanner.animate().alpha(1f).setDuration(120) }
            return
        }
        if (primary && button.toggle) {
            val key = toggleKey(page, index)
            if (!toggledOn.remove(key)) toggledOn += key
        }
        if (hold && Macro.isHoldable(remote) && steps.none { it is Step.Tablet }) {
            heldSteps[token] = remote[0]
            hid.press(token, remote[0])
        } else {
            runSteps(hid, remote)
        }
    }

    /** Runs a macro; long texts show a banner (tap to stop) and say which characters can't be typed. */
    private fun runSteps(hid: HidPeripheral, steps: List<Step>) {
        val text = steps.filterIsInstance<Step.Text>().joinToString("") { it.text }
        val skipped = hid.keyboardLayout.unsupported(text)
        if (skipped.isNotEmpty()) {
            ui.toast("Can't type ${skipped.joinToString(" ").take(40)} with the ${hid.keyboardLayout.label} layout; skipped")
        }
        if (text.length < LONG_TEXT) return hid.run(steps)
        typingRuns++
        typingBanner.text = "Typing ${text.length} characters… tap here to stop"
        typingBanner.visibility = View.VISIBLE
        hid.run(steps) {
            runOnUiThread {
                typingRuns--
                if (typingRuns == 0) typingBanner.visibility = View.GONE
            }
        }
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
            HidPeripheral.State.CONNECTED -> Palette.colors[1]
            HidPeripheral.State.ADVERTISING -> Palette.colors[0]
            HidPeripheral.State.STARTING -> Palette.colors[2]
            HidPeripheral.State.OFF, HidPeripheral.State.UNAVAILABLE -> RED
        }
        statusDot.background = ui.roundRect(color, ui.dp(5).toFloat())
        val offline = state != HidPeripheral.State.CONNECTED
        offlineBanner.visibility = if (offline) View.VISIBLE else View.GONE
        dockRow.visibility = if (offline) View.INVISIBLE else View.VISIBLE
        offlineBanner.text = when (state) {
            HidPeripheral.State.ADVERTISING ->
                "Not connected — keys won't reach a computer. " +
                    (hid?.preferredHost()?.let { "Waiting for ${it.name}." } ?: "Waiting for any paired computer.")
            HidPeripheral.State.OFF -> "Bluetooth is off — tap to turn it on"
            else -> "Not connected — $detail"
        }
        statusText.text = when (state) {
            HidPeripheral.State.ADVERTISING -> if (hid?.preferredHost() != null) detail else "Ready to pair"
            HidPeripheral.State.CONNECTED -> detail.removePrefix("Connected to ")
            else -> detail
        }
        switchButton.visibility = if ((hid?.knownHosts()?.size ?: 0) >= 2) View.VISIBLE else View.GONE
        if (state == HidPeripheral.State.CONNECTED) hid?.currentHost()?.let { onComputerConnected(it.address) }
        if (state != HidPeripheral.State.CONNECTED && toggledOn.isNotEmpty()) {
            toggledOn.clear() // the computer's state is unknown after a reconnect
            renderPage()
        }
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
        recorder.start()
        renderPage()
        ui.toast("Recording — keys you press are sent live and captured")
    }

    private fun stopRecording() {
        val macro = recorder.finish() ?: return ui.toast("Nothing recorded")
        // Recordings go to the first free slot of the Custom page.
        val custom = layout.pages.firstOrNull { it.name == DefaultLayout.CUSTOM_PAGE }
            ?: Page(DefaultLayout.CUSTOM_PAGE, mutableListOf()).also { layout.pages += it }
        selectPage(layout.pages.indexOf(custom))
        renderPage()
        val slot = (0 until layout.cells).firstOrNull { layout.button(custom, it) == null }
            ?: return ui.toast("Custom page is full — make the grid bigger or delete a key")
        val count = custom.buttons.count { it != null } + 1
        KeyEditor.show(ui, "Save recording", MacroButton("Recording $count", macro, Palette.colors[4], "bolt"), false,
            onSave = { layout.setButton(custom, slot, it); saveAndRender() },
            onDelete = {})
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_EXPORT = 2
        private const val REQUEST_IMPORT = 3
        private const val TRACKPAD_KEYS = 5
        private const val SIDEBAR_WIDTH_DP = 128
        /** Texts at least this long show the "tap to stop" banner. */
        private const val LONG_TEXT = 60
        private const val LONG_PRESS_MS = 450L

        fun bringToFront(context: Context) {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
}
