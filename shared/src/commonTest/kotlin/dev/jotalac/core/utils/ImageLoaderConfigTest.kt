package dev.jotalac.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageLoaderConfigTest {

    private val notebook = "/Users/me/notes/work"

    @Test
    fun `a relative image link is found in the notebook folder`() {
        // how pasted images are linked
        assertEquals("$notebook/images/photo.png", resolveImagePath("images/photo.png", notebook))
        assertEquals("$notebook/images/photo.png", resolveImagePath("./images/photo.png", notebook))
    }

    @Test
    fun `a trailing slash on the notebook folder does not double it`() {
        assertEquals("$notebook/photo.png", resolveImagePath("photo.png", "$notebook/"))
    }

    @Test
    fun `absolute paths are used as they are`() {
        assertEquals("/tmp/photo.png", resolveImagePath("/tmp/photo.png", notebook))
        assertEquals("/tmp/photo.png", resolveImagePath("file:///tmp/photo.png", notebook))
        assertEquals("C:\\Users\\me\\photo.png", resolveImagePath("C:\\Users\\me\\photo.png", notebook))
    }

    @Test
    fun `a link in angle brackets is found without them`() {
        // markdown's way of writing a link with spaces: ![](<images/my photo.png>)
        assertEquals("$notebook/images/my photo.png", resolveImagePath("<images/my photo.png>", notebook))
    }

    @Test
    fun `spaces encoded as percent20 are decoded`() {
        // how other editors (obsidian, github) write a link with spaces: ![](images/my%20photo.png)
        assertEquals("$notebook/images/my photo.png", resolveImagePath("images/my%20photo.png", notebook))
    }

    @Test
    fun `encoded characters outside ascii are decoded and a lone percent sign is kept`() {
        assertEquals("$notebook/images/čaj.png", resolveImagePath("images/%C4%8Daj.png", notebook))
        assertEquals("$notebook/images/50% off.png", resolveImagePath("images/50%%20off.png", notebook))
    }

    @Test
    fun `web images are left to the http loader`() {
        assertNull(resolveImagePath("https://example.com/photo.png", notebook))
    }
}
