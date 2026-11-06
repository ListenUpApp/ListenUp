package com.calypsan.listenup.client.features.contributors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.presentation.bookdetail.groupContributorsByRole
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AdaptiveSheet
import com.calypsan.listenup.client.design.components.SheetPresentation
import com.calypsan.listenup.client.design.components.ContributorPhotoCircle
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookContributor
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_authors
import listenup.composeapp.generated.resources.book_detail_credits
import listenup.composeapp.generated.resources.book_detail_cast_count_all
import listenup.composeapp.generated.resources.book_detail_cast_count_authors
import listenup.composeapp.generated.resources.book_detail_cast_count_narrators
import listenup.composeapp.generated.resources.book_detail_full_cast
import org.jetbrains.compose.resources.stringResource

/** Which contributor role a [FullCastSheet] is showing — selected by a folded hero line. */
enum class CastRole { Authors, Narrators, All }

/**
 * Opens the [FullCastSheet] for the given [role], resolving the title and count line from string
 * resources. Centralises the wiring so the compact and wide layouts both just track a nullable
 * [CastRole] and render this when it is set.
 *
 * @param authors      The book's authors (shown when [role] is [CastRole.Authors]).
 * @param narrators    The book's narrators (shown when [role] is [CastRole.Narrators]).
 * @param everyone     Every credited contributor, each once (shown when [role] is [CastRole.All], Book Detail's
 *   "All credits"); empty falls back to the authors and narrators.
 */
@Composable
fun FullCastSheetFor(
    role: CastRole,
    authors: List<BookContributor>,
    narrators: List<BookContributor>,
    onContributorClick: (String) -> Unit,
    onDismiss: () -> Unit,
    everyone: List<BookContributor> = emptyList(),
) {
    val contributors =
        when (role) {
            CastRole.Authors -> authors
            CastRole.Narrators -> narrators
            CastRole.All -> everyone.ifEmpty { (authors + narrators).distinctBy { it.id } }
        }
    val title =
        stringResource(
            when (role) {
                CastRole.Authors -> Res.string.book_detail_authors
                CastRole.Narrators -> Res.string.book_detail_full_cast
                CastRole.All -> Res.string.book_detail_credits
            },
        )
    val countText =
        stringResource(
            when (role) {
                CastRole.Authors -> Res.string.book_detail_cast_count_authors
                CastRole.Narrators -> Res.string.book_detail_cast_count_narrators
                CastRole.All -> Res.string.book_detail_cast_count_all
            },
            contributors.size,
        )
    FullCastSheet(
        title = title,
        countText = countText,
        contributors = contributors,
        onContributorClick = onContributorClick,
        onDismiss = onDismiss,
        byRole = role == CastRole.All,
    )
}

/** One headed run of the roster: a role and its people, or (with no [heading]) the whole list. */
private data class CastSection(
    val heading: String?,
    val contributors: List<BookContributor>,
)

/** The roster's sections: by role ("Author", "Narrators" — `groupContributorsByRole`) or one unheaded run. */
private fun castSections(
    contributors: List<BookContributor>,
    byRole: Boolean,
): List<CastSection> =
    if (byRole) {
        groupContributorsByRole(contributors).map { CastSection(it.roleLabel, it.contributors) }
    } else {
        listOf(CastSection(heading = null, contributors = contributors))
    }

/**
 * The full roster overlay opened from a folded hero contributor line (e.g. "Roy Dotrice, 13 other
 * narrators"). Lists every contributor in [contributors] individually with an initials avatar, each
 * tappable to its detail page. No contributor is singled out as a "lead" — co-narrated books and
 * anthologies have no canonical primary, so every row reads equally.
 *
 * Adapts to width through [AdaptiveSheet]: a bottom sheet with a single column on compact/medium screens, a
 * centred dialog with a two-column roster at expanded width — mirroring the Book Detail screen's own layout split.
 *
 * @param title         Overlay heading (e.g. "Full cast", "Authors").
 * @param countText     Supporting line under the title (e.g. "14 narrators").
 * @param contributors  The full roster; the caller guarantees a non-empty list.
 * @param onContributorClick Invoked with a contributor id when a row is tapped (also dismisses).
 * @param onDismiss     Invoked when the overlay is dismissed without a selection.
 * @param byRole        Head the roster by role, each role's people under it (Book Detail's All credits, spec D8); a
 *   person in two roles is listed under both.
 * @param supporting    An optional line under a person's name — the player's "14 books in your library" (spec
 *   §5.5.5, PK1); null shows no line. Book Detail shows none.
 */
