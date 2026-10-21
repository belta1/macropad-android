package dev.belta.macropad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
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
import dev.belta.macropad.model.Step
import dev.belta.macropad.ui.Dialogs
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

    private var editing = false
    private var pendingResetPairing = false

    // Keys currently held, by touched view or hardware key code.
    private val heldSteps = HashMap<Any, Step>()

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

        setContentView(buildUi())
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
        heldSteps.keys.toList().forEach { releaseKey(it) }
        service?.removeListener(stateListener)
        unbindService(connection)
        unregisterReceiver(pairingReceiver)
        unregisterReceiver(quitReceiver)
        super.onDestroy()
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
        releaseKey(keyCode)
        return true
    }

    // ---------------------------------------------------------------- UI construction

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12))
        }

        // Top bar: page tabs · status chip · action buttons
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
            setPadding(ui.dp(14), 0, ui.dp(16), 0)
            background = ui.pressable(SURFACE, ui.dp(22).toFloat())
            setOnClickListener { onStatusTapped() }
        }
        statusDot = View(this)
        statusChip.addView(statusDot, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)))
        statusText = TextView(this).apply {
            setTextColor(TEXT)
            typeface = ui.fontMedium
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(ui.dp(10), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = ui.dp(260)
            text = "Starting…"
        }
        statusChip.addView(statusText)
        switchButton = ui.iconButton("swap") { switchComputer() }.apply { visibility = View.GONE }
        bar.addView(switchButton)
        bar.addView(statusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)).apply {
            marginStart = ui.dp(10)
        })

        recordButton = ui.iconButton("record") { if (recorder.isRecording) stopRecording() else startRecording() }
        editButton = ui.iconButton("edit") { toggleEdit() }
        bar.addView(recordButton)
        bar.addView(editButton)
        bar.addView(ui.iconButton("settings") { dialogs.settings() })
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
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(76)).apply {
            setMargins(ui.dp(5), ui.dp(10), ui.dp(5), 0)
        })
        return root
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        val hidden = hiddenPages()
        layout.pages.forEachIndexed { i, p ->
            if (p.name in hidden) return@forEachIndexed
            val active = i == pageIndex
            tabs.addView(TextView(this).apply {
                text = p.name
                typeface = if (active) ui.fontBold else ui.fontSemiBold
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (active) Color.WHITE else MUTED)
                gravity = Gravity.CENTER
                setPadding(ui.dp(18), 0, ui.dp(18), 0)
                background = if (active) {
                    ui.roundRect(SURFACE_ACTIVE, ui.dp(20).toFloat()).apply { setStroke(ui.dp(1), STROKE_STRONG) }
                } else {
                    ui.pressable(Color.TRANSPARENT, ui.dp(20).toFloat())
                }
                setOnClickListener { selectPage(i); renderPage() }
                setOnLongClickListener { dialogs.renamePage(i); true }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(36)))
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
                .firstOrNull { (it as TextView).text == page.name } ?: return@post
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
        heldSteps.keys.toList().forEach { releaseKey(it) }

        content.removeAllViews()
        content.addView(if (page.kind == PageKind.TRACKPAD) buildTrackpad(page) else buildGrid(page))

        dockRow.removeAllViews()
        for (index in 0 until Layout.DOCK_SIZE) {
            dockRow.addView(keyView(layout.dock, index, compact = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(ui.dp(3), 0, ui.dp(3), 0)
                })
        }
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

    private fun keyView(page: Page, index: Int, compact: Boolean) = keyViews.build(
        page, index, layout.button(page, index), compact, editing,
        toggledOn = toggleKey(page, index) in toggledOn,
        emphasize = page === layout.dock && index == 1,
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
        releaseKey(view)
        // Toggle keys flip on press; redraw them once the finger lifts.
        if (button.toggle) keyViews.replace(view, keyView(page, index, compact = page === layout.dock))
    }

    override fun onEditKey(page: Page, index: Int) = editKey(page, index)

    /** [token] identifies the physical source (touched view or hardware key) for press/release pairing. */
    private fun pressKey(token: Any, page: Page, index: Int, button: MacroButton, view: View?) {
        val steps = try {
            Macro.parse(button.action)
        } catch (e: MacroException) {
            ui.toast("${button.label}: ${e.message}")
            return
        }
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        recorder.record(button.action)
        val hid = hid
        if (hid == null || !connected) {
            if (!recorder.isRecording) offlineBanner.animate().alpha(0.3f).setDuration(120)
                .withEndAction { offlineBanner.animate().alpha(1f).setDuration(120) }
            return
        }
        if (button.toggle) {
            val key = toggleKey(page, index)
            if (!toggledOn.remove(key)) toggledOn += key
        }
        when {
            Macro.isHoldable(steps) -> {
                heldSteps[token] = steps[0]
                hid.press(token, steps[0])
            }
            else -> hid.run(steps)
        }
    }

    private fun releaseKey(token: Any) {
        heldSteps.remove(token)?.let { hid?.release(token, it) }
    }

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

        fun bringToFront(context: Context) {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
}
