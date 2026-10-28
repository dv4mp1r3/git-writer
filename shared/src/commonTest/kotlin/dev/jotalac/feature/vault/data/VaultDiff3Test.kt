package dev.jotalac.feature.vault.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/*
* The line merge behind the vault auto-merge: git's diff3 on the decrypted note text. MergedChunk is
* the contract - Clean chunks are the merge, Conflict chunks go to the resolve dialog.
* */
class VaultDiff3Test {

    private fun lines(text: String) = text.split('\n')

    /** The merged text; fails the test when any chunk came out conflicting. */
    private fun cleanMerge(base: String, ours: String, theirs: String): String {
        val conflict = diff3Merge(lines(base), lines(ours), lines(theirs))
            .filterIsInstance<MergedChunk.Conflict>()
            .firstOrNull()
        if (conflict != null) error("unexpected conflict: ${conflict.ours} vs ${conflict.theirs}")
        return cleanLines(base, ours, theirs)
    }

    private fun cleanLines(base: String, ours: String, theirs: String): String =
        diff3Merge(lines(base), lines(ours), lines(theirs))
            .filterIsInstance<MergedChunk.Clean>()
            .flatMap { it.lines }
            .joinToString("\n")

    @Test
    fun `an untouched note roundtrips byte for byte`() {
        assertEquals("first\nsecond\nthird", cleanMerge("first\nsecond\nthird", "first\nsecond\nthird", "first\nsecond\nthird"))
        // the trailing newline is an empty last line - it survives
        assertEquals("a\nb\n", cleanMerge("a\nb\n", "a\nb\n", "a\nb\n"))
    }

    @Test
    fun `a change on one side wins`() {
        assertEquals("A\nb\nc", cleanMerge("a\nb\nc", "A\nb\nc", "a\nb\nc"))
        assertEquals("a\nb\nC", cleanMerge("a\nb\nc", "a\nb\nc", "a\nb\nC"))
    }

    @Test
    fun `the same change on both sides is taken once`() {
        assertEquals("A\nb\nc", cleanMerge("a\nb\nc", "A\nb\nc", "A\nb\nc"))
    }

    @Test
    fun `edits of different lines combine`() {
        assertEquals("laptop\nsecond\nphone", cleanMerge("first\nsecond\nthird", "laptop\nsecond\nthird", "first\nsecond\nphone"))
        // the other way around too - the other side's edit sits earlier in the note
        assertEquals("phone\nsecond\nlaptop", cleanMerge("first\nsecond\nthird", "first\nsecond\nlaptop", "phone\nsecond\nthird"))
    }

    @Test
    fun `lines added on both ends land in the merge`() {
        assertEquals("top\na\nb\nend", cleanMerge("a\nb", "top\na\nb", "a\nb\nend"))
    }

    @Test
    fun `the same line changed differently conflicts`() {
        val chunks = diff3Merge(lines("one"), lines("ours"), lines("theirs"))
        val conflict = assertIs<MergedChunk.Conflict>(chunks.single())
        assertEquals(listOf("ours"), conflict.ours)
        assertEquals(listOf("theirs"), conflict.theirs)
    }

    @Test
    fun `adjacent edits conflict like in git`() {
        // the edits touch neighbouring lines - merged they would interleave
        val conflicts = diff3Merge(lines("a\nb\nc"), lines("a\nB\nc"), lines("a\nb2\nC"))
            .filterIsInstance<MergedChunk.Conflict>()
        assertEquals(1, conflicts.size, "the neighbouring edits become one conflict")
        // both sides extend over the region they share
        assertEquals(listOf("B", "c"), conflicts.single().ours)
        assertEquals(listOf("b2", "C"), conflicts.single().theirs)
    }

    @Test
    fun `a deletion and an edit of the same region conflict`() {
        val conflicts = diff3Merge(lines("a\nb"), lines("a"), lines("a\nB"))
            .filterIsInstance<MergedChunk.Conflict>()
        assertEquals(1, conflicts.size)
        assertEquals(emptyList(), conflicts.single().ours)
        assertEquals(listOf("B"), conflicts.single().theirs)
    }

    @Test
    fun `the same addition to an empty note merges`() {
        assertEquals("added", cleanMerge("", "added", "added"))
    }

    @Test
    fun `different additions to an empty note conflict`() {
        val chunks = diff3Merge(emptyList(), lines("ours"), lines("theirs"))
        val conflict = assertIs<MergedChunk.Conflict>(chunks.single())
        assertEquals(listOf("ours"), conflict.ours)
        assertEquals(listOf("theirs"), conflict.theirs)
    }
}
