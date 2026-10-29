package dev.belta.macropad.ui

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import dev.belta.macropad.BuildConfig
import dev.belta.macropad.MacropadService
import dev.belta.macropad.hid.DeviceQuirks
import dev.belta.macropad.hid.HidPeripheral
import dev.belta.macropad.hid.KeyTiming
import dev.belta.macropad.hid.KeyboardLayout
import dev.belta.macropad.model.AppSettings
import dev.belta.macropad.model.ComputerProfile
import dev.belta.macropad.model.DefaultLayout
import dev.belta.macropad.model.Layout
import dev.belta.macropad.model.Macro
import dev.belta.macropad.model.MacroStore
import dev.belta.macropad.model.Page
import dev.belta.macropad.model.PageKind
import dev.belta.macropad.model.SecretStore
import dev.belta.macropad.model.TextVariables
import dev.belta.macropad.net.Updater
import dev.belta.macropad.ui.Ui.Companion.GREEN
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.RED
import dev.belta.macropad.ui.Ui.Companion.HAIRLINE_STRONG
import dev.belta.macropad.ui.Ui.Companion.RAISED

/** What the dialogs need from the activity. */
interface MacropadHost {
    val ui: Ui
    var layout: Layout
    val settings: AppSettings
    val store: MacroStore
    val secrets: SecretStore
    val service: MacropadService?
    val pageIndex: Int
    fun saveAndRender()
    fun selectPage(index: Int)
    fun editKey(page: Page, index: Int, title: String? = null)
    fun exportLayout()
    fun importLayout()
    fun quit()
    /** Number of toggle keys currently shown as ON. */
    val toggledKeys: Int
    fun resetToggles()
    /** Moves the sidebar to the side in [AppSettings.sidebarLeft]. */
    fun placeSidebar()
    /** A computer's name or settings changed. */
    fun profilesChanged()
    /** [AppSettings.screenShift] changed. */
    fun screenShiftChanged()
    /** Runs [action] once the PIN (if any) has been entered. */
    fun withUnlock(action: () -> Unit)
}

/** Settings menu and the dialogs behind it. */
class Dialogs(private val host: MacropadHost) {
    private val ui get() = host.ui
    private val hid: HidPeripheral? get() = host.service?.hid

    fun settings() {
        val items = listOfNotNull(
            if (host.toggledKeys > 0) "Reset ON/OFF keys (${host.toggledKeys} on)" to host::resetToggles else null,
            "Grid size (${host.layout.rows} × ${host.layout.cols})" to ::gridSize,
            "Sidebar: ${if (host.settings.sidebarLeft) "left" else "right"}" to {
                host.settings.sidebarLeft = !host.settings.sidebarLeft
                host.placeSidebar()
            },
            "Default keyboard layout: ${host.settings.keyboardLayout.label}" to ::keyboardLayout,
            "Default key timing: ${host.settings.keyTiming.label}" to ::keyTiming,
            "Computers…" to ::computers,
            "Connection…" to ::connection,
            "Tablet volume buttons…" to ::tabletButtons,
            "Saved values for logins…" to ::savedValues,
            "Pages…" to ::pages,
            "Backup and restore…" to ::backup,
            "Startup…" to ::startup,
            "Dim when idle: ${dimLabel(host.settings.idleDimMinutes)}" to ::idleDim,
            "Screen protection: ${if (host.settings.screenShift) "on" else "off"}" to {
                host.settings.screenShift = !host.settings.screenShift
                host.screenShiftChanged()
                ui.toast(if (host.settings.screenShift) "The screen moves a few pixels now and then (OLED burn-in)"
                    else "Screen protection off")
            },
            "PIN lock: ${if (host.settings.pinHash == null) "off" else "on"}" to ::pinLock,
            "Updates: ${BuildConfig.VERSION_NAME}…" to ::updates,
            "Diagnostics…" to ::diagnostics,
            "Quit macropad" to { host.quit() },
        )
        ui.dialog()
            .setTitle("Settings")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    // ---------------------------------------------------------------- connection

    fun connectionInfo() {
        val hid = hid ?: return
        when (hid.state) {
            HidPeripheral.State.CONNECTED -> ui.dialog()
                .setTitle("Connected")
                .setMessage(hid.diagnostics().detail)
                .setPositiveButton("OK", null)
                .setNegativeButton("Switch computer…") { _, _ -> host.withUnlock { computers() } }
                .setNeutralButton("Diagnostics") { _, _ -> host.withUnlock { diagnostics() } }
                .show()
            else -> ui.dialog()
                .setTitle("Pairing")
                .setMessage(
                    "${hid.diagnostics().detail}\n\n" +
                        "On your computer open Bluetooth settings → Add device, and pick " +
                        "\"${hid.deviceName}\". Confirm the code on both screens.\n\n" +
                        "The macropad shows up as a Bluetooth LE keyboard and mouse; no software is needed " +
                        "on the computer. Don't open Bluetooth settings on this tablet while pairing."
                )
                .setPositiveButton("OK", null)
                .setNegativeButton("Diagnostics") { _, _ -> host.withUnlock { diagnostics() } }
                .apply {
                    // Over Classic Bluetooth the computer only finds the tablet while it is visible.
                    if (hid.activeTransport == HidPeripheral.Transport.CLASSIC) setNeutralButton("Make visible") { _, _ -> makeVisible() }
                    else setNeutralButton("Restart Bluetooth") { _, _ -> hid.restart() }
                }
                .show()
        }
    }

    /** Lets computers find the tablet over Classic Bluetooth for two minutes (Android asks first). */
    private fun makeVisible() = startSystemScreen(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))

