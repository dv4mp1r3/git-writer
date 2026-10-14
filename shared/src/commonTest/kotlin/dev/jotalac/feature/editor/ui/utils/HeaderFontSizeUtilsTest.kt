package dev.jotalac.feature.editor.ui.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class HeaderFontSizeUtilsTest {

    @Test
    fun `heading level is the number of hashes`() {
        for (level in 1..6) {
            assertEquals(level, getHeaderLevel("#".repeat(level) + " Title"), "level $level")
        }
    }

    @Test
    fun `seven hashes is not a heading`() {
        // markdown only has six heading levels
        assertEquals(0, getHeaderLevel("####### Title"))
    }

    @Test
    fun `hashes without a space after them are not a heading`() {
        assertEquals(0, getHeaderLevel("#hashtag"))
    }

    @Test
    fun `a hash later in the line is not a heading`() {
        assertEquals(0, getHeaderLevel("Title # not a heading"))
    }
}
