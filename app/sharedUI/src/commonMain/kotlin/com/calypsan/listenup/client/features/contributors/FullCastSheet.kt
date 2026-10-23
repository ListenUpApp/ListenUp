package com.calypsan.listenup.client.features.contributors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberBottomSheetState
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
import androidx.compose.ui.window.Dialog
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.ContributorPhotoCircle
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookContributor
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_authors
import listenup.composeapp.generated.resources.book_detail_credits
import listenup.composeapp.generated.resources.book_detail_cast_count_all
import listenup.composeapp.generated.resources.book_detail_cast_count_authors
import listenup.composeapp.generated.resources.book_detail_cast_count_narrators
import listenup.composeapp.generated.resources.book_detail_done
import listenup.composeapp.generated.resources.book_detail_full_cast
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.BottomSheetDefaults

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
 * Adapts to width: a bottom sheet on compact/medium screens, a centred dialog at expanded width —
 * mirroring the Book Detail screen's own layout split.
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
@OptIn(ExperimentalMaterial3Api::class)
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
    val expanded =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )

    val onSelect: (String) -> Unit = { id ->
        onContributorClick(id)
        onDismiss()
    }

    if (expanded) {
        FullCastDialog(
            title = title,
            countText = countText,
            sections = sections,
            supporting = supporting,
            onSelect = onSelect,
            onDismiss = onDismiss,
        )
    } else {
        FullCastBottomSheet(
            title = title,
            countText = countText,
            sections = sections,
            supporting = supporting,
            onSelect = onSelect,
            onDismiss = onDismiss,
        )
    }
}

/** Compact / medium: a Material bottom sheet with a drag handle and a scrollable roster. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullCastBottomSheet(
    title: String,
    countText: String,
    sections: List<CastSection>,
    supporting: (BookContributor) -> String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        shape = BottomSheetDefaults.ExpandedShape,
    ) {
        CastHeader(
            title = title,
            countText = countText,
            modifier = Modifier.padding(start = Spacing.screenMargin, end = Spacing.screenMargin, bottom = Spacing.sm),
        )
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
}

/** Expanded: a centred dialog with a scrollable two-column roster and a Done action. */
@Composable
private fun FullCastDialog(
    title: String,
    countText: String,
    sections: List<CastSection>,
    supporting: (BookContributor) -> String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHaptics.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(top = 26.dp, bottom = 12.dp)) {
                CastHeader(
                    title = title,
                    countText = countText,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    sections.forEach { section ->
                        section.heading?.let { RoleHeading(it, Modifier.padding(horizontal = 8.dp)) }
                        CastPairs(section.contributors, supporting, onSelect)
                    }
                }
                Row(
                    modifier =
                        Modifier.fillMaxWidth().padding(
                            horizontal = Spacing.screenMargin,
                            vertical = Spacing.xs,
                        ),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            haptics.press()
                            onDismiss()
                        },
                    ) {
                        Text(text = stringResource(Res.string.book_detail_done))
                    }
                }
            }
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

/** Shared title + supporting count line for both overlay variants. */
@Composable
private fun CastHeader(
    title: String,
    countText: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style =
                MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = countText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
            supporting?.let {
                Text(
                    text = it,
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
