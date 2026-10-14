package dev.jotalac.feature.editor.ui.utils

import dev.jotalac.testing.fieldOf
import dev.jotalac.testing.notation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownTextModificationUtilsTest {

    /** What pressing enter does in a list, null when the cursor is not in one. */
    private fun pressEnter(notation: String): String? =
        handleMarkdownListContinuation(fieldOf(notation))?.notation()

    private fun isInsideCode(notation: String): Boolean {
        val value = fieldOf(notation)
        return isInsideCodeBlock(value.text, value.selection.start)
    }

    // --- list continuation

    @Test
    fun `enter in a bullet item starts the next bullet`() {
        assertEquals("- milk\n- |", pressEnter("- milk|"))
    }

    @Test
    fun `enter keeps the indentation of a nested item`() {
        assertEquals("- food\n  - milk\n  - |", pressEnter("- food\n  - milk|"))
    }

    @Test
    fun `enter in the middle of an item moves the rest of it to the new item`() {
        assertEquals("- mi\n- |lk", pressEnter("- mi|lk"))
    }

    @Test
    fun `a checked checkbox continues with an unchecked one`() {
        assertEquals("- [x] done\n- [ ] |", pressEnter("- [x] done|"))
    }

    @Test
    fun `numbered items continue with 1 because markdown numbers the list itself`() {
        assertEquals("1. first\n2. second\n1. |", pressEnter("1. first\n2. second|"))
    }

    @Test
    fun `a quote continues with a line break that markdown keeps`() {
        // without the two trailing spaces markdown would join both lines into one
        assertEquals("> quote  \n> |", pressEnter("> quote|"))
    }

    @Test
    fun `enter on an empty item ends the list`() {
        assertEquals("- milk\n|", pressEnter("- milk\n- |"))
        assertEquals("- [ ] task\n|", pressEnter("- [ ] task\n- [ ] |"))
        assertEquals("1. first\n|", pressEnter("1. first\n1. |"))
        assertEquals("> quote  \n|", pressEnter("> quote  \n> |"))
    }

    @Test
    fun `ending a list keeps the cursor on the emptied line when more items follow`() {
        assertEquals("- a\n|\n- b", pressEnter("- a\n- |\n- b"))
    }

    @Test
    fun `enter outside of a list is left to the editor`() {
        // null means nothing was continued, so the editor splits the block instead
        assertNull(pressEnter("plain text|"))
        assertNull(pressEnter("-no space so not a list|"))
    }

    // --- code blocks

    @Test
    fun `cursor after an opening fence is inside a code block`() {
        assertTrue(isInsideCode("```kotlin\nval x = 1|"))
    }

    @Test
    fun `cursor after a closed code block is outside of it`() {
        assertFalse(isInsideCode("```\ncode\n```\ntext|"))
    }

    @Test
    fun `cursor before the opening fence is outside of the code block`() {
        assertFalse(isInsideCode("text|\n```\ncode"))
    }

    // --- line break inside a block

    @Test
    fun `a line break inside a block ends the line with two spaces so markdown keeps it`() {
        assertEquals("first  \n|second", handleNewLineWithinBlock(fieldOf("first|second")).notation())
    }

    // --- indentation (tab / shift + tab)

    @Test
    fun `tab indents only the line with the cursor`() {
        assertEquals("a\n    b|\nc", fieldOf("a\nb|\nc").increaseIndentation().notation())
    }

    @Test
    fun `tab indents every selected line and keeps them selected`() {
        assertEquals(
            "    «one\n    two»\nthree",
            fieldOf("«one\ntwo»\nthree").increaseIndentation().notation()
        )
    }

    @Test
    fun `a backwards selection stays backwards after indenting`() {
        val indented = fieldOf("«one\ntwo»", reversed = true).increaseIndentation()

        assertEquals("    «one\n    two»", indented.notation())
        assertTrue(indented.selection.reversed)
    }

    @Test
    fun `shift tab removes at most four spaces`() {
        assertEquals("  deep|", fieldOf("      deep|").decreaseIndentation().notation())
    }

    @Test
    fun `shift tab removes a tab character`() {
        assertEquals("item|", fieldOf("\titem|").decreaseIndentation().notation())
    }

    @Test
    fun `shift tab with the cursor inside the removed indentation moves it to the line start`() {
        assertEquals("|text", fieldOf("  |  text").decreaseIndentation().notation())
    }

    @Test
    fun `shift tab on a line without indentation changes nothing`() {
        val value = fieldOf("text|")

        assertEquals(value, value.decreaseIndentation())
    }
}
