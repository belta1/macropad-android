package dev.belta.macropad.gesture

import kotlin.math.abs
import kotlin.math.hypot

/** A point in the upright, mirrored camera image: y from 0 (top) to 1, x from 0 to width / height. */
data class Point(val x: Float, val y: Float)

/**
 * One hand in one frame: its 21 MediaPipe landmarks (0 wrist; thumb 1–4; index 5–8; middle 9–12;
 * ring 13–16; pinky 17–20, each from knuckle to tip), which hand it is, and MediaPipe's own pose
 * name ("Open_Palm", …) when it was sure.
 */
class Hand(val points: List<Point>, val left: Boolean, val builtIn: String?)

/** Hand shapes MediaPipe doesn't name, worked out from the landmarks. */
object HandShapes {
    const val PINCH = "Pinch"

    /** MediaPipe's well-trained poses win over the shapes below. */
    private val TRUSTED = setOf("Open_Palm", "Closed_Fist", "Thumb_Up", "Thumb_Down", "ILoveYou")

    fun pose(hand: Hand): String? {
        if (hand.builtIn in TRUSTED) return hand.builtIn
        return shape(hand.points) ?: hand.builtIn
    }

    /** "OK", "Rock", "Call_Me", "Three", "Four", "Point_Left", "Point_Right", [PINCH] or null. */
    fun shape(p: List<Point>): String? {
        if (p.size < 21) return null
        val size = dist(p[0], p[9]) // wrist to middle knuckle: the hand's scale
        if (size <= 0f) return null
        val index = extended(p, 8, 6)
        val middle = extended(p, 12, 10)
        val ring = extended(p, 16, 14)
        val pinky = extended(p, 20, 18)
        val thumb = dist(p[4], p[17]) > dist(p[2], p[17]) * 1.15f && dist(p[4], p[5]) > 0.35f * size
        val touching = dist(p[4], p[8]) < 0.3f * size
        return when {
            touching && middle && ring && pinky -> "OK"
            touching -> PINCH
            index && pinky && !middle && !ring && !thumb -> "Rock"
            thumb && pinky && !index && !middle && !ring -> "Call_Me"
            index && middle && ring && pinky && !thumb -> "Four"
            index && middle && ring && !pinky -> "Three"
            index && !middle && !ring && !pinky -> {
                val dx = p[8].x - p[5].x
                val dy = p[8].y - p[5].y
                when {
                    abs(dx) < 1.5f * abs(dy) -> null // up or down: MediaPipe's Pointing_Up covers up
                    dx < 0 -> "Point_Left"
                    else -> "Point_Right"
                }
            }
            else -> null
        }
    }

    /** Open hand, for swipes: MediaPipe's open palm, or four straight fingers. */
    fun open(hand: Hand): Boolean {
        val p = hand.points
        return hand.builtIn == "Open_Palm" ||
            (p.size >= 21 && extended(p, 8, 6) && extended(p, 12, 10) && extended(p, 16, 14) && extended(p, 20, 18))
    }

    /** A finger is straight when its tip is clearly farther from the wrist than its middle joint. */
    private fun extended(p: List<Point>, tip: Int, joint: Int) = dist(p[0], p[tip]) > dist(p[0], p[joint]) * 1.15f

    fun center(p: List<Point>) = Point((p[0].x + p[5].x + p[9].x + p[13].x + p[17].x) / 5, (p[0].y + p[5].y + p[9].y + p[13].y + p[17].y) / 5)

    fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
}

/**
 * Turns hands frame by frame into gestures:
 * - a pose held still for [holdMs] fires once (again only after the hand changes pose or leaves);
 * - an open hand moved quickly across a quarter of the view is a swipe;
 * - a pinch moved up or down is a slider, one step per [DRAG_STEP] of travel.
 * Pure logic (no camera), so it can be tested with made-up hands.
 */
class HandTracker(private val holdMs: Long = 500) {

    sealed class Event {
        data class Pose(val id: String, val left: Boolean) : Event()
        data class Swipe(val id: String) : Event()
        data class Drag(val up: Boolean) : Event()
    }

    private var candidate: Pair<String, Boolean>? = null
    private var since = 0L
    private var anchor: Point? = null
    private var fired = false

    private val trail = ArrayDeque<Pair<Long, Point>>()
    private var swipeBlockedUntil = 0L

    private var dragging = false
    private var pinchFrames = 0
    private var dragAnchor = 0f

    fun update(now: Long, hand: Hand?): List<Event> {
        if (hand == null || hand.points.size < 21) {
            reset()
            return emptyList()
        }
        val events = mutableListOf<Event>()
        val center = HandShapes.center(hand.points)
        val size = HandShapes.dist(hand.points[0], hand.points[9])
        val pose = HandShapes.pose(hand)

        // Pinch and drag.
        if (pose == HandShapes.PINCH) {
            val y = (hand.points[4].y + hand.points[8].y) / 2
            if (!dragging && ++pinchFrames >= 2) {
                dragging = true
                dragAnchor = y
            }
            if (dragging) {
                while (dragAnchor - y >= DRAG_STEP) { events += Event.Drag(up = true); dragAnchor -= DRAG_STEP }
                while (y - dragAnchor >= DRAG_STEP) { events += Event.Drag(up = false); dragAnchor += DRAG_STEP }
            }
            trail.clear()
            candidate = null
            return events
        }
        dragging = false
        pinchFrames = 0

        // Swipes: an open hand travelling fast and mostly in one direction.
        if (HandShapes.open(hand) && now >= swipeBlockedUntil) {
            trail.addLast(now to center)
            while (trail.isNotEmpty() && now - trail.first().first > SWIPE_MS) trail.removeFirst()
            val start = trail.first().second
            val dx = center.x - start.x
            val dy = center.y - start.y
            val swipe = when {
                abs(dx) >= SWIPE_DISTANCE && abs(dx) > 2 * abs(dy) -> if (dx < 0) "Swipe_Left" else "Swipe_Right"
                abs(dy) >= SWIPE_DISTANCE && abs(dy) > 2 * abs(dx) -> if (dy < 0) "Swipe_Up" else "Swipe_Down"
                else -> null
            }
            if (swipe != null) {
                events += Event.Swipe(swipe)
                trail.clear()
                swipeBlockedUntil = now + SWIPE_PAUSE_MS
                // The open hand that swiped mustn't also count as a held palm.
                candidate = pose?.let { it to hand.left }
                fired = true
                return events
            }
        } else {
            trail.clear()
        }

        // Poses: the same pose, held still.
        val key = pose?.let { it to hand.left }
        if (key != candidate) {
            candidate = key
            since = now
            anchor = center
            fired = false
            return events
        }
        if (key == null || fired) return events
        if (HandShapes.dist(center, anchor!!) > STILL * size) {
            since = now
            anchor = center
            return events
        }
        if (now - since >= holdMs) {
            fired = true
            events += Event.Pose(key.first, key.second)
        }
        return events
    }

    private fun reset() {
        candidate = null
        fired = false
        dragging = false
        pinchFrames = 0
        trail.clear()
    }

    companion object {
        /** Slider travel per step, as a share of the view's height. */
        const val DRAG_STEP = 0.05f
        const val SWIPE_DISTANCE = 0.25f
        const val SWIPE_MS = 700L
        const val SWIPE_PAUSE_MS = 800L
        /** How far (in hand sizes) a held pose may drift. */
        const val STILL = 0.3f
    }
}
