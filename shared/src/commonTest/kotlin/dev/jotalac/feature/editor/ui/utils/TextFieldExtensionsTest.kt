package dev.jotalac.feature.editor.ui.utils

import dev.jotalac.testing.fieldOf
import dev.jotalac.testing.notation
import kotlin.test.Test
import kotlin.test.assertEquals

class TextFieldExtensionsTest {

    @Test
    fun `bold without a selection puts the cursor between the markers`() {
        assertEquals("make **|**", fieldOf("make |").applyBold().notation())
    }

    @Test
    fun `bold wraps the selection and puts the cursor after it`() {
        assertEquals("**hello**| world", fieldOf("«hello» world").applyBold().notation())
    }

    @Test
    fun `a backwards selection is wrapped the same way`() {
        // selecting with shift + left arrow (or dragging backwards) gives a reversed selection
        assertEquals("**hello**| world", fieldOf("«hello» world", reversed = true).applyBold().notation())
    }

    @Test
    fun `code block puts the selected code on its own lines`() {
        assertEquals("```\nval x = 1\n```|", fieldOf("«val x = 1»").applyCodeBlock().notation())
    }

    @Test
    fun `link without a selection puts the cursor where the link text goes`() {
        assertEquals("see [|]()", fieldOf("see |").addLink().notation())
    }

    @Test
    fun `the checkbox button inserts a valid task list item`() {
        // markdown only renders a checkbox with the space after "- [ ]", list continuation needs it too
        assertEquals("- [ ] |", fieldOf("|").applyCheckbox().notation())
    }
}
