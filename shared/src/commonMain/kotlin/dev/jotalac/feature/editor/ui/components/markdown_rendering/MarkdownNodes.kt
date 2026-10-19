package dev.jotalac.feature.editor.ui.components.markdown_rendering

import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

// small helpers for the markdown syntax tree, shared by the components

internal fun ASTNode.hasText(content: String): Boolean = getTextInNode(content).isNotBlank()

/** The children that hold text - the whitespace and line breaks between them left out. */
internal fun ASTNode.textChildren(content: String): List<ASTNode> = children.filter { it.hasText(content) }

/** This node or the first node of [type] below it. */
internal fun ASTNode.findDescendant(type: IElementType): ASTNode? {
    if (this.type == type) return this
    return children.firstNotNullOfOrNull { it.findDescendant(type) }
}
