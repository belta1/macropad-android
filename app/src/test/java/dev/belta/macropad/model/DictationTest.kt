package dev.belta.macropad.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTest {
    @Test
    fun `spoken punctuation in Spanish and English`() {
        assertEquals("hola, cómo estás?", Dictation.marks("hola coma cómo estás signo de interrogación"))
        assertEquals("listo.\nsaludos", Dictation.marks("listo punto y aparte saludos"))
        assertEquals("ver: la lista", Dictation.marks("ver dos puntos la lista"))
        assertEquals("¿vienes?", Dictation.marks("abre signo de interrogación vienes cierra signo de interrogación"))
        assertEquals("see you, bye.", Dictation.marks("see you comma bye period"))
        assertEquals("puntual", Dictation.marks("puntual")) // a word starting like a mark stays
    }

    @Test
    fun `the Dictate key - a step of its own, in the dock's first free slot once`() {
        assertEquals(listOf(Step.Tablet("dictate")), Macro.parse("tablet:dictate"))
        assertEquals("Dictation", Macro.describe("tablet:dictate"))
        assertEquals("Dims tablet", Macro.describe("tablet:dim"))
        val dock = DefaultLayout.dock()
        dock.buttons.replaceAll { if (it?.action == "tablet:dictate") null else it } // an older dock
        org.junit.Assert.assertTrue(DefaultLayout.addDictate(dock))
        org.junit.Assert.assertFalse(DefaultLayout.addDictate(dock))
        assertEquals(1, dock.buttons.count { it?.action == "tablet:dictate" })
    }

    @Test
    fun `phrases join with spaces and capitals`() {
        var text = ""
        text = Dictation.join(text, "hola equipo coma")
        text = Dictation.join(text, "el informe está listo punto")
        text = Dictation.join(text, "lo reviso mañana punto y aparte")
        text = Dictation.join(text, "saludos")
        assertEquals("Hola equipo, el informe está listo. Lo reviso mañana.\nSaludos", text)
        assertEquals("Hola", Dictation.join("Hola", "   "))
    }
}