@Composable
fun FullCastSheet(
    title: String,
    countText: String,
    contributors: List<BookContributor>,
    onContributorClick: (String) -> Unit,
    onDismiss: () -> Unit,
    byRole: Boolean = false,
    supporting: (BookContributor) -> String? = { null },
) {
    val sections = castSections(contributors, byRole)
    val onSelect: (String) -> Unit = { id ->
        onContributorClick(id)
        onDismiss()
    }

    // The roster scrolls itself in both placements, so the sheet's own scroll stays off.
    AdaptiveSheet(
        title = title,
        subtitle = countText,
        onDismiss = onDismiss,
        dialogWidth = 560.dp,
        scrollable = false,
    ) { presentation ->
        when (presentation) {
            SheetPresentation.BottomSheet -> CastList(sections, supporting, onSelect)
            SheetPresentation.Dialog -> CastGrid(sections, supporting, onSelect)
        }
    }
}

/** Compact / medium: a single scrollable column of rows. */
@Composable
private fun CastList(
    sections: List<CastSection>,
    supporting: (BookContributor) -> String?,
    onSelect: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        sections.forEach { section ->
            section.heading?.let { heading ->
                item(key = "role:$heading") { RoleHeading(heading, Modifier.padding(horizontal = 8.dp)) }
            }
            itemsIndexed(
                items = section.contributors,
                key = { _, c -> "${section.heading.orEmpty()}:${c.id}" },
            ) { index, contributor ->
                CastRow(
                    contributor = contributor,
                    index = index,
                    onClick = { onSelect(contributor.id) },
                    supporting = supporting(contributor),
                )
            }
        }
    }
}

/** Expanded: a scrollable two-column roster. */
@Composable
private fun ColumnScope.CastGrid(
    sections: List<CastSection>,
    supporting: (BookContributor) -> String?,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sections.forEach { section ->
            section.heading?.let { RoleHeading(it, Modifier.padding(horizontal = 8.dp)) }
            CastPairs(section.contributors, supporting, onSelect)
        }
    }
}

/** The dialog's two-column roster for one section. */
@Composable
private fun CastPairs(
    contributors: List<BookContributor>,
    supporting: (BookContributor) -> String?,
    onSelect: (String) -> Unit,
) {
    contributors.chunked(2).forEachIndexed { rowIndex, pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEachIndexed { colIndex, contributor ->
                CastRow(
                    contributor = contributor,
                    index = rowIndex * 2 + colIndex,
                    onClick = { onSelect(contributor.id) },
                    supporting = supporting(contributor),
                    modifier = Modifier.weight(1f),
                )
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** A role's heading over its people ("Author", "Narrators"); a heading for TalkBack. */
@Composable
private fun RoleHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.md, bottom = Spacing.xs).semantics { heading() },
    )
}

/**
 * One roster row: the contributor's framed photo (or initials), their name, and an optional [supporting] line under it,
 * which a screen reader hears with the name ("Michael Kramer, 14 books in your library"). Every row reads equally.
 */
@Composable
internal fun CastRow(
    contributor: BookContributor,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val haptics = LocalHaptics.current
    val spoken = supporting?.let { "${contributor.name}, $it" }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
                .clickable {
                    haptics.press()
                    onClick()
                }.padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContributorPhotoCircle(
            contributorId = contributor.id,
            imagePath = contributor.imagePath,
            frame = contributor.imageFrame,
            size = CAST_AVATAR_SIZE,
        ) {
            CastAvatar(name = contributor.name, index = index)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contributor.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supporting?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Initials-circle avatar; the background tone cycles through the container palette by [index]. */
@Composable
private fun CastAvatar(
    name: String,
    index: Int,
    size: Dp = CAST_AVATAR_SIZE,
) {
    val (background, foreground) =
        when (index % 3) {
            1 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            2 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        }
    Box(
        modifier = Modifier.size(size).background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = castInitials(name),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = foreground,
        )
    }
}

private val CAST_AVATAR_SIZE = 44.dp

/** First + last word initials of [name], uppercased; "?" when [name] yields nothing. */
private fun castInitials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    val first = parts.first().first()
    val last = if (parts.size > 1) parts.last().first() else ""
    return "$first$last".uppercase()
}
