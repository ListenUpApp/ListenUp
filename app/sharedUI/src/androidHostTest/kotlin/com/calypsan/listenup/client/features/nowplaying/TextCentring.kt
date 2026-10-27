package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.text.TextLayoutResult

/**
 * The horizontal centre of each laid-out line of a text node, in root pixels: what "the words sit on the axis" means.
 * A full-width text node is trivially centred as a box; its lines are not, unless the text is.
 */
internal fun SemanticsNodeInteraction.lineCentresPx(): List<Float> {
    val node = fetchSemanticsNode()
    val layouts = mutableListOf<TextLayoutResult>()
    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    val layout = layouts.single()
    val left = node.positionInRoot.x
    return (0 until layout.lineCount).map { line -> left + (layout.getLineLeft(line) + layout.getLineRight(line)) / 2f }
}

/** The centre of a node's box, in root pixels. */
internal fun SemanticsNodeInteraction.centreXPx(): Float =
    fetchSemanticsNode().boundsInRoot.let { (it.left + it.right) / 2f }

/** The centre of the union of every node's box, in root pixels: a row's drawn content, wherever its parent ends. */
internal fun SemanticsNodeInteractionCollection.unionCentreXPx(): Float =
    fetchSemanticsNodes()
        .map { it.boundsInRoot }
        .reduce {
            a,
            b,
            ->
            Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
        }.let { (it.left + it.right) / 2f }
