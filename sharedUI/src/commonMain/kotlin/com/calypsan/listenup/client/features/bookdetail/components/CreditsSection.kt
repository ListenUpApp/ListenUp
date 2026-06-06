package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.domain.model.BookContributor
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_credits
import org.jetbrains.compose.resources.stringResource

private const val GRID_COLUMNS = 2

/**
 * Credits section listing all contributor roles for a book.
 *
 * Each contributor with multiple roles appears once per role. A contributor with no roles is shown
 * once under a generic "Contributor" label. Every name is tappable, routing to the contributor
 * detail screen via [onContributorClick].
 *
 * Two layout modes:
 * - [grid] = true  — 2-column role/name grid (desktop, inside the About card). Each cell shows a
 *   small role label above the clickable name.
 * - [grid] = false — divided list (mobile). Each row: name (left, clickable) + role (right), with
 *   `outlineVariant` dividers between rows.
 *
 * When [showHeader] is true a "Credits" heading matching the app's section-heading style is
 * rendered above the content. Pass `showHeader = false` when [AboutSection]'s own "Credits"
 * overline already labels the slot.
 *
 * Renders nothing when [credits] is empty.
 *
 * @param credits             All contributors for this book.
 * @param grid                True for 2-column desktop grid; false for compact divided list.
 * @param onContributorClick  Called with the contributor id when a name is tapped.
 * @param modifier            Modifier for the outermost container.
 * @param showHeader          Whether to render a standalone "Credits" section heading.
 */
@Composable
fun CreditsSection(
    credits: List<BookContributor>,
    grid: Boolean,
    onContributorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    if (credits.isEmpty()) return

    // Expand to one (role, contributor) pair per role. Contributors with no roles get a single
    // generic entry so they are never silently omitted.
    val rows: List<Pair<String, BookContributor>> =
        credits.flatMap { contributor ->
            if (contributor.roles.isEmpty()) {
                listOf("Contributor" to contributor)
            } else {
                contributor.roles.map { role -> role to contributor }
            }
        }

    Column(modifier = modifier.fillMaxWidth()) {
        if (showHeader) {
            CreditsSectionHeader()
        }

        if (grid) {
            CreditsGrid(rows = rows, onContributorClick = onContributorClick)
        } else {
            CreditsList(rows = rows, onContributorClick = onContributorClick)
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
 * 2-column grid layout — each cell is a role label above a clickable name.
 *
 * Rows are iterated pairwise so that every pair of (role, contributor) entries occupies one
 * horizontal row, giving a tidy two-column alignment without a formal `LazyVerticalGrid`.
 */
@Composable
private fun CreditsGrid(
    rows: List<Pair<String, BookContributor>>,
    onContributorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        rows.chunked(GRID_COLUMNS).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                chunk.forEach { (roleLabel, contributor) ->
                    CreditGridCell(
                        role = roleLabel,
                        name = contributor.name,
                        onClick = { onContributorClick(contributor.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // If the last row has only one item, fill the remaining column with blank weight.
                if (chunk.size < GRID_COLUMNS) {
                    repeat(GRID_COLUMNS - chunk.size) {
                        // Empty spacer to maintain column alignment
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * A single cell in the credits grid: role label (small, `onSurfaceVariant`) above the clickable
 * contributor name (`titleSmall`, `onSurface`).
 */
@Composable
private fun CreditGridCell(
    role: String,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = role.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .semantics { this.role = Role.Button }
                    .clickable(onClick = onClick),
        )
    }
}

/**
 * Compact divided list — each row: clickable name on the left, role label on the right.
 * An `outlineVariant` divider separates consecutive rows; no divider after the final row.
 */
@Composable
private fun CreditsList(
    rows: List<Pair<String, BookContributor>>,
    onContributorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, (roleLabel, contributor) ->
            CreditListRow(
                role = roleLabel,
                name = contributor.name,
                onClick = { onContributorClick(contributor.id) },
            )
            if (index < rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/**
 * A single row in the credits list: name (left, clickable `titleSmall`) + role (right,
 * `bodyMedium`, `onSurfaceVariant`).
 */
@Composable
private fun CreditListRow(
    role: String,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { this.role = Role.Button }
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = role.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
