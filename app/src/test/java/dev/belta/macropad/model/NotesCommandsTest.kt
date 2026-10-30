package dev.belta.macropad.model

import dev.belta.macropad.model.NotesCommands.Part
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesCommandsTest {
    @Test
    fun `commands between the text, with or without Enter`() {
        val notes = "Primero creamos el entorno:\n> python -m venv .venv\n  >> .venv\\Scripts\\activate\n\nY después:\nexplicar pip\n>   \n> pip list"
        assertEquals(listOf(
            Part.Text("Primero creamos el entorno:"),
            Part.Command("python -m venv .venv", enter = true),
            Part.Command(".venv\\Scripts\\activate", enter = false),
            Part.Text("Y después:\nexplicar pip"),
            Part.Command("pip list", enter = true),
        ), NotesCommands.parse(notes))
    }

    @Test
    fun `no commands - just the text`() {
        assertEquals(listOf(Part.Text("a > b\nc")), NotesCommands.parse("a > b\nc\n"))
        assertEquals(listOf(Part.Text("a")), NotesCommands.parse("a\n>  ")) // an empty ">" is dropped
        assertEquals(emptyList<Part>(), NotesCommands.parse(""))
    }

    @Test
    fun `undoes PowerPoint's AutoCorrect`() {
        assertEquals("""python -c "print('hola')" --version ...""",
            NotesCommands.typable("python -c “print(‘hola’)” –version …"))
        assertEquals("git log --oneline", NotesCommands.typable("git log —oneline"))
        assertEquals("a – b", NotesCommands.typable("a – b")) // a dash between words stays
    }
}
