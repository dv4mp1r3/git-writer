package dev.jotalac.feature.editor.ui.components.markdown_rendering

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/**
 * A markdown image - like the library's MarkdownImage, which has no way to set a size, plus the
 * Obsidian style size in the alt text: `![photo|300](images/photo.png)`, see [ImageAlt].
 */
@Composable
fun CustomImageComponent(model: MarkdownComponentModel) {
    val link = model.node.imageLink(model.content, LocalReferenceLinkHandler.current) ?: return
    val alt = parseImageAlt(model.node.imageAltText(model.content))
    val imageTransformer = LocalImageTransformer.current
    val imageData = imageTransformer.transform(link) ?: return
    // the size of the loaded picture, unspecified until it has loaded
    val pictureSize = imageTransformer.intrinsicSize(imageData.painter)

    Image(
        painter = imageData.painter,
        contentDescription = alt.text ?: imageData.contentDescription,
        modifier = imageData.modifier
            .imageSize(alt, pictureSize)
            .clip(RoundedCornerShape(8.dp)),
        alignment = imageData.alignment,
        contentScale = if (alt.isStretched) ContentScale.FillBounds else imageData.contentScale,
        alpha = imageData.alpha,
        colorFilter = imageData.colorFilter,
    )
}

/** The image node when it is the only thing in this paragraph. */
internal fun ASTNode.loneImage(content: String): ASTNode? =
    textChildren(content).singleOrNull()?.takeIf { it.type == MarkdownElementTypes.IMAGE }

// --- size ----------------------------------------------------------------------------------------

// wider than any screen, so the editor width still decides - layout constraints cannot hold sizes of
// millions of pixels and crash, so ![photo|1000000](...) must not get that far
private const val MAX_IMAGE_SIDE_DP = 10_000

private fun Int.toImageSide(): Dp = coerceAtMost(MAX_IMAGE_SIDE_DP).dp

// never wider than the editor - the incoming constraints cap the width
private fun Modifier.imageSize(alt: ImageAlt, pictureSize: Size): Modifier {
    val width = alt.width?.toImageSide() ?: return this
    val height = alt.height?.toImageSide()

    return when {
        height != null -> size(width, height)
        // only a width: the height follows the picture's proportions. Set explicitly - an Image is never
        // taller than its picture on its own, so a small picture given a big width would stay a thin strip
        pictureSize.hasArea() -> width(width).aspectRatio(pictureSize.width / pictureSize.height)
        else -> width(width)
    }
}

private fun Size.hasArea(): Boolean = isSpecified && width > 0f && height > 0f

// --- the image node ------------------------------------------------------------------------------

// ![alt](link), or ![alt][reference] with the link defined elsewhere in the note
private fun ASTNode.imageLink(content: String, referenceLinkHandler: ReferenceLinkHandler): String? {
    findDescendant(MarkdownElementTypes.LINK_DESTINATION)?.let { return it.getUnescapedTextInNode(content) }

    val reference = findDescendant(MarkdownElementTypes.FULL_REFERENCE_LINK)
        ?: findDescendant(MarkdownElementTypes.SHORT_REFERENCE_LINK)
        ?: return null
    val label = reference.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
        ?.getUnescapedTextInNode(content)
        ?: return null
    return referenceLinkHandler.find(label).takeIf { it.isNotEmpty() }
}

private fun ASTNode.imageAltText(content: String): String? =
    (findDescendant(MarkdownElementTypes.LINK_TEXT) ?: findDescendant(MarkdownElementTypes.LINK_LABEL))
        ?.getUnescapedTextInNode(content)
        ?.trim('[', ']')
