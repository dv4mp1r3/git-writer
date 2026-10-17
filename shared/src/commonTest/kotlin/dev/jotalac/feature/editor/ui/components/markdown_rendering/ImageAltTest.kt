package dev.jotalac.feature.editor.ui.components.markdown_rendering

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageAltTest {

    @Test
    fun `a width after the bar sizes the image and is not part of the alt text`() {
        assertEquals(ImageAlt(text = "photo", width = 300), parseImageAlt("photo|300"))
    }

    @Test
    fun `a width and a height are both read`() {
        assertEquals(ImageAlt(text = "photo", width = 300, height = 200), parseImageAlt("photo|300x200"))
    }

    @Test
    fun `an image can have a size without an alt text`() {
        assertEquals(ImageAlt(text = null, width = 300), parseImageAlt("|300"))
    }

    @Test
    fun `spaces around the size are allowed`() {
        assertEquals(ImageAlt(text = "photo", width = 300, height = 200), parseImageAlt("photo | 300x200 "))
    }

    @Test
    fun `only the last bar can start the size - earlier ones belong to the alt text`() {
        assertEquals(ImageAlt(text = "before | after", width = 120), parseImageAlt("before | after|120"))
    }

    @Test
    fun `text after the bar that is not a size keeps the whole alt text`() {
        // e.g. an alt text that just contains a bar - it must not be cut off
        assertEquals(ImageAlt(text = "cats | dogs"), parseImageAlt("cats | dogs"))
        assertEquals(ImageAlt(text = "photo|300px"), parseImageAlt("photo|300px"))
        assertEquals(ImageAlt(text = "photo|x200"), parseImageAlt("photo|x200"))
    }

    @Test
    fun `a size of zero or one too large to read is ignored instead of hiding the image`() {
        assertEquals(ImageAlt(text = "photo|0"), parseImageAlt("photo|0"))
        assertEquals(ImageAlt(text = "photo|99999999999"), parseImageAlt("photo|99999999999"))
        // a usable width with an unusable height keeps the width
        assertEquals(ImageAlt(text = "photo", width = 300), parseImageAlt("photo|300x0"))
    }

    @Test
    fun `an image without alt text has no size`() {
        assertEquals(ImageAlt(text = null), parseImageAlt(null))
        assertEquals(ImageAlt(text = null), parseImageAlt(""))
    }
}
