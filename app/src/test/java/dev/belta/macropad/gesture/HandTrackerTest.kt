package dev.belta.macropad.gesture

import dev.belta.macropad.gesture.HandTracker.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandTrackerTest {

    /** An upright hand facing the camera, wrist at the bottom; [at] moves the whole hand. */
    private fun hand(
        index: Boolean = false, middle: Boolean = false, ring: Boolean = false, pinky: Boolean = false,
        thumbOut: Boolean = false, pinch: Boolean = false, pointRight: Boolean = false,
        builtIn: String? = null, left: Boolean = false, at: Point = Point(0f, 0f),
    ): Hand {
        val p = MutableList(21) { Point(0f, 0f) }
        p[0] = Point(0.5f, 0.8f)
        fun finger(base: Int, x: Float, straight: Boolean) {
            p[base] = Point(x, 0.6f)
            if (straight) { p[base + 1] = Point(x, 0.52f); p[base + 2] = Point(x, 0.47f); p[base + 3] = Point(x, 0.42f) }
            else { p[base + 1] = Point(x, 0.55f); p[base + 2] = Point(x, 0.6f); p[base + 3] = Point(x, 0.63f) }
        }
        finger(5, 0.44f, index)
        finger(9, 0.5f, middle)
        finger(13, 0.56f, ring)
        finger(17, 0.62f, pinky)
        if (pointRight) { p[6] = Point(0.52f, 0.6f); p[7] = Point(0.6f, 0.6f); p[8] = Point(0.68f, 0.6f) }
        p[1] = Point(0.42f, 0.75f)
        p[2] = Point(0.38f, 0.7f)
        p[3] = if (thumbOut) Point(0.33f, 0.66f) else Point(0.45f, 0.66f)
        p[4] = when {
            pinch -> p[8]
            thumbOut -> Point(0.28f, 0.62f)
            else -> Point(0.5f, 0.62f)
        }
        return Hand(p.map { Point(it.x + at.x, it.y + at.y) }, left, builtIn)
    }

    @Test
    fun `names the extra hand shapes`() {
        assertEquals("Rock", HandShapes.shape(hand(index = true, pinky = true).points))
        assertEquals("Call_Me", HandShapes.shape(hand(pinky = true, thumbOut = true).points))
        assertEquals("Three", HandShapes.shape(hand(index = true, middle = true, ring = true).points))
        assertEquals("Four", HandShapes.shape(hand(index = true, middle = true, ring = true, pinky = true).points))
        assertEquals("OK", HandShapes.shape(hand(middle = true, ring = true, pinky = true, pinch = true).points))
        assertEquals(HandShapes.PINCH, HandShapes.shape(hand(pinch = true).points))
        assertEquals("Point_Right", HandShapes.shape(hand(index = true, pointRight = true).points))
        assertNull(HandShapes.shape(hand().points)) // a fist: MediaPipe's job
    }

    @Test
    fun `trusts MediaPipe's own poses first`() {
        assertEquals("Open_Palm", HandShapes.pose(hand(index = true, middle = true, ring = true, pinky = true, builtIn = "Open_Palm")))
        assertEquals("Three", HandShapes.pose(hand(index = true, middle = true, ring = true, builtIn = "Victory")))
        assertEquals("Victory", HandShapes.pose(hand(index = true, middle = true, builtIn = "Victory")))
    }

    @Test
    fun `a pose fires once after being held still`() {
        val tracker = HandTracker(holdMs = 500)
        val rock = hand(index = true, pinky = true, left = true)
        assertTrue(tracker.update(0, rock).isEmpty())
        assertTrue(tracker.update(300, rock).isEmpty())
        assertEquals(listOf(Event.Pose("Rock", left = true)), tracker.update(600, rock))
        assertTrue(tracker.update(900, rock).isEmpty())
        tracker.update(1000, null) // hand gone
        tracker.update(1100, rock)
        assertEquals(listOf(Event.Pose("Rock", left = true)), tracker.update(1700, rock))
    }

    @Test
    fun `a moving hand doesn't count as a held pose`() {
        val tracker = HandTracker(holdMs = 500)
        for (t in 0..10) {
            val events = tracker.update(t * 100L, hand(index = true, pinky = true, at = Point(t * 0.02f, 0f)))
            assertTrue(events.none { it is Event.Pose })
        }
    }

    @Test
    fun `an open hand moved quickly sideways is a swipe, not a palm`() {
        val tracker = HandTracker(holdMs = 500)
        val events = (0..4).flatMap { t ->
            tracker.update(t * 100L, hand(index = true, middle = true, ring = true, pinky = true, builtIn = "Open_Palm", at = Point(t * 0.08f, 0f)))
        }
        assertEquals(listOf(Event.Swipe("Swipe_Right")), events)
        // Held still afterwards, the same palm doesn't fire.
        val after = (5..12).flatMap { t ->
            tracker.update(t * 100L, hand(index = true, middle = true, ring = true, pinky = true, builtIn = "Open_Palm", at = Point(0.32f, 0f)))
        }
        assertTrue(after.isEmpty())
    }

    @Test
    fun `pinch and drag gives one slider step per stretch of travel`() {
        val tracker = HandTracker()
        tracker.update(0, hand(pinch = true))
        tracker.update(100, hand(pinch = true))
        val up = tracker.update(200, hand(pinch = true, at = Point(0f, -0.11f)))
        assertEquals(listOf(Event.Drag(up = true), Event.Drag(up = true)), up)
        val down = tracker.update(300, hand(pinch = true, at = Point(0f, -0.05f)))
        assertEquals(listOf(Event.Drag(up = false)), down)
        assertTrue(tracker.update(400, hand(pinch = true, at = Point(0f, -0.05f))).isEmpty())
    }
}
