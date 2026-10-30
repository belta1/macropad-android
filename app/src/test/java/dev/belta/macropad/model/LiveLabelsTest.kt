package dev.belta.macropad.model

import dev.belta.macropad.pc.PcMedia
import dev.belta.macropad.pc.PcShell
import dev.belta.macropad.pc.PcStatus
import dev.belta.macropad.pc.PcVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLabelsTest {

    @Test
    fun `plain labels stay as they are`() {
        assertFalse(LiveLabels.isLive("Copy {x}"))
        assertEquals("Copy {x}", LiveLabels.expand("Copy {x}", PcStatus()))
        assertTrue(LiveLabels.isLive("{venv|Which Python}\n{folder}"))
    }

    @Test
    fun `unknown values show the fallback and empty lines go`() {
        val python = "{venv|Which Python}\n{folder}"
        assertEquals("Which Python", LiveLabels.expand(python, null)) // no PC link
        assertEquals("Which Python", LiveLabels.expand(python, PcStatus())) // no shell hook
        assertEquals("—", LiveLabels.expand("{pcclipboard:3|—}", PcStatus(clips = listOf("a", "b"))))
    }

    @Test
    fun `the terminal's venv and folder`() {
        val python = "{venv|Which Python}\n{folder}"
        assertEquals("No venv\nshop", LiveLabels.expand(python, PcStatus(shell = PcShell("C:\\code\\shop", null, null))))
        assertEquals(".venv · 3.12.1\nshop", LiveLabels.expand(python,
            PcStatus(shell = PcShell("C:\\code\\shop\\", "C:\\code\\shop\\.venv", "3.12.1"))))
        assertEquals("C:", LiveLabels.expand("{folder}", PcStatus(shell = PcShell("C:\\", null, null))))
    }

    @Test
    fun `clipboard entries on one line`() {
        val status = PcStatus(clips = listOf("def f():\n\treturn 1", "second"))
        assertEquals("def f(): return 1", LiveLabels.expand("{pcclipboard}", status))
        assertEquals("second", LiveLabels.expand("{pcclipboard:2|—}", status))
    }

    @Test
    fun `what's playing, and nothing playing once the computer says so`() {
        val label = "{title|Now playing}\n{artist}"
        assertEquals("Now playing", LiveLabels.expand(label, PcStatus()))
        assertEquals("Nothing playing", LiveLabels.expand(label, PcStatus(hasMedia = true)))
        val playing = PcStatus(hasMedia = true, media = PcMedia("Song", "Band", playing = true))
        assertEquals("Song\nBand", LiveLabels.expand(label, playing))
        assertEquals("Song — Band", LiveLabels.expand("{media}", playing))
    }

    @Test
    fun `the next meeting, and when the join key lights`() {
        val now = java.util.Date(1_000_000_000_000)
        fun meeting(inMinutes: Long, under: Boolean = false) = PcStatus(hasOutlook = true, meeting =
            dev.belta.macropad.net.NetActions.CalendarEvent("Standup", java.util.Date(now.time + inMinutes * 60_000), null, under, hasJoin = true))
        val label = "{meeting|Join meeting}"
        assertEquals("Join meeting", LiveLabels.expand(label, PcStatus(), now))
        assertTrue(LiveLabels.expand(label, meeting(30), now).startsWith("Standup · "))
        assertEquals("Standup · now", LiveLabels.expand(label, meeting(-5, under = true), now))
        assertFalse(LiveLabels.meetingSoon(meeting(30), now))
        assertTrue(LiveLabels.meetingSoon(meeting(4), now))
        assertTrue(LiveLabels.meetingSoon(meeting(-5, under = true), now))
        assertFalse(LiveLabels.meetingSoon(meeting(4).copy(hasOutlook = false), now)) // Outlook closed
    }

    @Test
    fun `volume and the app in front`() {
        assertEquals("42% · code", LiveLabels.expand("{volume} · {app}", PcStatus(app = "code", volume = PcVolume(42, false))))
        assertEquals("Muted", LiveLabels.expand("{volume}", PcStatus(volume = PcVolume(42, true))))
    }

    @Test
    fun `the call's time, from when Teams said the call started`() {
        val now = java.util.Date(1_000_000_000L)
        val inCall = PcStatus(callStartedAt = now.time - 65_000)
        assertEquals("Leave call\n1:05", LiveLabels.expand(DefaultLayout.LEAVE_CALL_LABEL, inCall, now))
        assertEquals("Leave call", LiveLabels.expand(DefaultLayout.LEAVE_CALL_LABEL, PcStatus(), now)) // no call: no second line
        assertEquals("1:02:09", LiveLabels.duration(3729))
        assertTrue(LiveLabels.isTicking(DefaultLayout.LEAVE_CALL_LABEL))
        assertFalse(LiveLabels.isTicking("{meeting}"))

        // The tablet notes when a call starts, keeps it while it lasts, and drops it after.
        val teams = dev.belta.macropad.pc.TeamsState(meeting = true)
        val started = dev.belta.macropad.pc.PcProtocol.apply(PcStatus(), dev.belta.macropad.pc.PcMessage.Teams(teams))
        val later = dev.belta.macropad.pc.PcProtocol.apply(started, dev.belta.macropad.pc.PcMessage.Teams(teams.copy(muted = true)))
        assertEquals(started.callStartedAt, later.callStartedAt)
        assertEquals(null, dev.belta.macropad.pc.PcProtocol.apply(later, dev.belta.macropad.pc.PcMessage.Teams(teams.copy(meeting = false))).callStartedAt)
    }

    @Test
    fun `an untouched Leave key gets the call's time, an edited one doesn't`() {
        val pages = DefaultLayout.layout().pages.onEach { page ->
            page.buttons.filterNotNull().filter { it.label == DefaultLayout.LEAVE_CALL_LABEL }.forEach { it.label = "Leave call" }
        }
        assertTrue(DefaultLayout.callTimerLabel(pages))
        assertTrue(pages.first { it.name == DefaultLayout.TEAMS_PAGE }.buttons.any { it?.label == DefaultLayout.LEAVE_CALL_LABEL })
        assertFalse(DefaultLayout.callTimerLabel(pages)) // once
    }
}
