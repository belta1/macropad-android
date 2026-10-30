package dev.belta.macropad.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.belta.macropad.model.AppSettings
import dev.belta.macropad.model.Step
import dev.belta.macropad.net.HaEntity
import dev.belta.macropad.net.HaSocket
import dev.belta.macropad.net.HomeAssistant
import dev.belta.macropad.net.NetActions
import dev.belta.macropad.ui.Ui.Companion.AMBER
import dev.belta.macropad.ui.Ui.Companion.BLUE
import dev.belta.macropad.ui.Ui.Companion.GREEN
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.RAISED
import dev.belta.macropad.ui.Ui.Companion.RED
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.inkOn
import dev.belta.macropad.ui.Ui.Companion.withAlpha
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Home Assistant's lights, switches, covers…, scenes, scripts and automations, [columns] across,
 * read over Wi-Fi with the `ha_url` / `ha_token` saved values ([secret]) every 10 s while [active].
 * Only the main devices show at first: the ones exposed to a voice assistant (Everything shows
 * the rest). Tapping one toggles it (runs a scene or script); holding it pins it to the top, hides
 * it or runs an automation now. Filters by kind and a search box narrow it down.
 */
@SuppressLint("ViewConstructor")
class HomeAssistantView(
    private val ui: Ui, private val columns: Int, private val settings: AppSettings,
    private val secret: (String) -> String?, private val active: () -> Boolean,
) : FrameLayout(ui.context) {

    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private var entities: List<HaEntity>? = null
    private var lookup = HomeAssistant.Lookup()
    private var lookupAt = 0L
    private var error: String? = null
    private var loading = false
    /** Entities just tapped, shown as they will be until Home Assistant reports them (id to until). */
    private val expected = HashMap<String, Pair<HaEntity, Long>>()
    /** Entities exposed to a voice assistant: the main ones ([HomeAssistant.main]). */
    private var exposed = emptySet<String>()
    private var domain: String? = null
    private var showHidden = false
    /** Show the devices that aren't main too. */
    private var everything = false
    /** What's on screen, to skip redrawing when nothing changed. */
    private var shown: Any? = null

    private val search = EditText(context).apply {
        hint = "Search"
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        typeface = ui.fontBody
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = ui.roundRect(RAISED, ui.dp(12).toFloat())
        setPadding(ui.dp(14), 0, ui.dp(14), 0)
        ui.showKeyboardOnFocus(this)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
    }
    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val sections = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroller = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(sections, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val message = pcPageMessage(ui, "")
    private val status = TextView(context).apply {
        typeface = ui.fontHint
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(10), 0, ui.dp(4), 0)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (active()) refresh()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(3))
            addView(search, LinearLayout.LayoutParams(ui.dp(200), ui.dp(44)))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
            addView(status)
            addView(ui.iconButton("refresh", "Refresh") { refresh(lookupToo = true) }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(FrameLayout(context).apply {
                addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(poll)
        refresh(lookupToo = true)
        handler.postDelayed(poll, POLL_MS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(poll)
        network.shutdown()
        super.onDetachedFromWindow()
    }

    /** Reads every state again; the areas and hidden entities every 10 minutes ([lookupToo]: now). */
    private fun refresh(lookupToo: Boolean = false) {
        if (loading || network.isShutdown) return
        loading = true
        val readLookup = lookupToo || System.currentTimeMillis() - lookupAt > LOOKUP_MS
        network.execute {
            var newLookup: HomeAssistant.Lookup? = null
            var newExposed: Set<String>? = null
            if (readLookup) {
                newLookup = try {
                    HomeAssistant.parseLookup(NetActions.haTemplate(secret, HomeAssistant.LOOKUP_TEMPLATE))
                } catch (e: Exception) {
                    Log.w(TAG, "areas: $e") // an old Home Assistant, or a token that can't render templates
                    null
                }
                newExposed = try {
                    HomeAssistant.parseExposed(HaSocket.command(secret(NetActions.HA_URL).orEmpty(),
                        secret(NetActions.HA_TOKEN).orEmpty(), HomeAssistant.EXPOSED_COMMAND))
                } catch (e: Exception) {
                    Log.w(TAG, "exposed entities: $e") // then the page shows them all
                    null
                }
            }
            val result = runCatching { NetActions.haStates(secret) }
            handler.post {
                loading = false
                if (readLookup) lookupAt = System.currentTimeMillis()
                newLookup?.let { lookup = it }
                newExposed?.let { exposed = it }
                result.onSuccess { json ->
                    entities = runCatching { HomeAssistant.parseStates(json, lookup) }.getOrElse {
                        error = "Home Assistant sent something unexpected"
                        return@post render()
                    }
                    error = null
                }.onFailure { e ->
                    error = (e as? IOException)?.message ?: "Can't reach Home Assistant"
                }
                render()
            }
        }
    }

    /** The entities as they should show: Home Assistant's, with the ones just tapped as expected. */
    private fun current(): List<HaEntity>? {
        val now = System.currentTimeMillis()
        expected.entries.removeAll { it.value.second < now }
        // Once Home Assistant reports the new state (or something else happened), its own wins.
        return entities?.map { e -> expected[e.id]?.first?.takeIf { it.state != e.state } ?: e.also { expected.remove(e.id) } }
    }

    private fun render() {
        val list = current()
        val hidden = settings.haHidden
        val text = when {
            secret(NetActions.HA_URL).isNullOrBlank() || secret(NetActions.HA_TOKEN).isNullOrBlank() ->
                "Shows Home Assistant's lights, switches, scenes and automations.\n\nSet ha_url (e.g. " +
                    "http://homeassistant.local:8123) and ha_token (Home Assistant → your profile → Security → " +
                    "Long-lived access tokens) in ⚙ → Saved values."
            list == null && error != null -> "$error\n\nTap ↻ to try again."
            list == null -> "Loading Home Assistant…"
            else -> null
        }
        status.text = if (list != null && error != null) "Offline" else ""
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
        scroller.visibility = if (text == null) View.VISIBLE else View.GONE
        if (list == null) { shown = null; return }
        val pinned = settings.haPinned
        val unhidden = list.filter { it.id !in hidden }
        val main = HomeAssistant.main(unhidden, exposed, pinned)
        val query = search.text.toString()
        renderChips(if (everything) unhidden else main, list.size - unhidden.size, main.size < unhidden.size)
        // A search looks through everything.
        val visible = when {
            showHidden -> list.filter { it.id in hidden }
            everything || query.isNotBlank() -> unhidden
            else -> main
        }
        val groups = HomeAssistant.sections(visible, if (showHidden) emptyList() else pinned, domain, query)
        val now = listOf(groups, pinned, showHidden)
        if (now == shown) return
        shown = now
        sections.removeAllViews()
        if (groups.isEmpty()) {
            sections.addView(pcPageMessage(ui, if (showHidden) "Nothing hidden" else "Nothing matches").apply {
                setPadding(0, ui.dp(48), 0, 0)
            })
        }
        for ((title, members) in groups) {
            sections.addView(ui.sectionTitle(title).apply { setPadding(ui.dp(10), ui.dp(12), 0, ui.dp(2)) })
            val grid = GridLayout(context).apply { columnCount = columns.coerceAtLeast(1) }
            for (entity in members) {
                grid.addView(tile(entity, entity.id in pinned), GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
                ).apply {
                    width = 0
                    height = ui.sdp(TILE_HEIGHT_DP)
                    setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
                })
            }
            // Keep the tiles of a short last row as wide as the others.
            repeat((columns - members.size % columns) % columns) {
                grid.addView(View(context), GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                    .apply { width = 0; height = ui.sdp(TILE_HEIGHT_DP) })
            }
            sections.addView(grid)
        }
    }

    private var chipsShown: Any? = null

    /**
     * All, a chip per kind in [list], Everything when the page shows only the main entities
     * ([canWiden]; on, it shows the rest too), and Hidden when some are.
     */
    private fun renderChips(list: List<HaEntity>, hiddenCount: Int, canWiden: Boolean) {
        val present = HomeAssistant.DOMAINS.keys.filter { d -> list.any { it.domain == d } }
        if (showHidden && hiddenCount == 0) showHidden = false
        if (!canWiden) everything = false
        if (domain != null && domain !in present) domain = null
        val key = listOf(present, hiddenCount, domain, showHidden, canWiden, everything)
        if (key == chipsShown) return
        chipsShown = key
        chips.removeAllViews()
        fun chip(text: String, icon: String, selected: Boolean, onClick: () -> Unit) =
            chips.addView(ui.chip(text, icon, if (selected) BLUE else null, onClick))
        chip("All", "home", domain == null && !showHidden) { domain = null; showHidden = false; render() }
        for (d in present) {
            val kind = HomeAssistant.DOMAINS.getValue(d)
            chip(kind.title, kind.icon, domain == d && !showHidden) { domain = d; showHidden = false; render() }
        }
        if (canWiden) chip("Everything", "add", everything) { everything = !everything; showHidden = false; render() }
        if (hiddenCount > 0) chip("Hidden ($hiddenCount)", "close", showHidden) { showHidden = true; domain = null; render() }
    }

    private fun color(entity: HaEntity) = when (entity.domain) {
        "light" -> AMBER
        "lock", "climate" -> RED
        "scene", "script", "automation" -> GREEN
        else -> BLUE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun tile(entity: HaEntity, pinned: Boolean): View {
        val color = color(entity)
        val lit = entity.active
        val cap = KeyCap(ui, color, lit = lit)
        val ink = if (lit) inkOn(color) else TEXT
        val dim = !entity.available
        val key = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cap
            alpha = if (dim) 0.5f else 1f
            val side = cap.insetSide.toInt() + ui.dp(12)
            setPadding(side, cap.insetTop.toInt() + ui.dp(10), side, cap.insetBottom.toInt() + ui.dp(8))
            contentDescription = "${entity.name}, ${HomeAssistant.stateText(entity)}"
        }
        key.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.icon(HomeAssistant.DOMAINS[entity.domain]?.icon ?: "home", if (lit) ink else Ui.legendTone(color)),
                LinearLayout.LayoutParams(ui.sdp(24), ui.sdp(24)).apply { marginEnd = ui.dp(10) })
            addView(TextView(context).apply {
                text = HomeAssistant.stateText(entity)
                typeface = ui.fontHint
                setTextColor(if (lit) withAlpha(ink, 0.8f) else MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f * ui.scale)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (pinned) addView(ui.icon("star", if (lit) ink else MUTED), LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)))
        })
        key.addView(TextView(context).apply {
            text = entity.name
            typeface = ui.fontLabel
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * ui.scale)
            maxLines = 2
            setLineSpacing(0f, 0.92f)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.BOTTOM
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // The face drops while touched, like a key; the click and the long press are the view's own.
        key.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> press(v as ViewGroup, cap, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(v as ViewGroup, cap, false)
            }
            false
        }
        key.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            tap(entity)
        }
        key.setOnLongClickListener { menu(entity); true }
        return key
    }

    private fun press(key: ViewGroup, cap: KeyCap, down: Boolean) {
        for (i in 0 until key.childCount) key.getChildAt(i).translationY = if (down) cap.travel else 0f
    }

    private fun tap(entity: HaEntity) {
        if (!entity.available) return ui.toast("${entity.name} is unavailable in Home Assistant")
        val service = HomeAssistant.tap(entity)
        when {
            service != null -> call(entity, service.first, service.second, HomeAssistant.tapped(entity))
            entity.domain == "lock" -> {
                val unlock = entity.state == "locked"
                ui.dialog()
                    .setTitle("${if (unlock) "Unlock" else "Lock"} ${entity.name}?")
                    .setPositiveButton(if (unlock) "Unlock" else "Lock") { _, _ ->
                        call(entity, "lock", if (unlock) "unlock" else "lock", null)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> menu(entity)
        }
    }

    private fun menu(entity: HaEntity) {
        val pinned = settings.haPinned
        val hidden = settings.haHidden
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += (if (entity.id in pinned) "Unpin" else "Pin to the top") to {
            settings.haPinned = if (entity.id in pinned) pinned - entity.id else pinned + entity.id
            render()
        }
        when (entity.domain) {
            "automation" -> items += "Run it now" to { call(entity, "automation", "trigger", null) }
            "climate", "media_player", "fan", "light" -> {
                items += "Turn on" to { call(entity, entity.domain, "turn_on", null) }
                items += "Turn off" to { call(entity, entity.domain, "turn_off", null) }
            }
            "cover" -> {
                items += "Open" to { call(entity, "cover", "open_cover", null) }
                items += "Close" to { call(entity, "cover", "close_cover", null) }
                items += "Stop" to { call(entity, "cover", "stop_cover", null) }
            }
        }
        items += (if (entity.id in hidden) "Show on this page" else "Hide from this page") to {
            settings.haHidden = if (entity.id in hidden) hidden - entity.id else hidden + entity.id
            render()
        }
        ui.dialog()
            .setTitle(entity.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** Calls a service for [entity], showing [expect] meanwhile; reads the states again after. */
    private fun call(entity: HaEntity, domain: String, service: String, expect: HaEntity?) {
        if (expect != null && expect != entity) {
            expected[entity.id] = expect to System.currentTimeMillis() + EXPECT_MS
            render()
        }
        if (network.isShutdown) return
        network.execute {
            val failure = runCatching { NetActions.run(Step.Ha(domain, service, entity.id), secret) { it } }.exceptionOrNull()
            handler.post {
                if (failure != null) {
                    expected.remove(entity.id)
                    ui.toast("${entity.name}: ${(failure as? IOException)?.message ?: "Home Assistant didn't answer"}")
                    render()
                }
                handler.postDelayed({ refresh() }, 400)
            }
        }
    }

    private companion object {
        const val TAG = "HomeAssistantView"
        const val TILE_HEIGHT_DP = 96
        const val POLL_MS = 10_000L
        const val LOOKUP_MS = 10 * 60_000L
        /** How long a tapped entity shows its new state while Home Assistant hasn't reported it. */
        const val EXPECT_MS = 4_000L
    }
}
