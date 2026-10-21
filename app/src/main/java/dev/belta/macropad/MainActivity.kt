package dev.belta.macropad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var hid: HidPeripheral
    private lateinit var store: MacroStore
    private lateinit var layout: Layout

    private lateinit var fontMedium: Typeface
    private lateinit var fontSemiBold: Typeface
    private lateinit var fontBold: Typeface

    private lateinit var tabs: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var statusChip: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var editButton: ImageView
    private lateinit var recordButton: ImageView
    private lateinit var recordBar: LinearLayout
    private lateinit var recordStatus: TextView
    private lateinit var dockRow: LinearLayout

    private var pageIndex = 0
    private var editing = false

    // Keys currently held, by touched view or hardware key code.
    private val heldSteps = HashMap<Any, Step>()

    // Non-null while recording: macro lines captured so far.
    private var recording: MutableList<String>? = null
    private var lastRecordedAt = 0L

    private val page get() = layout.pages[pageIndex.coerceIn(0, layout.pages.lastIndex)]

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // A macropad should be usable without unlocking the tablet first.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        fontMedium = resources.getFont(R.font.inter_medium)
        fontSemiBold = resources.getFont(R.font.inter_semibold)
        fontBold = resources.getFont(R.font.inter_bold)

        store = MacroStore(this)
        layout = store.load()
        pageIndex = getPreferences(MODE_PRIVATE).getInt("page", 0).coerceIn(0, layout.pages.lastIndex)

        setContentView(buildUi())
        // Re-hide the status bar if something (e.g. the notification shade) brought it back.
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { flags ->
            if (flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) window.decorView.postDelayed({ hideSystemUi() }, 2000)
        }
        renderPage()

        hid = HidPeripheral(applicationContext)
        hid.listener = HidPeripheral.Listener { state, detail -> showState(state, detail) }
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(pairingReceiver, IntentFilter(android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST))
        if (hasBluetoothPermissions()) hid.start() else requestBluetoothPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** `adb shell am start -n dev.belta.macropad/.MainActivity --ez reset_pairing true` */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("reset_pairing", false) == true) hid.resetPairing()
    }

    override fun onDestroy() {
        unregisterReceiver(bluetoothReceiver)
        unregisterReceiver(pairingReceiver)
        hid.listener = null
        hid.stop()
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

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> hid.start()
                BluetoothAdapter.STATE_TURNING_OFF -> hid.stop()
                BluetoothAdapter.STATE_OFF -> hid.onBluetoothOff()
            }
        }
    }

    private fun hasBluetoothPermissions() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        BT_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) requestPermissions(BT_PERMISSIONS, REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_PERMISSIONS && hasBluetoothPermissions()) hid.start()
    }

    @SuppressLint("MissingPermission")
    private fun requestBluetoothOn() {
        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    // ---------------------------------------------------------------- tablet volume buttons

    private fun hardwareSlot(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> MacroStore.HW_VOLUME_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> MacroStore.HW_VOLUME_DOWN
        else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val button = hardwareSlot(keyCode)?.let { layout.button(layout.hardware, it) }
            ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) pressButton(keyCode, button, null)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        hardwareSlot(keyCode)?.let { layout.button(layout.hardware, it) } ?: return super.onKeyUp(keyCode, event)
        releaseButton(keyCode)
        return true
    }

    // ---------------------------------------------------------------- UI construction

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }

        // Top bar: page tabs · status chip · action buttons
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundRect(SURFACE, dp(24).toFloat())
        }
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }
        bar.addView(tabScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        statusChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(16), 0)
            background = pressable(SURFACE, dp(22).toFloat())
            setOnClickListener { onStatusTapped() }
        }
        statusDot = View(this)
        statusChip.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusText = TextView(this).apply {
            setTextColor(TEXT)
            typeface = fontMedium
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(260)
        }
        statusChip.addView(statusText)
        bar.addView(statusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            marginStart = dp(10)
        })

        recordButton = iconButton("record") { if (recording == null) startRecording() else stopRecording() }
        editButton = iconButton("edit") { toggleEdit() }
        bar.addView(recordButton)
        bar.addView(editButton)
        bar.addView(iconButton("settings") { openSettings() })
        root.addView(bar)

        grid = GridLayout(this).apply {
            isMotionEventSplittingEnabled = true
            clipChildren = false
            clipToPadding = false
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
        })

        recordBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(6), dp(6), dp(6))
            background = roundRect(0xFF2A1219.toInt(), dp(24).toFloat()).apply {
                setStroke(dp(1), withAlpha(RED, 0.45f))
            }
        }
        recordStatus = TextView(this).apply {
            setTextColor(0xFFFF8FA3.toInt())
            typeface = fontSemiBold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        recordBar.addView(recordStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        recordBar.addView(chip("Type text", "keyboard") { recordText() })
        recordBar.addView(chip("Key combo", "command") { recordCombo() })
        recordBar.addView(chip("Undo", "undo") { undoRecordedStep() })
        recordBar.addView(chip("Discard", "delete") { recording = null; renderPage() })
        recordBar.addView(chip("Save", "check", RED) { stopRecording() })
        root.addView(recordBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(5), dp(8), dp(5), 0) })

        // Media dock, shared by every page
        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = roundRect(SURFACE, dp(26).toFloat()).apply { setStroke(dp(1), STROKE) }
            isMotionEventSplittingEnabled = true
        }
        root.addView(dockRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply {
            setMargins(dp(5), dp(10), dp(5), 0)
        })
        return root
    }

    private fun iconButton(icon: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(Icons.get(icon)!!)
        imageTintList = ColorStateList.valueOf(TEXT)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = pressable(SURFACE, dp(22).toFloat())
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }
    }

    private fun chip(text: String, icon: String, accent: Int? = null, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(14), 0)
        background = pressable(accent ?: SURFACE_HI, dp(18).toFloat())
        addView(ImageView(context).apply {
            setImageResource(Icons.get(icon)!!)
            imageTintList = ColorStateList.valueOf(TEXT)
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(TextView(context).apply {
            this.text = text
            setTextColor(TEXT)
            typeface = fontSemiBold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(6), 0, 0, 0)
        })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
            marginStart = dp(6)
        }
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        layout.pages.forEachIndexed { i, p ->
            val active = i == pageIndex
            tabs.addView(TextView(this).apply {
                text = p.name
                typeface = if (active) fontBold else fontSemiBold
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (active) Color.WHITE else MUTED)
                gravity = Gravity.CENTER
                setPadding(dp(18), 0, dp(18), 0)
                background = if (active) {
                    roundRect(SURFACE_ACTIVE, dp(20).toFloat()).apply { setStroke(dp(1), STROKE_STRONG) }
                } else {
                    pressable(Color.TRANSPARENT, dp(20).toFloat())
                }
                setOnClickListener { switchPage(i - pageIndex) }
                setOnLongClickListener { promptRenamePage(i); true }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        }
    }

    private fun renderPage() {
        renderTabs()
        tintIconButton(editButton, if (editing) "check" else "edit", if (editing) Palette.colors[1] else null)
        tintIconButton(recordButton, if (recording == null) "record" else "stop", if (recording != null) RED else null)
        recordButton.imageTintList = ColorStateList.valueOf(if (recording == null) RED else Color.WHITE)
        recordBar.visibility = if (recording == null) View.GONE else View.VISIBLE
        updateRecordStatus()

        heldSteps.keys.toList().forEach { releaseButton(it) }
        grid.removeAllViews()
        grid.rowCount = layout.rows
        grid.columnCount = layout.cols
        for (index in 0 until layout.cells) {
            val cell = buildKey(page, index, layout.button(page, index), compact = false)
            grid.addView(cell, GridLayout.LayoutParams(
                GridLayout.spec(index / layout.cols, 1f), GridLayout.spec(index % layout.cols, 1f),
            ).apply {
                width = 0
                height = 0
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }

        dockRow.removeAllViews()
        for (index in 0 until DOCK_SIZE) {
            dockRow.addView(buildKey(layout.dock, index, layout.button(layout.dock, index), compact = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                })
        }
    }

    private fun tintIconButton(view: ImageView, icon: String, fill: Int?) {
        view.setImageResource(Icons.get(icon)!!)
        view.imageTintList = ColorStateList.valueOf(TEXT)
        view.background = pressable(fill ?: SURFACE, dp(22).toFloat())
    }

    /**
     * A key: tinted icon badge, label and a muted shortcut hint on a dark surface washed with
     * the key's accent color. [compact] keys (the dock) lay the icon and label side by side.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildKey(target: Page, index: Int, button: MacroButton?, compact: Boolean): View {
        val key = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isHapticFeedbackEnabled = true
        }
        val radius = dp(if (compact) 20 else 22).toFloat()

        if (button == null) {
            if (editing) {
                key.background = GradientDrawable().apply {
                    cornerRadius = radius
                    setStroke(dp(2), STROKE_STRONG, dp(8).toFloat(), dp(6).toFloat())
                }
                key.addView(icon("add", MUTED), LinearLayout.LayoutParams(dp(28), dp(28)))
            }
        } else {
            val accent = button.color
            key.background = keyBackground(accent, radius, highlight = compact && index == 1)
            val iconRes = Icons.get(button.icon)
            if (compact) {
                if (iconRes != null) key.addView(icon(button.icon!!, if (index == 1) Color.WHITE else accent),
                    LinearLayout.LayoutParams(dp(26), dp(26)))
                key.addView(label(button.label, 14f, 15f).apply {
                    maxLines = 1
                    setPadding(if (iconRes != null) dp(10) else 0, 0, 0, 0)
                })
            } else {
                if (iconRes != null) {
                    val badge = FrameLayout(this).apply {
                        background = roundRect(withAlpha(accent, 0.18f), dp(14).toFloat())
                        addView(icon(button.icon!!, accent), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                    }
                    key.addView(badge, LinearLayout.LayoutParams(dp(46), dp(46)).apply { bottomMargin = dp(8) })
                }
                key.addView(label(button.label, 13f, if (iconRes != null) 18f else 24f))
                key.addView(TextView(this).apply {
                    text = Macro.describe(button.action)
                    typeface = fontMedium
                    setTextColor(MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                })
            }
            if (editing) key.foreground = editBadge(radius)
        }

        key.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (button != null || editing) {
                        v.isPressed = true
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).setInterpolator(null).start()
                    }
                    if (!editing && button != null) pressButton(v, button, v)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(3f)).start()
                    if (ev.actionMasked == MotionEvent.ACTION_UP && editing) openEditor(target, index)
                    else releaseButton(v)
                }
            }
            true
        }
        return key
    }

    private fun label(text: String, minSp: Float, maxSp: Float) = TextView(this).apply {
        this.text = text
        typeface = fontSemiBold
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        maxLines = 2
        setAutoSizeTextTypeUniformWithConfiguration(minSp.toInt(), maxSp.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        layoutParams = LinearLayout.LayoutParams(
            if (maxSp > 15f) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(if (maxSp > 15f) (maxSp * 2.6f).toInt() else 24),
        )
    }

    private fun icon(name: String, tint: Int) = ImageView(this).apply {
        Icons.get(name)?.let { setImageResource(it) }
        imageTintList = ColorStateList.valueOf(tint)
    }

    private fun keyBackground(accent: Int, radius: Float, highlight: Boolean): Drawable {
        fun face(pressed: Boolean) = if (highlight) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(if (pressed) lighten(accent, 0.25f) else accent, darken(accent, if (pressed) 0.15f else 0.3f)))
                .apply { cornerRadius = radius }
        } else {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                blend(accent, SURFACE_HI, if (pressed) 0.38f else 0.14f),
                blend(accent, SURFACE, if (pressed) 0.22f else 0.04f),
            )).apply {
                cornerRadius = radius
                setStroke(dp(1), withAlpha(accent, if (pressed) 0.8f else 0.22f))
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(true))
            addState(intArrayOf(), face(false))
        }
    }

    /** Small pencil in the corner of every key while editing. */
    private fun editBadge(radius: Float): Drawable {
        val pencil = getDrawable(Icons.get("edit")!!)!!.mutate().apply { setTint(TEXT) }
        val dot = roundRect(withAlpha(Color.BLACK, 0.55f), dp(13).toFloat())
        return android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply { cornerRadius = radius; setStroke(dp(2), withAlpha(Color.WHITE, 0.35f)) },
            dot, pencil,
        )).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.END)
            setLayerSize(1, dp(26), dp(26))
            setLayerInset(1, 0, dp(8), dp(8), 0)
            setLayerGravity(2, Gravity.TOP or Gravity.END)
            setLayerSize(2, dp(14), dp(14))
            setLayerInset(2, 0, dp(14), dp(14), 0)
        }
    }

    // ---------------------------------------------------------------- pressing

    /** [token] identifies the physical source (touched view or hardware key) for press/release pairing. */
    private fun pressButton(token: Any, button: MacroButton, view: View?) {
        val steps = try {
            Macro.parse(button.action)
        } catch (e: MacroException) {
            toast("${button.label}: ${e.message}")
            return
        }
        view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        record(button.action)
        if (hid.state != HidPeripheral.State.CONNECTED) {
            if (recording == null) toast("Not connected to a computer yet")
            return
        }
        if (Macro.isHoldable(steps)) {
            heldSteps[token] = steps[0]
            hid.press(token, steps[0])
        } else {
            hid.run(steps)
        }
    }

    private fun releaseButton(token: Any) {
        heldSteps.remove(token)?.let { hid.release(token, it) }
    }

    // ---------------------------------------------------------------- status

    private fun showState(state: HidPeripheral.State, detail: String) {
        val color = when (state) {
            HidPeripheral.State.CONNECTED -> Palette.colors[1]
            HidPeripheral.State.ADVERTISING -> Palette.colors[0]
            HidPeripheral.State.STARTING -> Palette.colors[2]
            HidPeripheral.State.OFF, HidPeripheral.State.UNAVAILABLE -> RED
        }
        statusDot.background = roundRect(color, dp(5).toFloat())
        statusText.text = when (state) {
            HidPeripheral.State.ADVERTISING -> "Ready to pair"
            HidPeripheral.State.CONNECTED -> detail.removePrefix("Connected to ")
            else -> detail
        }
        statusChip.tag = detail
    }

    private fun onStatusTapped() {
        val detail = statusChip.tag as? String ?: ""
        when (hid.state) {
            HidPeripheral.State.OFF -> requestBluetoothOn()
            HidPeripheral.State.CONNECTED -> AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Connected")
                .setMessage(detail)
                .setPositiveButton("OK", null)
                .setNeutralButton("Disconnect") { _, _ -> hid.restart() }
                .show()
            else -> AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Pairing")
                .setMessage(
                    "$detail\n\n" +
                        "On your computer open Bluetooth settings → Add device, and pick " +
                        "\"${HidPeripheral.DEVICE_NAME}\". Accept the pairing prompt on this tablet if asked.\n\n" +
                        "The macropad shows up as a Bluetooth LE keyboard; no software is needed on the computer. " +
                        "Typed text assumes a US keyboard layout on the computer."
                )
                .setPositiveButton("OK", null)
                .setNeutralButton("Restart Bluetooth") { _, _ -> hid.restart() }
                .show()
        }
    }

    /**
     * Fire OS usually puts the "confirm pairing code" prompt in a notification while another app is
     * in front, which is easy to miss — and the computer gives up if the tablet never confirms.
     * Show the code ourselves and pull the notification shade down so its Pair button is visible.
     */
    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(
                android.bluetooth.BluetoothDevice.EXTRA_DEVICE) ?: return
            val code = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_PAIRING_KEY, -1)
            @SuppressLint("MissingPermission") val name = device.name ?: device.address
            AlertDialog.Builder(this@MainActivity, DIALOG_THEME)
                .setTitle("Pairing with $name")
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

    // ---------------------------------------------------------------- pages

    private fun switchPage(delta: Int) {
        val n = layout.pages.size
        pageIndex = ((pageIndex + delta) % n + n) % n
        getPreferences(MODE_PRIVATE).edit().putInt("page", pageIndex).apply()
        renderPage()
    }

    private fun promptRenamePage(i: Int) {
        val target = layout.pages[i]
        promptText("Rename page", target.name) { name -> target.name = name; saveAndRender() }
    }

    private fun toggleEdit() {
        editing = !editing
        renderPage()
        if (editing) toast("Tap a key to edit it · long-press a tab to rename it")
    }

    // ---------------------------------------------------------------- editors

    private fun openEditor(target: Page, index: Int, prefill: MacroButton? = null, title: String? = null) {
        val existing = layout.button(target, index)
        val initial = prefill ?: existing
        var color = initial?.color ?: Palette.colors[0]
        var iconName: String? = initial?.icon

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val label = EditText(this).apply {
            hint = "Label"
            setText(initial?.label ?: "")
            setSingleLine()
        }
        val action = EditText(this).apply {
            hint = "Action, e.g. ctrl+shift+t"
            setText(initial?.action ?: "")
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }
        content.addView(label)
        content.addView(action)

        content.addView(sectionTitle("Color"))
        val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(swatches)

        content.addView(sectionTitle("Icon"))
        val icons = GridLayout(this).apply { columnCount = 10 }
        content.addView(icons)

        fun paint() {
            for (i in 0 until swatches.childCount) {
                val c = Palette.colors[i]
                swatches.getChildAt(i).background = roundRect(c, dp(18).toFloat()).apply {
                    if (c == color) setStroke(dp(3), Color.WHITE)
                }
            }
            for (i in 0 until icons.childCount) {
                val v = icons.getChildAt(i) as ImageView
                val selected = v.tag == iconName
                v.background = roundRect(if (selected) withAlpha(color, 0.3f) else SURFACE_HI, dp(10).toFloat()).apply {
                    if (selected) setStroke(dp(2), color)
                }
                v.imageTintList = ColorStateList.valueOf(if (selected) color else TEXT)
            }
        }
        for (c in Palette.colors) {
            swatches.addView(View(this).apply {
                setOnClickListener { color = c; paint() }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        }
        for (name in listOf<String?>(null) + Icons.all.keys) {
            icons.addView(ImageView(this).apply {
                tag = name
                setImageResource(Icons.get(name) ?: R.drawable.ic_none)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = name ?: "No icon"
                setOnClickListener { iconName = name; paint() }
            }, GridLayout.LayoutParams().apply {
                width = dp(40); height = dp(40)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        paint()

        content.addView(sectionTitle("Action syntax"))
        content.addView(TextView(this).apply {
            text = HELP
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
        })

        val dialog = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(title ?: when {
                prefill != null -> "Save recording"
                existing == null -> "New key"
                else -> "Edit key"
            })
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply {
                if (existing != null) setNeutralButton("Delete") { _, _ ->
                    layout.setButton(target, index, null)
                    saveAndRender()
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val actionText = action.text.toString().trim()
                try {
                    Macro.parse(actionText)
                } catch (e: MacroException) {
                    action.error = e.message
                    return@setOnClickListener
                }
                val labelText = label.text.toString().trim().ifEmpty { actionText.lines().first() }
                layout.setButton(target, index, MacroButton(labelText, actionText, color, iconName))
                saveAndRender()
                dialog.dismiss()
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        typeface = fontSemiBold
        letterSpacing = 0.08f
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun editTabletButtons() {
        val names = listOf("Volume up button", "Volume down button")
        val items = names.mapIndexed { i, n ->
            "$n  →  ${layout.button(layout.hardware, i)?.let { "${it.label} (${Macro.describe(it.action)})" } ?: "tablet volume"}"
        }.toTypedArray()
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Tablet volume buttons")
            .setItems(items) { _, i -> openEditor(layout.hardware, i, title = names[i]) }
            .setPositiveButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------- recording

    private fun startRecording() {
        if (editing) editing = false
        recording = mutableListOf()
        lastRecordedAt = 0L
        renderPage()
        toast("Recording — keys you press are sent live and captured")
    }

    /** Appends an action to the recording, keeping the pause since the previous one. */
    private fun record(action: String) {
        val steps = recording ?: return
        val now = System.currentTimeMillis()
        if (steps.isNotEmpty()) {
            val gap = ((now - lastRecordedAt).coerceAtMost(MAX_RECORDED_DELAY) / 50) * 50
            if (gap >= MIN_RECORDED_DELAY) steps += "delay:$gap"
        }
        steps += action.lines().map { it.trim() }.filter { it.isNotEmpty() }
        lastRecordedAt = now
        updateRecordStatus()
    }

    private fun updateRecordStatus() {
        val steps = recording ?: return
        val actions = steps.count { !it.startsWith("delay:") }
        recordStatus.text = "●  Recording · $actions action${if (actions == 1) "" else "s"}" +
            (steps.lastOrNull { !it.startsWith("delay:") }?.let { "  ·  last: ${Macro.describe(it)}" } ?: "")
    }

    private fun recordText() {
        val input = EditText(this).apply {
            hint = "Text to type on the computer"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val pressEnter = CheckBox(this).apply { text = "Press Enter afterwards" }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(pressEnter)
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Type text")
            .setView(content)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString() + if (pressEnter.isChecked) "\n" else ""
                if (text.isEmpty()) return@setPositiveButton
                val action = "text:" + text.replace("\n", "\\n").replace("\t", "\\t")
                record(action)
                if (hid.state == HidPeripheral.State.CONNECTED) hid.run(Macro.parse(action))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun recordCombo() {
        val input = EditText(this).apply {
            hint = "e.g. win+r, ctrl+shift+esc, alt+f4"
            setSingleLine()
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val quick = GridLayout(this).apply { columnCount = 4 }
        val dialog = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Key combo")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
                addView(quick)
            })
            .setPositiveButton("Send", null)
            .setNegativeButton("Close", null)
            .create()
        fun send(spec: String): Boolean {
            val steps = try { Macro.parse(spec) } catch (e: MacroException) { input.error = e.message; return false }
            record(spec)
            if (hid.state == HidPeripheral.State.CONNECTED) hid.run(steps)
            return true
        }
        // Tapping a quick key sends it immediately and keeps the dialog open for the next one.
        for (key in QUICK_KEYS) {
            quick.addView(TextView(this).apply {
                text = Macro.describe(key)
                typeface = fontSemiBold
                setTextColor(TEXT)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                background = pressable(SURFACE_HI, dp(10).toFloat())
                setOnClickListener { send(key) }
            }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                .apply { width = 0; setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val spec = input.text.toString().trim().lowercase()
                if (spec.isNotEmpty() && send(spec)) input.setText("")
            }
        }
        dialog.show()
    }

    private fun undoRecordedStep() {
        val steps = recording ?: return
        if (steps.isNotEmpty()) steps.removeAt(steps.lastIndex)
        while (steps.lastOrNull()?.startsWith("delay:") == true) steps.removeAt(steps.lastIndex)
        updateRecordStatus()
    }

    private fun stopRecording() {
        val steps = recording ?: return
        recording = null
        if (steps.none { !it.startsWith("delay:") }) {
            toast("Nothing recorded")
            renderPage()
            return
        }
        // Recordings go to the first free slot of the Custom page.
        val custom = layout.pages.firstOrNull { it.name == MacroStore.CUSTOM_PAGE }
            ?: Page(MacroStore.CUSTOM_PAGE, mutableListOf()).also { layout.pages += it }
        pageIndex = layout.pages.indexOf(custom)
        val slot = (0 until layout.cells).firstOrNull { layout.button(custom, it) == null }
        renderPage()
        if (slot == null) {
            toast("Custom page is full — make the grid bigger or delete a key")
            return
        }
        val count = custom.buttons.count { it != null } + 1
        openEditor(custom, slot, MacroButton("Recording $count", steps.joinToString("\n"), Palette.colors[4], "bolt"))
    }

    // ---------------------------------------------------------------- settings

    private fun openSettings() {
        val options = arrayOf(
            "Grid size (${layout.rows} × ${layout.cols})", "Tablet volume buttons…", "Add page",
            "Rename this page", "Delete this page", "Bluetooth / pairing", "Restart Bluetooth",
            "Reset pairing (forget computers)", "Reset all keys to defaults",
        )
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> editGridSize()
                    1 -> editTabletButtons()
                    2 -> promptText("New page", "Page ${layout.pages.size + 1}") { name ->
                        layout.pages += Page(name, mutableListOf())
                        pageIndex = layout.pages.lastIndex
                        saveAndRender()
                    }
                    3 -> promptRenamePage(pageIndex)
                    4 -> deletePage()
                    5 -> onStatusTapped()
                    6 -> hid.restart()
                    7 -> confirm("Reset pairing?",
                        "The tablet forgets the computers it was paired with and restarts Bluetooth. " +
                            "Then remove \"${HidPeripheral.DEVICE_NAME}\" on each computer and add it again.") {
                        hid.resetPairing()
                    }
                    8 -> confirm("Reset everything?", "All pages and keys will be replaced with the defaults.") {
                        layout = MacroStore.defaults()
                        pageIndex = 0
                        saveAndRender()
                    }
                }
            }
            .show()
    }

    private fun editGridSize() {
        fun picker(value: Int) = NumberPicker(this).apply {
            minValue = 1; maxValue = 8; this.value = value
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val rows = picker(layout.rows)
        val cols = picker(layout.cols)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(rows)
            addView(TextView(context).apply { text = "  rows   ×   columns  "; setTextColor(Color.WHITE) })
            addView(cols)
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Grid size")
            .setView(FrameLayout(this).apply { setPadding(0, dp(12), 0, 0); addView(content) })
            .setPositiveButton("OK") { _, _ ->
                layout.rows = rows.value
                layout.cols = cols.value
                saveAndRender()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePage() {
        if (layout.pages.size == 1) return toast("Can't delete the only page")
        confirm("Delete \"${page.name}\"?", "Its keys will be removed.") {
            layout.pages.removeAt(pageIndex)
            pageIndex = pageIndex.coerceAtMost(layout.pages.lastIndex)
            saveAndRender()
        }
    }

    private fun promptText(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); setSingleLine(); selectAll() }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(title)
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(title: String, message: String, onOk: () -> Unit) {
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onOk() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAndRender() {
        store.save(layout)
        getPreferences(MODE_PRIVATE).edit().putInt("page", pageIndex).apply()
        renderPage()
    }

    // ---------------------------------------------------------------- helpers

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun pressable(color: Int, radius: Float) = StateListDrawable().apply {
        val pressed = if (color == Color.TRANSPARENT) withAlpha(Color.WHITE, 0.08f) else lighten(color, 0.12f)
        addState(intArrayOf(android.R.attr.state_pressed), roundRect(pressed, radius))
        addState(intArrayOf(), roundRect(color, radius))
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private val BT_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        private val DIALOG_THEME = R.style.Theme_Macropad_Dialog

        private const val BG_TOP = 0xFF10131A.toInt()
        private const val BG_BOTTOM = 0xFF07080B.toInt()
        private const val SURFACE = 0xFF161A22.toInt()
        private const val SURFACE_HI = 0xFF1E232E.toInt()
        private const val SURFACE_ACTIVE = 0xFF2A3040.toInt()
        private const val STROKE = 0x14FFFFFF
        private const val STROKE_STRONG = 0x33FFFFFF
        private const val TEXT = 0xFFF2F4F8.toInt()
        private const val MUTED = 0xFF8A93A6.toInt()
        private val RED = Palette.colors[3]

        private const val DOCK_SIZE = 5
        private const val MIN_RECORDED_DELAY = 300L
        private const val MAX_RECORDED_DELAY = 2000L
        private val QUICK_KEYS = listOf(
            "enter", "esc", "tab", "backspace", "up", "down", "left", "right",
            "win", "win+r", "win+e", "win+d", "alt+tab", "alt+f4", "ctrl+c", "ctrl+v",
        )

        fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

        fun blend(top: Int, bottom: Int, amount: Float): Int {
            fun ch(a: Int, b: Int) = (b + (a - b) * amount).toInt()
            return Color.rgb(ch(Color.red(top), Color.red(bottom)), ch(Color.green(top), Color.green(bottom)),
                ch(Color.blue(top), Color.blue(bottom)))
        }

        fun lighten(color: Int, amount: Float) = blend(Color.WHITE, color, amount)
        fun darken(color: Int, amount: Float) = blend(Color.BLACK, color, amount)

        private const val HELP = """One step per line:
  ctrl+shift+t     key combo
  media:volup      media key
  text:Hi there\n  type text (\n = Enter)
  delay:200        wait (ms)

Modifiers: ctrl shift alt super/win/cmd
           rctrl rshift ralt rgui
Keys: a-z 0-9 f1-f24 enter esc tab space
  backspace delete insert home end
  pageup pagedown up down left right
  printscreen menu capslock plus minus
  equal comma period slash backslash
  semicolon quote grave kp0-kp9 …
Media: playpause next prev stop mute
  volup voldown brightup brightdown
  calc browser mail files search

A single combo/media key is held while
touched; multi-step macros run on tap.
The bottom row (media keys) is shared
by all pages."""
    }
}
