package dev.belta.macropad.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class TextVariablesTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 21, 14, 5, 9) }.time

    @Test
    fun `date and time`() {
        assertEquals("2026-10-21 14:05", TextVariables.expand("{date} {time}", now))
        assertEquals("21/10/2026", TextVariables.expand("{date:dd/MM/yyyy}", now))
        assertEquals("14:05:09", TextVariables.expand("{time:HH:mm:ss}", now))
    }

    @Test
    fun `clipboard, escapes and unknown names`() {
        assertEquals("Ref: ABC-1", TextVariables.expand("Ref: {clipboard}", now) { "ABC-1" })
        assertEquals("Ref: ", TextVariables.expand("Ref: {clipboard}", now) { null })
        assertEquals("{date} {name}", TextVariables.expand("{{date} {name}", now))
        assertEquals("{date:qq}", TextVariables.expand("{date:qq}", now)) // invalid pattern stays as typed
    }

    @Test
    fun `page steps parse and describe`() {
        assertEquals(listOf(Step.Page("next")), Macro.parse("page:next"))
        assertEquals(listOf(Step.Page("Teams")), Macro.parse("page: Teams"))
        assertEquals("Next page", Macro.describe("page:next"))
        assertEquals("Page Teams", Macro.describe("page:Teams"))
    }

    @Test
    fun `secret values are filled in and listed`() {
        val text = "{secret:work_user} / {secret: work_password}"
        assertEquals(listOf("work_user", "work_password"), TextVariables.secretNames(text))
        assertEquals("me / ", TextVariables.expand(text, secret = { if (it == "work_user") "me" else null }))
    }
}
