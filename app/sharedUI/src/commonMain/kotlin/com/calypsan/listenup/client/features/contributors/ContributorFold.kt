package com.calypsan.listenup.client.features.contributors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.calypsan.listenup.client.design.text.firstStrongDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.model.BookContributor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A (possibly folding) row of tappable contributor names.
 *
 * When [overflowTextRes] and [onOverflowClick] are both supplied and the contributor count exceeds
 * [foldLimit], the entire line collapses to "{lead}, N other …" and the whole line becomes a single
 * tap target that opens the full-cast overlay. Without overflow params the names are rendered
 * individually, each tappable to its own detail page.
 *
 * @param contributors      Full list of contributors to render (or fold from).
 * @param onContributorClick Invoked with the contributor id when a non-folded name is tapped.
 * @param style             Text style applied to every text token (names, separators, prefix).
 * @param nameColor         Colour applied to contributor name tokens.
 * @param separatorColor    Colour applied to separator tokens (", ", " & ") and the [prefix].
 * @param modifier          Optional layout modifier applied to the outer [FlowRow].
 * @param horizontalArrangement Horizontal arrangement of the [FlowRow] children.
 * @param leadingIcon       Optional composable rendered before the prefix/names (e.g. a mic icon).
 * @param prefix            Optional text token prepended before the names (e.g. "Narrated by ").
 * @param foldLimit         Contributors above this count trigger the folded
 *   "{lead}, N other …" summary. Folding only happens when both [overflowTextRes] and
 *   [onOverflowClick] are supplied; defaults to [Int.MAX_VALUE] (never fold).
 * @param overflowTextRes Format string for the folded summary, taking the lead name (`%1$s`) and
 *   the other-count (`%2$d`) — e.g. "%1$s, %2$d other narrators".
 * @param onOverflowClick Invoked when the folded line is tapped; wired to open the full-cast overlay
 */
@Composable
fun ClickableContributorLine(
    contributors: List<BookContributor>,
    onContributorClick: (contributorId: String) -> Unit,
    style: TextStyle,
    nameColor: Color,
    separatorColor: Color,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
    leadingIcon: (@Composable () -> Unit)? = null,
    prefix: String? = null,
    foldLimit: Int = Int.MAX_VALUE,
    overflowTextRes: StringResource? = null,
    onOverflowClick: (() -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    // Fold to "{lead}, N other …" once the inline list would exceed [foldLimit]; the whole line
    // becomes one tap target that opens the full-cast overlay.
    val folded = overflowTextRes != null && onOverflowClick != null && contributors.size > foldLimit

    val lineModifier =
        if (folded) {
            modifier.clickable {
                haptics.press()
                onOverflowClick()
            }
        } else {
            modifier
        }
    val foldedText =
        if (folded) {
            stringResource(
                overflowTextRes,
                contributors.first().name,
                contributors.size - 1,
            )
        } else {
            null
        }
    val tokens: @Composable (Modifier, Arrangement.Horizontal) -> Unit = { rowModifier, arrangement ->
        FlowRow(
            modifier = rowModifier,
            horizontalArrangement = arrangement,
            verticalArrangement = Arrangement.Center,
        ) {
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier.padding(end = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    leadingIcon()
                }
            }

            if (prefix != null) {
                Text(text = prefix, style = style, color = separatorColor)
            }

            if (foldedText != null) {
                // Lead and "N others" share one colour — secondary names are not made more prominent
                // than the lead (the whole line is the affordance into the full cast).
                Text(
                    text = foldedText,
                    style = style,
                    color = nameColor,
                )
                return@FlowRow
            }

            contributors.forEachIndexed { index, contributor ->
                Text(
                    text = contributor.name,
                    style = style,
                    color = nameColor,
                    modifier = Modifier.clickable { onContributorClick(contributor.id) },
                )

                if (index < contributors.lastIndex) {
                    val separator = if (index == contributors.size - 2) " & " else ", "
                    Text(text = separator, style = style, color = separatorColor)
                }
            }
        }
    }
    // The line is one sentence laid out word by word ("Narrated by", each name, each separator): its words must run
    // in the sentence's own reading order, which can differ from the layout's (English strings in an Arabic locale).
    InReadingOrder(
        sentence = prefix.orEmpty() + contributors.joinToString(" ") { it.name },
        modifier = lineModifier,
        arrangement = horizontalArrangement,
        content = tokens,
    )
}

/**
 * Lays out [content], a [sentence]'s words, in the sentence's own reading order. Where that matches the layout's
 * direction it is the layout itself; where it does not, the words take the sentence's direction while [modifier] and
 * [arrangement] keep speaking the layout's, so the line still sits where the caller put it.
 */
@Composable
private fun InReadingOrder(
    sentence: String,
    modifier: Modifier,
    arrangement: Arrangement.Horizontal,
    content: @Composable (Modifier, Arrangement.Horizontal) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val readingDirection = firstStrongDirection(sentence) ?: layoutDirection
    if (readingDirection == layoutDirection) {
        content(modifier, arrangement)
        return
    }
    val readingOrder = remember(arrangement, layoutDirection) { ReadingOrderArrangement(arrangement, layoutDirection) }
    Box(modifier, propagateMinConstraints = true) {
        CompositionLocalProvider(LocalLayoutDirection provides readingDirection) { content(Modifier, readingOrder) }
    }
}

/**
 * [layout]'s packing (start, centre, end, spacing, as the layout reads them) with the items in reading order: the
 * arrangement for a sentence whose words run against the layout, laid out in the sentence's direction. [layout] places
 * the line as the layout would, reversing the words; this keeps where it packs them and puts them back in order.
 */
private class ReadingOrderArrangement(
    private val layout: Arrangement.Horizontal,
    private val layoutDirection: LayoutDirection,
) : Arrangement.Horizontal {
    override val spacing: Dp get() = layout.spacing

    override fun Density.arrange(
        totalSize: Int,
        sizes: IntArray,
        layoutDirection: LayoutDirection,
        outPositions: IntArray,
    ) {
        with(layout) {
            arrange(
                totalSize = totalSize,
                sizes = sizes,
                layoutDirection = this@ReadingOrderArrangement.layoutDirection,
                outPositions = outPositions,
            )
        }
        // Left to right as packed; the gaps between neighbours, then the same span with the words in order.
        val packed = sizes.indices.sortedBy { outPositions[it] }
        val gaps = packed.zipWithNext { a, b -> outPositions[b] - (outPositions[a] + sizes[a]) }.reversed()
        var x = packed.firstOrNull()?.let { outPositions[it] } ?: return
        sizes.indices.forEach { index ->
            outPositions[index] = x
            x += sizes[index] + (gaps.getOrNull(index) ?: 0)
        }
    }
}
