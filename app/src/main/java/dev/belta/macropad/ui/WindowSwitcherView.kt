package dev.belta.macropad.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.belta.macropad.pc.PcStatus
import dev.belta.macropad.pc.PcWindow
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.inkOn
import dev.belta.macropad.ui.Ui.Companion.withAlpha

/** A page drawn from what the PC link reports; [update] is called on every report. */
interface PcStatusView {
    fun update(status: PcStatus?)
}

/** What a PC link page says when there is nothing to show. */
internal fun pcPageMessage(ui: Ui, text: String) = TextView(ui.context).apply {
    this.text = text
    setTextColor(MUTED)
    typeface = ui.fontBody
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    gravity = Gravity.CENTER
    setPadding(ui.dp(32), 0, ui.dp(32), 0)
}

/**
 * The computer's open windows (PC link) as keys, [columns] across: the app's icon, the window's
 * title and the app's name; the window in front is lit. Tapping one calls [onPick] (bring it to
 * the front). Windows are grouped by app in a fixed order, so a key keeps its place as you switch.
 */
@SuppressLint("ViewConstructor")
class WindowSwitcherView(
    private val ui: Ui, private val columns: Int, private val icon: (String) -> Bitmap?,
    private val onPick: (PcWindow) -> Unit,
) : FrameLayout(ui.context), PcStatusView {

    private val grid = GridLayout(context).apply { columnCount = columns.coerceAtLeast(1) }
    private val scroller = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val message = pcPageMessage(ui, "")
    /** What's on screen, to skip redrawing when nothing changed. */
    private var shown: Pair<List<PcWindow>, Long?>? = null

    init {
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun update(status: PcStatus?) {
        val windows = status?.windows
        val text = when {
            status == null -> "Lists the computer's windows through the PC link (docs/pc-link.md)."
            windows == null -> "Update the PC link script (tools/macropad-link.ps1) to list the computer's windows."
            windows.isEmpty() -> "No windows open"
            else -> null
        }
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
        scroller.visibility = if (text == null) View.VISIBLE else View.GONE
        if (text != null) { shown = null; return }
        val sorted = windows!!.sortedWith(compareBy({ it.exe }, { it.id }))
        val now = sorted to status!!.frontWindow
        if (now == shown) return
        shown = now
        grid.removeAllViews()
        for (window in sorted) {
            grid.addView(tile(window, front = window.id == status.frontWindow), GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                height = ui.sdp(TILE_HEIGHT_DP)
                setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
            })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun tile(window: PcWindow, front: Boolean): View {
        // Each app has its own color, like the fallback tab icons.
        val color = Ui.ACCENTS[Math.floorMod(window.exe.hashCode(), Ui.ACCENTS.size)]
        val cap = KeyCap(ui, color, lit = front)
        val ink = if (front) inkOn(color) else TEXT
        val key = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cap
            val side = cap.insetSide.toInt() + ui.dp(12)
            setPadding(side, cap.insetTop.toInt() + ui.dp(10), side, cap.insetBottom.toInt() + ui.dp(8))
            contentDescription = "${window.title}, ${window.app}" + if (front) ", in front" else ""
        }
        key.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                val bitmap = icon(window.exe)
                if (bitmap != null) setImageBitmap(bitmap) else setImageDrawable(AppIcons.drawable(ui, null, window.app))
            }, LinearLayout.LayoutParams(ui.sdp(26), ui.sdp(26)).apply { marginEnd = ui.dp(10) })
            addView(TextView(context).apply {
                text = window.app
                typeface = ui.fontHint
                setTextColor(if (front) withAlpha(ink, 0.75f) else MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f * ui.scale)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        key.addView(TextView(context).apply {
            text = window.title
            typeface = ui.fontLabel
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * ui.scale)
            maxLines = 2
            setLineSpacing(0f, 0.92f)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.BOTTOM
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // The face drops while touched, like a key; a scroll that starts on it doesn't pick it.
        key.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> press(v as ViewGroup, cap, true)
                MotionEvent.ACTION_UP -> {
                    press(v as ViewGroup, cap, false)
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onPick(window)
                }
                MotionEvent.ACTION_CANCEL -> press(v as ViewGroup, cap, false)
            }
            true
        }
        key.setOnClickListener { onPick(window) } // TalkBack
        return key
    }

    private fun press(key: ViewGroup, cap: KeyCap, down: Boolean) {
        key.isPressed = down
        for (i in 0 until key.childCount) key.getChildAt(i).translationY = if (down) cap.travel else 0f
    }

    private companion object {
        const val TILE_HEIGHT_DP = 104
    }
}