    private fun startSystemScreen(intent: Intent) {
        try {
            ui.context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            ui.toast("This tablet has no screen for that")
        }
    }

    // ---------------------------------------------------------------- connection settings

    private fun connection() {
        val hid = hid
        val settings = host.settings
        val inUse = hid?.activeTransport?.takeIf { settings.transport == HidPeripheral.Transport.AUTO }
        val items = listOfNotNull(
            "Bluetooth: ${settings.transport.label}${inUse?.let { " (${it.label})" } ?: ""}" to ::transport,
            "Bluetooth name: ${settings.bluetoothName}" to {
                promptText("Bluetooth name", settings.bluetoothName, allowEmpty = true,
                    hint = "Empty = ${DeviceQuirks.defaultDeviceName}") { name ->
                    settings.bluetoothName = name
                    host.service?.applyConnectionSettings()
                    ui.toast("Computers show \"${settings.bluetoothName}\" when pairing")
                }
            },
            if (hid?.activeTransport == HidPeripheral.Transport.CLASSIC) "Make visible for pairing" to ::makeVisible else null,
            "Run in the background: ${if (batteryUnrestricted()) "allowed" else "may be stopped"}" to {
                if (batteryUnrestricted()) ui.toast("Already allowed")
                else startSystemScreen(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ui.context.packageName}")))
            },
            "Follow the computer's app: ${if (settings.followPcApp) "on" else "off"}" to {
                settings.followPcApp = !settings.followPcApp
                ui.toast(if (settings.followPcApp) "Pages follow the app in front on the computer (PC link)"
                    else "Pages no longer follow the computer's app")
            },
        )
        ui.dialog()
            .setTitle("Connection")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun transport() {
        val options = HidPeripheral.Transport.values()
        val labels = options.map {
            when (it) {
                HidPeripheral.Transport.AUTO -> "Automatic — Classic where this tablet has it"
                HidPeripheral.Transport.BLE -> "Bluetooth LE — any tablet"
                HidPeripheral.Transport.CLASSIC -> "Classic Bluetooth — reconnects by itself"
            }
        }
        ui.dialog()
            .setTitle("Bluetooth")
            .setMessage("Changing this makes the macropad a different keyboard: pair it with the computers once more.")
            .setSingleChoiceItems(labels.toTypedArray(), options.indexOf(host.settings.transport)) { d, i ->
                d.dismiss()
                if (options[i] == host.settings.transport) return@setSingleChoiceItems
                host.settings.transport = options[i]
                host.service?.applyConnectionSettings()
                ui.toast("Using ${options[i].label}; pair with the computer again")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Whether the system leaves the macropad running in the background (no battery optimisation). */
    private fun batteryUnrestricted() =
        ui.context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ui.context.packageName) != false

    /** Known computers; tap one for its settings. */
    fun computers() {
        val hid = hid ?: return
        val hosts = hid.knownHosts()
        if (hosts.isEmpty()) {
            ui.toast("No computers yet — pair one first")
            return
        }
        val current = hid.currentHost()?.address
        val preferred = hid.preferredHost()?.address
        val labels = hosts.map { h ->
            val status = when {
                h.address == current -> "connected"
                preferred == null || h.address == preferred -> "waiting"
                else -> "ignored"
            }
            "${h.name}  ·  $status"
        }
        ui.dialog()
            .setTitle(if (preferred == null) "Computers (any can connect)" else "Computers")
            .setItems(labels.toTypedArray()) { _, i -> computer(hosts[i]) }
            .setNegativeButton("Close", null)
            .apply {
                if (preferred != null) setNeutralButton("Allow any computer") { _, _ -> hid.setPreferredHost(null) }
            }
            .show()
    }

    private fun computer(computer: HidPeripheral.Host) {
        val hid = hid ?: return
        val profiles = host.service?.profiles ?: return
        val address = computer.address
        val profile = profiles[address]
        val defaults = host.settings
        val pagesShown = host.layout.pages.count { it.name !in profile.hiddenPages }
        val items = listOfNotNull(
            if (hid.preferredHost()?.address != address) "Use the macropad with this computer" to {
                hid.switchTo(address)
                ui.toast("Switching to ${computer.name}…")
            } else null,
            "Rename…" to { renameComputer(computer) },
            "Keyboard layout: ${profile.keyboardLayout?.label ?: "default (${defaults.keyboardLayout.label})"}" to {
                choose("Keyboard layout for ${computer.name}", KeyboardLayout.values().toList(), profile.keyboardLayout,
                    "Default (${defaults.keyboardLayout.label})", { it.label }) { choice ->
                    updateProfile(address) { it.keyboardLayout = choice }
                }
            },
            "Key timing: ${profile.keyTiming?.label ?: "default (${defaults.keyTiming.label})"}" to {
                choose("Key timing for ${computer.name}", KeyTiming.values().toList(), profile.keyTiming,
                    "Default (${defaults.keyTiming.label})", { "${it.label} — held ${it.holdMs} ms" }) { choice ->
                    updateProfile(address) { it.keyTiming = choice }
                }
            },
            "Pages shown: $pagesShown of ${host.layout.pages.size}" to { computerPages(computer) },
            "Start page: ${profile.startPage ?: "last used"}" to {
                val pages = host.layout.pages.map { it.name }
                choose("Start page for ${computer.name}", pages, profile.startPage, "Last used", { it }) { choice ->
                    updateProfile(address) { it.startPage = choice }
                }
            },
            "Forget this computer…" to {
                confirm("Forget ${computer.name}?",
                    "The tablet unpairs it. Remove \"${hid.deviceName}\" on that computer too.") {
                    hid.forgetHost(address)
                    profiles.remove(address)
                    host.profilesChanged()
                }
            },
        )
        ui.dialog()
            .setTitle(computer.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Back") { _, _ -> computers() }
            .show()
    }

    private fun renameComputer(computer: HidPeripheral.Host) {
        val bluetoothName = hid?.bluetoothName(computer.address) ?: computer.address
        promptText("Rename computer", computer.name, allowEmpty = true,
            hint = "Empty = Bluetooth name ($bluetoothName)") { name ->
            updateProfile(computer.address) { it.name = name.ifBlank { null } }
        }
    }

    private fun computerPages(computer: HidPeripheral.Host) {
        val pages = host.layout.pages.map { it.name }
        val hidden = host.service?.profiles?.get(computer.address)?.hiddenPages ?: return
        val shown = BooleanArray(pages.size) { pages[it] !in hidden }
        ui.dialog()
            .setTitle("Pages for ${computer.name}")
            .setMultiChoiceItems(pages.toTypedArray(), shown) { _, i, checked -> shown[i] = checked }
            .setPositiveButton("Save") { _, _ ->
                if (shown.none { it }) return@setPositiveButton ui.toast("Keep at least one page")
                updateProfile(computer.address) { p ->
                    p.hiddenPages.clear()
                    pages.filterIndexedTo(p.hiddenPages) { i, _ -> !shown[i] }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateProfile(address: String, change: (ComputerProfile) -> Unit) {
        host.service?.profiles?.update(address, change) ?: return
        host.profilesChanged()
    }

    /** Single choice among [options] plus a first "use the default" entry ([current] null). */
    private fun <T> choose(
        title: String, options: List<T>, current: T?, defaultLabel: String, label: (T) -> String, onPick: (T?) -> Unit,
    ) {
        val labels = listOf(defaultLabel) + options.map(label)
        ui.dialog()
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current?.let { options.indexOf(it) + 1 } ?: 0) { d, i ->
                onPick(if (i == 0) null else options[i - 1])
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun diagnostics() {
        val hid = hid ?: return
        val d = hid.diagnostics()
        val text = SpannableStringBuilder()
        fun line(label: String, value: String, ok: Boolean? = null) {
            text.append("$label: ")
            val start = text.length
            text.append(value)
            ok?.let {
                text.append(if (it) "  ✓" else "  ✗")
                text.setSpan(ForegroundColorSpan(if (it) GREEN else RED), start, text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text.append("\n")
        }
        line("State", d.detail)
        line("Computer", d.host?.let { "${it.name} (${it.address})" } ?: "none")
        d.bonded?.let { line("Paired with it", if (it) "yes" else "no", it) }
        line("Use with", d.preferredHost?.name ?: "any computer")
        d.classicConnectable?.let { line("Classic Bluetooth", if (it) "connectable" else "hidden", !it) }
        line("Secure-settings permission", if (d.secureSettingsGranted) "granted" else
            "missing — adb shell pm grant ${ui.context.packageName} android.permission.WRITE_SECURE_SETTINGS",
            d.secureSettingsGranted)
        d.audioProfilesBlocked?.let { line("Audio profiles blocked", if (it) "yes" else "no", it) }
        line("Keyboard layout", hid.keyboardLayout.label)
        line("Key timing", hid.keyTiming.label)
        line("Connection", d.transport?.label ?: "not running")
        val pc = host.service?.pc?.status
        line("PC link", when {
            pc != null -> "${pc.host ?: "connected"} (Teams ${if (pc.teams != null) "✓" else "—"}, Outlook ${if (pc.hasOutlook) "✓" else "—"}" +
                (pc.app?.let { ", in front: $it" } ?: "") + ")"
            d.transport == HidPeripheral.Transport.CLASSIC -> "waiting for tools/macropad-link.ps1"
            else -> "needs Classic Bluetooth"
        }, pc?.let { true })
        line("Run in the background", if (batteryUnrestricted()) "allowed" else "may be stopped — ⚙ → Connection",
            batteryUnrestricted())
        text.append("\nRecent events\n")
        val eventsStart = text.length
        text.append(if (d.events.isEmpty()) "(none)" else d.events.joinToString("\n"))
        text.setSpan(ForegroundColorSpan(MUTED), eventsStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val body = TextView(ui.context).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Ui.TEXT)
            setTextIsSelectable(true)
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
        }
        ui.dialog()
            .setTitle("Diagnostics")
            .setView(ScrollView(ui.context).apply { addView(body) })
            .setPositiveButton("Close", null)
            .setNegativeButton("Reset pairing…") { _, _ -> resetPairing() }
            .setNeutralButton("Restart Bluetooth") { _, _ -> hid.restart() }
            .show()
    }

    private fun resetPairing() = confirm("Reset pairing?",
        "The tablet forgets the computers it was paired with" +
            (if (DeviceQuirks.canToggleBluetooth) " and restarts Bluetooth. " else ". Then turn Bluetooth off and on. ") +
            "Then remove \"${hid?.deviceName}\" on each computer and add it again.") {
        hid?.resetPairing()
    }

    // ---------------------------------------------------------------- updates

    /** Looks for a newer release on GitHub and offers to install it. */
    fun updates() {
        val checking = ui.dialog().setTitle("Updates").setMessage("Looking for a new version…")
            .setNegativeButton("Cancel", null).show()
        background {
            val result = runCatching { Updater.latest() }
            main {
                if (!checking.isShowing) return@main
                checking.dismiss()
                val release = result.getOrElse { return@main ui.toast("Can't check for updates: ${it.message}") }
                if (release == null || !Updater.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    ui.dialog().setTitle("Up to date")
                        .setMessage("Version ${BuildConfig.VERSION_NAME} is the latest.")
                        .setPositiveButton("OK", null).show()
                    return@main
                }
                ui.dialog()
                    .setTitle("Version ${release.version} is available")
                    .setMessage(release.notes.take(1200).ifBlank { "No release notes." } +
                        "\n\nThe macropad restarts after the update; the computer reconnects by itself.")
                    .setPositiveButton("Install") { _, _ -> installUpdate(release) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun installUpdate(release: Updater.Release) {
        ui.toast("Downloading version ${release.version}…")
        val context = ui.context.applicationContext
        background {
            val result = runCatching { Updater.install(context, Updater.download(context, release)) }
            result.exceptionOrNull()?.let { e ->
                main {
                    ui.dialog().setTitle("Update not installed").setMessage(e.message ?: "Unknown error")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun background(work: () -> Unit) = Thread(work, "updates").start()
    private fun main(work: () -> Unit) = Handler(Looper.getMainLooper()).post(work)

    // ---------------------------------------------------------------- settings pages

    private fun keyboardLayout() {
        val layouts = KeyboardLayout.values()
        ui.dialog()
            .setTitle("Default keyboard layout")
            .setSingleChoiceItems(layouts.map { it.label }.toTypedArray(), layouts.indexOf(host.settings.keyboardLayout)) { d, i ->
                host.settings.keyboardLayout = layouts[i]
                host.service?.applySettings()
                d.dismiss()
                ui.toast("Typed text now uses ${layouts[i].label}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun keyTiming() {
        val timings = KeyTiming.values()
        ui.dialog()
            .setTitle("Key timing")
            .setSingleChoiceItems(
                timings.map { "${it.label} — held ${it.holdMs} ms" }.toTypedArray(),
                timings.indexOf(host.settings.keyTiming),
            ) { d, i ->
                host.settings.keyTiming = timings[i]
                host.service?.applySettings()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun tabletButtons() {
        val names = listOf("Volume up button", "Volume down button")
        val layout = host.layout
        val items = names.mapIndexed { i, n ->
            "$n  →  ${layout.button(layout.hardware, i)?.let { "${it.label} (${Macro.describe(it.action)})" } ?: "tablet volume"}"
        }.toTypedArray()
        ui.dialog()
            .setTitle("Tablet volume buttons")
            .setItems(items) { _, i -> host.editKey(layout.hardware, i, names[i]) }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun pages() {
        val page = host.layout.pages[host.pageIndex]
        val items = listOf(
            "Add keys page" to { addPage(PageKind.KEYS) },
            "Add trackpad page" to { addPage(PageKind.TRACKPAD) },
            "Rename \"${page.name}\"" to { renamePage(host.pageIndex) },
            "Change icon of \"${page.name}\"" to { pageIcon(host.pageIndex) },
            "Move \"${page.name}\" left" to { movePage(-1) },
            "Move \"${page.name}\" right" to { movePage(1) },
            "Delete \"${page.name}\"" to ::deletePage,
        )
        ui.dialog()
            .setTitle("Pages")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun backup() {
        val file = host.store.backupFile
        val items = listOf(
            "Export layout to a file…" to { host.exportLayout() },
            "Import layout from a file…" to { host.importLayout() },
            "Reset all keys to defaults" to {
                confirm("Reset everything?", "All pages and keys will be replaced with the defaults.") {
                    host.layout = DefaultLayout.layout()
                    host.selectPage(0)
                    host.saveAndRender()
                }
            },
        )
        ui.dialog()
            .setTitle("Backup and restore")
            .setMessage("Every change is also saved automatically to\n${file?.absolutePath ?: "(storage unavailable)"}\n" +
                "— copy it with: adb pull <that path>")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun startup() {
        val s = host.settings
        val options = arrayOf("Open when the tablet starts", "Open when the charger is plugged in")
        val checked = booleanArrayOf(s.openOnBoot, s.openOnCharge)
        ui.dialog()
            .setTitle("Startup")
            .setMultiChoiceItems(options, checked) { _, which, on ->
                if (which == 0) s.openOnBoot = on else s.openOnCharge = on
            }
            .setPositiveButton("Done") { _, _ ->
                if ((s.openOnBoot || s.openOnCharge) && !canOpenFromBackground()) askOverlayPermission()
            }
            .show()
    }

    private fun canOpenFromBackground() =
        !DeviceQuirks.needsOverlayToOpenFromBackground || Settings.canDrawOverlays(ui.context)

    private fun askOverlayPermission() {
        ui.dialog()
            .setTitle("Open by itself")
            .setMessage("On this Android version the macropad can only come to the front by itself with " +
                "\"Display over other apps\". Without it, a notification offers to open it instead.")
            .setPositiveButton("Allow") { _, _ ->
                startSystemScreen(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ui.context.packageName}")))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun gridSize() {
        fun picker(value: Int) = NumberPicker(ui.context).apply {
            minValue = 1; maxValue = 8; this.value = value
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val rows = picker(host.layout.rows)
        val cols = picker(host.layout.cols)
        val content = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(rows)
            addView(TextView(context).apply {
                text = "  rows   ×   columns  "; setTextColor(Ui.TEXT); typeface = ui.fontLabel; textSize = 17f
            })
            addView(cols)
        }
        ui.dialog()
            .setTitle("Grid size")
            .setView(FrameLayout(ui.context).apply { setPadding(0, ui.dp(12), 0, 0); addView(content) })
            .setPositiveButton("OK") { _, _ ->
                host.layout.rows = rows.value
                host.layout.cols = cols.value
                host.saveAndRender()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- pages

    private fun addPage(kind: PageKind) {
        val suggested = if (kind == PageKind.TRACKPAD) DefaultLayout.TRACKPAD_PAGE else "Page ${host.layout.pages.size + 1}"
        promptText("New page", suggested) { name ->
            val buttons = if (kind == PageKind.TRACKPAD) {
                DefaultLayout.layout().pages.first { it.kind == PageKind.TRACKPAD }.buttons
            } else {
                mutableListOf()
            }
            host.layout.pages += Page(name, buttons, kind)
            host.selectPage(host.layout.pages.lastIndex)
            host.saveAndRender()
        }
    }

    /** Long-press on a tab. */
    fun pageMenu(index: Int) {
        val page = host.layout.pages[index]
        ui.dialog()
            .setTitle(page.name)
            .setItems(arrayOf("Rename", "Change icon", "Show for apps…")) { _, which ->
                when (which) {
                    0 -> renamePage(index)
                    1 -> pageIcon(index)
                    else -> pageApps(index)
                }
            }
            .show()
    }

    /** The computer's apps that bring this page up when they come to the front (PC link). */
    private fun pageApps(index: Int) {
        val page = host.layout.pages[index]
        val front = host.service?.pc?.status?.app
        promptText("Show \"${page.name}\" for apps", page.appNames.joinToString(", "), allowEmpty = true,
            hint = front?.let { "e.g. $it (in front now)" } ?: "Process names, e.g. ms-teams, outlook") { apps ->
            page.apps = apps
            host.saveAndRender()
        }
    }

    private fun pageIcon(index: Int) {
        val page = host.layout.pages[index]
        val grid = GridLayout(ui.context).apply {
            columnCount = 5
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), 0)
        }
        val dialog = ui.dialog()
            .setTitle("Icon for \"${page.name}\"")
            .setView(grid)
            .setNegativeButton("Cancel", null)
            .create()
        for (key in DefaultLayout.APP_ICONS) {
            val current = (page.icon ?: DefaultLayout.iconFor(page.name)) == key
            grid.addView(LinearLayout(ui.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
                background = if (current) ui.roundRect(RAISED, ui.dp(12).toFloat()).apply {
                    setStroke(ui.dp(2), HAIRLINE_STRONG)
                } else ui.pressable(Color.TRANSPARENT, ui.dp(12).toFloat())
                addView(ImageView(context).apply { setImageDrawable(AppIcons.drawable(ui, key, page.name)) },
                    LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
                addView(TextView(context).apply {
                    text = AppIcons.names[key]
                    setTextColor(MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setPadding(0, ui.dp(4), 0, 0)
                })
                setOnClickListener {
                    page.icon = key
                    host.saveAndRender()
                    dialog.dismiss()
                }
            }, GridLayout.LayoutParams().apply { width = ui.dp(96); setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2)) })
        }
        dialog.show()
    }

    fun renamePage(index: Int) {
        val page = host.layout.pages[index]
        promptText("Rename page", page.name) { name ->
            host.service?.profiles?.renamePage(page.name, name)
            page.name = name
            host.saveAndRender()
        }
    }

    private fun movePage(delta: Int) {
        val pages = host.layout.pages
        val from = host.pageIndex
        val to = from + delta
        if (to !in pages.indices) return
        pages.add(to, pages.removeAt(from))
        host.selectPage(to)
        host.saveAndRender()
    }

    private fun deletePage() {
        val pages = host.layout.pages
        if (pages.size == 1) return ui.toast("Can't delete the only page")
        val page = pages[host.pageIndex]
        confirm("Delete \"${page.name}\"?", "Its keys will be removed.") {
            pages.removeAt(host.pageIndex)
            host.selectPage(host.pageIndex.coerceAtMost(pages.lastIndex))
            host.saveAndRender()
        }
    }

    // ---------------------------------------------------------------- saved values

    /** `{secret:name}` values: the ones the keys use plus any others already saved. */
    private fun savedValues() {
        val l = host.layout
        val used = (l.pages + l.dock + l.sidebar + l.hardware).flatMap { it.buttons }.filterNotNull()
            .flatMap { listOf(it.action) + listOfNotNull(it.longAction) }
            .flatMap { TextVariables.secretNames(it) }
        val names = (used + host.secrets.names).filter { it.isNotEmpty() }.distinct().sorted()
        val items = names.map { name ->
            val value = host.secrets[name]
            "$name: " + when {
                value == null -> "not set"
                looksSecret(name) -> "••••••"
                else -> value
            }
        } + "Add a value…"
        ui.dialog()
            // An AlertDialog shows either a message or a list, so the note lives in the title.
            .setTitle("Saved values · encrypted, this tablet only")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < names.size) editSavedValue(names[which])
                else promptText("Name (letters, digits, _)", "") { name ->
                    val clean = name.replace(Regex("[^A-Za-z0-9_]"), "_")
                    editSavedValue(clean)
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun looksSecret(name: String) = Regex("pass|pin|secret|token|key", RegexOption.IGNORE_CASE).containsMatchIn(name)

    private fun editSavedValue(name: String) {
        val hidden = looksSecret(name)
        val input = EditText(ui.context).apply {
            setSingleLine()
            if (hidden) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
            } else {
                setText(host.secrets[name].orEmpty())
            }
            hint = if (hidden && host.secrets[name] != null) "(unchanged)" else name
        }
        val dialog = ui.dialog()
            .setTitle(name)
            .setView(FrameLayout(ui.context).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input) })
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString()
                if (value.isNotEmpty()) host.secrets[name] = value
                savedValues()
            }
            .setNeutralButton("Clear") { _, _ -> host.secrets[name] = null; savedValues() }
            .setNegativeButton("Cancel") { _, _ -> savedValues() }
            .create()
        dialog.setOnShowListener { input.requestFocus() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun dimLabel(minutes: Int) = if (minutes == 0) "never" else "after $minutes min"

    private fun idleDim() {
        val options = listOf(0, 1, 2, 5, 10, 30)
        ui.dialog()
            .setTitle("Dim to a clock when idle")
            .setSingleChoiceItems(options.map { dimLabel(it) }.toTypedArray(),
                options.indexOf(host.settings.idleDimMinutes).coerceAtLeast(0)) { d, i ->
                host.settings.idleDimMinutes = options[i]
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pinLock() {
        val settings = host.settings
        fun setNew() = askPin("New PIN (4+ digits)") { pin ->
            if (pin.length < 4) return@askPin ui.toast("Use at least 4 digits")
            askPin("Repeat the PIN") { again ->
                if (again != pin) return@askPin ui.toast("PINs don't match")
                settings.pinHash = AppSettings.hashPin(pin)
                ui.toast("PIN set — editing and settings now ask for it")
            }
        }
        if (settings.pinHash == null) return setNew()
        ui.dialog()
            .setTitle("PIN lock")
            .setMessage("Editing keys, recording, settings and computer changes ask for the PIN. " +
                "Using the keys never does.")
            .setPositiveButton("Change PIN") { _, _ -> setNew() }
            .setNeutralButton("Remove PIN") { _, _ -> settings.pinHash = null; ui.toast("PIN removed") }
            .setNegativeButton("Close", null)
            .show()
    }

    fun askPin(title: String, onPin: (String) -> Unit) {
        val input = EditText(ui.context).apply {
            setSingleLine() // before the input type: it would replace the password masking
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        ui.dialog()
            .setTitle(title)
            .setView(FrameLayout(ui.context).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input) })
            .setPositiveButton("OK") { _, _ -> onPin(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
            .also {
                input.requestFocus()
                it.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
    }

    // ---------------------------------------------------------------- helpers

    fun promptText(title: String, initial: String, allowEmpty: Boolean = false, hint: String? = null, onOk: (String) -> Unit) {
        val input = EditText(ui.context).apply {
            setText(initial); setSingleLine(); selectAll(); this.hint = hint
        }
        ui.dialog()
            .setTitle(title)
            .setView(FrameLayout(ui.context).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input) })
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { allowEmpty || it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun confirm(title: String, message: String, onOk: () -> Unit) {
        ui.dialog()
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onOk() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
