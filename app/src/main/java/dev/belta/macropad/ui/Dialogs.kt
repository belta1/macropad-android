package dev.belta.macropad.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import dev.belta.macropad.MacropadService
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
import dev.belta.macropad.ui.Ui.Companion.GREEN
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.RED

/** What the dialogs need from the activity. */
interface MacropadHost {
    val ui: Ui
    var layout: Layout
    val settings: AppSettings
    val store: MacroStore
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
    /** A computer's name or settings changed. */
    fun profilesChanged()
}

/** Settings menu and the dialogs behind it. */
class Dialogs(private val host: MacropadHost) {
    private val ui get() = host.ui
    private val hid: HidPeripheral? get() = host.service?.hid

    fun settings() {
        val items = listOfNotNull(
            if (host.toggledKeys > 0) "Reset ON/OFF keys (${host.toggledKeys} on)" to host::resetToggles else null,
            "Grid size (${host.layout.rows} × ${host.layout.cols})" to ::gridSize,
            "Default keyboard layout: ${host.settings.keyboardLayout.label}" to ::keyboardLayout,
            "Default key timing: ${host.settings.keyTiming.label}" to ::keyTiming,
            "Computers…" to ::computers,
            "Tablet volume buttons…" to ::tabletButtons,
            "Pages…" to ::pages,
            "Backup and restore…" to ::backup,
            "Startup…" to ::startup,
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
                .setNegativeButton("Switch computer…") { _, _ -> computers() }
                .setNeutralButton("Diagnostics") { _, _ -> diagnostics() }
                .show()
            else -> ui.dialog()
                .setTitle("Pairing")
                .setMessage(
                    "${hid.diagnostics().detail}\n\n" +
                        "On your computer open Bluetooth settings → Add device, and pick " +
                        "\"${HidPeripheral.DEVICE_NAME}\". Confirm the code on both screens.\n\n" +
                        "The macropad shows up as a Bluetooth LE keyboard and mouse; no software is needed " +
                        "on the computer. Don't open Bluetooth settings on this tablet while pairing."
                )
                .setPositiveButton("OK", null)
                .setNegativeButton("Diagnostics") { _, _ -> diagnostics() }
                .setNeutralButton("Restart Bluetooth") { _, _ -> hid.restart() }
                .show()
        }
    }

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
                    "The tablet unpairs it. Remove \"${HidPeripheral.DEVICE_NAME}\" on that computer too.") {
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
        "The tablet forgets the computers it was paired with and restarts Bluetooth. " +
            "Then remove \"${HidPeripheral.DEVICE_NAME}\" on each computer and add it again.") {
        hid?.resetPairing()
    }

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
            .setPositiveButton("Done", null)
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
            addView(TextView(context).apply { text = "  rows   ×   columns  "; setTextColor(Color.WHITE) })
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

    // ---------------------------------------------------------------- helpers

    fun promptText(title: String, initial: String, allowEmpty: Boolean = false, hint: String? = null, onOk: (String) -> Unit) {
        val input = EditText(ui.context).apply { setText(initial); setSingleLine(); selectAll(); this.hint = hint }
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
