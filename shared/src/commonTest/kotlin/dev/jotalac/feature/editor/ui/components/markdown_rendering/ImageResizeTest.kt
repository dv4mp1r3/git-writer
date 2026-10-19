package dev.jotalac.feature.editor.ui.components.markdown_rendering

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageResizeTest {

    /** The block text after choosing [width] in the image's menu, parsed like the editor parses it. */
    private fun resize(blockText: String, width: Int?): String? {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(blockText)
        val image = tree.findDescendant(MarkdownElementTypes.IMAGE) ?: error("no image in: $blockText")
        return image.resizedIn(blockText, width)
    }

    // --- rewriting the note

    @Test
    fun `a size is added to an image without one`() {
        assertEquals("![photo|500](images/a.png)", resize("![photo](images/a.png)", 500))
    }

    @Test
    fun `a new size replaces the old one and a stretched image gets its proportions back`() {
        assertEquals("![photo|150](images/a.png)", resize("![photo|300x200](images/a.png)", 150))
    }

    @Test
    fun `resetting removes the size and keeps the alt text`() {
        assertEquals("![photo](images/a.png)", resize("![photo|300](images/a.png)", null))
    }

    @Test
    fun `an image without alt text gets only the size`() {
        assertEquals("![|200](images/a.png)", resize("![](images/a.png)", 200))
    }

    @Test
    fun `only the image changes - the text around it stays`() {
        assertEquals(
            "See ![photo|100](images/a.png) and the text after it.",
            resize("See ![photo](images/a.png) and the text after it.", 100),
        )
    }

    @Test
    fun `escaped characters in the alt text stay escaped`() {
        assertEquals("""![a \] b|100](images/a.png)""", resize("""![a \] b](images/a.png)""", 100))
    }

    @Test
    fun `a bar in the alt text is still alt text after resizing`() {
        val resized = resize("![cats | dogs](images/a.png)", 100)

        assertEquals("![cats | dogs|100](images/a.png)", resized)
        assertEquals(ImageAlt(text = "cats | dogs", width = 100), parseImageAlt("cats | dogs|100"))
    }

    @Test
    fun `an image with a reference link is resized too`() {
        assertEquals(
            "![photo|100][pic]\n\n[pic]: images/a.png",
            resize("![photo][pic]\n\n[pic]: images/a.png", 100),
        )
    }

    // --- the sizes in the menu

    @Test
    fun `a scale is relative to the picture's own width`() {
        assertEquals(250, scaledImageWidth(pictureWidth = 1000f, scale = 0.25f))
        assertEquals(2000, scaledImageWidth(pictureWidth = 1000f, scale = 2f))
        assertEquals(167, scaledImageWidth(pictureWidth = 333f, scale = 0.5f))
    }

    @Test
    fun `a tiny picture scaled down still gets a width of at least one`() {
        // 0 would not be read back as a size, the image would jump back to full size
        assertEquals(1, scaledImageWidth(pictureWidth = 3f, scale = 0.1f))
    }

    // --- writing the alt text back

    @Test
    fun `the written alt text reads back as the same size`() {
        listOf(
            ImageAlt(text = "photo", width = 300),
            ImageAlt(text = "photo", width = 300, height = 200),
            ImageAlt(text = null, width = 50),
            ImageAlt(text = "photo"),
        ).forEach { alt ->
            assertEquals(alt, parseImageAlt(alt.toAltText()), "written as ${alt.toAltText()}")
        }
    }
}
