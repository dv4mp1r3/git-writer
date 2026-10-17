package dev.jotalac.feature.editor.ui.components.markdown_rendering

/**
 * The alt text of a markdown image, with the size written after a `|` like in Obsidian:
 * `![photo|300](images/photo.png)` is 300 wide (height follows the image), `![photo|300x200](...)` is
 * stretched to exactly 300 x 200. Sizes are in dp.
 */
data class ImageAlt(
    val text: String?,
    val width: Int? = null,
    val height: Int? = null,
) {
    /** Both sides given - the picture is stretched to exactly that size, like in Obsidian. */
    val isStretched: Boolean
        get() = width != null && height != null
}

private val SIZE_SUFFIX = Regex("""(\d+)(?:x(\d+))?""")

fun parseImageAlt(alt: String?): ImageAlt {
    if (alt == null) return ImageAlt(text = null)

    val separatorIndex = alt.lastIndexOf('|')
    val size = if (separatorIndex >= 0) SIZE_SUFFIX.matchEntire(alt.substring(separatorIndex + 1).trim()) else null
    // a zero or a number too big for an Int is not a usable size
    val width = size?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    // no size after the last | - the whole text is the alt text ("a | b" stays as it is)
    if (width == null) return ImageAlt(text = alt.trim().ifEmpty { null })

    return ImageAlt(
        text = alt.substring(0, separatorIndex).trim().ifEmpty { null },
        width = width,
        height = size.groupValues[2].toIntOrNull()?.takeIf { it > 0 },
    )
}

/** The alt text as it is written between the brackets of the image - [parseImageAlt] reads it back. */
fun ImageAlt.toAltText(): String = buildString {
    append(text.orEmpty())
    if (width != null) {
        append('|').append(width)
        if (height != null) append('x').append(height)
    }
}
