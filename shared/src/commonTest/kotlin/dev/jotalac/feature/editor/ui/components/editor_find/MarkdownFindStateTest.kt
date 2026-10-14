package dev.jotalac.feature.editor.ui.components.editor_find

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownFindStateTest {

    private val blocks = listOf("# Shopping", "milk, more milk", "no match here", "MILK")

    // --- findAll

    @Test
    fun `finds every match across all blocks ignoring case`() {
        assertEquals(
            listOf(
                FindMatch(blockIndex = 1, start = 0, end = 4),
                FindMatch(blockIndex = 1, start = 11, end = 15),
                FindMatch(blockIndex = 3, start = 0, end = 4),
            ),
            findAll(blocks, "milk"),
        )
    }

    @Test
    fun `case sensitive search skips other casing`() {
        assertEquals(listOf(FindMatch(3, 0, 4)), findAll(blocks, "MILK", ignoreCase = false))
    }

    @Test
    fun `matches do not overlap`() {
        assertEquals(listOf(FindMatch(0, 0, 2), FindMatch(0, 2, 4)), findAll(listOf("aaaa"), "aa"))
    }

    @Test
    fun `an empty query finds nothing`() {
        assertEquals(emptyList(), findAll(blocks, ""))
    }

    // --- find bar state

    @Test
    fun `typing a query jumps to the first match`() {
        val find = MarkdownFindState()

        find.updateQuery("milk", blocks)

        assertEquals(3, find.matches.size)
        assertEquals(0, find.currentIndex)
    }

    @Test
    fun `next goes through the matches and wraps to the first`() {
        val find = MarkdownFindState().apply { updateQuery("milk", blocks) }

        assertEquals(FindMatch(1, 11, 15), find.findNext())
        assertEquals(FindMatch(3, 0, 4), find.findNext())
        assertEquals(FindMatch(1, 0, 4), find.findNext())
    }

    @Test
    fun `previous from the first match wraps to the last`() {
        val find = MarkdownFindState().apply { updateQuery("milk", blocks) }

        assertEquals(FindMatch(3, 0, 4), find.findPrevious())
        assertEquals(2, find.currentIndex)
    }

    @Test
    fun `changing the query starts again from the first match`() {
        val find = MarkdownFindState().apply { updateQuery("milk", blocks) }
        find.findNext()

        find.updateQuery("milk,", blocks)

        assertEquals(listOf(FindMatch(1, 0, 5)), find.matches)
        assertEquals(0, find.currentIndex)
    }

    @Test
    fun `next and previous do nothing without matches`() {
        val find = MarkdownFindState().apply { updateQuery("bread", blocks) }

        assertNull(find.findNext())
        assertNull(find.findPrevious())
    }

    @Test
    fun `closing the find bar clears the search`() {
        val find = MarkdownFindState().apply {
            open()
            updateQuery("milk", blocks)
            findNext()
        }

        find.close()

        assertFalse(find.isOpen)
        assertEquals("", find.query)
        assertTrue(find.matches.isEmpty())
        assertEquals(0, find.currentIndex)
    }
}
