package dev.belta.macropad.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeFilterTest {

    private fun mail(from: String, address: String? = null) = PcNotice("mail", 1, from, "Budget", address)

    @Test
    fun `reads the list by lines and commas`() {
        assertEquals(listOf("Ana Pérez", "boss@example.com", "Luis"),
            NoticeFilter.parseSenders(" Ana Pérez, boss@example.com\n\nLuis ; Ana Pérez"))
    }

    @Test
    fun `shows mail from people on the list, by part of the name or the whole address`() {
        val filter = NoticeFilter(teams = false, senders = listOf("perez", "boss@example.com"))
        assertTrue(filter.shows(mail("Ana Pérez")))
        assertTrue(filter.shows(mail("Someone", "Boss@Example.com")))
        assertFalse(filter.shows(mail("Someone", "other@example.com")))
        assertFalse(filter.shows(mail("boss")))
    }

    @Test
    fun `Teams notices follow their switch, and mail needs someone on the list`() {
        val teams = PcNotice("teams", 2, "Ana", "hola")
        assertTrue(NoticeFilter(teams = true, senders = emptyList()).shows(teams))
        assertFalse(NoticeFilter(teams = false, senders = listOf("Ana")).shows(teams))
        assertFalse(NoticeFilter(teams = true, senders = emptyList()).wantsMail)
        assertFalse(NoticeFilter(teams = true, senders = emptyList()).shows(mail("Ana")))
    }
}
