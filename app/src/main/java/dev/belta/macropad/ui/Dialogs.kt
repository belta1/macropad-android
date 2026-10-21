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
import dev.belta.macropad.hid.KeyboardLayout
import dev.belta.macropad.model.AppSettings
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
}

/** Settings menu and the dialogs behind it. */
class Dialogs(private val host: MacropadHost) {
    private val ui get() = host.ui
    private val hid: HidPeripheral? get() = host.service?.hid

    fun settings() {
        val items = listOf(
            "Grid size (${host.layout.rows} × ${host.layout.cols})" to ::gridSize,
            "Computer keyboard layout: ${host.settings.keyboardLayout.label}" to ::keyboardLayout,
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

    fun computers() {
        val hid = hid ?: return
        val hosts = hid.knownHosts()
        if (hosts.isEmpty()) {
            ui.toast("No computers yet — pair one first")
            return
        }
        val names = listOf("Any computer (first to connect)") + hosts.map { it.name }
        val selected = hid.preferredHost()?.let { p -> hosts.indexOfFirst { it.address == p.address } + 1 } ?: 0
        ui.dialog()
            .setTitle("Use the macropad with")
            .setSingleChoiceItems(names.toTypedArray(), selected) { d, which ->
                hid.setPreferredHost(if (which == 0) null else hosts[which - 1].address)
                d.dismiss()
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Forget a computer…") { _, _ ->
                ui.dialog()
                    .setTitle("Forget computer")
                    .setItems(hosts.map { it.name }.toTypedArray()) { _, i ->
                        confirm("Forget ${hosts[i].name}?",
                            "The tablet unpairs it. Remove \"${HidPeripheral.DEVICE_NAME}\" on that computer too.") {
                            hid.forgetHost(hosts[i].address)
                        }
                    }
                    .show()
            }
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
        line("Computer keyboard layout", host.settings.keyboardLayout.label)
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
            .setTitle("Computer keyboard layout")
            .setSingleChoiceItems(layouts.map { it.label }.toTypedArray(), layouts.indexOf(host.settings.keyboardLayout)) { d, i ->
                host.settings.keyboardLayout = layouts[i]
                host.service?.applySettings()
                d.dismiss()
                ui.toast("Typed text now uses ${layouts[i].label}")
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
        promptText("Rename page", page.name) { name -> page.name = name; host.saveAndRender() }
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

    fun promptText(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(ui.context).apply { setText(initial); setSingleLine(); selectAll() }
        ui.dialog()
            .setTitle(title)
            .setView(FrameLayout(ui.context).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input) })
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
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
