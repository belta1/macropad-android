package dev.belta.macropad.ui

import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.belta.macropad.pc.PcNotice
import dev.belta.macropad.ui.Ui.Companion.DIALOG
import dev.belta.macropad.ui.Ui.Companion.HAIRLINE_STRONG
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.TEXT

/**
 * A notification from the computer (a Teams message, a mail from someone on the list) as a card at
 * the top of the screen for a few seconds. Tapping it opens it on the computer ([onOpen]); the ✕
 * puts it away. Ones that come while a card shows wait their turn.
 */
class NoticeCard(private val ui: Ui, private val onOpen: (PcNotice) -> Unit) : LinearLayout(ui.context) {
    private val icon = ImageView(context)
    private val title = TextView(context)
    private val body = TextView(context)
    private val queue = ArrayDeque<PcNotice>()
    private var current: PcNotice? = null
    private val hide = Runnable { next() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        elevation = ui.dp(12).toFloat()
        setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(12))
        background = ui.roundRect(DIALOG, ui.dp(16).toFloat()).apply { setStroke(ui.dp(1), HAIRLINE_STRONG) }
        addView(icon, LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(14) })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title.apply {
                setTextColor(TEXT)
                typeface = ui.fontLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(body.apply {
                setTextColor(MUTED)
                typeface = ui.fontBody
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(ui.icon("close", MUTED).apply {
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            background = ui.pressable(android.graphics.Color.TRANSPARENT, ui.dp(20).toFloat())
            contentDescription = "Dismiss"
            setOnClickListener { next() }
        }, LayoutParams(ui.dp(44), ui.dp(44)))
        setOnClickListener {
            val notice = current
            next()
            notice?.let(onOpen)
        }
    }

    /** Shows [notice] now, or after the ones already waiting. */
    fun show(notice: PcNotice) {
        if (current == null) present(notice) else if (queue.size < MAX_WAITING) queue.addLast(notice)
    }

    private fun present(notice: PcNotice) {
        current = notice
        val teams = notice.kind == "teams"
        icon.setImageDrawable(AppIcons.drawable(ui, if (teams) "teams" else "outlook", if (teams) "Teams" else "Outlook"))
        title.text = when {
            teams && notice.mention -> "@ ${notice.from}"
            else -> notice.from.ifBlank { if (teams) "Teams" else "New mail" }
        }
        body.text = notice.text
        contentDescription = "${if (teams) "Teams" else "Mail"} from ${notice.from}: ${notice.text}. Tap to open it on the computer"
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            translationY = -ui.dp(120).toFloat()
            animate().translationY(0f).setDuration(220).start()
        }
        removeCallbacks(hide)
        postDelayed(hide, SHOWN_MS)
    }

    private fun next() {
        removeCallbacks(hide)
        current = null
        val waiting = queue.removeFirstOrNull()
        if (waiting != null) present(waiting) else visibility = View.GONE
    }

    companion object {
        const val SHOWN_MS = 7_000L
        private const val MAX_WAITING = 5

        /** Where the card sits in the screen's frame: across the top, centred. */
        fun params(ui: Ui) = FrameLayout.LayoutParams(ui.dp(560), FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(10) }
    }
}
