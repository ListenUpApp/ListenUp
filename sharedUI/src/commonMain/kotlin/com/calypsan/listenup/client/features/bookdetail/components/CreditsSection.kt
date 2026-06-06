package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.presentation.bookdetail.CreditRoleGroup
import com.calypsan.listenup.client.presentation.bookdetail.groupContributorsByRole
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_credits
import org.jetbrains.compose.resources.stringResource

/**
 * Credits section listing every contributor role for a book as a divided list.
 *
 * Contributors sharing a role are grouped into a single row — a fourteen-narrator book shows one
 * "Narrators" row with the names comma-joined and wrapping, not fourteen rows. Each name stays
 * individually tappable, routing to its contributor detail screen via [onContributorClick]. A
 * contributor with no roles is grouped under a generic "Contributor" label so it is never dropped.
 *
 * Each row: the (wrapping) names on the left, the role label on the right, with `outlineVariant`
 * dividers between rows.
 *
 * When [showHeader] is true a "Credits" heading matching the app's section-heading style is rendered
 * above the content. Pass `showHeader = false` when an enclosing section already labels the slot.
 *
 * Renders nothing when [credits] is empty.
 *
 * @param credits            All contributors for this book.
 * @param onContributorClick Called with the contributor id when a name is tapped.
 * @param modifier           Modifier for the outermost container.
 * @param showHeader         Whether to render a standalone "Credits" section heading.
 */
@Composable
fun CreditsSection(
    credits: List<BookContributor>,
    onContributorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    if (credits.isEmpty()) return

    val groups = groupContributorsByRole(credits)

    Column(modifier = modifier.fillMaxWidth()) {
        if (showHeader) {
            CreditsSectionHeader()
        }
        groups.forEachIndexed { index, group ->
            CreditRow(group = group, onContributorClick = onContributorClick)
            if (index < groups.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Section heading — matches TagsSection / ChaptersSection heading style. */
@Composable
private fun CreditsSectionHeader(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.book_detail_credits),
        style =
            MaterialTheme.typography.titleLarge.copy(
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.SemiBold,
            ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(bottom = 12.dp),
    )
}

/**
 * A single credits row: the role's contributor names on the left (each tappable, wrapping across
 * lines when there are many), and the role label on the right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreditRow(
    group: CreditRoleGroup,
    onContributorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(modifier = Modifier.weight(1f)) {
            group.contributors.forEachIndexed { index, contributor ->
                Text(
                    text = contributor.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .semantics { this.role = Role.Button }
                            .clickable { onContributorClick(contributor.id) },
                )
                if (index < group.contributors.lastIndex) {
                    Text(
                        text = ", ",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = group.roleLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
