package com.calypsan.listenup.client.features.contributors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
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
@OptIn(ExperimentalLayoutApi::class)
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
    // Fold to "{lead}, N other …" once the inline list would exceed [foldLimit]; the whole line
    // becomes one tap target that opens the full-cast overlay.
    val folded = overflowTextRes != null && onOverflowClick != null && contributors.size > foldLimit

    FlowRow(
        modifier = if (folded) modifier.clickable(onClick = onOverflowClick) else modifier,
        horizontalArrangement = horizontalArrangement,
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

        if (folded) {
            // Lead and "N others" share one colour — secondary names are not made more prominent
            // than the lead (the whole line is the affordance into the full cast).
            Text(
                text = stringResource(overflowTextRes, contributors.first().name, contributors.size - 1),
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
