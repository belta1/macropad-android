package dev.belta.macropad.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.belta.macropad.model.MacroButton
import dev.belta.macropad.model.Palette
import dev.belta.macropad.pc.PcStatus
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.TEXT

/**
 * The computer's volume mixer (PC link): the speakers, then each app playing through them, each a
 * vertical [SliderView] that sits at the app's real level. Dragging calls [onLevel] (app null =
 * the speakers), tapping the speaker icon [onMute], − / + [onStep].
 */
@SuppressLint("ViewConstructor")
class MixerView(
    private val ui: Ui, private val icon: (String) -> Bitmap?,
    private val onLevel: (app: String?, level: Int, done: Boolean) -> Unit,
    private val onMute: (app: String?, mute: Boolean) -> Unit,
    private val onStep: (app: String?, level: Int, up: Boolean) -> Unit,
) : FrameLayout(ui.context), PcStatusView {

    private class Channel(val app: String?, val name: String, val level: Int, val muted: Boolean)

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        isMotionEventSplittingEnabled = true
    }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private val message = pcPageMessage(ui, "")
    /** The channels on screen (app, null = speakers), their sliders and level texts. */
    private var shown = emptyList<String?>()
    private val sliders = ArrayList<SliderView>()
    private val levels = ArrayList<TextView>()
    /** Latest levels, for − / +. */
    private var current = emptyList<Channel>()

    init {
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun update(status: PcStatus?) {
        val volume = status?.volume
        message.text = when {
            status == null -> "Shows the computer's volume per app through the PC link (docs/pc-link.md)."
            else -> "Update the PC link script (tools/macropad-link.ps1) to see the computer's volume."
        }
        message.visibility = if (volume == null) View.VISIBLE else View.GONE
        scroller.visibility = if (volume == null) View.GONE else View.VISIBLE
        if (volume == null) { shown = emptyList(); return }
        current = listOf(Channel(null, "Speakers", volume.level, volume.muted)) +
            volume.apps.map { Channel(it.exe, it.app, it.level, it.muted) }
        if (current.map { it.app } != shown) return rebuild()
        current.forEachIndexed { i, c ->
            sliders[i].setLevel(c.level, c.muted)
            levels[i].text = levelText(c)
        }
    }

    private fun levelText(c: Channel) = if (c.muted) "Muted" else "${c.level}%"

    private fun rebuild() {
        shown = current.map { it.app }
        row.removeAllViews()
        sliders.clear()
        levels.clear()
        current.forEachIndexed { i, c -> row.addView(column(i, c), LinearLayout.LayoutParams(ui.sdp(COLUMN_DP), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
        }) }
    }

    private fun column(index: Int, c: Channel) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val color = if (c.app == null) Palette.colors[0] else Palette.colors[Math.floorMod(c.app.hashCode(), Palette.colors.size)]
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bitmap = c.app?.let(icon)
            addView(if (bitmap != null) ImageView(context).apply { setImageBitmap(bitmap) }
                else if (c.app == null) ui.icon("volume_up", TEXT)
                else ImageView(context).apply { setImageDrawable(AppIcons.drawable(ui, null, c.name)) },
                LinearLayout.LayoutParams(ui.sdp(22), ui.sdp(22)).apply { marginEnd = ui.dp(6) })
            addView(TextView(context).apply {
                text = c.name
                typeface = ui.fontLabel
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * ui.scale)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val level = TextView(context).apply {
            text = levelText(c)
            typeface = ui.fontHint
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f * ui.scale)
            setPadding(0, ui.dp(2), 0, ui.dp(6))
        }
        levels += level
        addView(level)
        // A volume slider (its icon mutes), absolute at the channel's level.
        val button = MacroButton(c.name, "slide:volume", color, "volume_up")
        val slider = SliderView(ui, button, KeyCap(ui, color, lit = false), compact = false,
            onTap = { onMute(c.app, !current[index].muted) },
            level = c.level, muted = c.muted,
            onLevel = { value, done -> onLevel(c.app, value, done) }) { up ->
            onStep(c.app, current[index].level, up)
        }
        sliders += slider
        addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private companion object {
        const val COLUMN_DP = 128
    }
}
