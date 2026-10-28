package dev.jotalac.feature.vault.data

/**
 * Line-based three-way merge - the diff3 of git, built on one longest-common-subsequence diff per
 * side, so no merge library is needed. Lines are the note's content as `content.split('\n')`, which
 * makes a merge that takes the input as is round-trip byte for byte, trailing newline included.
 */
internal sealed interface MergedChunk {

    /** Lines both sides agree on: unchanged, changed on one side only, or the same change on both. */
    data class Clean(val lines: List<String>) : MergedChunk

    /** Both sides changed the same lines, differently. */
    data class Conflict(val ours: List<String>, val theirs: List<String>) : MergedChunk
}

/** One region [baseStart, baseEnd) of [base] that [other] replaced by [otherStart, otherEnd). */
private data class ChangedRegion(val baseStart: Int, val baseEnd: Int, val otherStart: Int, val otherEnd: Int)

/** One region one side changed, with its extents in the base and in that side's lines. */
private data class Hunk(val side: Int, val baseStart: Int, val baseEnd: Int, val start: Int, val end: Int)

private const val OURS = 0
private const val THEIRS = 1

/**
 * Combines the edits [ours] and [theirs] both made against [base]: regions only one side touched are
 * taken from that side, the same change on both sides is taken once, edits of the same lines from
 * both sides become a [MergedChunk.Conflict] for the user to decide.
 */
internal fun diff3Merge(base: List<String>, ours: List<String>, theirs: List<String>): List<MergedChunk> {
    // grouped in base order - the sides are merged into one list, so it has to be sorted
    val hunks = (changedRegions(base, ours).map { Hunk(OURS, it.baseStart, it.baseEnd, it.otherStart, it.otherEnd) } +
        changedRegions(base, theirs).map { Hunk(THEIRS, it.baseStart, it.baseEnd, it.otherStart, it.otherEnd) })
        .sortedBy { it.baseStart }

    val result = mutableListOf<MergedChunk>()
    var stable = 0 // base lines before this index are emitted already
    var i = 0
    while (i < hunks.size) {
        // one region = all hunks that overlap it in the base; adjacent edits would interleave, so
        // they conflict just like edits of the very same lines
        val groupStart = hunks[i].baseStart
        var groupEnd = hunks[i].baseEnd
        var j = i + 1
        while (j < hunks.size && hunks[j].baseStart <= groupEnd) {
            groupEnd = maxOf(groupEnd, hunks[j].baseEnd)
            j++
        }

        result += MergedChunk.Clean(base.subList(stable, groupStart))
        if (j == i + 1) {
            // only one side touched this region - the merge takes that side
            val hunk = hunks[i]
            result += MergedChunk.Clean((if (hunk.side == OURS) ours else theirs).subList(hunk.start, hunk.end))
        } else {
            result += mergedGroup(hunks, i, j, groupStart, groupEnd, ours, theirs)
        }
        stable = groupEnd
        i = j
    }
    result += MergedChunk.Clean(base.subList(stable, base.size))

    return result.filterNot { it is MergedChunk.Clean && it.lines.isEmpty() }
}

/** Both sides changed one region - extends each side's edits to the whole region and compares them. */
private fun mergedGroup(
    hunks: List<Hunk>,
    from: Int,
    until: Int,
    groupStart: Int,
    groupEnd: Int,
    ours: List<String>,
    theirs: List<String>,
): MergedChunk {
    // per side: [start, end] of the side's lines and [baseStart, baseEnd] of the base they replace
    val bounds = Array(2) { intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE) }
    for (k in from until until) {
        val hunk = hunks[k]
        val b = bounds[hunk.side]
        b[0] = minOf(b[0], hunk.start)
        b[1] = maxOf(b[1], hunk.end)
        b[2] = minOf(b[2], hunk.baseStart)
        b[3] = maxOf(b[3], hunk.baseEnd)
    }

    fun slice(side: Int, lines: List<String>): List<String> {
        val b = bounds[side]
        // the parts of the region a side left unchanged are the base's lines, wherever they sit
        return lines.subList(b[0] + (groupStart - b[2]), b[1] + (groupEnd - b[3]))
    }

    val oursLines = slice(OURS, ours)
    val theirsLines = slice(THEIRS, theirs)
    // the same edit made on both devices is not a conflict
    return if (oursLines == theirsLines) MergedChunk.Clean(oursLines) else MergedChunk.Conflict(oursLines, theirsLines)
}

private fun changedRegions(base: List<String>, other: List<String>): List<ChangedRegion> {
    val regions = mutableListOf<ChangedRegion>()
    var baseIndex = 0
    var otherIndex = 0
    for ((matchedBase, matchedOther) in lcsMatches(base, other)) {
        if (matchedBase > baseIndex || matchedOther > otherIndex) {
            regions += ChangedRegion(baseIndex, matchedBase, otherIndex, matchedOther)
        }
        baseIndex = matchedBase + 1
        otherIndex = matchedOther + 1
    }
    if (base.size > baseIndex || other.size > otherIndex) {
        regions += ChangedRegion(baseIndex, base.size, otherIndex, other.size)
    }
    return regions
}

/** The matches of the longest common subsequence of [base] and [other], as index pairs in order. */
private fun lcsMatches(base: List<String>, other: List<String>): List<Pair<Int, Int>> {
    // the common head and tail match line for line - only what is between them needs the table
    var start = 0
    while (start < base.size && start < other.size && base[start] == other[start]) start++
    var endBase = base.size
    var endOther = other.size
    while (endBase > start && endOther > start && base[endBase - 1] == other[endOther - 1]) {
        endBase--
        endOther--
    }

    val matches = mutableListOf<Pair<Int, Int>>()
    for (k in 0 until start) matches += k to k

    val middleBase = endBase - start
    val middleOther = endOther - start
    if (middleBase > 0 && middleOther > 0) {
        // the table needs O(length * length) memory - notes beyond that lose their inner matches,
        // which at worst makes a merge conflict where a merge was possible
        if (middleBase.toLong() * middleOther <= 4_000_000) {
            val table = Array(middleBase + 1) { IntArray(middleOther + 1) }
            for (b in middleBase - 1 downTo 0) {
                for (o in middleOther - 1 downTo 0) {
                    table[b][o] = if (base[start + b] == other[start + o]) {
                        table[b + 1][o + 1] + 1
                    } else {
                        maxOf(table[b + 1][o], table[b][o + 1])
                    }
                }
            }
            var b = 0
            var o = 0
            while (b < middleBase && o < middleOther) {
                when {
                    base[start + b] == other[start + o] -> {
                        matches += (start + b) to (start + o)
                        b++
                        o++
                    }
                    table[b + 1][o] >= table[b][o + 1] -> b++
                    else -> o++
                }
            }
        }
    }

    var otherIndex = endOther
    for (k in endBase until base.size) {
        matches += k to otherIndex
        otherIndex++
    }
    return matches
}
