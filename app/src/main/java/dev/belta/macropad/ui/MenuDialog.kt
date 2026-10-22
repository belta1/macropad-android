package dev.belta.macropad.ui

import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListAdapter
import android.widget.TextView
import dev.belta.macropad.R
import dev.belta.macropad.ui.Ui.Companion.BLUE
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.RED
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.lighten
import dev.belta.macropad.ui.Ui.Companion.withAlpha

/**
 * The app's dialog builder. Plain item lists and single-choice lists are drawn as menu rows: an icon
 * (when every row has one), the title, its current value as a pill ("Grid size (3 × 5)",
 * "Sidebar: left", "laptop  ·  connected"), a chevron for entries that open another menu ("…"), and
 * a check on the chosen option. Callers keep using the standard [AlertDialog.Builder] calls.
 */
class MenuDialogBuilder(private val ui: Ui) : AlertDialog.Builder(ui.context, R.style.Theme_Macropad_Dialog) {
    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var hasList = false

    override fun setTitle(title: CharSequence?): AlertDialog.Builder {
        this.title = title
        return super.setTitle(title)
    }

    override fun setMessage(message: CharSequence?): AlertDialog.Builder {
        this.message = message
        return super.setMessage(message)
    }

    override fun setAdapter(adapter: ListAdapter?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder {
        hasList = true
        return super.setAdapter(adapter, listener)
    }

    override fun setSingleChoiceItems(adapter: ListAdapter?, checkedItem: Int, listener: DialogInterface.OnClickListener?): AlertDialog.Builder {
        hasList = true
        return super.setSingleChoiceItems(adapter, checkedItem, listener)
    }

    /** A message would hide a list, so with a list it goes under the title instead. */
    override fun create(): AlertDialog {
        val note = message
        if (hasList && note != null) {
            super.setMessage(null)
            setCustomTitle(LinearLayout(ui.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(24), ui.dp(22), ui.dp(24), ui.dp(8))
                title?.let { addView(TextView(context).apply {
                    text = it
                    typeface = ui.fontBold
                    setTextColor(TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                }) }
                addView(TextView(context).apply {
                    text = note
                    typeface = ui.fontMedium
                    setTextColor(MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, ui.dp(6), 0, 0)
                })
            })
        }
        return super.create()
    }

    override fun setItems(items: Array<out CharSequence>?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        setAdapter(MenuAdapter(ui, items.orEmpty().map { it.toString() }, checked = null), listener)

    override fun setSingleChoiceItems(
        items: Array<out CharSequence>?, checkedItem: Int, listener: DialogInterface.OnClickListener?,
    ): AlertDialog.Builder {
        val adapter = MenuAdapter(ui, items.orEmpty().map { it.toString() }, checked = checkedItem)
        return super.setSingleChoiceItems(adapter, checkedItem) { d, which ->
            adapter.checked = which
            adapter.notifyDataSetChanged()
            listener?.onClick(d, which)
        }
    }
}

private class MenuAdapter(private val ui: Ui, labels: List<String>, var checked: Int?) : BaseAdapter() {

    private class Row(val title: String, val value: String?, val submenu: Boolean, val icon: String?, val danger: Boolean)

    private val rows: List<Row>

    init {
        val parsed = labels.map(::parse)
        // Icons only when every row has one, so a list never looks half-decorated.
        val withIcons = checked == null && parsed.all { it.icon != null }
        rows = if (withIcons) parsed else parsed.map { Row(it.title, it.value, it.submenu, null, it.danger) }
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position].title
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = rows[position]
        val selected = position == checked
        val tint = if (row.danger) lighten(RED, 0.25f) else lighten(BLUE, 0.3f)
        return FullWidthRow(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            minimumHeight = ui.dp(52)
            setPadding(ui.dp(14), ui.dp(6), ui.dp(12), ui.dp(6))
            background = ui.pressable(if (selected) withAlpha(BLUE, 0.14f) else Color.TRANSPARENT, ui.dp(14).toFloat())
            row.icon?.let { icon ->
                addView(FrameLayout(context).apply {
                    background = ui.roundRect(withAlpha(if (row.danger) RED else BLUE, 0.16f), ui.dp(10).toFloat())
                    addView(ui.icon(icon, tint), FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER))
                }, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { marginEnd = ui.dp(14) })
            }
            addView(TextView(context).apply {
                text = row.title
                typeface = if (selected) ui.fontSemiBold else ui.fontMedium
                setTextColor(if (row.danger) tint else TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.value?.let { value ->
                addView(TextView(context).apply {
                    text = value
                    typeface = ui.fontMedium
                    setTextColor(MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                    maxWidth = ui.dp(300)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
                    background = ui.roundRect(withAlpha(Color.WHITE, 0.06f), ui.dp(10).toFloat())
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = ui.dp(12)
                })
            }
            when {
                selected -> addView(ui.icon("check", lighten(BLUE, 0.3f)),
                    LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginStart = ui.dp(10) })
                row.submenu -> addView(ui.icon("chevron_right", MUTED),
                    LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginStart = ui.dp(8) })
            }
        }
    }

    private fun parse(label: String): Row {
        var title = label.trim()
        val submenu = title.endsWith("…") || title.endsWith("...")
        title = title.removeSuffix("…").removeSuffix("...").trimEnd()
        var value: String? = null
        val dot = listOf("  ·  ", "  →  ").map { title.indexOf(it) }.firstOrNull { it > 0 } ?: -1
        val colon = Regex("""^([^:]{2,40}):\s+(.+)$""").find(title)
        val paren = Regex("""^(.+?)\s+\(([^()]+)\)$""").find(title)
        when {
            dot > 0 -> { value = title.substring(dot + 5); title = title.substring(0, dot) }
            colon != null -> { title = colon.groupValues[1]; value = colon.groupValues[2] }
            paren != null -> { title = paren.groupValues[1]; value = paren.groupValues[2] }
        }
        val lower = title.lowercase()
        val danger = DANGER.any { lower.startsWith(it) }
        val icon = ICONS.firstOrNull { (key, _) -> lower.contains(key) }?.second
        return Row(title, value, submenu, icon, danger)
    }

    private companion object {
        val DANGER = listOf("quit", "forget", "delete", "remove", "discard", "clear", "reset pairing")

        /** Matched in order against the lower-case title; the first hit wins. */
        val ICONS = listOf(
            "reset on/off" to "refresh", "grid" to "table", "sidebar" to "sidebar",
            "keyboard layout" to "keyboard", "key timing" to "tune", "computers" to "desktop",
            "volume" to "volume_up", "saved values" to "bookmark", "pages shown" to "document",
            "pages" to "document", "start page" to "star", "backup" to "save", "startup" to "bolt",
            "dim" to "history", "pin" to "lock", "diagnostics" to "bug", "quit" to "close",
            "use the macropad" to "bluetooth", "rename" to "edit", "forget" to "delete",
            "change icon" to "star", "export" to "open_in_new", "import" to "download",
        )
    }
}

/**
 * The dialog measures its list "at most" its width, which would leave rows only as wide as their
 * text; filling the width keeps values and chevrons lined up on the right.
 */
private class FullWidthRow(context: android.content.Context) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY)
        } else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }
}
